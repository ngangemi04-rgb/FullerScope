# Hardware integration — what the app needs from the microcontroller

> **Status as of 3 August 2026: resolved and implemented.** The board enumerates as a USB
> CDC serial device running MicroPython, not as USB Audio Class. Rather than rewrite the
> firmware in C, the app now reads it over USB Host CDC. Working firmware lives in
> [`firmware/main.py`](firmware/) and the app-side transport in `audio/usb/`. Sections 2
> and 2a below are retained because they explain *why* the UAC route was rejected, but
> they no longer describe outstanding work.

## Confirmed specification

| Property | Value |
|---|---|
| Microcontroller | **RP2040** (native USB peripheral) |
| ADC resolution | **12-bit** (RP2040 native) |
| Sample rate | **16 000 Hz** (was 4000 as originally specified, then 8000) |
| Data rate | **32 kB/s** |
| Channels | **1** (mono) |
| Connection | USB-C **OTG / host** |

**On the sample rate.** 4000 Hz was the original spec, 8000 the first firmware, and
**16 000 is now fixed** by the app's signal chain: capture at 16 kHz → decimate by 8 to
the 2 kHz PCG output (`PcgSpec.CAPTURE_SAMPLE_RATE`). The ÷8 is
exact, and 8× oversampling of a ~9-effective-bit ADC is free resolution once the decimation
filter averages it down. At 16 kHz the ADC clock divider is 2999, well above the 96-cycle
floor, so this is nowhere near a hardware limit. The app now **checks and reflashes** the
board's firmware on every attach, so a board running an older rate corrects itself rather
than quietly halving the captured resolution.

Two bytes per sample throughout: each 12-bit reading is padded into a 16-bit word rather
than bit-packed. That is the easy case to consume — see §3 for the one thing that
still matters about *how* it is padded.

It also confirms **one channel**, which settles an earlier open question: the
board's "two outputs" are wires, not two audio channels.

The app captures audio through Android's normal audio framework
(`AudioRecord` + `setPreferredDevice`), routed to whatever USB input the phone
reports. Everything below is about making the microcontroller show up as that
input.

**Before reading further: plug the hardware into the phone, open
Settings → Hardware diagnostics, and tap Refresh.** That screen answers most of
these questions directly and can be shared as text. It is much faster than
reasoning from datasheets.

---

## 1. Physical path — confirmed OK

The signal is digitized on the microcontroller and reaches the phone over a
**USB-C OTG / host adapter**, so the phone is in host mode and the board
enumerates as a real USB device. This is the right arrangement.

> For anyone picking up a different cable later: it must **not** be a USB-C
> *headphone* adapter (the kind with a 3.5 mm jack). Those contain their own
> DAC/ADC and only accept an **analog** signal on the mic pin, so a digital
> stream cannot pass through them at all.

## 2. The microcontroller must enumerate as USB Audio Class

The board uses a chip with a **native USB peripheral** (STM32 / RP2040 / Teensy
class), so implementing **USB Audio Class (UAC)** is a firmware choice rather
than a hardware limitation. That is the target: with a UAC input terminal, the
phone treats the board as a microphone and the app works with no changes.

Check the diagnostics screen. Three outcomes, in order of likelihood:

1. **Listed under *Audio inputs seen by Android* with a `*`** — done, it works.
2. **Listed under *USB devices (raw)* with interface class `AUDIO`, but absent
   from the audio inputs list.** This is the common near-miss: the descriptors
   advertise audio but Android won't open it as a recording source. Usually the
   input terminal is missing, the device is declared output-only, or the audio
   streaming interface has no alternate setting with a format Android accepts
   (16-bit PCM at a supported rate). Firmware fix, not an app fix.
3. **Interface class `CDC_DATA` / `COMM`, or `VENDOR_SPECIFIC`.** The firmware is
   streaming raw bytes rather than presenting as audio. `AudioRecord` cannot see
   this at all. Options: change the firmware to UAC (preferred, since the chip
   supports it), or add a USB Host + CDC capture path to the app to replace
   `audio/StethoscopeRecorder.kt`. The latter is real work — worth avoiding if
   the firmware can be changed instead.

## 2a. 4000 Hz may not be accepted by the phone

> **Resolved, and moot on the USB path.** The capture rate is now **16 kHz**, which every
> phone accepts, and the USB CDC path does not go through `AudioRecord` at all — there is
> no audio HAL to refuse a rate or silently resample. This section only still applies to
> the microphone fallback used for development. Retained because the reasoning is what
> chose 16 kHz.

This was the biggest open risk, and it is an Android limitation rather than a
firmware one. **Android only guarantees 44100 Hz.** Most phones support 8000,
16000, 44100 and 48000; **4000 Hz is frequently refused outright** by
`AudioRecord`, regardless of what the USB device advertises.

The app requests 16 000 Hz, and if the phone refuses it, `audio/AudioCapture.kt` falls
back through the device's advertised rates and then 8000 / 48000 / 4000 / 44100, recording
whichever it managed to open. The rate actually used is stored on the recording, shown on
the diagnostics screen with a warning when it differs from the request, and called out in
red on the testing-mode scope.

Falling back means the USB audio HAL **resamples** — it interpolates samples the
board never produced. Harmless for playback, but the algorithm team needs to know
it happened, which is why it is surfaced rather than silently absorbed.

The *Buffer probe* table on the diagnostics screen shows exactly which rates that
phone will accept. If 4000 is unsupported there, the options are: accept 8 kHz
capture with resampling, or have the firmware sample and advertise 8 kHz natively
(the RP2040 ADC runs to 500 kSPS, so 8 kHz is not a constraint). **Native 8 kHz
is the better fix** if the algorithm team is happy with that rate.

## 3. There is no 12-bit USB audio format

UAC PCM supports 8, 16, 24, and 32-bit subframes. **12-bit is not a valid wire
format**, so the firmware has to place each 12-bit ADC sample inside a 16-bit
word. There are two ways to do that, and they sound very different:

| Packing | What the phone receives | Result |
|---|---|---|
| **Left-aligned** (`(sample - 2048) << 4`, signed) | Uses the full ±32767 range | Correct — ideal |
| **Right-aligned** (±2047 signed) | Uses 1/16 of the range | ~24 dB too quiet |
| **Unsigned offset binary** (raw 0–4095) | Huge DC offset *and* 1/16 range | Heart sounds swamped |

**Left-aligned signed is what you want.** The RP2040's `adc_read()` returns an
*unsigned* 0–4095 centred on 2048, so writing it straight into a `uint16_t`
produces the third row — the worst of the three. Subtract 2048 to centre it, then
shift left by 4:

```c
int16_t sample = ((int16_t)adc_read() - 2048) << 4;
```

The app defends against the other two anyway — `audio/SignalConditioner.kt` runs
one pass over each finished recording, subtracts the DC offset, and applies an
exact power-of-two gain (×16 for right-aligned 12-bit). After recording, the
diagnostics screen reports what it had to do:

```
Captured 1 channel(s) · peak 2013 (~12-bit), DC offset 2048, gain applied x16
```

A gain of ×16 there is proof the firmware is sending right-aligned samples. The
correction is exact and lossless, so it is not urgent to fix — but fixing it in
firmware is cleaner.

## 4. Channels — mono, settled

The 8 kB/s data rate confirms a single channel, so nothing needs deciding here.

The app still handles two-channel hardware if that ever changes: it captures
stereo and folds to mono via **Settings → Input channel** (*Left / ch 1*,
*Right / ch 2*, *Mix both*), because the diagnostic algorithm expects one channel.
That path is currently unused.

---

## Checklist

- [x] Adapter is USB-C **OTG/host**, not a headphone adapter
- [x] Channel count known: **mono**
- [x] Capture firmware written and verified on the board (`firmware/main.py`)
- [x] App reads the board over USB Host CDC (`audio/usb/`)
- [x] Sample rate settled: **16 kHz**, decimated ÷8 to 2 kHz (no filtering since 2026-09-23)
- [x] App checks and reflashes board firmware on attach, so the rate cannot drift
- [x] DC bias measured at **2047.7** against an ideal 2048 — mid-rail biasing is correct
- [ ] Verify on the phone: USB permission prompt, then a recording with 0 dropped frames
- [ ] **Verify the 16 kHz firmware on the real board** — 32 kB/s over CDC and a 1024-sample
      frame are both untested; watch `test_stream.py` for seq gaps and ADC overruns
- [ ] **Verify the app's firmware auto-flash against the real board**, including the
      bootstrap case of a board with an empty filesystem
- [ ] Confirm signal amplitude on a real chest (bench idle showed only 279 of 4095 counts)
- [ ] Confirm the analog anti-alias corner is below 8 kHz (half the 16 kHz sample rate).
      **This matters more than it used to.** Nothing digital can help with content that
      aliases inside the ADC, and the 600 Hz lowpass that used to clean up afterwards is
      gone — whatever the front end passes now reaches the algorithm.
- [x] Algorithm team has confirmed the 2 kHz target rate, and asked for **no filtering**
      (2026-09-23) — they condition the signal themselves

## Superseded: the USB Audio Class route

Kept for reference. The RP2040 has a native USB peripheral, so a C firmware built on the
Pico SDK and TinyUSB could enumerate as a USB Audio Class microphone, and the app's
original `AudioRecord` path would then have worked unchanged. That route was rejected
because the board already runs MicroPython — which cannot do UAC — and rewriting in C plus
authoring UAC descriptors is substantially more work than reading a serial stream. The
CDC route also removes the audio HAL from the path entirely, which eliminated the separate
risk that Android would refuse the sample rate.
