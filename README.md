# Whisper Android

On-device English speech recognition Android app (whisper.cpp + JNI). **English only — multilingual is not supported in this or any future phase.**

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

## Building a release APK

The release build is **minified (R8) and resource-shrunk** — APK shrinks from ~15 MB (debug) to ~6.7 MB. It must be signed with the release keystore before it will install on a device or be eligible for Play distribution.

### One-time setup (local builds)

1. Make sure the keystore exists at `~/.android/keystores/whisper-android-release.jks` (alias `whisper-android`).
2. Create `~/.android/whisper-keystore.properties` with the four required keys, then lock down its permissions:

   ```ini
   storeFile=/home/<your-user>/.android/keystores/whisper-android-release.jks
   storePassword=<your-keystore-password>
   keyAlias=whisper-android
   keyPassword=<your-key-password>
   ```

   ```bash
   chmod 600 ~/.android/whisper-keystore.properties
   ```

   The file is `.gitignore`-d (`whisper-keystore.properties` glob) — never commit it.

### Build & install

```bash
# Build the signed release APK
./gradlew :app:assembleRelease --no-daemon

# Output
ls app/build/outputs/apk/release/app-release.apk

# Verify the signature
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk

# Install on a connected device / emulator
adb install -r app/build/outputs/apk/release/app-release.apk
```

If `~/.android/whisper-keystore.properties` is missing, `assembleRelease` produces an **unsigned** APK at `app-release-unsigned.apk` (good enough for ProGuard rule validation, not installable). To force a hard error, supply the env-var alternative below — when *neither* the props file nor the env vars are set, AGP will silently produce the unsigned variant.

### CI / env-var alternative

The GitHub Actions release workflow (`.github/workflows/release.yml`) uses environment variables instead of the properties file:

| Env var | Meaning |
|---|---|
| `WHISPER_KEYSTORE_PATH` | Absolute path to the `.jks` file |
| `WHISPER_KEYSTORE_PASSWORD` | Keystore password |
| `WHISPER_KEYSTORE_ALIAS` | Key alias (default: `whisper-android`) |
| `WHISPER_KEY_PASSWORD` | Key password |

When all four are present, the build picks them up automatically. See [`.github/workflows/release.yml`](.github/workflows/release.yml) for the full pipeline (tag-triggered `v*` releases that restore the keystore from a base64 secret and upload the signed APK as a workflow artefact).

#### Registering GitHub secrets (one-time)

1. Base64-encode your keystore on the machine that holds it:

   ```bash
   base64 -w0 ~/.android/keystores/whisper-android-release.jks > /tmp/keystore.b64
   ```

2. In the repo settings → *Secrets and variables → Actions*, register:
   - `WHISPER_KEYSTORE_BASE64` — paste the contents of `/tmp/keystore.b64`
   - `WHISPER_KEYSTORE_PASSWORD` — the keystore password
   - `WHISPER_KEY_PASSWORD` — the key password

3. (Optional) `rm /tmp/keystore.b64` afterwards.

A release is then triggered by either pushing a `v*` tag (e.g. `git tag v0.1.0 && git push origin v0.1.0`) or running the workflow manually from the Actions tab.

## Manual smoke test on emulator

End-to-end validation without unit-test mocks. Detailed walkthrough: [docs/SMOKE_TEST.md](docs/SMOKE_TEST.md).
