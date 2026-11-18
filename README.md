# Whisper APK — Keal Transcriber

An offline Android speech-to-text app powered by [whisper.cpp](https://github.com/ggml-org/whisper.cpp).

## Features
- Import audio through Android's document picker (MP3, WAV, M4A and formats supported by the device decoder).
- Decode to 16 kHz mono PCM on-device.
- Run recognition locally; audio is not uploaded to a transcription service.
- Real token-aligned word timestamps using Whisper's experimental token timestamp API.
- Beam-search decoding (beam size 5) for quality-oriented recognition.
- Automatic language detection, English and Hindi selection. Automatic mode is recommended for mixed Hindi-English speech.
- Export word-level or sentence-level SRT and TXT.
- Selectable quantized multilingual models from Tiny through Large v3.
- Downloaded models are stored in app-private storage and reused offline.

## Model choices

| Model | Approx. download | Intended use |
|---|---:|---|
| Tiny Q5 | 31 MB | Fastest, lowest resource use |
| Base Q5 | 57 MB | Lightweight |
| Small Q5 | 181 MB | Mid-range phones |
| Medium Q5 | 514 MiB | Higher recognition quality |
| Large v3 Turbo Q5 | 547 MiB | Recommended high-accuracy mobile option |
| Large v3 Q5 | 1.1 GiB | Highest-quality option; very high RAM/storage demand |

Large models are not guaranteed to load on every Android device. If Large v3 fails or the phone becomes unstable, use Large v3 Turbo or Medium. Model size on disk is not the same as runtime RAM usage.

## Accuracy and timestamps

Word-level timestamps are derived from Whisper's experimental token-level aligner and merged into words. This is substantially more precise than assigning the same sentence timestamp to every word, but it is not forced alignment or a guarantee that every boundary is exact. Languages that do not separate words with spaces cannot be reliably word-segmented by this whitespace-based formatter; their transcript remains grouped at the segment level.

Beam search improves decoding robustness but cannot make speech recognition infallible. Noise, overlapping speakers, names, numbers, distant microphones, strong accents and code-switching can still cause errors. For Hindi-English code-switching, use Automatic language detection and review uncertain passages manually. The app preserves the spoken language; it does not translate.

## Build

Requirements: Android Studio, JDK 17, Android SDK 35, NDK 27.0.12077973, and CMake 3.22.1.

Open the repository in Android Studio and build the `:app` configuration. CMake fetches whisper.cpp v1.7.6 during the first native build, so the first build needs internet access.

Or run:
```bash
gradle :app:assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## First launch

The app defaults to Large v3 Turbo Q5. Its model file is approximately 547 MiB and is downloaded on first use. Choose Large v3 Q5 only if the phone has sufficient free memory and storage. The model download is retained locally for subsequent offline transcription.

## License

The app code in this repository is MIT licensed. whisper.cpp and model files retain their respective upstream licenses and terms.
