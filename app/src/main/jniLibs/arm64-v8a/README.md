# JNI Native Libraries — arm64-v8a

Place compiled `.so` files here before building the APK.

---

## 1. libwhisper.so — whisper.cpp

### Build steps

```bash
# Requirements: Android NDK r25+, CMake 3.22+
export ANDROID_NDK=/path/to/ndk

git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp

mkdir build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DGGML_VULKAN=ON \
  -DWHISPER_BUILD_TESTS=OFF \
  -DWHISPER_BUILD_EXAMPLES=OFF

make -j$(nproc)

# Copy output here
cp libwhisper.so /path/to/lumi-android/app/src/main/jniLibs/arm64-v8a/
cp ggml/src/libggml.so /path/to/lumi-android/app/src/main/jniLibs/arm64-v8a/
cp ggml/src/libggml-base.so /path/to/lumi-android/app/src/main/jniLibs/arm64-v8a/
cp ggml/src/libggml-cpu.so /path/to/lumi-android/app/src/main/jniLibs/arm64-v8a/
# Vulkan backend (if available):
cp ggml/src/libggml-vulkan.so /path/to/lumi-android/app/src/main/jniLibs/arm64-v8a/
```

### Copy JNI Kotlin bindings
```bash
# From whisper.cpp project
cp examples/whisper.android/app/src/main/java/com/whispercpp/whisper/WhisperLib.kt \
   /path/to/lumi-android/app/src/main/kotlin/ai/lumi/voice/
# Then update the package declaration and adapt to WhisperJNI.kt interface
```

---

## 2. libgenieX_jni.so — Qualcomm GenieX SDK

Provided by Qualcomm as part of the GenieX Android SDK (NDA required).

```
1. Download GenieX SDK from https://developer.qualcomm.com
2. Extract libgenieX_jni.so and dependencies
3. Place here: app/src/main/jniLibs/arm64-v8a/libgenieX_jni.so
4. Place genieX.aar in: app/libs/genieX.aar
5. Uncomment in app/build.gradle.kts:
   implementation(files("libs/genieX.aar"))
```

---

## 3. libllama.so — llama.cpp (budget tier Vulkan fallback)

```bash
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
mkdir build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DGGML_VULKAN=ON

make -j$(nproc)
cp libllama.so /path/to/lumi-android/app/src/main/jniLibs/arm64-v8a/
```

---

## Without native libs (CI / dev machine)

The app will run in mock mode:
- Whisper → returns hardcoded transcript "Send 500 rupees on PhonePe"
- GenieX → returns canned JSON inference result
- TTS → Android System TTS only

This is sufficient for UI development and unit testing.
All mock paths are in `WhisperJNI.transcribeMock()` and `MockQairtRuntime`.
