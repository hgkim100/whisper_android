# Whisper Android

로컬 추론 기반 한국어 음성인식 Android 앱 (whisper.cpp + JNI).

설계 문서: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 빌드 환경
- JDK 17 (Temurin 권장)
- Android SDK: build-tools 34.0.0, platforms;android-34, platform-tools
- Gradle 8.7 / AGP 8.5.2 / Kotlin 2.0.21
- minSdk 26 / targetSdk 34 / compileSdk 34
- ABI: arm64-v8a, x86_64

## 빠른 시작
```bash
# 환경변수 (예시 — 실제 경로로 조정)
export JAVA_HOME="$HOME/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# 디버그 APK 빌드
./gradlew :app:assembleDebug --no-daemon

# 에뮬레이터 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 구조
```
app/src/main/java/com/hgkim/whisperandroid/
├── MainActivity.kt
├── audio/      AudioRecorder, PcmBuffer        (Task #7)
├── whisper/    WhisperEngine, WhisperJni       (Task #5)
├── model/      ModelDownloader, ModelStore     (Task #6)
├── ui/         MainScreen, MainViewModel       (Task #8)
└── data/       AppPreferences (DataStore)
```

자세한 컴포넌트/데이터 플로우는 ARCHITECTURE.md §3-§4 참조.
