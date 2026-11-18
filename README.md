# Whisper APK — Keal Transcriber

An offline Android audio transcription app powered by [whisper.cpp](https://github.com/ggml-org/whisper.cpp).

## Features
- Select MP3, WAV, and M4A files through Android's document picker.
- Decode audio to 16 kHz mono PCM on-device.
- Run Whisper locally using the Android NDK/JNI bridge.
- Tiny and Base multilingual models, downloaded on first use.
- Automatic language detection, English, and Hindi.
- Sentence and word-oriented timestamp modes.
- Export timestamped transcripts as TXT or SRT.
- GitHub Actions builds a debug APK and uploads it as an artifact.

## Build
Requirements: Android Studio, JDK 17, Android SDK 35, NDK 27.0.12077973, and CMake 3.22.1.

Open this repository in Android Studio and build the `:app` configuration. The CMake build fetches whisper.cpp v1.7.6 on the first native build, so the first build needs internet access.

Or run:
```bash
gradle :app:assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## First launch
Choose Tiny or Base. The selected model is downloaded once and stored in the app's private files directory. After download, transcription runs offline. Tiny is the recommended starting point for mid-range phones.

## Timestamp notes
Whisper returns timestamped segments. Word mode requests very short segments from the native engine; exact word alignment can vary by language and audio quality. SRT end times are estimated from the next segment start (and 3 seconds for the final segment).

## License
The app code in this repository is MIT licensed. whisper.cpp and model files retain their respective upstream licenses and terms.
