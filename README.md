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

## 릴리즈 APK 만들어 핸드폰에 설치하기 (간단 가이드)

처음 한 번 keystore 만들고 비밀번호 파일 작성한 뒤로는 2단계(빌드 → 옮겨서 설치)만 반복하면 됩니다.

### 0) 사전 준비 (1회) — keystore 생성

이미 만들어 두었으면 건너뜁니다.

```bash
mkdir -p ~/.android/keystores && keytool -genkeypair -v \
  -keystore ~/.android/keystores/whisper-android-release.jks \
  -alias whisper-android \
  -keyalg RSA -keysize 2048 -validity 10000
```

대화형 프롬프트에서 keystore 비번 / 본인 이름 / 조직 등을 입력합니다. 비밀번호는 분실하면 같은 키로 서명된 앱 업그레이드가 불가하니 안전하게 보관하세요.

### 1) 비밀번호 properties 파일 작성 (1회)

WSL/Linux 셸에서:

```bash
cat > ~/.android/whisper-keystore.properties <<'EOF'
storeFile=/home/<본인유저>/.android/keystores/whisper-android-release.jks
storePassword=여기에키스토어비번
keyAlias=whisper-android
keyPassword=여기에키비번
EOF
chmod 600 ~/.android/whisper-keystore.properties
```

- `<본인유저>` / `여기에...` 자리는 실제 값으로 교체.
- keystore 생성 시 key 비번을 따로 안 정했으면(Enter만 눌렀으면) `keyPassword` 는 `storePassword` 와 동일.
- 이 파일은 `.gitignore`에 포함되어 절대 커밋되지 않습니다.

### 2) Release APK 빌드

```bash
cd /home/hgkim/whisper_android
git pull origin main
source ~/.bashrc                          # JAVA_HOME / ANDROID_HOME 로드
./gradlew :app:assembleRelease --no-daemon
```

3분 정도 소요. 끝나면 산출물 확인:

```bash
ls -lh app/build/outputs/apk/release/app-release.apk
```

약 **6.4 MB** 정도 나와야 정상 (debug 15 MB → R8 최적화로 -57%).

### 3) 서명 확인 (선택)

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

`Verified using v1/v2/v3 scheme` 류 메시지 나오면 정상.

### 4) 핸드폰에 옮기고 설치

옮기는 방법은 편한 것 하나:

| 방식 | 방법 |
|---|---|
| USB | USB 케이블 연결 후 파일 탐색기로 복사 |
| 클라우드 | Google Drive / iCloud / Dropbox 업로드 후 핸드폰에서 다운로드 |
| 메신저 | 카카오톡 "나에게 보내기" 에 첨부 |
| adb (USB 디버깅 켜진 상태) | `adb install -r app/build/outputs/apk/release/app-release.apk` |

핸드폰에서 APK 탭하면:

1. 안드로이드 설정 → 보안 → "출처를 알 수 없는 앱 설치" 허용 (한 번만)
2. APK 탭 → 설치
3. "Whisper Android" 앱 실행 → 마이크 권한 허용
4. Record 버튼 → 영어로 5–10초 발화 ("Hello, this is a test of speech recognition" 등)
5. Stop 버튼 → Transcribing → Result 화면에 인식 결과 표시

> 처음 Record 누를 때만 `ggml-tiny.en.bin` (75 MB) 다운로드 진행률이 표시됩니다. Wi-Fi 환경 권장.

막히는 단계가 있으면 단계 번호 + 에러 메시지를 함께 알려주세요.

자세한 옵션(환경변수 / GitHub Actions / CI secrets)은 아래 영문 섹션 참고.

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
