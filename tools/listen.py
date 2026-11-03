"""
Listen to the stethoscope board live, through the PC's speakers or headphones.

    python tools/listen.py                       # listen until Ctrl-C
    python tools/listen.py --gain 40             # fixed gain instead of auto
    python tools/listen.py --tone                # the firmware's 100 Hz test tone
    python tools/listen.py --save captures/s1    # listen AND keep the recording

Streams STH1 frames off the board's USB CDC port and plays them as they arrive.
A level meter runs alongside, which matters more than it sounds: heart sounds live
at 20-150 Hz, laptop speakers roll off well above that, and a signal you cannot
hear is not the same as a signal that is not there. Use headphones for the audio
and the meter for the truth.

## The monitor gain is not in the signal path

Playback is amplified, by a lot - a bench capture is a few dozen ADC counts, which
is inaudible without something like 100x. That gain exists **only** to drive the
sound card. It never touches a saved WAV: `--save` writes the same unconditioned
samples `record_pcg.py` would, and the app's own recordings are unaffected. This
matters because the app deliberately stopped conditioning the signal (see
PcgSpec.kt) and a monitor that quietly changed what got stored would undo that.

Requires pyserial. Audio goes out through Windows' winmm, driven by ctypes, so
there is nothing else to install.
"""

import argparse
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pcgkit                                                    # noqa: E402
import waveout                                                   # noqa: E402
from record_pcg import (                                         # noqa: E402
    FrameReader, describe_ports, find_port, open_board, to_int16,
)

# One frame is 1024 samples at 16 kHz, so a buffer of one frame is 64 ms and four
# of them put the worst-case latency around a quarter of a second. Lower would be
# nicer to listen through and much easier to starve.
BUFFER_SAMPLES = 1024
BUFFERS = 4

METER_HZ = 12.0
AUTO_GAIN_TARGET = 0.45   # of full scale, leaving room for a loud S1
AUTO_GAIN_MAX = 400.0
AUTO_GAIN_WINDOW_S = 1.5


class AutoGain:
    """
    Tracks a recent peak and scales towards a comfortable listening level.

    Falls quickly and rises slowly, which is the right way round here: a sudden
    loud S1 should pull the gain down at once rather than clip, while a quiet
    passage should not be pumped up so fast that the gaps between beats fill with
    amplified noise. Heart murmurs are quiet events next to S1, and a fast-acting
    AGC would flatten exactly that contrast - which is why this is a monitor-only
    control and nothing like it runs in the recording path.
    """

    def __init__(self):
        self.peak = 0.0
        self.gain = 1.0

    def update(self, block_peak, dt):
        # Peak decays with a half-life of AUTO_GAIN_WINDOW_S, in wall-clock time,
        # so the behaviour does not change with how fast the read loop happens to
        # spin.
        self.peak = max(block_peak, self.peak * (0.5 ** (dt / AUTO_GAIN_WINDOW_S)))
        target = AUTO_GAIN_MAX if self.peak <= 0 else min(
            AUTO_GAIN_TARGET * 32767.0 / self.peak, AUTO_GAIN_MAX
        )
        target = max(target, 1.0)
        if target < self.gain:
            self.gain = target                                  # duck immediately
        else:
            self.gain += (1.0 - 0.5 ** (dt / 2.0)) * (target - self.gain)  # ~2 s to rise
        return self.gain


def apply_gain(samples, gain):
    """Scales for playback and clamps. Monitor-only - see the module docstring."""
    out = [0] * len(samples)
    for i, v in enumerate(samples):
        s = int(v * gain)
        if s > 32767:
            s = 32767
        elif s < -32768:
            s = -32768
        out[i] = s
    return out


def meter_line(peak_counts, gain, starved, elapsed, mv_per_count):
    """One-line VU meter, in dB relative to the 12-bit ADC's full scale."""
    width = 32
    full = 32768.0
    frac = min(peak_counts / full, 1.0)
    # dBFS against the ADC range, which is what tells you whether the front end
    # has headroom left - the playback gain is deliberately not in this number.
    db = 20.0 * math.log10(max(frac, 1e-6))
    # Map -60..0 dB onto the bar; linear would leave every real signal invisible.
    filled = int(width * max(0.0, (db + 60.0) / 60.0))
    bar = "#" * filled + "-" * (width - filled)
    mv = peak_counts * mv_per_count * 1000.0
    return "\r [%s] %6.1f dBFS  %6.1f mV  gain x%-5.0f %5.1fs  %s" % (
        bar, db, mv, gain, elapsed, "starved %d" % starved if starved else "        ",
    )


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", help="serial port, e.g. COM5 (default: auto-detect VID 2E8A)")
    ap.add_argument("--seconds", type=float, default=0.0,
                    help="stop after this long (default: run until Ctrl-C)")
    ap.add_argument("--gain", type=float, default=0.0,
                    help="fixed playback gain; default is auto-ranging")
    ap.add_argument("--tone", action="store_true",
                    help="listen to the firmware's synthetic 100 Hz tone instead of the ADC")
    ap.add_argument("--save", metavar="PREFIX",
                    help="also write _raw16k.wav and _pcg2k.wav of what you heard, "
                         "unconditioned and without the playback gain")
    ap.add_argument("--mute", action="store_true",
                    help="meter only, no audio (useful over remote desktop)")
    ap.add_argument("--list", action="store_true", help="list serial ports and exit")
    args = ap.parse_args()

    if args.list:
        describe_ports()
        return 0

    if not args.mute and waveout.device_count() == 0:
        print("No audio output device found. Re-run with --mute for the level meter only.")
        return 2

    port = args.port or find_port()
    if not port:
        print("No RP2040 board found (looking for VID 2E8A).")
        describe_ports()
        return 2

    print("Port %s" % port)
    sp, _ = open_board(port)

    reader = FrameReader()
    pending = []
    kept = []
    audio = None
    gain = args.gain if args.gain > 0 else 1.0
    auto = None if args.gain > 0 else AutoGain()
    peak_recent = 0.0
    start = time.time()
    next_meter = start
    last_tick = start
    first_t = None
    first_n = 0
    last_t = start
    total_samples = 0
    mv_per_count = 3.3 / 65536.0  # exact: (raw-2048)<<4 maps +-32768 onto +-1.65 V

    print()
    print("Listening. Ctrl-C to stop.")
    print("  Heart sounds are 20-150 Hz and laptop speakers mostly cannot reproduce that.")
    print("  Use headphones, and trust the meter over your ears for whether there is signal.")
    if args.gain > 0:
        print("  Fixed playback gain x%.0f (monitor only; never written to a file)." % args.gain)
    else:
        print("  Playback gain is auto-ranging (monitor only; never written to a file).")
    print()

    try:
        sp.reset_input_buffer()
        sp.write(b"T" if args.tone else b"S")

        while True:
            if args.seconds and (time.time() - start) >= args.seconds:
                break

            # Drain only what has actually arrived. A plain sp.read(8192) blocks
            # until 8 kB is available, which at 32 kB/s is a quarter of a second of
            # audio per read - a backlog the monitor then plays late, on top of the
            # buffers below. For a live monitor that latency is the whole ballgame.
            waiting = sp.in_waiting
            if waiting:
                reader.feed(sp.read(waiting))
            if reader.samples:
                fresh = to_int16(reader.samples)
                reader.samples = []
                # Rate is measured first frame to last frame. Timing from the moment
                # the command was sent would charge the board for its own start-up
                # latency, which over a short listen is several percent.
                if first_t is None:
                    first_t, first_n = time.time(), len(fresh)
                total_samples += len(fresh)
                last_t = time.time()
                pending.extend(fresh)
                if args.save is not None:
                    kept.extend(fresh)
                block_peak = max(abs(v) for v in fresh)
                peak_recent = max(peak_recent, block_peak)

                if audio is None and not args.mute:
                    rate = reader.rate or pcgkit.CAPTURE_SAMPLE_RATE
                    audio = waveout.WaveOut(rate, BUFFER_SAMPLES, BUFFERS)

            now = time.time()
            dt, last_tick = now - last_tick, now
            if auto is not None:
                gain = auto.update(peak_recent, dt)

            # Hand whole buffers to the sound card while it has room for them.
            while audio is not None and len(pending) >= BUFFER_SAMPLES:
                chunk = apply_gain(pending[:BUFFER_SAMPLES], gain)
                if audio.write(chunk) == 0:
                    break  # every buffer still in flight; keep the data and retry
                del pending[:BUFFER_SAMPLES]

            # Latency guard. If the board outruns the sound card the backlog grows
            # without bound and you end up listening several seconds behind the
            # chest, which makes placing the bell impossible.
            if len(pending) > BUFFER_SAMPLES * BUFFERS * 2:
                del pending[:len(pending) - BUFFER_SAMPLES * BUFFERS]

            # Only once playing has actually begun; every buffer is idle before the
            # first write, which is not starvation.
            if audio is not None and audio.played and audio.free_slots() == BUFFERS \
                    and len(pending) < BUFFER_SAMPLES:
                audio.note_starved()

            if now >= next_meter:
                sys.stdout.write(meter_line(
                    peak_recent, gain, audio.starved if audio else 0,
                    now - start, mv_per_count,
                ))
                sys.stdout.flush()
                next_meter = now + 1.0 / METER_HZ
                peak_recent *= 0.6  # let the displayed peak fall back between beats

            time.sleep(0.005)

    except KeyboardInterrupt:
        pass
    finally:
        sys.stdout.write("\r" + " " * 100 + "\r")
        try:
            sp.write(b"X")
            time.sleep(0.2)
            reader.feed(sp.read(1 << 20))
        except Exception:
            pass
        sp.close()
        if audio is not None:
            audio.close()

    elapsed = time.time() - start
    rate = reader.rate or pcgkit.CAPTURE_SAMPLE_RATE
    stream_s = (last_t - first_t) if first_t else 0.0
    measured = ((total_samples - first_n) / stream_s) if stream_s > 0.5 else 0.0

    print("Stopped after %.1f s." % elapsed)
    if reader.frames:
        print("  frames %d, dropped %d, ADC overruns %d"
              % (reader.frames, reader.dropped, reader.overruns))
    if measured:
        print("  delivered %.0f samples/s against %d declared" % (measured, rate))
    if audio is not None and audio.starved:
        print("  audio ran dry %d times." % audio.starved)
        if args.tone:
            print("  Expected on --tone: the firmware synthesises that sine in Python and only")
            print("  manages about 11 kSa/s, while the sound card is consuming %d. The gaps are"
                  % rate)
            print("  in the delivery, not in your audio path - use a real capture to judge sound.")
        elif measured and measured < rate * 0.98:
            print("  The board is delivering %.0f samples/s against %d, so the sound card keeps"
                  % (measured, rate))
            print("  catching up with it. Harmless for listening; see the warning below if saving.")
        else:
            print("  Brief gaps while the sound card caught up. Harmless for listening.")
    if not reader.frames:
        print("  NO DATA. Is another program holding the port, or the board unflashed?")
        return 1

    if args.save is not None:
        if args.save != "" and os.path.dirname(os.path.abspath(args.save)):
            os.makedirs(os.path.dirname(os.path.abspath(args.save)), exist_ok=True)
        kept.extend(to_int16(reader.samples))

        # Same check record_pcg.py makes: the WAV is written at the rate the frames
        # declare, so if the board under-delivers the timeline stretches and any
        # heart rate read off the file is wrong by the same percentage. Listening
        # for a while before saving makes this easy to miss, hence the warning.
        if measured and abs(measured - rate) / rate > 0.05:
            print("  WARNING: %.0f samples/s delivered against %d declared. The saved file's"
                  % (measured, rate))
            print("           timeline is stretched by %.0f%%; re-record with record_pcg.py"
                  % (100 * abs(measured - rate) / rate))
            print("           if the timing matters.")

        raw_path = args.save + "_raw16k.wav"
        pcg_path = args.save + "_pcg2k.wav"
        pcgkit.write_wav(raw_path, kept, rate)
        samples, report = pcgkit.process_samples(kept, rate)
        pcgkit.write_wav(pcg_path, samples, pcgkit.SAMPLE_RATE)
        print("  wrote %s (%d samples at %d Hz)" % (raw_path, len(kept), rate))
        print("  wrote %s (%.1f s, unconditioned - no playback gain, no filtering)"
              % (pcg_path, report["duration_s"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
