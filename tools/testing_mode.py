"""
StethoScope testing mode, on the PC. The bench counterpart of the app's
Settings -> Testing mode screen, for working on the hardware without a phone.

    python tools/testing_mode.py            # live scope + record
    python tools/testing_mode.py --tone     # firmware's synthetic 100 Hz tone
    python tools/testing_mode.py --seconds 10   # wider initial timebase

A window with four things:

  * a **live phonocardiogram** - the board's signal, scrolling, per-pixel min/max so
    an S1 transient cannot hide between plotted points;
  * a **voltage view** (tick "Voltage view") - the same samples as absolute volts at
    the ADC pin against the 0-3.3 V rails, with mid-rail marked. The axis is fixed at
    the full rail and never auto-ranges: auto-ranging would make a 40 mV signal fill
    the plot and look healthy, which is the exact misreading this view exists to
    prevent. No pass/fail band is drawn, because nobody has yet measured what a real
    chest produces through this front end;
  * a **live spectrum**, averaged Welch/Hann, with the 400 Hz heart-sound edge and
    the 1 kHz fold of the 2 kHz output marked;
  * a **Record** button that captures for a fixed duration and writes exactly the
    pair of WAVs the phone would: `_raw16k.wav` as captured, and `_pcg2k.wav` run
    through the same pipeline (`pcgkit`, the port of the app's `PcgPreprocessor`).

Plus the numbers that actually decide whether the front end is working: peak in
dBFS and millivolts at the ADC pin, DC offset, delivered sample rate, frame count,
dropped frames and ADC overruns.

## Display choices are not signal choices

Auto-scaling, the DC-centred y-axis and the spectrum's dB-relative-to-peak are how
the *plot* is drawn. None of them touch what Record writes: those files are
unconditioned - no filtering, no DC removal, no normalization - exactly as the app
now hands them to the algorithm team (see PcgSpec.kt). A bench tool that quietly
reshaped what it saved would defeat the point of the exercise.

tkinter because it is in the standard library: there is no matplotlib and no numpy
on this machine, and a bench tool that needs a pip install before it shows you
anything is one nobody reaches for. Requires pyserial.
"""

import argparse
import math
import os
import sys
import time
import tkinter as tk
from collections import deque
from tkinter import ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pcgkit                                                    # noqa: E402
from record_pcg import (                                         # noqa: E402
    FrameReader, describe_ports, find_port, open_board, to_int16,
)

POLL_MS = 15              # serial drain + waveform redraw
SPECTRUM_EVERY = 0.35     # seconds; the FFT is pure Python and not free
SPECTRUM_SAMPLES = 4096   # last N samples fed to Welch, i.e. 7 overlapping windows
DISPLAY_SECONDS = 20.0    # scrollback the plot can show

BG = "#101418"
PANEL = "#161b22"
GRID = "#243038"
TRACE = "#39d353"
SPEC = "#58a6ff"
TEXT = "#c9d1d9"
MUTED = "#6e7681"
WARN = "#f85149"
OK = "#3fb950"

SUPPLY_V = 3.3                         # the RP2040's ADC reference
MID_RAIL_V = SUPPLY_V / 2.0            # where a correctly biased front end idles
V_PER_COUNT = SUPPLY_V / 65536.0       # (raw-2048)<<4 maps +-32768 onto +-1.65 V
MV_PER_COUNT = V_PER_COUNT * 1000.0


def counts_to_volts(counts):
    """Absolute potential at the ADC pin.

    Sample values are centred, because the firmware's frames are unsigned 12-bit and
    the host maps them with (raw - 2048) << 4. So zero counts is not zero volts, it is
    mid-rail - which is exactly the thing a voltage view exists to check.
    """
    return MID_RAIL_V + counts * V_PER_COUNT


class TestingMode:
    def __init__(self, root, sp, reader, args):
        self.root = root
        self.sp = sp
        self.reader = reader
        self.args = args

        self.window_s = args.seconds
        self.rate = pcgkit.CAPTURE_SAMPLE_RATE
        self.samples = deque(maxlen=int(DISPLAY_SECONDS * self.rate))
        self.paused = False
        self.closing = False

        self.total = 0
        self.first_t = None
        self.first_n = 0
        self.last_t = time.time()
        self.delivered = 0.0
        self.next_spectrum = 0.0
        self.spectrum = None
        self.span = 32768.0    # y half-range actually drawn; the status line reads it
        self.centre = 0.0

        # Recording is deliberately a separate buffer from the display ring: the plot
        # throws history away, a recording must not.
        self.rec_samples = None
        self.rec_target = 0
        self.rec_started = 0.0
        self.note = ""
        self.note_colour = MUTED

        root.title("StethoScope - PC testing mode")
        root.configure(bg=BG)
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.geometry("1060x700")
        root.minsize(760, 520)

        self.wave = tk.Canvas(root, bg=BG, highlightthickness=0, height=300)
        self.wave.pack(fill="both", expand=True, padx=8, pady=(8, 4))
        self.spec = tk.Canvas(root, bg=BG, highlightthickness=0, height=200)
        self.spec.pack(fill="both", expand=True, padx=8, pady=4)

        self._build_controls(root)

        # One reusable item per curve. Recreating a thousand canvas items per frame
        # is what makes naive Tk plots crawl; updating coords on a single polyline
        # does not.
        self.wave_line = self.wave.create_line(0, 0, 0, 0, fill=TRACE, width=1)
        self.spec_line = self.spec.create_line(0, 0, 0, 0, fill=SPEC, width=1)

        self.root.after(POLL_MS, self.tick)

    # ------------------------------------------------------------------ controls

    def _button(self, parent, text, command, width=12):
        return tk.Button(parent, text=text, command=command, width=width, bg=GRID,
                         fg=TEXT, activebackground=MUTED, activeforeground=TEXT,
                         relief="flat", highlightthickness=0)

    def _check(self, parent, text, var):
        return tk.Checkbutton(parent, text=text, variable=var, bg=BG, fg=TEXT,
                              selectcolor=GRID, activebackground=BG,
                              activeforeground=TEXT, highlightthickness=0)

    def _build_controls(self, root):
        bar = tk.Frame(root, bg=BG)
        bar.pack(fill="x", padx=8, pady=(0, 2))

        self.autoscale = tk.BooleanVar(value=True)
        self.full_band = tk.BooleanVar(value=False)
        self.voltage = tk.BooleanVar(value=False)

        self.record_btn = self._button(bar, "Record", self.toggle_record)
        self.record_btn.pack(side="left", padx=(0, 6))

        tk.Label(bar, text="for", bg=BG, fg=MUTED).pack(side="left")
        wanted = "%g s" % self.args.record_seconds
        choices = ["10 s", "30 s", "60 s"]
        if wanted not in choices:
            choices.append(wanted)          # honour an unusual --record-seconds
        self.duration = ttk.Combobox(bar, width=6, state="readonly", values=tuple(choices))
        self.duration.set(wanted)
        self.duration.pack(side="left", padx=6)

        self.pause_btn = self._button(bar, "Pause", self.toggle_pause, width=8)
        self.pause_btn.pack(side="left", padx=(12, 12))

        self._check(bar, "Voltage view", self.voltage).pack(side="left")
        self._check(bar, "Auto-scale", self.autoscale).pack(side="left")
        self._check(bar, "Full band (8 kHz)", self.full_band).pack(side="left", padx=(6, 12))

        tk.Label(bar, text="Timebase", bg=BG, fg=MUTED).pack(side="left")
        self.timebase = ttk.Combobox(bar, width=6, state="readonly",
                                     values=("1 s", "2 s", "5 s", "10 s", "20 s"))
        self.timebase.set("%d s" % int(self.window_s))
        self.timebase.bind("<<ComboboxSelected>>", self.on_timebase)
        self.timebase.pack(side="left", padx=6)

        self.status = tk.Label(root, text="waiting for frames...", bg=BG, fg=TEXT,
                               anchor="w", font=("Consolas", 9))
        self.status.pack(fill="x", padx=10, pady=(4, 0))
        self.note_label = tk.Label(root, text="", bg=BG, fg=MUTED, anchor="w",
                                   font=("Consolas", 9))
        self.note_label.pack(fill="x", padx=10, pady=(0, 8))

    def on_timebase(self, _event=None):
        self.window_s = float(self.timebase.get().split()[0])

    def toggle_pause(self):
        self.paused = not self.paused
        self.pause_btn.config(text="Resume" if self.paused else "Pause")

    # ------------------------------------------------------------------ recording

    @property
    def recording(self):
        return self.rec_samples is not None

    def toggle_record(self):
        if self.recording:
            self.finish_record()
        else:
            self.start_record()

    def start_record(self):
        seconds = float(self.duration.get().split()[0])
        self.rec_samples = []
        self.rec_target = int(seconds * self.rate)
        self.rec_started = time.time()
        self.paused = False
        self.pause_btn.config(text="Pause")
        self.record_btn.config(text="Stop")
        self.set_note("recording...", OK)

    def finish_record(self):
        """
        Writes whatever was captured and reports it.

        Whether the recording ran to completion is decided here, from the sample
        count, rather than by trusting a flag from whichever of the three callers
        got here - the timer in [pump], the Stop button, or [close] on a window
        shut mid-recording. A flag is one more thing that can disagree with the
        file on disk, and a recording mislabelled "stopped early" would send
        someone hunting for a truncation that never happened.
        """
        kept = self.rec_samples or []
        target = self.rec_target
        self.rec_samples = None
        self.record_btn.config(text="Record")
        if len(kept) < self.rate // 2:
            self.set_note("recording too short to save (%d samples)" % len(kept), WARN)
            return

        prefix = self.args.out
        directory = os.path.dirname(os.path.abspath(prefix))
        if directory:
            os.makedirs(directory, exist_ok=True)
        stamp = time.strftime("%Y%m%d_%H%M%S")
        raw_path = "%s_%s_raw16k.wav" % (prefix, stamp)
        pcg_path = "%s_%s_pcg2k.wav" % (prefix, stamp)

        # Paint before the resample: 60 s of audio is about a second of pure-Python
        # polyphase filtering, and a window that freezes with no explanation looks
        # like a crash.
        self.set_note("writing %s ..." % os.path.basename(pcg_path), TEXT)
        self.root.update_idletasks()

        pcgkit.write_wav(raw_path, kept, self.rate)
        out, report = pcgkit.process_samples(kept, self.rate)
        pcgkit.write_wav(pcg_path, out, pcgkit.SAMPLE_RATE)
        check = pcgkit.verify(pcg_path)

        complete = target > 0 and len(kept) >= target
        bits = ["%s %s" % ("saved" if complete else "stopped early -",
                           os.path.basename(pcg_path)),
                "%.1f s%s" % (report["duration_s"],
                              "" if complete else " of %.0f" % (target / float(self.rate))),
                "spec %s" % ("OK" if check["compliant"] else "; ".join(check["problems"]))]
        if report["clipped"]:
            bits.append("CLIPPED %d samples" % report["clipped"])
        self.set_note("   ".join(bits), WARN if report["clipped"] or not check["compliant"] else OK)
        print("saved %s" % raw_path)
        print("saved %s  (%.1f s, unconditioned: no filtering, no DC removal, no normalization)"
              % (pcg_path, report["duration_s"]))

    def set_note(self, text, colour=MUTED):
        self.note, self.note_colour = text, colour
        self.note_label.config(text=text, fg=colour)

    # ---------------------------------------------------------------------- loop

    def tick(self):
        if self.closing:
            return
        try:
            self.pump()
        except Exception as exc:                       # keep the window alive
            self.status.config(text="read error: %s" % exc, fg=WARN)
        self.root.after(POLL_MS, self.tick)

    def pump(self):
        waiting = self.sp.in_waiting
        if waiting:
            self.reader.feed(self.sp.read(waiting))
        if self.reader.samples:
            fresh = to_int16(self.reader.samples)
            self.reader.samples = []
            if self.reader.rate:
                self.retune(self.reader.rate)
            now = time.time()
            if self.first_t is None:
                self.first_t, self.first_n = now, len(fresh)
            self.total += len(fresh)
            self.last_t = now
            if self.recording:
                self.rec_samples.extend(fresh)
            if not self.paused:
                self.samples.extend(fresh)

        window = (self.last_t - self.first_t) if self.first_t else 0.0
        if window > 0.5:
            self.delivered = (self.total - self.first_n) / window

        if self.recording and len(self.rec_samples) >= self.rec_target:
            self.finish_record()

        if not self.paused or self.spectrum is None:
            self.draw_wave()
            now = time.time()
            if now >= self.next_spectrum:
                self.compute_spectrum()
                self.next_spectrum = now + SPECTRUM_EVERY
            self.draw_spectrum()
        self.draw_status()

    def retune(self, rate):
        """The board states its rate in every frame header; believe it over the default."""
        if rate == self.rate:
            return
        self.rate = rate
        self.samples = deque(self.samples, maxlen=int(DISPLAY_SECONDS * rate))

    # -------------------------------------------------------------------- render

    def visible(self):
        n = int(self.window_s * self.rate)
        data = list(self.samples)
        return data[-n:] if len(data) > n else data

    def draw_wave(self):
        c = self.wave
        w = max(c.winfo_width(), 2)
        h = max(c.winfo_height(), 2)
        data = self.visible()
        c.delete("grid")
        volts = self.voltage.get()
        self._wave_grid(c, w, h, volts)
        if len(data) < 2:
            c.coords(self.wave_line, 0, 0, 0, 0)
            return

        if volts:
            # Absolute volts against the full 0-3.3 V rail, fixed. Never auto-ranged:
            # auto-ranging would make a 40 mV signal fill the plot and look healthy,
            # which is the exact misreading this view exists to prevent.
            centre = 0.0                       # zero counts IS mid-rail
            span = MID_RAIL_V / V_PER_COUNT    # half the rail, expressed in counts
        else:
            centre = sum(data) / len(data)
            if self.autoscale.get():
                span = max(abs(v - centre) for v in data) * 1.15
                span = max(span, 4.0)      # a flat line should not fill the screen
            else:
                span = 32768.0
        self.span, self.centre = span, centre

        # Per-pixel min/max, the only honest way to draw 300 000 samples in 1000
        # pixels: a polyline through every Nth sample hides transients, and an S1
        # click is exactly the transient being looked for.
        coords = []
        per = len(data) / w
        mid = h / 2.0
        scale = (h / 2.0 - 4) / span
        i = 0
        for x in range(w):
            j = int((x + 1) * per)
            if j <= i:
                j = i + 1
            chunk = data[i:j]
            i = j
            if not chunk:
                continue
            lo = min(chunk) - centre
            hi = max(chunk) - centre
            coords.extend((x, mid - hi * scale, x, mid - lo * scale))
        if len(coords) >= 4:
            c.coords(self.wave_line, *coords)
        c.tag_raise(self.wave_line)

    def _wave_grid(self, c, w, h, volts=False):
        for k in range(1, 4):
            y = h * k / 4.0
            c.create_line(0, y, w, y, fill=GRID, tags="grid")
        # A gridline per second, so the beat interval can be read straight off.
        seconds = max(int(self.window_s), 1)
        for k in range(1, seconds):
            x = w * k / self.window_s
            c.create_line(x, 0, x, h, fill=GRID, tags="grid")

        if volts:
            # The rails and mid-rail, labelled. These are the only three numbers the
            # voltage view is really about.
            mid = h / 2.0
            scale = (h / 2.0 - 4) / (MID_RAIL_V / V_PER_COUNT)
            for value, label, colour in ((0.0, "0 V", WARN),
                                         (MID_RAIL_V, "%.2f V mid-rail" % MID_RAIL_V, SPEC),
                                         (SUPPLY_V, "%.1f V" % SUPPLY_V, WARN)):
                y = mid - ((value - MID_RAIL_V) / V_PER_COUNT) * scale
                c.create_line(0, y, w, y, fill=colour, dash=(4, 3), tags="grid")
                c.create_text(w - 6, y - 7, text=label, fill=colour, anchor="e",
                              font=("Consolas", 8), tags="grid")
            c.create_text(6, 10, text="%g s window - volts at the ADC pin" % self.window_s,
                          fill=MUTED, anchor="w", font=("Consolas", 8), tags="grid")
        else:
            c.create_text(6, 10, text="%g s window - PCG (counts)" % self.window_s,
                          fill=MUTED, anchor="w", font=("Consolas", 8), tags="grid")

    def compute_spectrum(self):
        data = list(self.samples)[-SPECTRUM_SAMPLES:]
        if len(data) < 512:
            self.spectrum = None
            return
        mean = sum(data) / len(data)
        # Centre first: the DC the pipeline deliberately preserves would otherwise
        # dominate bin 0 and flatten everything else against the top of the plot.
        freqs, power = pcgkit.welch([v - mean for v in data], self.rate, nfft=1024)
        peak = max(power) or 1.0
        self.spectrum = (freqs, [10.0 * math.log10(max(p / peak, 1e-12)) for p in power])

    def draw_spectrum(self):
        c = self.spec
        w = max(c.winfo_width(), 2)
        h = max(c.winfo_height(), 2)
        c.delete("grid")
        if not self.spectrum:
            c.coords(self.spec_line, 0, 0, 0, 0)
            return
        freqs, db = self.spectrum
        top_hz = self.rate / 2.0 if self.full_band.get() else 1000.0
        floor_db = -80.0

        # Guides worth having on a phonocardiogram: the top of the heart-sound band,
        # and the fold frequency of the 2 kHz file the algorithm receives.
        for hz, label, colour in ((400.0, "400 Hz", MUTED), (1000.0, "1 kHz fold", WARN)):
            if hz <= top_hz:
                x = w * hz / top_hz
                c.create_line(x, 0, x, h, fill=colour, dash=(3, 3), tags="grid")
                c.create_text(x + 4, 10, text=label, fill=colour, anchor="w",
                              font=("Consolas", 8), tags="grid")
        for d in (-20, -40, -60):
            y = h * (d / floor_db)
            c.create_line(0, y, w, y, fill=GRID, tags="grid")
            c.create_text(4, y - 7, text="%d dB" % d, fill=MUTED, anchor="w",
                          font=("Consolas", 8), tags="grid")
        c.create_text(w - 6, h - 10, text="0-%.0f Hz" % top_hz, fill=MUTED,
                      anchor="e", font=("Consolas", 8), tags="grid")

        bin_hz = (freqs[1] - freqs[0]) if len(freqs) > 1 else 1.0
        coords = []
        for x in range(w):
            hz = top_hz * x / max(w - 1, 1)
            k = min(int(hz / bin_hz), len(db) - 1)
            y = h * (max(db[k], floor_db) / floor_db)
            coords.extend((x, y))
        if len(coords) >= 4:
            c.coords(self.spec_line, *coords)
        c.tag_raise(self.spec_line)

    def draw_status(self):
        data = self.visible()
        if not data:
            self.status.config(text="waiting for frames...  (is the board streaming?)",
                               fg=MUTED)
            return
        mean = sum(data) / len(data)
        peak = max(abs(v - mean) for v in data)
        dbfs = 20.0 * math.log10(max(peak / 32768.0, 1e-6))

        if self.voltage.get():
            lo, hi = min(data), max(data)
            clipped = sum(1 for v in data if v >= 32767 - 16 or v <= -32768 + 16)
            swing_pct = 100.0 * (hi - lo) * V_PER_COUNT / SUPPLY_V
            bits = [
                "bias %.3f V (%+.0f mV off mid)" % (counts_to_volts(mean),
                                                    (counts_to_volts(mean) - MID_RAIL_V) * 1000),
                "min/max %.3f/%.3f V" % (counts_to_volts(lo), counts_to_volts(hi)),
                "swing %.1f%% of rail" % swing_pct,
                "headroom %.3f V" % min(SUPPLY_V - counts_to_volts(hi), counts_to_volts(lo)),
                "clipped %d" % clipped,
            ]
        else:
            bits = [
                "peak %6.1f dBFS /%8.2f mV" % (dbfs, peak * MV_PER_COUNT),
                "DC %+7.1f" % mean,
                "%5.0f Sa/s of %d" % (self.delivered, self.rate),
                "frames %d" % self.reader.frames,
            ]
        colour = TEXT
        if self.reader.dropped or self.reader.overruns:
            bits.append("DROPPED %d  OVERRUNS %d" % (self.reader.dropped, self.reader.overruns))
            colour = WARN
        if self.recording:
            left = max(self.rec_target - len(self.rec_samples), 0) / float(self.rate)
            bits.append("REC %4.1f s left" % left)
        elif self.paused:
            bits.append("PAUSED (display only - the board is still streaming)")
        self.status.config(text="   ".join(bits), fg=colour)

    # --------------------------------------------------------------------- close

    def close(self):
        self.closing = True
        if self.recording:
            try:
                self.finish_record()
            except Exception as exc:
                print("could not save the in-progress recording: %s" % exc)
        try:
            self.sp.write(b"X")
            time.sleep(0.15)
            self.sp.reset_input_buffer()
        except Exception:
            pass
        try:
            self.sp.close()
        except Exception:
            pass
        self.root.destroy()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", help="serial port, e.g. COM5 (default: auto-detect VID 2E8A)")
    ap.add_argument("--seconds", type=float, default=5.0,
                    help="initial plot timebase in seconds (default 5)")
    ap.add_argument("--record-seconds", type=float, default=60.0,
                    help="initial recording length, matching the app's default (60)")
    ap.add_argument("--tone", action="store_true",
                    help="use the firmware's synthetic 100 Hz tone instead of the ADC")
    ap.add_argument("--out", default="captures/bench",
                    help="path prefix for recordings (default captures/bench)")
    ap.add_argument("--list", action="store_true", help="list serial ports and exit")
    args = ap.parse_args()

    if args.list:
        describe_ports()
        return 0

    port = args.port or find_port()
    if not port:
        print("No RP2040 board found (looking for VID 2E8A).")
        describe_ports()
        return 2

    print("Port %s" % port)
    sp, _ = open_board(port)
    sp.reset_input_buffer()
    sp.write(b"T" if args.tone else b"S")

    root = tk.Tk()
    TestingMode(root, sp, FrameReader(), args)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
