# Research landscape and product design notes

**Status and scope.** This is an engineering-oriented starting review, not a systematic review and not medical advice. I cannot guarantee that this snapshot contains every newest 2025–2026 paper, nor that any model below has been validated for an individual hearing profile. Verify the original papers, licenses, and device-specific results before adopting them. Hearing-assistance claims require expert clinical, regulatory, and safety review.

## What the three modes actually require

### Mode 1 — audibility-oriented amplification
A practical hearing-aid-style path is not simply “turn up the frequencies the user cannot hear.” It needs a calibrated audiogram (typically dB HL by ear), transducer calibration, fitting prescription (e.g. NAL-NL2/DSL), multiband wide-dynamic-range compression, output limiting, feedback management, and verification. Gain is frequency- and input-level-dependent; excessive gain can be uncomfortable or harmful, and audibility does not automatically imply improved speech understanding. This repository's current pass-through demo has only a conservative software gain control and must not be interpreted as a fitting.

### Mode 2 — frequency lowering
Frequency compression/transposition moves or compresses energy from a source region into a lower destination region. It is not a lossless remapping of *all* sound into a single “heard” band. It can distort speech cues and needs individualized start/cutoff frequencies, compression ratio, acclimatization, and speech-in-noise/intelligibility evaluation. A naive FFT bin-shift or pitch shift is not an evidence-based fitting. Keep it as a research mode until tested by audiologists and users.

### Mode 3 — captions and optional speech output
Streaming ASR can provide a text fallback when speech is difficult to understand, but accuracy depends on noise, language, accent, distance, and model latency. On-device inference can improve privacy/offline use; a cloud path requires explicit consent and clear retention disclosures. Text-to-speech is a separate accessibility feature. Reading the recognized words aloud does not make the original acoustic signal intelligible, and changing TTS pitch is not equivalent to clinical frequency lowering.

## Recommended signal/data architecture

1. **Capture/routing:** Android `AudioRecord`/`AudioTrack` or a low-latency audio stack (AAudio/Oboe); negotiate sample rate and route, report actual latency, handle focus/device changes, and use headphones. Measure round-trip latency on real devices; Bluetooth can be unsuitable for live monitoring.
2. **DSP chain:** input level meter → acoustic feedback detection/management → noise reduction → speech enhancement → multiband dynamic compression based on a *calibrated* fitting profile → output limiter and clipping/route watchdog. Use a user-accessible bypass and hard stop. Do not infer SPL from digital PCM amplitude.
3. **Profile and calibration:** keep per-ear audiogram and fitting metadata separate from raw recordings; do not collect/store speech by default. Calibration needs a defined transducer and coupler, reference level, device-specific correction, repeatability and clinical comparison. The present tone page is only relative self-report.
4. **Speech/captions:** independent streaming ASR module, partial/final caption states, confidence indicators, language selection, noise handling, permission/privacy UI, optional TTS, and no default recording persistence.
5. **Testing:** objective latency/CPU/power/clipping tests; standardized speech-in-noise and word-recognition evaluation with qualified partners; user studies across hearing profiles; accessibility and fail-safe tests.

## Research and open-source starting points

These are useful reference points, not endorsements or ready-made hearing-aid products:

- **OpenMHA** — open-source platform for real-time hearing-aid algorithm research, fitting and evaluation. Useful for prototyping/benchmarking algorithms; it does not make this Android app calibrated or clinically validated. https://github.com/HoerTech-gGmbH/openMHA
- **DeepFilterNet** — real-time speech enhancement research and open implementation; useful for a noise-reduction baseline, but enhancement quality and latency must be evaluated for hearing-impaired listeners and mobile deployment. Publication: Schröter et al., “DeepFilterNet: A Low Complexity Speech Enhancement Framework for Full-Band Audio based on Deep Filtering,” Interspeech 2022. https://github.com/Rikorose/DeepFilterNet and https://www.isca-archive.org/interspeech_2022/schroeter22_interspeech.html
- **RNNoise** — low-complexity recurrent-neural-network noise suppression reference, with a C implementation. https://github.com/xiph/rnnoise
- **DNS Challenge** — community benchmark and datasets for noise suppression; benchmark scores on generic speech do not establish benefit/safety for hearing assistance. https://github.com/microsoft/DNS-Challenge
- **Android low-latency audio guidance** — platform constraints and APIs change by OS/device; use official Android documentation and measure actual routes. https://developer.android.com/ndk/guides/audio
- **Android SpeechRecognizer** — platform API for recognition, subject to service/device availability and privacy/data behavior. For dependable streaming captions, evaluate offline-capable ASR options and their licenses, language coverage, resource use, and latency rather than assuming this API is continuously streaming. https://developer.android.com/reference/android/speech/SpeechRecognizer

## Proposed staged implementation

- **A: Safe app foundation (current):** Android Kotlin/Compose scaffold, permission-gated audio demo, low software gain cap, explicit warnings, relative tone screening with no clinical units.
- **B: Measurement:** establish supported wired transducers and a calibration fixture/procedure; validate repeatability against a clinical reference before showing thresholds. Until then, retain screening-only language.
- **C: Mode 1:** implement block-based multiband WDRC and calibrated per-ear fitting profiles; add acoustic/electrical tests, maximum output controls, and fitting/verification workflow.
- **D: Enhancement:** compare classical noise reduction and licensed on-device models (e.g. DeepFilterNet) using latency, speech quality, and speech-intelligibility outcomes, not only generic enhancement metrics.
- **E: Mode 2:** select a frequency-lowering strategy with audiologists; implement tunable source/destination regions and test consonant preservation, speech intelligibility, comfort and acclimatization.
- **F: Mode 3:** add privacy-first streaming ASR and accessible captions; provide TTS as optional, separate output. Evaluate Mandarin and target languages in realistic noise.

### Suggested evaluation references/metrics

Use measures appropriate to the claim: calibrated threshold repeatability for measurement; real-time factor, round-trip latency, CPU/battery and clipping for implementation; PESQ/STOI only as supplementary signal measures; speech-in-noise intelligibility and validated participant/user outcomes for hearing benefit. Generic denoising metrics alone are insufficient. Work with audiologists, speech/hearing scientists, and people with hearing loss; secure ethics review before human-subject research.
