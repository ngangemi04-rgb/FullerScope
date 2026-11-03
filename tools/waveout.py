"""
Minimal streaming audio output for Windows, through winmm's waveOut API.

Exists because this machine has no sounddevice, no PyAudio and no PortAudio, and a
bench tool that needs `pip install` before it will make a sound is a bench tool
nobody uses. `winsound` can only play a finished file, so it is no help for a live
monitor. winmm is in every Windows install and ctypes can call it directly.

Usage:

    out = WaveOut(sample_rate=16000)
    while streaming:
        out.write(chunk_of_int16)   # returns False if the queue is full
    out.close()

The design is the standard double-buffer one: a ring of prepared WAVEHDR buffers
is handed to the driver, and each is refilled once the driver sets WHDR_DONE on
it. Nothing blocks — [feed] returns immediately, and [starved] reports whether the
driver ran out, which happens when the board delivers slightly slower than the
sound card consumes.
"""

import ctypes
import struct
from ctypes import wintypes

_winmm = ctypes.WinDLL("winmm")

WAVE_MAPPER = 0xFFFFFFFF
WAVE_FORMAT_PCM = 1
CALLBACK_NULL = 0
WHDR_DONE = 0x00000001
WHDR_PREPARED = 0x00000002
MMSYSERR_NOERROR = 0

# DWORD_PTR is pointer-sized, so it must not be hardcoded to 32 bits or every
# field after it in WAVEHDR lands at the wrong offset on a 64-bit Python.
DWORD_PTR = ctypes.c_size_t


class WAVEFORMATEX(ctypes.Structure):
    _fields_ = [
        ("wFormatTag", wintypes.WORD),
        ("nChannels", wintypes.WORD),
        ("nSamplesPerSec", wintypes.DWORD),
        ("nAvgBytesPerSec", wintypes.DWORD),
        ("nBlockAlign", wintypes.WORD),
        ("wBitsPerSample", wintypes.WORD),
        ("cbSize", wintypes.WORD),
    ]


class WAVEHDR(ctypes.Structure):
    pass


WAVEHDR._fields_ = [
    ("lpData", ctypes.c_void_p),
    ("dwBufferLength", wintypes.DWORD),
    ("dwBytesRecorded", wintypes.DWORD),
    ("dwUser", DWORD_PTR),
    ("dwFlags", wintypes.DWORD),
    ("dwLoops", wintypes.DWORD),
    ("lpNext", ctypes.POINTER(WAVEHDR)),
    ("reserved", DWORD_PTR),
]

HWAVEOUT = ctypes.c_void_p

_winmm.waveOutOpen.argtypes = [
    ctypes.POINTER(HWAVEOUT), ctypes.c_uint, ctypes.POINTER(WAVEFORMATEX),
    DWORD_PTR, DWORD_PTR, wintypes.DWORD,
]
_winmm.waveOutPrepareHeader.argtypes = [HWAVEOUT, ctypes.POINTER(WAVEHDR), ctypes.c_uint]
_winmm.waveOutUnprepareHeader.argtypes = [HWAVEOUT, ctypes.POINTER(WAVEHDR), ctypes.c_uint]
_winmm.waveOutWrite.argtypes = [HWAVEOUT, ctypes.POINTER(WAVEHDR), ctypes.c_uint]
_winmm.waveOutReset.argtypes = [HWAVEOUT]
_winmm.waveOutClose.argtypes = [HWAVEOUT]
_winmm.waveOutGetErrorTextW.argtypes = [ctypes.c_uint, ctypes.c_wchar_p, ctypes.c_uint]


class WaveOutError(RuntimeError):
    pass


def _check(code, what):
    if code != MMSYSERR_NOERROR:
        buf = ctypes.create_unicode_buffer(256)
        _winmm.waveOutGetErrorTextW(code, buf, 256)
        raise WaveOutError("%s failed (%d): %s" % (what, code, buf.value))


class WaveOut:
    """A streaming 16-bit mono output. Not thread-safe; drive it from one loop."""

    def __init__(self, sample_rate, buffer_samples=1024, buffers=4):
        self.sample_rate = sample_rate
        self.buffer_samples = buffer_samples
        self.starved = 0
        self.played = 0

        fmt = WAVEFORMATEX(
            wFormatTag=WAVE_FORMAT_PCM,
            nChannels=1,
            nSamplesPerSec=sample_rate,
            nAvgBytesPerSec=sample_rate * 2,
            nBlockAlign=2,
            wBitsPerSample=16,
            cbSize=0,
        )
        self._h = HWAVEOUT()
        _check(
            _winmm.waveOutOpen(ctypes.byref(self._h), WAVE_MAPPER, ctypes.byref(fmt),
                               0, 0, CALLBACK_NULL),
            "waveOutOpen",
        )

        nbytes = buffer_samples * 2
        self._buffers = []   # keep the Python objects alive; the driver holds raw pointers
        self._headers = []
        for _ in range(buffers):
            mem = ctypes.create_string_buffer(nbytes)
            hdr = WAVEHDR(
                lpData=ctypes.cast(mem, ctypes.c_void_p),
                dwBufferLength=nbytes,
                dwFlags=0,
            )
            _check(
                _winmm.waveOutPrepareHeader(self._h, ctypes.byref(hdr), ctypes.sizeof(hdr)),
                "waveOutPrepareHeader",
            )
            # Mark as done so the first pass through free_slots() picks it up.
            hdr.dwFlags |= WHDR_DONE
            self._buffers.append(mem)
            self._headers.append(hdr)

    def free_slots(self):
        """Number of buffers the driver has finished with and will take again."""
        return sum(1 for h in self._headers if h.dwFlags & WHDR_DONE)

    def write(self, samples):
        """
        Queues up to [buffer_samples] values. Returns the number consumed, which is
        0 when every buffer is still in flight — the caller keeps the rest.
        """
        if not samples:
            return 0
        for i, hdr in enumerate(self._headers):
            if not (hdr.dwFlags & WHDR_DONE):
                continue
            n = min(len(samples), self.buffer_samples)
            packed = _pack(samples[:n])
            ctypes.memmove(self._buffers[i], packed, len(packed))
            hdr.dwBufferLength = len(packed)
            # Clear DONE but keep PREPARED: a finished header can be rewritten
            # directly, and re-preparing it every time is both unnecessary and a
            # good way to leak driver resources.
            hdr.dwFlags &= ~WHDR_DONE
            _check(
                _winmm.waveOutWrite(self._h, ctypes.byref(hdr), ctypes.sizeof(hdr)),
                "waveOutWrite",
            )
            self.played += n
            return n
        return 0

    def note_starved(self):
        """Called by the caller when it had nothing to hand over. Counted, not fixed:
        a gap in a monitor is honest, and papering over it with silence would hide a
        board that is not keeping up."""
        self.starved += 1

    def close(self):
        if self._h is None:
            return
        try:
            _winmm.waveOutReset(self._h)
            for hdr in self._headers:
                if hdr.dwFlags & WHDR_PREPARED:
                    _winmm.waveOutUnprepareHeader(self._h, ctypes.byref(hdr),
                                                  ctypes.sizeof(hdr))
            _winmm.waveOutClose(self._h)
        finally:
            self._h = None
            self._headers = []
            self._buffers = []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def _pack(values):
    return struct.pack("<%dh" % len(values), *values)


def device_count():
    return _winmm.waveOutGetNumDevs()
