"""
Generates StethoScope_Technical_Documentation.docx

Run:  python docs/build_docs.py
Deps: pip install python-docx
"""
import os

from docx import Document
from docx.enum.section import WD_ORIENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

# ---------------------------------------------------------------- constants

CONTENT_W = 6.5          # inches of usable width (US Letter, 1" margins)
MONO = "Consolas"
BODY = "Calibri"
HEAD = "Calibri Light"
ACCENT = RGBColor(0x1F, 0x4E, 0x79)
ACCENT_HEX = "1F4E79"
H2_COLOR = RGBColor(0x2E, 0x5C, 0x8A)
H3_COLOR = RGBColor(0x44, 0x54, 0x6A)
GREY = "F2F2F2"
HDR_BG = "D9E2F3"
ZEBRA = "FAFAFA"

# Schema-mandated child order for <w:pPr>; appending out of order corrupts the file.
PPR_ORDER = [
    "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl",
    "numPr", "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens",
    "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE",
    "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind",
    "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection",
    "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr",
    "sectPr", "pPrChange",
]
TCPR_ORDER = [
    "cnfStyle", "tcW", "gridSpan", "hMerge", "vMerge", "tcBorders", "shd", "noWrap",
    "tcMar", "textDirection", "tcFitText", "vAlign", "hideMark",
]


def _insert_ordered(parent, el, order):
    tag = el.tag.split("}")[1]
    idx = order.index(tag)
    for existing in list(parent):
        etag = existing.tag.split("}")[1]
        if etag in order and order.index(etag) > idx:
            existing.addprevious(el)
            return
    parent.append(el)


def shade_paragraph(par, fill):
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    _insert_ordered(par._p.get_or_add_pPr(), shd, PPR_ORDER)


def left_bar(par, color=ACCENT_HEX, size=18):
    pbdr = OxmlElement("w:pBdr")
    left = OxmlElement("w:left")
    left.set(qn("w:val"), "single")
    left.set(qn("w:sz"), str(size))
    left.set(qn("w:space"), "8")
    left.set(qn("w:color"), color)
    pbdr.append(left)
    _insert_ordered(par._p.get_or_add_pPr(), pbdr, PPR_ORDER)


def shade_cell(cell, fill):
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    _insert_ordered(cell._tc.get_or_add_tcPr(), shd, TCPR_ORDER)


# ---------------------------------------------------------------- builders

doc = Document()

sec = doc.sections[0]
sec.orientation = WD_ORIENT.PORTRAIT
sec.page_width = Inches(8.5)
sec.page_height = Inches(11)
for side in ("top", "bottom", "left", "right"):
    setattr(sec, f"{side}_margin", Inches(1))

normal = doc.styles["Normal"]
normal.font.name = BODY
normal.font.size = Pt(11)
normal.paragraph_format.space_after = Pt(6)
normal.paragraph_format.line_spacing = 1.15

for name, size, color, font in (
    ("Heading 1", 17, ACCENT, HEAD),
    ("Heading 2", 13.5, H2_COLOR, HEAD),
    ("Heading 3", 11.5, H3_COLOR, BODY),
):
    st = doc.styles[name]
    st.font.name = font
    st.font.size = Pt(size)
    st.font.bold = True
    st.font.color.rgb = color
    st.paragraph_format.space_before = Pt(14)
    st.paragraph_format.space_after = Pt(6)
    st.paragraph_format.keep_with_next = True


def h(text, level=1):
    return doc.add_heading(text, level=level)


def para(text, **kw):
    p = doc.add_paragraph()
    r = p.add_run(text)
    for k, v in kw.items():
        setattr(r.font, k, v)
    return p


def rich(parts):
    """parts: list of str or (str, {'bold': True, ...})"""
    p = doc.add_paragraph()
    for item in parts:
        if isinstance(item, tuple):
            txt, opts = item
            r = p.add_run(txt)
            for k, v in opts.items():
                setattr(r.font, k, v)
        else:
            p.add_run(item)
    return p


def bullet(text):
    return doc.add_paragraph(text, style="List Bullet")


def step(text):
    return doc.add_paragraph(text, style="List Number")


def code(lines):
    for i, line in enumerate(lines):
        p = doc.add_paragraph()
        pf = p.paragraph_format
        pf.left_indent = Inches(0.18)
        pf.space_after = Pt(8 if i == len(lines) - 1 else 0)
        pf.space_before = Pt(4 if i == 0 else 0)
        pf.line_spacing = 1.0
        r = p.add_run(line if line else " ")
        r.font.name = MONO
        r.font.size = Pt(9)
        shade_paragraph(p, GREY)


def note(label, text):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.left_indent = Inches(0.14)
    pf.right_indent = Inches(0.14)
    pf.space_before = Pt(8)
    pf.space_after = Pt(10)
    left_bar(p)
    shade_paragraph(p, GREY)
    r = p.add_run(label + " ")
    r.font.bold = True
    r.font.color.rgb = ACCENT
    p.add_run(text)
    return p


def table(headers, rows, fracs, mono_cols=()):
    """fracs: column widths as fractions of CONTENT_W, must sum to 1."""
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Table Grid"
    t.autofit = False
    widths = [Inches(CONTENT_W * f) for f in fracs]

    hdr = t.rows[0].cells
    for i, text in enumerate(headers):
        hdr[i].text = ""
        p = hdr[i].paragraphs[0]
        p.paragraph_format.space_after = Pt(0)
        r = p.add_run(text)
        r.font.bold = True
        r.font.size = Pt(9.5)
        shade_cell(hdr[i], HDR_BG)

    for ri, row in enumerate(rows):
        cells = t.add_row().cells
        for ci, text in enumerate(row):
            cells[ci].text = ""
            p = cells[ci].paragraphs[0]
            p.paragraph_format.space_after = Pt(0)
            r = p.add_run(text)
            r.font.size = Pt(8.5) if ci in mono_cols else Pt(9.5)
            if ci in mono_cols:
                r.font.name = MONO
            if ri % 2 == 1:
                shade_cell(cells[ci], ZEBRA)

    for row in t.rows:
        for ci, w in enumerate(widths):
            row.cells[ci].width = w
    doc.add_paragraph().paragraph_format.space_after = Pt(4)
    return t


def toc_field():
    p = doc.add_paragraph()
    r = p.add_run()
    begin = OxmlElement("w:fldChar")
    begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = r'TOC \o "1-3" \h \z \u'
    sep = OxmlElement("w:fldChar")
    sep.set(qn("w:fldCharType"), "separate")
    end = OxmlElement("w:fldChar")
    end.set(qn("w:fldCharType"), "end")
    for el in (begin, instr, sep):
        r._r.append(el)
    ph = p.add_run("Right-click here and choose Update Field to build the contents.")
    ph.font.italic = True
    ph.font.color.rgb = RGBColor(0x88, 0x88, 0x88)
    p.add_run()._r.append(end)


def enable_update_fields():
    s = doc.settings.element
    el = OxmlElement("w:updateFields")
    el.set(qn("w:val"), "true")
    s.append(el)


def centered(text, size=11, bold=False, italic=False, color=None, font=None, after=6):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_after = Pt(after)
    r = p.add_run(text)
    r.font.size = Pt(size)
    r.font.bold = bold
    r.font.italic = italic
    if color:
        r.font.color.rgb = color
    if font:
        r.font.name = font
    return p


# ================================================================ TITLE

for _ in range(6):
    doc.add_paragraph()
centered("StethoScope", size=44, bold=True, color=ACCENT, after=6)
centered("Technical Documentation", size=22, color=RGBColor(0x55, 0x55, 0x55), after=26)
centered("Android application for digital heart auscultation", size=12, italic=True, after=4)
centered("USB-C stethoscope capture, PhysioNet-compatible signal conditioning,",
         size=11, color=RGBColor(0x66, 0x66, 0x66), after=0)
centered("binary rhythm diagnosis, and WhatsApp reporting",
         size=11, color=RGBColor(0x66, 0x66, 0x66), after=60)
centered("Capstone Project \u2014 Northeastern University", size=11, after=2)
centered("Document generated 12 August 2026", size=10, color=RGBColor(0x88, 0x88, 0x88), after=2)
centered("Source: C:\\dev\\StethoScope  \u2014  59 Kotlin source files, ~6,700 lines",
         size=9, color=RGBColor(0x88, 0x88, 0x88), font=MONO, after=2)
doc.add_page_break()

# ================================================================ TOC

h("Contents")
toc_field()
doc.add_page_break()

# ================================================================ 1

h("1. Overview")
para(
    "StethoScope is an Android application for digital heart auscultation. It captures heart "
    "sounds from a custom USB-C stethoscope, conditions the signal into a research-standard "
    "format, classifies it as normal or abnormal, and presents a result that a clinician can "
    "review, store, and send to a doctor over WhatsApp."
)
para(
    "The application is designed for field use in Ghana. That context drives several design "
    "decisions that would otherwise look unusual: WhatsApp is the primary delivery channel "
    "rather than email or a hospital network, the interface is available in English and Twi, "
    "and patient data is encrypted at rest on the assumption that devices are shared and may "
    "be lost."
)

h("1.1 Scope", 2)
rich([("In scope: ", {"bold": True}),
      "audio capture from the stethoscope hardware, live monitoring, recording, signal "
      "preprocessing, storage, encryption, report generation, sharing, localization, and the "
      "integration surface for the diagnostic algorithm."])
rich([("Out of scope: ", {"bold": True}),
      "the diagnostic algorithm itself, which a separate team implements against a single "
      "interface (Section 8), and the stethoscope firmware, which is specified in Section 5 "
      "but not written here."])
note("Diagnosis granularity.",
     "The result is NORMAL or ABNORMAL (possible murmur), plus UNKNOWN when too few clean "
     "cardiac cycles could be segmented. It is a screening aid: reported sensitivity of 76% "
     "means roughly one abnormal recording in four is missed, and the UI and report say so.")

h("1.2 Current status", 2)
para(
    "The system runs end to end on target hardware. Capture over USB from the stethoscope, "
    "signal conditioning, classification, PDF generation and WhatsApp delivery have all been "
    "exercised on a Motorola Moto G7 Power running Android 10, with the real board attached. "
    "All 73 unit tests pass."
)
para(
    "The classification stage is a Kotlin port of the team's MATLAB reference. Its individual "
    "components are verified against independent references, but the end-to-end feature values "
    "have not yet been diffed against the original — that comparison, and characterising the "
    "analogue front end against a real chest, are the two largest outstanding items. Section 15 "
    "lists everything still unverified."
)

# ================================================================ 2

h("2. Technology stack")
para(
    "Versions are pinned to match Android Studio AI-261 (2025.2) with SDK 36 and the bundled "
    "JBR 21. The single place to change any of them is gradle/libs.versions.toml."
)
table(
    ["Component", "Version", "Notes"],
    [
        ["Language", "Kotlin 2.1.21", "Coroutines for all asynchronous work"],
        ["UI toolkit", "Jetpack Compose", "BOM 2025.06.01, Material 3"],
        ["Architecture", "MVVM", "Single Activity, Navigation Compose 2.9.0"],
        ["Build", "AGP 8.11.1 / Gradle 8.13", "KSP 2.1.21-2.0.2"],
        ["Persistence", "Room 2.7.2", "Schema version 3"],
        ["Dependency injection", "Manual (AppContainer)", "No Hilt or Dagger, by choice"],
        ["Encryption", "Android Keystore", "AES-256-GCM, no third-party libraries"],
        ["Testing", "JUnit 4.13.2", "Local JVM tests only"],
        ["compileSdk / targetSdk", "36", "minSdk 26 (Android 8.0)"],
        ["Java / JVM target", "17", "Android Studio's bundled JBR works"],
    ],
    [0.28, 0.28, 0.44],
)
note("No third-party signal-processing library.",
     "The resampling and filtering in Section 7 are implemented from first principles in "
     "Kotlin. This avoids adding a native dependency to an app that must be easy to build and "
     "audit, and it keeps the mathematics inspectable and unit-testable.")

# ================================================================ 3

h("3. Architecture")
para(
    "A single Activity hosts a Compose navigation graph. Each screen has a ViewModel exposing "
    "immutable StateFlow properties; screens are otherwise stateless. All shared objects are "
    "constructed once in AppContainer and reached by ViewModels through their factories."
)

h("3.1 Package structure", 2)
table(
    ["Package", "Files", "Responsibility"],
    [
        ["analysis", "3", "HeartbeatClassifier contract, mock and team implementations"],
        ["analysis/pcg", "4", "PhysioNet-compatible preprocessing: spec, resampler, filter, pipeline"],
        ["audio", "11", "Capture, live monitoring, device negotiation, WAV IO, 12-bit conditioning, USB diagnostics"],
        ["data", "3", "Repository, settings, in-session state"],
        ["data/db", "3", "Room database, DAO, encrypted entity"],
        ["data/model", "2", "Recording and PatientDraft domain models"],
        ["di", "1", "AppContainer \u2014 the manual dependency graph"],
        ["export", "3", "PDF report, filename policy, WhatsApp sharing"],
        ["locale", "1", "Per-app language switching"],
        ["security", "1", "Keystore AES-256-GCM encryption"],
        ["ui", "20", "Compose screens: listen, record, review, result, library, settings, diagnostics"],
    ],
    [0.20, 0.09, 0.71],
    mono_cols=(0,),
)

h("3.2 Layering rules", 2)
bullet("UI observes ViewModels. It never touches the repository, recorder, or crypto directly.")
bullet("ViewModels orchestrate. They own coroutine scopes and translate domain results into UI state.")
bullet("The repository is the only component that reads or writes recordings, and the only one that calls Crypto.")
bullet("The audio layer knows nothing about patients, storage, or the algorithm \u2014 it produces WAV files.")
bullet("The analysis layer receives a prepared signal and returns a verdict, with no Android dependency beyond File.")

h("3.3 End-to-end data flow", 2)
code([
    "Listen screen        LiveMonitor  -> AudioRecord -> AudioTrack (earbuds)",
    "     |                            -> LevelMeter  -> Waveform",
    "     v",
    "Record screen        StethoscopeRecorder",
    "                       -> AudioCapture.open()      negotiate rate + channels",
    "                       -> stream PCM to temp WAV   header patched on stop",
    "                       -> SignalConditioner        12-bit DC + gain correction",
    "     v",
    "                     RecordingRepository.encryptRecording()",
    "                       -> Crypto (AES-256-GCM)  -> rec_<ts>.wav.enc",
    "                       -> Room row, patient fields encrypted",
    "     v",
    "Review screen        decryptToCache() -> AudioPlayer -> delete temp",
    "     v",
    "Result screen        decryptToCache()",
    "                       -> PcgPreprocessor  -> 2 kHz PhysioNet WAV",
    "                       -> HeartbeatClassifier.classify()",
    "                       -> HeartSoundResult stored on the Recording",
    "     v",
    "Export               ReportExporter (PDF) + WhatsAppSharer (PDF + WAV)",
])

# ================================================================ 4

doc.add_page_break()
h("4. Application flow")
table(
    ["Screen", "Route", "Purpose"],
    [
        ["Listen", "listen", "Start destination. Optional patient form, live waveform, earbud passthrough for positioning the stethoscope."],
        ["Review", "review/{id}", "Play the clip back, then Retake or Analyze."],
        ["Result", "result/{id}", "Runs the classifier, shows Normal or Abnormal with the probability of abnormal, offers export and WhatsApp."],
        ["Library", "library", "All stored recordings with their diagnoses."],
        ["Settings", "settings", "Language, sample rate, input channel, entry point to diagnostics."],
        ["Diagnostics", "diagnostics", "Read-only dump of what the phone sees on the USB port, with a plain-language verdict."],
    ],
    [0.16, 0.18, 0.66],
    mono_cols=(1,),
)

h("4.1 Listen", 2)
para(
    "The start destination, and the screen that makes the device usable. A clinician needs to "
    "hear the patient while positioning the stethoscope, so LiveMonitor continuously captures "
    "audio and writes it straight back out to a connected headset. Passthrough is muted unless "
    "a headset output is actually present, because routing microphone input to the phone "
    "speaker produces a feedback howl."
)
para(
    "Patient details entered here are optional and held in memory by SessionStore, then "
    "attached to whichever recording follows. They are cleared when the flow completes."
)

h("4.2 Recording in place", 2)
para(
    "Recording happens on the Listen screen rather than a screen of its own. It was originally "
    "separate, but navigating away removed the device-status banner and the patient details from "
    "view at the moment they mattered most. A countdown bar now pins itself below the toolbar, "
    "the waveform turns red, and competing controls are disabled until capture completes."
)
para(
    "PCM is streamed directly to a temporary WAV file rather than buffered in memory; because "
    "the total length is not known until the recording stops, 44 bytes are reserved at the head "
    "of the file and the header is patched in place at the end. On completion the clip is "
    "conditioned for 12-bit sources (Section 5.7), encrypted, and inserted into the database "
    "with the patient details from the session."
)
note("One capture device.",
     "Listening and recording both open a PCM source, and the USB stethoscope's interface can "
     "only be claimed once, so starting a recording explicitly stops live monitoring first. "
     "Navigating away used to handle that implicitly.")

h("4.3 Review and Diagnosis", 2)
para(
    "Review decrypts the recording to a temporary cache file for playback and deletes it "
    "afterwards. Diagnosis decrypts again, runs the PCG preprocessing pipeline, calls the "
    "classifier, stores the verdict, and offers the export actions. Both temporary plaintext "
    "files are deleted in a finally block whether or not analysis succeeds."
)

# ================================================================ 5

doc.add_page_break()
h("5. Hardware integration")

h("5.1 Measured state of the hardware, 3 August 2026", 2)
para(
    "The board was connected to a Windows PC and enumerated. This is what it actually reported, "
    "which differs in an important way from the specification the project had been assuming:"
)
table(
    ["Observation", "Value"],
    [
        ["USB vendor / product ID", "VID_2E8A  PID_0005"],
        ["Enumerated as", "USB Composite Device with a CDC function"],
        ["Windows device class", "Ports \u2014 \"USB Serial Device (COM5)\""],
        ["New audio endpoint", "None"],
        ["Serial output", "MicroPython v1.28.0 REPL banner"],
        ["Board identifier", "RPI_PICO, RP2040 at 125 MHz"],
        ["Filesystem contents", "os.listdir() returned [] \u2014 empty"],
        ["rp2.DMA available", "Yes"],
    ],
    [0.32, 0.68],
    mono_cols=(1,),
)
para(
    "Two conclusions follow. VID 2E8A is Raspberry Pi and PID 0005 is the standard MicroPython "
    "/ Pico SDK CDC identity, which confirms the RP2040. And because the filesystem is empty, "
    "there is no capture firmware on the board at all \u2014 the CDC port is MicroPython's "
    "interactive REPL, not a sample stream."
)
note("Correction to an earlier assumption.",
     "The 4000 Hz, 12-bit, 8 kB/s figures recorded elsewhere in this project are design intent, "
     "not measured behaviour. Nothing was streaming when the board was examined. Section 5.3 "
     "explains why this matters more than it first appears.")

h("5.2 Intended specification", 2)
para("The design intent for the capture chain, to be implemented in firmware:")
table(
    ["Property", "Value", "Consequence for the app"],
    [
        ["Microcontroller", "RP2040 (Raspberry Pi Pico)", "Native USB peripheral; DMA available for jitter-free sampling"],
        ["ADC resolution", "12-bit", "Needs the conditioning pass in Section 5.7"],
        ["Sample rate", "4000 Hz", "Below what Android guarantees \u2014 see Section 5.6"],
        ["Data rate", "8 kB/s", "2 bytes per sample, so 12 bits padded into 16-bit words"],
        ["Channels", "1 (mono)", "Derived from the data rate; the stereo path is unused"],
        ["Connection", "USB-C OTG / host", "Phone is USB host; board enumerates as a USB device"],
    ],
    [0.20, 0.24, 0.56],
)
para(
    "The data rate is worth spelling out because it settles two questions at once. 8000 bytes "
    "per second divided by 4000 samples per second is 2 bytes per sample, which means each "
    "12-bit reading is padded into a 16-bit word rather than bit-packed, and that there is "
    "exactly one channel."
)

h("5.3 Why MicroPython rules out USB Audio Class", 2)
para(
    "The application's existing capture path uses AudioRecord with setPreferredDevice, which "
    "only works when Android exposes the hardware as a recording input. That in turn requires "
    "the device to enumerate as USB Audio Class with an input terminal."
)
para(
    "MicroPython's USB stack on the RP2040 provides CDC (serial) and MSC (mass storage). It has "
    "no USB Audio Class support, and implementing UAC descriptors in Python is not a realistic "
    "proposition. So as long as the board runs MicroPython, Android's audio framework will "
    "never see it \u2014 which is exactly what the enumeration in Section 5.1 shows."
)
para("That leaves two viable routes, and the project has chosen the second:")
table(
    ["Route", "Firmware work", "App work", "Trade-off"],
    [
        ["Rewrite in C (Pico SDK + TinyUSB) as a UAC microphone",
         "Substantial \u2014 abandon MicroPython, write C, author UAC descriptors",
         "None \u2014 already built and tested",
         "Board becomes a standard microphone, recordable in Audacity on a laptop, which is excellent for validating the analog front end independently"],
        ["Keep MicroPython, stream samples over CDC  (chosen)",
         "Small \u2014 roughly 40 lines using rp2.DMA",
         "New USB Host CDC capture layer",
         "Bypasses Android's audio HAL entirely, so the 4 kHz refusal risk and any HAL resampling disappear; exact 12-bit samples at exactly the board's rate"],
    ],
    [0.22, 0.24, 0.18, 0.36],
)
note("Status of the chosen route.",
     "Implemented and verified. Firmware lives in firmware/main.py and the host-side transport "
     "in audio/usb/. Bench testing recorded zero dropped frames and zero converter overruns at "
     "7936 samples per second against a declared 8000, and the full path has been confirmed "
     "working on the phone with the real board attached. The built-in microphone remains as a "
     "fallback so the app can be exercised with no hardware present.")

h("5.4 The USB path", 2)
para(
    "The signal is digitized on the board, so the phone must receive it as USB data. The "
    "connector between board and phone must be a plain USB-C OTG / host adapter."
)
note("Adapter warning.",
     "It must not be a USB-C headphone adapter \u2014 the kind with a 3.5 mm jack. Those contain "
     "their own DAC/ADC and accept only an analog signal on the microphone pin, so a digital "
     "stream cannot pass through them at all. This is the most likely cause of \"nothing "
     "happens when I plug it in\".")

h("5.5 Hardware diagnostics screen", 2)
para(
    "Reached from Settings. Built because the capture path depends entirely on how the hardware "
    "presents itself, and that cannot be determined from a datasheet \u2014 as Section 5.1 "
    "demonstrates. Enumerating USB devices needs no permission, so it is safe to run at any "
    "time. It reports:"
)
bullet("Raw USB devices: vendor and product ID, manufacturer and product strings, device class, and every interface with its class, subclass, protocol, and endpoint types.")
bullet("Audio inputs as Android sees them: device type, product name, advertised sample rates, channel counts, and encodings.")
bullet("An AudioRecord buffer probe showing which rates that specific phone will actually accept, mono and stereo, which is not always what the device advertises.")
bullet("The last recording: negotiated rate, channel count, and the conditioning statistics from Section 5.7.")
bullet("A plain-language verdict distinguishing USB Audio Class, a CDC/serial device, a vendor-specific device, and nothing detected at all.")
para(
    "The report can be shared as text, which makes it the fastest way to settle a hardware "
    "question remotely. On the current firmware it would report the CDC/serial verdict."
)

h("5.6 Sample-rate negotiation", 2)
para(
    "Android guarantees only 44100 Hz. Most phones support 8000, 16000, 44100 and 48000, and "
    "many reject 4000 outright regardless of what the USB device advertises. AudioCapture "
    "therefore tries the requested rate first, then any rate the device advertises, then a "
    "fallback list, and reports which rate it actually opened."
)
code(["Fallback order: 8000, 16000, 48000, 4000, 44100, 22050, 11025"])
para(
    "The order favours integer multiples of the 2 kHz preprocessing target: 8000 divides by 4, "
    "16000 by 8, and 48000 by 24, all of which resample with short, cheap filters. 44100 to "
    "2000 needs a 20/441 rational conversion with a filter two orders of magnitude longer."
)
note("Why this is no longer critical.",
     "Because the pipeline resamples everything to 2 kHz anyway (Section 7), the capture rate "
     "no longer has to match the board. The rate actually used is written into the WAV header, "
     "stored on the Recording, and flagged in red on the diagnostics screen when it differs "
     "from the request, because a silent resample is exactly the kind of thing that misleads an "
     "algorithm team later. On the chosen CDC route the question disappears entirely, since the "
     "audio HAL is not involved.")

h("5.7 12-bit signal conditioning", 2)
para(
    "AudioRecord always hands back 16-bit PCM. When the source is a 12-bit ADC, the firmware "
    "must place each reading inside a 16-bit word, and there are three ways to do it that sound "
    "very different."
)
table(
    ["Packing", "What the phone receives", "Result"],
    [
        ["Left-aligned, centred", "Full \u00b132767 range", "Correct \u2014 ideal"],
        ["Right-aligned signed", "Only \u00b12047, one sixteenth of range", "About 24 dB too quiet"],
        ["Unsigned offset binary", "Raw 0\u20134095, centred on 2048", "Large DC offset and 1/16 range"],
    ],
    [0.26, 0.34, 0.40],
)
para(
    "In C, the RP2040 returns unsigned 0\u20134095 from adc_read(), so writing it straight into a "
    "uint16_t produces the third and worst case. The recommended line is:"
)
code(["int16_t sample = ((int16_t)adc_read() - 2048) << 4;"])
para(
    "In MicroPython the situation differs usefully: machine.ADC.read_u16() already scales the "
    "12-bit reading up to a full 16-bit unsigned range, so the result is centred near 32768 "
    "with the full span used. That produces a large DC offset but no loss of range, and the "
    "conditioner removes the offset while correctly applying no gain."
)
para("SignalConditioner defends against all three variants. After a recording completes it makes one pass over the finished file:")
step("Measure and subtract the mean, removing DC offset from offset-binary data and any electrical bias in the analog front end.")
step("Apply a single power-of-two gain derived from the observed peak, so right-aligned 12-bit data is restored by exactly 16x rather than by an arbitrary float.")
step("Clamp, so a stray outlier cannot wrap around to full-scale negative \u2014 which would sound like a click and look like an arrhythmia.")
para(
    "The gain is computed after the fact and held constant across the whole recording, so it "
    "cannot pump or breathe the way automatic gain control would. That matters because a murmur "
    "is a quiet event next to S1 and S2, and AGC would flatten exactly the contrast the "
    "algorithm looks for."
)
note("Deliberately narrow trigger.",
     "Gain is applied only when the loudest sample in the entire clip still fits inside 12 bits. "
     "Over a 60-second auscultation that is not a quiet patient, it is a right-aligned ADC. A "
     "genuinely soft 16-bit recording is left alone, because its amplitude is real clinical "
     "information.")
para(
    "What the pass did is reported on the diagnostics screen after any recording, for example: "
    "\"peak 2013 (~12-bit), DC offset 2048, gain applied x16\". A gain of 16x is proof the "
    "firmware is sending right-aligned samples."
)

h("5.8 Two-channel support", 2)
para(
    "The intended hardware is mono, so this path is currently unused. It exists because a "
    "device offering only two channels would otherwise fail to open at all. AudioCapture "
    "queries the declared channel counts, falls back to stereo when mono is unsupported, and "
    "folds the result down to mono on write \u2014 recordings are always saved mono because the "
    "algorithm expects one channel. Settings offers Left, Right, or Mix. Partial stereo frames "
    "are carried between reads rather than dropped, since losing one would swap the channels "
    "for the remainder of the recording."
)

h("5.9 What no digital processing can fix", 2)
para(
    "Everything the application does happens after digitizing. Content above the capture "
    "Nyquist has already folded down inside the ADC before the app sees a single sample, and "
    "folded energy is unrecoverable. At 4 kHz sampling that means anything above 2 kHz at the "
    "analog input lands permanently somewhere in the output band."
)
para(
    "The analog front end therefore matters more than any firmware or app detail. A piezo or "
    "electret pickup on a chest produces millivolts while the ADC expects 0\u20133.3 V, so a "
    "preamp with gain in the 100\u20131000 range is needed, biased to mid-rail, with a bandpass of "
    "roughly 20\u2013600 Hz. The anti-alias low-pass is not optional: without it, mains hum "
    "harmonics and cloth rustle fold straight into the heart-sound band and nothing downstream "
    "can separate them again."
)
para(
    "Oversampling is worth doing. The RP2040's ADC delivers roughly 9 effective bits in "
    "practice rather than 12, because of noise and known differential-nonlinearity issues. "
    "Sampling at a higher rate and averaging down \u2014 for example 32 samples per output sample "
    "\u2014 buys around 2 extra bits of real resolution and provides free anti-alias filtering, "
    "for very little code. Accumulate in a 32-bit integer, not 16."
)

# ================================================================ 6

doc.add_page_break()
h("6. Audio capture layer")
table(
    ["Class", "Responsibility"],
    [
        ["AudioCapture", "Negotiates rate and channel count with the hardware and opens AudioRecord. Shared by the recorder and the live monitor so they cannot disagree about what the device supports."],
        ["StethoscopeRecorder", "Streams mono 16-bit PCM to a WAV file, folds stereo if needed, drives the level meter, and runs the 12-bit conditioning pass."],
        ["LiveMonitor", "Continuous capture with earbud passthrough for positioning the stethoscope."],
        ["AudioDeviceMonitor", "Tracks USB input and headset output connect/disconnect as StateFlows for the whole app lifetime."],
        ["LevelMeter", "Converts raw PCM into a 0\u20131 display level with DC removal and auto-ranging."],
        ["WaveformWindow", "Fixed-width rolling buffer of recent amplitudes that produces the scrolling waveform."],
        ["WavHeader", "Builds the canonical 44-byte RIFF/PCM header. One implementation, two callers."],
        ["PcmSource", "Abstraction over the microphone and the USB stethoscope, so callers need not know which is active."],
        ["AudioPlayer", "MediaPlayer wrapper for review playback, one recording at a time."],
        ["UsbAudioDiagnostics", "Produces the diagnostics report described in Section 5.5."],
        ["SignalConditioner", "The 12-bit correction pass described in Section 5.7."],
    ],
    [0.24, 0.76],
    mono_cols=(0,),
)

h("6.1 Unprocessed audio source", 2)
para(
    "Both capture paths request MediaRecorder.AudioSource.UNPROCESSED where the device reports "
    "support for it, falling back to MIC otherwise. This matters clinically: body sounds are "
    "quiet and low-frequency, and the automatic gain control and noise suppression applied to "
    "the normal voice pipeline treat them as junk and would filter out the very thing being "
    "recorded."
)

h("6.2 Level metering", 2)
para(
    "The on-screen meter and waveform apply two corrections that the stored audio does not. A "
    "microcontroller sending unsigned samples parks the signal far from zero, so raw peak "
    "measurement would report a constant near-full level regardless of the patient; the meter "
    "therefore tracks and removes DC. A right-aligned 12-bit source only ever reaches a "
    "sixteenth of full scale, which would draw as a flat line; the meter therefore tracks a "
    "decaying ceiling instead of assuming 32767."
)
note("Display only.",
     "Auto-ranging makes the waveform legible whatever the hardware turns out to do, but it is "
     "not a calibrated measurement. Samples written to disk are corrected separately and "
     "exactly by SignalConditioner, so what the algorithm receives is never guessed at.")

# ================================================================ 7

doc.add_page_break()
h("7. PCG preprocessing pipeline")
para(
    "The diagnostic algorithm does not receive the raw capture. It receives a file that is "
    "byte-compatible with the PhysioNet/CinC Challenge 2016 heart-sound dataset. This is the "
    "single most important guarantee the application makes to the algorithm team: whatever the "
    "hardware or the phone negotiated on the way in, the algorithm always sees the same format "
    "at the same rate."
)
para("The implementation is a port of a reference Python script (pcg_wav.py) and lives in analysis/pcg.")

note("Bandpassing is disabled on the analysis path.",
     "The production classifier applies its own causal 25-400 Hz Butterworth filter, exactly as "
     "the MATLAB it was ported from, and its trained coefficients assume the signal reaching "
     "that filter is unfiltered. Bandpassing here as well would filter twice and move every "
     "spectral feature - particularly the 25-45 Hz diastolic band, which sits on the corner - "
     "off the distribution the model was fitted against. The bandpass described below therefore "
     "remains implemented and tested, but is bypassed when preparing audio for classification.")

h("7.1 Target specification", 2)
table(
    ["Property", "Value"],
    [
        ["Container", "RIFF/WAVE, PCM (format tag 1)"],
        ["Sample rate", "2000 Hz \u2014 fixed, not a setting"],
        ["Bit depth", "16-bit signed, little-endian"],
        ["Channels", "1 (mono)"],
        ["Header", "Canonical 44 bytes: fmt chunk size 16, data chunk at offset 36"],
        ["Passband", "25\u2013400 Hz"],
        ["Normalization", "Peak to 1 dB below full scale"],
    ],
    [0.30, 0.70],
)
para(
    "PcgSpec.verify(file) checks any file against this specification and reports precisely what "
    "is wrong, mirroring the verify() function in the Python reference."
)

h("7.2 Pipeline stages", 2)
code([
    "raw capture at any rate",
    "   |",
    "   v  Resampler          polyphase, Kaiser-windowed sinc anti-alias",
    "2000 Hz",
    "   |",
    "   v  Butterworth        25-400 Hz, order 4, zero-phase (forward + backward)",
    "bandpassed",
    "   |",
    "   v  DC removal         subtract the mean",
    "   v  Normalize          peak to -1 dB, measured over an interior guard",
    "   v  int16 + clamp",
    "   |",
    "   v  WavHeader          canonical 44-byte header",
    "2 kHz PhysioNet-compatible WAV",
])
para("The ordering is not arbitrary:")
bullet("Resample before filtering, because the bandpass is specified in the 2 kHz domain. Filtering first would mean designing it against a rate the app does not control.")
bullet("Remove DC before normalizing, because scaling to a peak that still contains an offset scales by the offset rather than by the signal.")
bullet("Clamp after normalizing, so the headroom actually does its job.")

h("7.3 Resampling", 2)
para(
    "Rational sample-rate conversion, the counterpart of scipy.signal.resample_poly. Upsample "
    "by up, lowpass at 1/max(up, down) of the intermediate Nyquist, decimate by down. The "
    "filter is a Kaiser-windowed sinc with beta 5.0 and length 2 x 10 x max(up, down) + 1, "
    "scaled so its DC gain is exact."
)
note("Why not just drop samples.",
     "Decimating by taking every Nth sample folds everything above the new Nyquist back into "
     "the passband, irreversibly. Going 8 kHz to 2 kHz that way would alias energy from 1\u20134 kHz "
     "on top of the heart sounds, and no downstream filter could separate them again. The "
     "lowpass must be applied before the decimation.")
para(
    "The convolution is evaluated in polyphase form: only products involving a non-zero input "
    "sample are computed, and only at output positions that are kept. Written naively \u2014 "
    "materialise the zero-stuffed signal, convolve, discard most of it \u2014 a 44.1 kHz to 2 kHz "
    "conversion of a 60-second clip is on the order of 10^11 operations. In polyphase form it "
    "is around 5 x 10^7."
)

h("7.4 Bandpass filtering", 2)
para(
    "An order-4 Butterworth bandpass applied zero-phase, the counterpart of "
    "scipy.signal.sosfiltfilt. It is built as a cascade of second-order sections using "
    "bilinear-transform biquad forms with Butterworth Q values of 0.5412 and 1.3066 \u2014 an "
    "order-4 highpass at 25 Hz followed by an order-4 lowpass at 400 Hz. A cascade is better "
    "behaved numerically than a single high-order section."
)
note("Two filters, two phase conventions.",
     "This zero-phase filter belongs to the PhysioNet-format export path. The classifier uses a "
     "separate, causal order-4 bandpass (Section 8.2) built from a true lp2bp transformation, "
     "because its segmentation stage measures envelope peak timing and a phase shift would "
     "displace it. Confusing the two would either smear S1/S2 timing or filter the signal twice.")

h("7.5 Measured frequency response", 2)
para(
    "Because the filter is applied twice, the response is squared. A nominal order-4 "
    "Butterworth (-3 dB corner, 24 dB/octave) therefore becomes -6 dB at the corner with "
    "48 dB/octave skirts. The reference sosfiltfilt behaves identically, so the two agree \u2014 but "
    "the number differs from what butter(4, ...) alone would suggest."
)
para(
    "Output for an input tone of amplitude 1000. Starred rows are measured; the rest are "
    "computed from a model that matched those measurements to three significant figures."
)
table(
    ["Input", "Output", "dB", "Note"],
    [
        ["10 Hz", "0.7", "-64", "baseline wander removed"],
        ["20 Hz", "143", "-17", ""],
        ["25 Hz", "500", "-6.0", "highpass corner"],
        ["50 Hz", "996", "-0.03", ""],
        ["100 Hz", "1000 *", "0.0", "measured 999.98"],
        ["200 Hz", "998", "-0.01", ""],
        ["300 Hz", "945 *", "-0.5", "measured 944.72"],
        ["400 Hz", "500", "-6.0", "lowpass corner"],
        ["500 Hz", "72 *", "-22.8", "measured 72.05"],
        ["600 Hz", "6.0", "-44", ""],
        ["800 Hz", "0.009 *", "-101", "measured 0.009"],
        ["1000 Hz", "0", "-inf", "Nyquist"],
    ],
    [0.16, 0.18, 0.14, 0.52],
)
para("The response is flat within 1 dB from roughly 31 Hz to 350 Hz, which covers the S1, S2, and murmur band.")

h("7.6 Aliasing rejection", 2)
para(
    "Decimating 8 kHz to 2 kHz folds content around multiples of 2 kHz. Most of it lands above "
    "400 Hz where the bandpass removes it, but content near 2 kHz folds into the passband \u2014 "
    "1800 Hz and 2200 Hz both land on 200 Hz, 1900 and 2100 on 100 Hz. Once folded it is "
    "indistinguishable from a real heart sound, so the anti-alias filter is the only defence "
    "and its stopband performance is a correctness property. Measured:"
)
table(
    ["Input", "Folds to", "Output"],
    [
        ["1800 Hz", "200 Hz", "0.000"],
        ["1900 Hz", "100 Hz", "0.000"],
        ["2100 Hz", "100 Hz", "0.000"],
        ["2200 Hz", "200 Hz", "0.000"],
        ["3000 Hz", "1000 Hz", "0.000"],
        ["3800 Hz", "200 Hz", "0.000"],
    ],
    [0.26, 0.26, 0.48],
)
para(
    "All below one least-significant bit, better than -66 dB. This is locked down by regression "
    "tests rather than left as a one-off measurement."
)

h("7.7 Two caveats worth carrying forward", 2)
rich([("Normalization destroys absolute amplitude. ", {"bold": True}),
      "Each recording is scaled to near-full-scale, which is how the PhysioNet recordings look "
      "and therefore what a model trained on them expects. The consequence is that two "
      "recordings are no longer comparable in loudness. If the algorithm ever needs calibrated "
      "intensity \u2014 comparing murmur loudness across patients \u2014 the pipeline accepts "
      "normalize = false and one fixed device gain should be applied instead."])
rich([("The extreme edges are unreliable. ", {"bold": True}),
      "Zero-phase filtering leaves ringing in the first and last few samples that no padding "
      "scheme removes: the seam between invented padding and real data sits at the boundary of "
      "the retained region by construction. filtfilt behaves the same way. Do not base a "
      "decision on the first or last 50 ms. Peak normalization measures over an interior guard "
      "for exactly this reason \u2014 otherwise a 12% edge overshoot would set the gain for the "
      "whole file."])

h("7.8 Divergence from the reference implementation", 2)
para(
    "The pipeline is functionally equivalent to the SciPy reference, not bit-identical to it. "
    "Same filter families and parameters, but floating-point summation order differs, so expect "
    "agreement to a few least-significant bits rather than exact equality. Two deliberate "
    "departures: the Butterworth sections are built as biquad cascades rather than by "
    "translating SciPy pole placement, and the pipeline works in int16 units throughout rather "
    "than the reference \u00b11.0 float convention, because its input comes straight off a 16-bit "
    "WAV."
)

# ================================================================ 8

doc.add_page_break()
h("8. Classification")
para(
    "Classification is a Kotlin port of the team's MATLAB reference, classifyHeartSound.m: "
    "Hilbert-envelope segmentation into S1 / systole / S2 / diastole, 15 hand-selected interval "
    "and spectral features, then logistic regression. All trained values are hardcoded, so there "
    "is no model file to ship."
)
para(
    "The source reports 5-fold cross-validation on 3,222 recordings from the 2016 PhysioNet/CinC "
    "Challenge training set: AUC 0.8093, sensitivity 75.7%, specificity 72.3%. Roughly one "
    "abnormal recording in four is therefore missed, which is why the application presents the "
    "result as a screening aid rather than a diagnosis."
)

h("8.1 Pipeline", 2)
code([
    "2 kHz mono audio (un-bandpassed)",
    "   |",
    "   v  causal Butterworth bandpass 25-400 Hz, order 4",
    "   v  Hilbert analytic envelope",
    "   v  causal Butterworth lowpass 20 Hz, order 2",
    "   |",
    "   v  peak picking: > 0.15 x max, >= 0.2 s apart",
    "   v  alternating S1/S2 labelling by interval length",
    "   v  cycle triples, +/- 0.05 s windows",
    "   |",
    "   v  15 features: intervals, amplitude ratio, kurtosis, band medians",
    "   v  standardise, dot with coefficients, sigmoid",
    "   v  threshold 0.25328982",
    "NORMAL | ABNORMAL | UNKNOWN",
])

h("8.2 Reimplemented primitives", 2)
para(
    "MATLAB supplies butter, filter, hilbert, fft, kurtosis and hamming from its toolbox; all "
    "had to be written out. Three choices where the convenient option would have been wrong:"
)
bullet("The bandpass is a genuine 8-pole design via analog prototype, lp2bp transformation and bilinear transform, realised as four biquads. A highpass-then-lowpass cascade of the same order is a different filter, close for a wide band but not identical.")
bullet("Filtering is causal, matching MATLAB's filter rather than filtfilt, because the interval features measure exactly the peak positions a phase shift would move.")
bullet("Kurtosis is the biased, non-excess form. MATLAB does not subtract 3, and doing so would shift three of the fifteen features by a constant.")
para(
    "The FFT supports arbitrary lengths through Bluestein's algorithm, because MATLAB's hilbert "
    "transforms at the signal's exact length and a 60-second recording at 2 kHz is 120,000 "
    "samples \u2014 not a power of two. Zero-padding would disturb the envelope at the boundaries "
    "and could gain or lose a cardiac cycle."
)

h("8.3 What the classifier returns", 2)
code([
    "HeartSoundResult(",
    "    finding = HeartFinding.ABNORMAL,   // NORMAL | ABNORMAL | UNKNOWN",
    "    probabilityAbnormal = 0.71f,",
    "    cycleCount = 14,",
    ")",
])
para(
    "UNKNOWN is a legitimate outcome rather than an error: too few clean cardiac cycles to "
    "segment. The probability is of abnormality, not a confidence score \u2014 for a normal result "
    "of 0.12 the model leans 88% toward normal, and labelling it confidence would read as the "
    "opposite of the truth."
)
note("Verification status.",
     "The primitives are tested against independent references: the bandpass against the "
     "analytic Butterworth magnitude expression to within 1e-6, the FFT against a naive DFT, "
     "kurtosis against MATLAB's documented value. What remains is diffing the 15 end-to-end "
     "feature values against the MATLAB on the same recording, since individually correct "
     "components can still be assembled wrongly and the failure mode is a plausible but wrong "
     "probability. MatlabHeartSoundClassifier.explain() and the Diagnostics screen expose those "
     "values; docs/MATLAB_PARITY.md gives the procedure.")
note("Encryption is invisible to the classifier.",
     "The application decrypts to a temporary file, preprocesses it, classifies, and deletes "
     "both files afterwards.")

# ================================================================ 9

doc.add_page_break()
h("9. Data persistence and security")

h("9.1 Storage model", 2)
para(
    "Room holds one row per recording; the audio itself lives on disk as an encrypted file, "
    "referenced by path. The domain model (Recording) is plaintext and used throughout the UI; "
    "the entity (RecordingEntity) stores the sensitive columns as encrypted Base64. The "
    "repository is the only component that maps between them, and therefore the only one that "
    "calls Crypto."
)
table(
    ["Column", "Storage", "Rationale"],
    [
        ["patientNameEnc", "Encrypted Base64", "Directly identifying"],
        ["patientIdEnc", "Encrypted Base64", "Directly identifying"],
        ["patientNotesEnc", "Encrypted Base64", "May contain clinical detail"],
        ["patientAgeYears", "Plaintext", "Low sensitivity, useful for queries"],
        ["patientSex", "Plaintext", "Low sensitivity"],
        ["filePath", "Plaintext", "Points at an encrypted .wav.enc file"],
        ["rhythm, confidence, analyzedAt", "Plaintext", "Not identifying on its own"],
        ["createdAt, durationMs, sampleRate", "Plaintext", "Technical metadata"],
    ],
    [0.32, 0.22, 0.46],
    mono_cols=(0,),
)

h("9.2 Encryption", 2)
para(
    "A single AES-256-GCM key is held in the hardware-backed Android Keystore under the alias "
    "stethoscope_patient_data_key, generated on first use. The key is not extractable and never "
    "leaves secure storage. No third-party cryptography libraries are used."
)
code([
    "Blob format:    [12-byte IV][ciphertext + 16-byte GCM tag]",
    "Transformation: AES/GCM/NoPadding, 128-bit tag",
])
bullet("Database: sensitive columns are encrypted to Base64 strings and decrypted on read.")
bullet("Audio: WAV files are stored as *.wav.enc and decrypted to a temporary cache file only when played, analyzed, or exported.")
bullet("Temporary plaintext files are deleted in finally blocks, so a failure part-way through analysis does not leave decrypted audio behind.")
bullet("Files staged for sharing go to a cache subdirectory wiped at the start of every share, so one patient's audio is never left sitting next to the next one's.")
note("Not yet verified on a device.",
     "The cryptography compiles and follows standard practice, but Keystore behaviour has never "
     "been exercised on real hardware. This is the highest-priority outstanding verification "
     "item \u2014 Keystore failures are device-specific and do not reproduce on an emulator.")

h("9.3 Schema migration", 2)
para(
    "The database is at version 3 and currently uses destructive migration: a version bump "
    "drops and recreates all tables. That is acceptable while the schema is still moving during "
    "the capstone and no real patient data exists. It must be replaced with explicit Migration "
    "objects before any production deployment."
)

# ================================================================ 10

doc.add_page_break()
h("10. Export and sharing")

h("10.1 WhatsApp \u2014 the primary delivery path", 2)
para(
    "Sending results over WhatsApp is how a diagnosis actually reaches a doctor in the field, so "
    "it is the primary action on the Diagnosis screen. One tap attaches the PDF report and the "
    "original WAV and opens the contact picker. Three implementation decisions matter, each "
    "easy to get wrong:"
)
rich([("1. Files are sent as documents, never as an audio message. ", {"bold": True}),
      "WhatsApp re-encodes anything sent as a voice or audio message to lossy Opus, which would "
      "damage the exact signal the diagnostic algorithm reads. Documents are delivered "
      "byte-exact, so a received WAV is still analyzable. This is why the intent type stays "
      "generic rather than using an audio wildcard."])
rich([("2. The intent targets the WhatsApp package directly, ", {"bold": True}),
      "so the clinician lands on the contact picker instead of a share sheet. This requires a "
      "queries element in the manifest for both com.whatsapp and com.whatsapp.w4b \u2014 without it, "
      "package visibility rules on Android 11 and later hide the app and the button would "
      "always degrade to the generic share sheet."])
rich([("3. URI grants ride on ClipData. ", {"bold": True}),
      "Setting a package skips the chooser, and the chooser is what normally propagates "
      "FLAG_GRANT_READ_URI_PERMISSION to multiple EXTRA_STREAM items. Without ClipData the "
      "attachments arrive permission-denied. An explicit grantUriPermission call is made as "
      "well."])
para(
    "If neither WhatsApp flavour is installed, or WhatsApp refuses the intent shape, the code "
    "falls back to the system share sheet rather than failing."
)

h("10.2 Filenames", 2)
para(
    "Once a file lands in a WhatsApp thread it is identified by its filename alone, so "
    "play_7.wav is useless to the clinician receiving it. ExportNaming produces names of the "
    "form StethoScope_KwameMensah_20260727-1432.wav, falling back to the patient ID and then "
    "the recording ID when no name was entered. Characters awkward on FAT/exFAT storage are "
    "stripped and the length is capped."
)

h("10.3 PDF report", 2)
para(
    "A single-page A4 report generated with Android's PdfDocument, containing only the patient "
    "fields that were actually entered, the recording metadata, the finding, the probability of "
    "abnormality and the number of cardiac cycles analysed, and a footer stating the reported "
    "model performance so a reader can weigh the result."
)

h("10.4 Other export paths", 2)
bullet("Export report (PDF) and Export audio (WAV) write to a user-chosen location through the Storage Access Framework.")
bullet("A generic share button opens the normal Android share sheet with both files, for SD cards, email, or any other target.")
note("Raw versus processed audio in exports.",
     "Exports and WhatsApp attach the raw capture \u2014 full bandwidth and unnormalized, which is "
     "what a doctor wants to listen to. The 2 kHz PCG file is derived per analysis and is what "
     "the algorithm sees. If reproducibility ever matters more than listenability, sending the "
     "exact file the algorithm saw is a small change.")

# ================================================================ 11

h("11. Localization")
para(
    "English and Twi, switchable in Settings. LocaleHelper stores the choice in "
    "SharedPreferences and applies it in MainActivity.attachBaseContext, before any resources "
    "resolve. This gives per-app language independent of the phone's system language, which "
    "matters on shared clinic devices. Untranslated keys fall back to English automatically."
)
note("Twi strings need review.",
     "The Twi strings in res/values-tw/strings.xml are AI-generated best-effort and must be "
     "verified by a fluent Twi speaker before clinical use \u2014 especially finding_normal and "
     "finding_abnormal, which are the words a clinician acts on. The diagnostics "
     "screen is intentionally left untranslated; it is a technical dump aimed at the hardware "
     "team.")

# ================================================================ 12

doc.add_page_break()
h("12. Testing")
para(
    "The signal-processing and protocol layers are pure Kotlin with no Android dependencies, so "
    "they are covered by local JVM tests that run without a device. **73 tests, all passing.**"
)
code(["gradlew.bat :app:testDebugUnitTest"])
table(
    ["Suite", "Tests", "What it covers"],
    [
        ["MatlabDspTest", "19",
         "The ported primitives against independent references: the Butterworth design against "
         "the analytic magnitude expression, the FFT against a naive DFT including Bluestein "
         "lengths, causality, MATLAB's kurtosis and std conventions, Hamming coefficients, and "
         "band-power bin selection."],
        ["PcgPreprocessorTest", "17",
         "Format compliance, resampling without aliasing, rational rate ratios, passband "
         "rejection, zero-phase timing, DC removal, normalisation headroom, clipping, and "
         "degenerate input."],
        ["StethoscopeFrameParserTest", "16",
         "The USB wire protocol: split frames, byte-at-a-time delivery, resynchronisation after "
         "garbage, false magic in payload data, sequence-gap detection and overrun flags."],
        ["HeartSoundClassifierTest", "11",
         "End-to-end segmentation on synthetic heart sounds with known cycle counts and interval "
         "ratios, S1/S2 labelling, 60-second truncation, and graceful handling of unclassifiable "
         "audio."],
        ["ExportNamingTest", "8",
         "Filename readability and safety, since a filename is all that identifies a recording "
         "in a chat thread."],
        ["AliasProbeTest", "2",
         "Content folding into the passband during decimation, where the bandpass offers no "
         "second line of defence."],
    ],
    [0.26, 0.10, 0.64],
    mono_cols=(0,),
)
note("What the tests do not establish.",
     "Coverage stops at the boundary of Android. There are no instrumented tests for the "
     "recorder, repository, encryption or UI. Nor do these tests prove the classifier port "
     "matches MATLAB end to end — only the feature diff in Section 8.3 can do that.")

h("12.1 Two bugs these tests caught", 2)
rich([("Saturation on the unnormalized path. ", {"bold": True}),
      "The reference script clips to \u00b11.0 before scaling by 32767, which assumes float input "
      "in that range. This pipeline's input is int16 units read from a WAV, so every sample "
      "above 1 LSB saturated to full scale. The conversion now works in int16 units throughout. "
      "This failure was total and silent \u2014 the output was a valid WAV full of clipped square "
      "waves."])
rich([("Edge artifacts, and a wrong first diagnosis. ", {"bold": True}),
      "An 800 Hz tone that should attenuate to 0.009 was emerging at 645. The initial "
      "assumption was insufficient padding; lengthening it improved the number to 150, which "
      "looked like progress but was not the mechanism. The padding seam sits at the boundary of "
      "the retained region by construction, so padding length cannot remove it. What did help "
      "was switching from odd to reflect extension \u2014 an odd extension of a near-Nyquist tone "
      "produced a seam value of -2127 against a signal amplitude of 1000 \u2014 and, more "
      "importantly, measuring the normalization peak over an interior guard so a boundary "
      "overshoot cannot set the gain for the entire recording."])
note("Testing gap.",
     "Only the signal-processing layer is covered. There are no instrumented tests for the "
     "recorder, repository, encryption, or UI, all of which need a device or emulator. The "
     "encryption path in particular is untested (Section 9.2).")

# ================================================================ 13

h("13. Build and development")
table(
    ["Task", "Command"],
    [
        ["Build debug APK", "gradlew.bat :app:assembleDebug"],
        ["Run unit tests", "gradlew.bat :app:testDebugUnitTest"],
        ["Install on a connected device", "gradlew.bat :app:installDebug"],
    ],
    [0.34, 0.66],
    mono_cols=(1,),
)
para(
    "Requires the Android SDK, with its path in local.properties, and JDK 17 or newer \u2014 the "
    "JBR bundled with Android Studio works. Set JAVA_HOME to that JBR for command-line builds."
)
note("Project location.",
     "The project lives at C:\\dev\\StethoScope, deliberately outside OneDrive. OneDrive was "
     "locking app/build mid-sync and breaking incremental builds. A stale copy remains at the "
     "old OneDrive path and should not be used.")

h("13.1 Querying the board directly", 2)
para(
    "Because the microcontroller presents a CDC serial port, its MicroPython REPL can be "
    "queried from a PC without any additional tooling. This is how the findings in Section 5.1 "
    "were obtained and is the fastest way to inspect the board:"
)
code([
    "$sp = New-Object System.IO.Ports.SerialPort 'COM5',115200,'None',8,'One'",
    "$sp.DtrEnable = $true; $sp.Open()",
    "$sp.Write(\"import os`r`nprint(os.listdir())`r`n\")",
    "Start-Sleep -Milliseconds 800; $sp.ReadExisting(); $sp.Close()",
])

# ================================================================ 14

doc.add_page_break()
h("14. Source file reference")
para("Paths are relative to app/src/main/java/com/capstone/stethoscope/.")
table(
    ["File", "Purpose"],
    [
        ["MainActivity.kt", "Single Activity; applies the chosen locale before inflating UI"],
        ["StethoscopeApp.kt", "Application class; builds AppContainer and starts device monitoring"],
        ["analysis/HeartbeatClassifier.kt", "The contract: HeartSignal, HeartSoundResult, HeartFinding"],
        ["analysis/MockHeartbeatClassifier.kt", "Deterministic placeholder for demos with no hardware"],
        ["analysis/heartsound/MatlabHeartSoundClassifier.kt", "Production classifier; trained values and the explain() dump"],
        ["analysis/heartsound/HeartSoundFeatures.kt", "Segmentation and the 15 features"],
        ["analysis/heartsound/MatlabDsp.kt", "butter, filter, hilbert, kurtosis, hamming, band power"],
        ["analysis/heartsound/Fft.kt", "Arbitrary-length FFT (radix-2 plus Bluestein)"],
        ["analysis/pcg/PcgSpec.kt", "Target format constants and verify() compliance checker"],
        ["analysis/pcg/Resampler.kt", "Polyphase rational resampling with Kaiser-windowed sinc"],
        ["analysis/pcg/Butterworth.kt", "Biquad cascades and zero-phase bandpass filtering"],
        ["analysis/pcg/PcgPreprocessor.kt", "The full pipeline; DC removal, normalization, WAV output"],
        ["audio/AudioCapture.kt", "Rate and channel negotiation; stereo-to-mono folding"],
        ["audio/StethoscopeRecorder.kt", "Streams PCM to WAV; drives meter; runs conditioning"],
        ["audio/LiveMonitor.kt", "Continuous capture with earbud passthrough"],
        ["audio/AudioDeviceMonitor.kt", "USB input and headset output connection state"],
        ["audio/SignalConditioner.kt", "12-bit DC and power-of-two gain correction"],
        ["audio/UsbAudioDiagnostics.kt", "USB and audio-device report with a plain-language verdict"],
        ["audio/AudioUtils.kt", "LevelMeter and 16-bit sample read/write helpers"],
        ["audio/WaveformWindow.kt", "Rolling amplitude buffer for the scrolling waveform"],
        ["audio/WavHeader.kt", "Canonical 44-byte RIFF/PCM header, shared by both writers"],
        ["audio/PcmSource.kt", "Capture-source abstraction over microphone and USB"],
        ["audio/PcmSourceFactory.kt", "Prefers the stethoscope, falls back to the microphone"],
        ["audio/usb/UsbStethoscope.kt", "Device discovery and runtime USB permission"],
        ["audio/usb/UsbCdcConnection.kt", "CDC-ACM transport: interfaces, DTR, bulk reads"],
        ["audio/usb/UsbCdcPcmSource.kt", "PcmSource reading the framed stream"],
        ["audio/usb/StethoscopeFrameParser.kt", "STH1 frame decoding, resync and loss reporting"],
        ["audio/AudioPlayer.kt", "MediaPlayer wrapper for review playback"],
        ["data/RecordingRepository.kt", "Single source of truth; owns the encryption boundary"],
        ["data/AppSettings.kt", "Duration, sample rate, input channel (SharedPreferences)"],
        ["data/SessionStore.kt", "In-memory patient draft across the Listen-to-Record flow"],
        ["data/db/AppDatabase.kt", "Room database, schema version 4"],
        ["data/db/RecordingDao.kt", "Queries; observeAll returns a Flow"],
        ["data/db/RecordingEntity.kt", "Room row with encrypted patient columns"],
        ["data/model/Recording.kt", "Plaintext domain model used by the UI"],
        ["data/model/PatientDraft.kt", "Optional patient details entered before recording"],
        ["di/AppContainer.kt", "Manual dependency graph; the one line to switch classifier"],
        ["export/ReportExporter.kt", "One-page PDF report and SAF file copying"],
        ["export/ExportNaming.kt", "Clinician-readable filename policy"],
        ["export/WhatsAppSharer.kt", "Package-targeted document sharing with URI grants"],
        ["locale/LocaleHelper.kt", "Per-app language switching (English / Twi)"],
        ["security/Crypto.kt", "Keystore AES-256-GCM encryption for fields and files"],
        ["ui/navigation/AppNavHost.kt", "Navigation graph"],
        ["ui/navigation/Routes.kt", "Route constants and argument keys"],
        ["ui/listen/*", "Listen screen and ViewModel; also owns recording, in place"],
        ["ui/review/*", "Review screen and ViewModel"],
        ["ui/result/*", "Result screen and ViewModel; runs preprocessing and classifier"],
        ["ui/library/*", "Library screen and ViewModel"],
        ["ui/settings/*", "Settings screen and ViewModel"],
        ["ui/diagnostics/DiagnosticsScreen.kt", "Hardware diagnostics report UI"],
        ["ui/components/Waveform.kt", "Scrolling waveform Canvas component"],
        ["ui/components/LifecycleEffects.kt", "Lifecycle-aware Compose helpers"],
        ["ui/theme/*", "Material 3 colour scheme and theme"],
    ],
    [0.39, 0.61],
    mono_cols=(0,),
)

h("14.1 Documentation files", 2)
table(
    ["File", "Audience"],
    [
        ["README.md", "Anyone opening the project \u2014 overview, flow, build, layout"],
        ["INTEGRATION.md", "The diagnostic algorithm team \u2014 the interface and audio guarantees"],
        ["HARDWARE.md", "Whoever writes the firmware \u2014 USB class, packing, sample rate, checklist"],
        ["firmware/README.md", "Installing, verifying and understanding the Pico firmware"],
        ["docs/MATLAB_PARITY.md", "Procedure for proving the classifier port faithful"],
    ],
    [0.28, 0.72],
    mono_cols=(0,),
)

# ================================================================ 15

doc.add_page_break()
h("15. Known limitations and open items")
para("Ordered by how much risk each carries.")
table(
    ["Item", "Status", "Risk if unaddressed"],
    [
        ["Classifier port not yet diffed against MATLAB",
         "Open",
         "Highest \u2014 components are verified individually, but a wrongly assembled chain returns a plausible wrong probability rather than failing (Section 8.3)"],
        ["Analogue front end not characterised on a chest",
         "Hardware side",
         "High \u2014 bench readings used only ~280 of 4095 counts; gain may be insufficient, and no digital processing can undo aliasing that happened in the converter"],
        ["Twi strings not reviewed by a fluent speaker",
         "Open",
         "High \u2014 the result words are what a clinician acts on"],
        ["Model sensitivity is 75.7%",
         "Inherent",
         "High \u2014 about one abnormal recording in four is missed; a normal result is weak evidence and the UI must keep saying so"],
        ["Encryption not confirmed across an app restart",
         "Partly verified",
         "Medium \u2014 decryption demonstrably works in-session; persistence across restart and reboot is untested"],
        ["Sustained transfer only confirmed over two seconds",
         "Open",
         "Medium \u2014 USB backpressure problems tend to appear over time, so a full-length recording needs verifying"],
        ["Destructive Room migrations",
         "Accepted for now",
         "Medium \u2014 must become real migrations before production data exists"],
        ["No tests outside the DSP and protocol layers",
         "Open",
         "Medium \u2014 recorder, repository, crypto and UI are unverified"],
        ["WhatsApp may rename the audio attachment",
         "Open",
         "Low \u2014 affects identifiability in a chat thread, not the data itself"],
        ["12-bit packing not confirmed in firmware",
         "Mitigated",
         "Low \u2014 SignalConditioner corrects all three variants losslessly"],
        ["Port not bit-identical to MATLAB",
         "By design",
         "Low \u2014 differing summation order and a better-conditioned filter realisation; expect agreement to ~10 significant figures"],
    ],
    [0.32, 0.16, 0.52],
)

h("15.1 Recommended next steps", 2)
step("Diff the classifier's 15 feature values against classifyHeartSound.m on the same recording, comparing detected cycle count first (docs/MATLAB_PARITY.md).")
step("Measure signal amplitude with the stethoscope on a real chest and set the preamplifier gain accordingly; confirm the anti-alias corner sits below half the sampling rate.")
step("Verify a full-length recording retains zero dropped frames and zero converter overruns.")
step("Confirm encryption survives an app restart: save a recording, close the app, reopen the Library and play it back.")
step("Have a fluent Twi speaker review res/values-tw/strings.xml.")
step("Replace the destructive database migration before any real patient data is recorded.")
step("Discard a short settling period at the start of each recording, so pressing the record control is not captured.")

# ================================================================ 16

h("16. Appendix: troubleshooting")
table(
    ["Symptom", "Likely cause and check"],
    [
        ["Board appears as a COM port, not an audio device",
         "Expected on the current MicroPython firmware. See Section 5.3 \u2014 this is why the CDC capture route was chosen."],
        ["Nothing happens when the board is plugged in",
         "Wrong adapter type \u2014 a USB-C headphone adapter cannot pass digital data. Confirm it is an OTG/host adapter, then check the diagnostics raw USB list."],
        ["Banner says the microphone will be used, with the board plugged in",
         "USB permission has not been granted. Tap Allow USB access, or replug the board to trigger the system prompt."],
        ["Device connects but no data arrives",
         "DTR was not asserted, or main.py is not running on the board. Verify with firmware/test_stream.py from a PC."],
        ["Recording fails with a rate error",
         "The phone refused every candidate rate on the microphone path. Check the diagnostics buffer probe for which rates it accepts."],
        ["Waveform is a flat line but audio is present",
         "Expected with a right-aligned 12-bit source before conditioning; the meter auto-ranges, so check the diagnostics gain figure."],
        ["Result seems insensitive to the recording",
         "MockHeartbeatClassifier may be wired in \u2014 it derives its answer from recording length. Confirm AppContainer uses MatlabHeartSoundClassifier."],
        ["Result is 'Could not analyse'",
         "Too few clean cardiac cycles were segmented. Usually a weak or noisy signal; check the cycle count on the Diagnostics screen."],
        ["WhatsApp opens with no attachments",
         "URI permission issue. Confirm the manifest queries element and that ClipData is set on the intent."],
        ["Library entries show but playback fails",
         "Decryption or Keystore problem. Check logcat for Keystore exceptions; decryption is known to work in-session, so suspect key persistence."],
        ["Build fails after moving the project",
         "Do not run from the OneDrive copy; use C:\\dev\\StethoScope."],
        ["Serial port is busy or unreadable",
         "Another program holds the COM port \u2014 close Thonny, the Arduino IDE, or any serial monitor first."],
    ],
    [0.32, 0.68],
)

doc.add_paragraph()
centered("End of document", size=10, italic=True, color=RGBColor(0x88, 0x88, 0x88), after=0)

# ================================================================ SAVE

enable_update_fields()
doc.core_properties.title = "StethoScope - Technical Documentation"
doc.core_properties.author = "StethoScope capstone"
doc.core_properties.comments = (
    "Technical documentation for the StethoScope Android heart auscultation app"
)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "StethoScope_Technical_Documentation.docx")
doc.save(out)
print("Wrote", out, os.path.getsize(out), "bytes")
