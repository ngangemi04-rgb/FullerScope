"""
Host-side test for the StethoScope firmware.

Soft-resets the board, reads the identify banner, then exercises both the
synthetic test tone and the real ADC, decoding STH1 frames and reporting
statistics. Run with the board on a USB port:

    python firmware/test_stream.py COM5
"""
import struct
import sys
import time

import serial

MAGIC = b"STH1"
HEADER_LEN = 16
PORT = sys.argv[1] if len(sys.argv) > 1 else "COM5"


def read_frames(sp, seconds, label):
    """Collect frames for a while and report what arrived."""
    buf = bytearray()
    frames = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        chunk = sp.read(4096)
        if chunk:
            buf += chunk
        # Pull out every complete frame currently buffered.
        while True:
            i = buf.find(MAGIC)
            if i < 0 or len(buf) - i < HEADER_LEN:
                break
            seq, n, rate, flags, _ = struct.unpack_from("<HHIHH", buf, i + 4)
            need = HEADER_LEN + n * 2
            if n == 0 or n > 4096 or len(buf) - i < need:
                break
            body = buf[i + HEADER_LEN: i + need]
            samples = struct.unpack("<%dH" % n, body)
            frames.append((seq, n, rate, flags, samples))
            del buf[: i + need]

    print("--- %s ---" % label)
    if not frames:
        print("  NO FRAMES RECEIVED")
        return None

    seqs = [f[0] for f in frames]
    gaps = sum(
        1 for a, b in zip(seqs, seqs[1:]) if ((b - a) & 0xFFFF) != 1
    )
    overruns = sum(1 for f in frames if f[3] & 1)
    rate = frames[0][2]
    n = frames[0][1]
    allsamp = [s for f in frames for s in f[4]]
    total = len(allsamp)

    print("  frames        : %d" % len(frames))
    print("  samples/frame : %d" % n)
    print("  rate declared : %d Hz" % rate)
    print("  seq gaps      : %d" % gaps)
    print("  ADC overruns  : %d" % overruns)
    print("  throughput    : %.0f samples/s over %.1fs" % (total / seconds, seconds))
    print("  raw 12-bit    : min %d  max %d  mean %.1f  span %d"
          % (min(allsamp), max(allsamp), sum(allsamp) / total, max(allsamp) - min(allsamp)))
    centred = [(s - 2048) << 4 for s in allsamp]
    print("  as int16      : min %d  max %d" % (min(centred), max(centred)))
    return frames


def main():
    sp = serial.Serial(PORT, 115200, timeout=0.3)
    sp.dtr = True
    try:
        # Ctrl-D at the REPL soft-resets, which runs main.py.
        sp.write(b"\x04")
        time.sleep(1.5)
        banner = sp.read(4096).decode("utf8", "replace")
        print("=== banner ===")
        for line in banner.splitlines():
            if line.strip():
                print("  " + line.strip())
        print()

        sp.reset_input_buffer()
        sp.write(b"T")
        tone = read_frames(sp, 2.0, "synthetic 100 Hz test tone")
        sp.write(b"X")
        time.sleep(0.4)
        sp.reset_input_buffer()
        print()

        sp.write(b"S")
        adc = read_frames(sp, 2.0, "live ADC capture")
        sp.write(b"X")
        time.sleep(0.3)
        print()

        ok = tone is not None and adc is not None
        print("RESULT:", "both paths streaming" if ok else "FAILURE - see above")
        if adc:
            allsamp = [s for f in adc for s in f[4]]
            span = max(allsamp) - min(allsamp)
            mean = sum(allsamp) / len(allsamp)
            print()
            print("Analog front-end read:")
            if span < 20:
                print("  Signal is essentially flat (span %d). The ADC pin is seeing a" % span)
                print("  steady voltage - check the mic, the preamp, and that the filter")
                print("  output actually reaches GP26.")
            if not (700 < mean < 3400):
                print("  DC bias is at %.0f of 4095, close to a rail. Mid-rail is ~2048;" % mean)
                print("  a signal biased near 0 or 3.3 V loses half its swing to clipping.")
            elif span >= 20:
                print("  Bias %.0f (mid-rail is 2048), swing %d counts of 4095." % (mean, span))
    finally:
        try:
            sp.write(b"X")
        except Exception:
            pass
        sp.close()


if __name__ == "__main__":
    main()
