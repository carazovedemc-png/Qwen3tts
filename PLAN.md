# Qwen3 TTS Android Plan

## Current State

- Repository: `C:\Development\qwen3-tts-android`
- Native engine is included as a Git submodule:
  - `external/qwen3-tts.cpp`
  - recursive `external/qwen3-tts.cpp/ggml`
- Android app builds successfully for `arm64-v8a`.
- Debug APK path:
  - `app/build/outputs/apk/debug/app-debug.apk`

## Build Command

```powershell
git submodule update --init --recursive
.\gradlew.bat :app:assembleDebug
```

## Implemented

- Minimal Jetpack Compose Android app.
- CPU-only native CMake wrapper for `qwen3_tts_jni`.
- JNI Kotlin wrapper compatible with the native class names expected by `qwen3-tts.cpp`.
- In-app Hugging Face downloader for:
  - `qwen-tokenizer-12hz-Q8_0.gguf`
  - `qwen-talker-0.6b-base-Q8_0.gguf`
- App-private model storage.
- Model load, text synthesis, and AudioTrack playback.

## Next Work

1. Install APK on a real Android arm64 device and verify:
   - model download completes,
   - model load succeeds,
   - a short English synthesis produces audible speech.

2. Measure first real-device performance:
   - model load time,
   - peak memory,
   - synthesis time for short text,
   - output sample rate and audio duration.

3. Improve runtime robustness:
   - prevent device sleep during model download/synthesis,
   - add clearer native error display,
   - add cancel support for downloads and synthesis where possible.

4. Improve UX:
   - show persistent downloaded/loaded state after app restart,
   - add generated audio duration,
   - add save/share WAV export,
   - add a small model information panel.

5. Native follow-ups:
   - evaluate whether the upstream `qwen3-tts.cpp` CMake can expose an Android-friendly option instead of the app-local wrapper CMake,
   - consider quantized/lower-memory model options if the 0.6B Q8 package is too heavy on target devices,
   - consider Vulkan/NNAPI only after CPU behavior is validated.

## Known Constraints

- Current app is CPU-only.
- The model package is roughly 1.2 GB before filesystem overhead.
- First synthesis may be slow on mobile CPU.
- No voice cloning UI is included yet; the first target is simple on-device text-to-speech.

