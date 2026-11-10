# Phone Hearing Assistant (Android)

An **experimental Android hearing-assistance prototype**. It is not a medical device and does not replace an audiologist, hearing aids, or a calibrated audiogram. Phone microphones, headphones, fit, and audio routing vary substantially; the screening page is **not calibrated** and cannot measure clinical hearing thresholds.

## Prototype scope

- Android app scaffold in Kotlin + Jetpack Compose (min SDK 26).
- A guided, relative tone-detection screening flow for 250 Hz–8 kHz. Results are self-reported and stored only in the current app session; they are not dB HL.
- A live microphone monitor with conservative, capped broad-band EQ and a limiter as an engineering demo. **Use wired headphones at a low volume; never use the phone speaker or high volume.** Stop immediately if uncomfortable, feedback occurs, or sound is distorted.
- An architecture and research note for the three proposed assistance modes, including what needs validated DSP/models before those modes can responsibly be called hearing assistance.

## Important limitations

This version is a starting point, not a finished hearing aid. It does not provide calibrated audiometry, individualized clinical fitting, verified frequency lowering, clinical speech recognition, guaranteed low latency, or hearing-loss diagnosis. Bluetooth and wireless routes can add substantial latency. Do not use the monitor while driving, walking in traffic, or in situations where environmental awareness is important. If you have sudden hearing loss, pain, tinnitus, or one-sided/asymmetric loss, seek medical care promptly.

## Build

Open this repository in Android Studio (current stable, with Android SDK 35 installed), allow Gradle sync, then run the `app` configuration on a physical Android device. Microphone permission is requested when starting the monitor. Emulator audio behavior is not representative. The app requires Android 8.0 (API 26) or newer.

## Research and design

See [docs/RESEARCH.md](docs/RESEARCH.md) for a literature/open-source landscape, proposed architecture, evaluation plan, and references. The notes distinguish established research tools from production-ready mobile features; citations should be checked against the original publications before clinical or commercial use.

## Roadmap

1. Establish audio routing, permission handling, latency and clipping measurements on supported devices.
2. Add a calibrated measurement path (known transducer + coupler + calibration procedure) before reporting thresholds or prescribing gains.
3. Evaluate hearing-aid-style multiband compression against speech-in-noise baselines; preserve safe output limits and user control.
4. Research frequency lowering separately; validate audibility, speech intelligibility, and adverse effects with qualified audiologists and participants.
5. Add on-device streaming ASR and accessible captions; offer optional TTS as a separate feature, not as a substitute for frequency lowering.
6. Add automated tests, device coverage, privacy disclosures, and independent safety/usability review.

Contributions are welcome, especially reproducible device/latency tests and research summaries with links to primary sources.