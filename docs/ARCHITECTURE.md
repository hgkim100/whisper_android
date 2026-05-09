# Whisper Android — Architecture & Design

작성: architect@whisper-android-team
일자: 2026-05-09
상태: v0.2

### Change log
- v0.2 (2026-05-09): 모델을 `ggml-base.bin (multilingual)` → **`ggml-tiny.en.bin` (English-only)**로 변경. UI를 미니멀 사양으로 단순화 (FAB/RMS 미터/뱃지 제거, 기본 Button/Text/LinearProgressIndicator만). 한국어 관련 서술을 영어 기준으로 전면 갱신. §2.1, §2.2, §2.4, §3.1, §3.2, §3.5, §3.7, §4.1–§4.3, §5, §6, §9, Appendix B 영향.
- v0.1 (2026-05-09): 초안.

---

## 1. Executive Summary

WSL2에서 빌드하고 Windows AVD에 배포되는, **로컬 추론 기반 영어 음성인식 Android 앱**.
whisper.cpp를 JNI로 통합한 단일 액티비티 + Jetpack Compose UI(미니멀, 기본 Material3 default). 1단계는 push-to-talk 일괄 처리, 2단계에서 슬라이딩 윈도우 준스트리밍을 검토.

---

## 2. Requirements

### 2.1 Functional
- 사용자가 녹음 버튼으로 녹음 시작/종료 (Record / Stop 토글)
- 종료 시 PCM 오디오를 whisper.cpp에 전달, **영어** 텍스트 트랜스크립트 표시
- **English-only 모델** 사용 (`ggml-tiny.en.bin`). 영어 외 입력은 1단계는 물론 후속 단계에서도 지원하지 않는다.
- 첫 실행 시 GGML 모델 다운로드(% 진행률 표시), 이후는 캐시 사용
- 트랜스크립트 결과를 화면에 표시 (스크롤 + 복사 가능). 영구 저장은 1단계 범위 외.

### 2.2 Non-functional
| 항목 | 목표 |
|---|---|
| 인식 정확도 (영어, 조용한 환경) | tiny.en 기준 WER ≤ 15% (clean speech, native speaker) |
| 인식 지연 (10초 발화) | 중급기에서 ≤ 3초 (1단계, tiny.en) |
| 앱 크기 (APK) | ≤ 30MB (모델 미포함). 모델 캐시 후 디바이스 점유 ~75MB |
| 오프라인 동작 | 모델 다운로드 후 네트워크 불필요 |
| 프라이버시 | 오디오 외부 송출 없음 (로컬 추론) |
| 배터리 | 추론 중 CPU 코어 4개 이내, 추론 종료 즉시 해제 |

### 2.3 Constraints
- 빌드 환경: WSL2 + OpenJDK 17 (Temurin) + Android SDK (`~/Android/Sdk`) + build-tools 34.0.0 + platforms;android-34 + NDK r26+
- 테스트 환경: Windows 호스트 AVD **API 37 / Android 17 / x86_64** (마이크 패스스루 ON). WSL adb는 portproxy 5037 경유, `ADB_SERVER_SOCKET=tcp:<windows-ip>:5037`로 동적 설정.
- 코드: github.com/hgkim100/whisper_android, main, 첫 커밋 작성 예정
- 팀: developer 1, reviewer 1, architect 1

**API 레벨 정책**: 에뮬레이터는 API 37이지만 빌드는 **compileSdk=34 / targetSdk=34** 유지. Android의 포워드 호환성으로 targetSdk=34 앱은 API 37 기기에서 정상 동작하며, 우리 앱이 의존하는 API는 모두 26+에서 안정적이라 신규 SDK 채택 이득이 0. compileSdk=37로 올리면 AGP 8.7+ 승격 + edge-to-edge inset 처리 등 부가 작업이 발생. **재승격 기준**: ① Play 배포 결정, ② API 35+ 전용 기능 필요, ③ API 37 디바이스에서 실제 버그 재현 — 이 중 하나 충족 시 검토.

### 2.4 Assumptions
- 디바이스 ABI: arm64-v8a 우선, x86_64는 에뮬레이터용으로 동시 빌드 (`abiFilters "arm64-v8a", "x86_64"`)
- 사용자 디바이스는 최소 1GB RAM 이상 (tiny.en 모델 로딩 + 추론 메모리; base 가정 대비 완화됨)
- 단일 사용자, 단일 세션 (멀티 트랙 동시 인식 없음)

---

## 3. Key Architectural Decisions

요구된 7개 결정에 대한 결론과 근거.

### 3.1 Whisper 통합 방식 → **whisper.cpp + JNI**

| 옵션 | 장점 | 단점 | 결론 |
|---|---|---|---|
| **whisper.cpp + JNI** | 오프라인, 빠름(GGML 양자화), `.en` 영어 전용 모델 그대로 로딩, MIT, 공식 Android 예제 존재 | NDK/CMake 빌드 필요, JNI 인터페이스 직접 작성 | **채택** |
| HF Transformers Android (ONNX/TFLite) | 양자화 그래프 자동 생성, NDK 없이 가능 | 모델 변환 파이프라인 필요, NDK 회피 이외 결정적 이득 부재 | 기각 |
| OpenAI API 원격 | 최고 품질, 코드 단순 | 오프라인 불가, API 키/요금/프라이버시, 네트워크 지연 | 기각 |

**근거**: 오프라인 + 프라이버시가 핵심 가치. whisper.cpp는 안정성/속도가 입증되었고, `tiny.en` 영어 전용 모델을 별도 옵션 없이 그대로 로딩한다. 빌드 복잡도는 공식 `whisper.cpp/examples/whisper.android` 참조로 단축 가능.

### 3.2 모델 크기 & 언어 → **ggml-tiny.en.bin (English-only), 첫 실행 시 다운로드**

| 모델 | 크기 | 영어 품질(체감) | 10초 오디오 추론 (S22 기준) | 결정 |
|---|---|---|---|---|
| **tiny.en** | ~75MB (FP16) | 적정(WER ~10–15% clean speech) | ~2–3초 | **기본** |
| tiny.en-q5_1 | ~40MB (양자화) | tiny.en 대비 미세 손실 | ~1.5–2초 | Phase 2 옵션 |
| base.en | ~142MB | 양호(WER ~7–10%) | ~5–8초 | Phase 2 옵션 |

- **다운로드 전략**: Hugging Face `ggerganov/whisper.cpp` 저장소(`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin`)에서 `ggml-tiny.en.bin`을 첫 실행 시 다운로드, `context.filesDir/models/ggml-tiny.en.bin`에 저장.
  - APK에 모델 미포함(크기/심사 영향 최소화).
  - 다운로드 실패 시 재시도, 부분 다운로드는 `.part` 임시 파일에 받고 SHA-256 검증 후 rename.
  - 체크섬은 `assets/models.json` manifest에 보관 (Task #6 참조). manifest에는 영어 모델 1개 엔트리만 둔다. 코드에 상수로 박지 않음.
- **언어 처리**: `WhisperEngine` 내부에서 `whisper_full_params.language` 를 `"en"` 으로 하드코드. Kotlin/JNI 어느 층에서도 외부 인자로 노출하지 않는다. tiny.en 모델은 비영어 토큰 자체가 없어 별도 처리도 불필요.
- **사용자 메시지 정책**: 비영어 입력은 인식 불가/정확도 매우 낮음. 앱은 영어 전용임을 README/UI 텍스트에서 명시한다.

### 3.3 녹음 파이프라인 → **AudioRecord (PCM 16-bit, 16kHz, mono)**

- `MediaRecorder`는 AAC/AMR 인코딩본을 주므로 디코딩 단계가 추가됨 → 불필요한 복잡도.
- `AudioRecord` 사용:
  - source: `MediaRecorder.AudioSource.VOICE_RECOGNITION` (NS/AGC 적용)
  - sampleRate: 16000, channelConfig: MONO, format: PCM_16BIT
  - 버퍼: `AudioRecord.getMinBufferSize() * 2` (언더런 방지)
- **변환**: short[] → float[] (값 / 32768.0f)로 정규화 후 native에 전달.
- 녹음 스레드는 별도 코루틴(`Dispatchers.IO`), 정지 시 누적 PCM을 native call.

### 3.4 스트리밍 vs 일괄 → **1단계 일괄(push-to-talk), 2단계 슬라이딩 윈도우 검토**

- Whisper는 encoder가 30초 청크 단위로 동작하고 non-causal → 진정한 토큰 스트리밍은 어려움.
- **1단계**: 사용자가 정지 버튼 누를 때까지 누적 → 한 번에 `whisper_full()` 호출. 단순/안정.
- 최대 길이 가드: 60초 초과 시 자동 정지 + 경고 (메모리/추론 시간 고려).
- **2단계 옵션 (추후 태스크화)**: 30초 청크 + 5초 오버랩, VAD로 무음 컷, 부분 결과 점진 표시. 1단계 안정화 이후 평가.

### 3.5 UI → **Jetpack Compose, 단일 Activity, 단일 화면, 미니멀**

**디자인 원칙**: Material3 default 테마 그대로. 색/타이포 커스터마이즈 X, 다크모드/접근성 별도 작업 X (시스템 default만 따름). RMS 미터/타이머/모델 뱃지/FAB 모두 1단계 범위 외.

```
┌──────────────────────────────┐
│ Status: Idle                 │  ← 상태 텍스트
│                              │
│ [████████░░░░] 67%           │  ← 모델 다운로드 시에만 표시
│                              │
│ ┌──────────────────────────┐ │
│ │ Result text shows here.  │ │  ← Transcript (스크롤 + 복사 가능)
│ │ Multiple lines supported.│ │
│ └──────────────────────────┘ │
│                              │
│        [  Record  ]          │  ← Button 1개 (Record / Stop 토글)
└──────────────────────────────┘
```

**구성요소** (이게 전부):
1. `Text("Status: $stateLabel")` — `stateLabel ∈ { Idle, Recording, Transcribing, Done, Error }`
2. `LinearProgressIndicator` + `Text("$pct%")` — 모델 다운로드 진행 중일 때만 visible
3. `SelectionContainer { Text(transcript, modifier = Modifier.verticalScroll(...)) }` — 결과 영역
4. `Button(onClick = onMicTap) { Text(if (recording) "Stop" else "Record") }`

**구조**:
- `MainActivity` (1개) + `MainScreen` Composable + `MainViewModel` (StateFlow)
- 상태: `Idle | ModelDownloading(pct) | Recording | Transcribing | Result(text) | Error(message)`
- 권한 거부/오류는 결과 영역에 텍스트로 인라인 표시 (별도 화면/다이얼로그 X). 권한 거부 시 Settings 인텐트 버튼 1개만 추가.
- 컴포넌트 분리 불필요 — `MainScreen.kt` 단일 파일, ~80 LOC 이내 목표.

### 3.6 권한 & 에뮬레이터 마이크

**권한**:
- `RECORD_AUDIO` (런타임, dangerous) — `ActivityResultContracts.RequestPermission`
- `INTERNET` (모델 다운로드)
- `POST_NOTIFICATIONS` — 1단계 불필요(전경 서비스 X). 2단계 백그라운드 녹음 시 추가.
- 외부 저장소 권한 없음 (앱 내부 `filesDir` 사용)

**에뮬레이터 마이크**:
- AVD 생성 시 `hw.audioInput=yes` 필요. Android Studio AVD Manager에서 "Microphone" 옵션 활성화.
- Windows 호스트 마이크 패스스루는 emulator 버전/드라이버에 따라 불안정한 사례 보고됨 (특히 USB 마이크).
- **검증 전략(필수)**:
  1. 에뮬레이터 마이크가 동작하면 그대로 사용
  2. 동작이 불안정하면 **WAV 파일 입력 경로**로 폴백 — `assets/test_en.wav` (영어 샘플) 또는 `adb push` 한 파일을 읽어 동일 파이프라인을 통과시키는 디버그 메뉴를 제공
- **실기기 검증 필요**: 인식 품질/지연/전력은 실기기에서만 신뢰 가능. 1단계 내 최소 1회 실기기 smoke test 권장.

### 3.7 모듈 구조 → **단일 `:app` 모듈, 패키지 분리**

```
app/
└── com.hgkim.whisperandroid/
    ├── MainActivity.kt
    ├── audio/         AudioRecorder, PcmBuffer
    ├── whisper/       WhisperEngine (Kotlin), WhisperJni (JNI bridge)
    ├── model/         ModelDownloader, ModelStore, ModelManifest
    ├── ui/            MainScreen, MainViewModel
    └── data/          AppPreferences (DataStore — 1단계는 거의 비어있음)
src/main/cpp/         CMakeLists.txt, whisper_jni.cpp (whisper.cpp는 git submodule)
src/main/assets/      models.json (모델 ID/URL/size/sha256 manifest)
```

**근거**:
- 멀티 모듈은 빌드 시간이 늘고 NDK CMake 설정이 복잡해짐.
- 코드량이 ~3K LOC 미만으로 예상되며 단일 모듈로 충분.
- 추후 다른 앱과 코어를 공유해야 할 때 `:core-audio`, `:core-whisper`로 분리.

---

## 4. Component Design

### 4.1 컴포넌트 다이어그램 (텍스트)

```
[MainScreen (Compose)]
       │  state/event
       ▼
[MainViewModel] ──── records ────▶ [AudioRecorder] ──PCM short[]──▶ [PcmBuffer]
       │                                                                 │
       │ requests transcribe                                              │ float[]
       ▼                                                                 ▼
[WhisperEngine (Kotlin)] ──JNI──▶ [WhisperJni (C++)] ──▶ libwhisper.so (whisper.cpp)
       │                                                       │
       │ needs model                                            │ reads
       ▼                                                       ▼
[ModelStore] ◀── downloads ── [ModelDownloader (OkHttp)]   filesDir/models/ggml-tiny.en.bin
```

### 4.2 핵심 인터페이스

```kotlin
// audio
class AudioRecorder(private val sampleRate: Int = 16_000) {
    fun start(): Flow<ShortArray>          // 청크 단위 emit
    fun stop(): FloatArray                 // 누적 PCM을 float[-1,1]로 반환
}

// whisper
class WhisperEngine(private val modelPath: String) {
    suspend fun load()                     // JNI로 whisper_init_from_file
    suspend fun transcribe(pcm: FloatArray, lang: String = "en"): String
    fun release()
}

// model
class ModelDownloader(private val client: OkHttpClient) {
    fun download(url: String, dest: File): Flow<DownloadProgress>
}
sealed class DownloadProgress {
    data class InProgress(val bytes: Long, val total: Long) : DownloadProgress()
    data class Done(val file: File) : DownloadProgress()
    data class Failed(val cause: Throwable) : DownloadProgress()
}

// JNI (C++)
extern "C" JNIEXPORT jlong  Java_..._WhisperJni_init(JNIEnv*, jobject, jstring path);
extern "C" JNIEXPORT jstring Java_..._WhisperJni_transcribe(JNIEnv*, jobject, jlong ctx, jfloatArray pcm, jstring lang);
extern "C" JNIEXPORT void   Java_..._WhisperJni_release(JNIEnv*, jobject, jlong ctx);
```

### 4.3 데이터 플로우 (정상 경로)

1. 사용자 Record 버튼 탭 → ViewModel `startRecording()`, state = Recording
2. AudioRecorder: AudioRecord open + read 루프 → ShortArray flow → 메모리에 누적
3. 사용자 Stop 버튼 탭 → `stopRecording()` → AudioRecorder가 FloatArray 반환
4. ViewModel: state = Transcribing → `WhisperEngine.transcribe(pcm, "en")`
5. JNI 호출 → whisper_full → 텍스트 반환
6. ViewModel: state = Result(text) → MainScreen이 표시 (Status: "Done")

### 4.4 오류 시나리오

| 케이스 | 처리 |
|---|---|
| RECORD_AUDIO 거부 | 인라인 안내 + 설정 화면 이동 버튼 |
| 모델 미다운로드 + 오프라인 | 다운로드 실패 표시, 재시도 버튼, Wi-Fi 안내 |
| 다운로드 중간 실패 | 임시 파일 삭제, 재시도 가능 |
| 추론 중 OOM | catch → 사용자 친화 메시지(state = Error("Device out of memory")) |
| AudioRecord init 실패 | 마이크 미사용 디바이스/에뮬레이터 → state = Error + WAV 파일 폴백 안내 |
| 60초 초과 녹음 | 자동 정지 + 결과 영역에 알림 텍스트 |

---

## 5. Data Architecture

- **모델 파일**: `context.filesDir/models/ggml-tiny.en.bin` (앱 전용, 백업 제외)
- **모델 manifest**: `assets/models.json` — 모델 ID/URL/expected size/sha256
- **사용자 설정**: DataStore는 의존성으로 두되 1단계 실제 사용 키는 없음 (모델/언어 모두 고정). Phase 2 진입 시 활용.
- **트랜스크립트**: 1단계는 메모리 only(Activity 재생성 시 ViewModel SavedState로 보존). 2단계에서 Room으로 영구 저장 검토.

데이터 일관성 이슈 없음 (단일 사용자, 동시 쓰기 없음).

---

## 6. Technology Stack

| 레이어 | 기술 | 버전(권장) | 근거 |
|---|---|---|---|
| 언어 | Kotlin | 2.0.21 | K2 컴파일러 안정 |
| 빌드 | Gradle / AGP | 8.7 / 8.5.2 | Kotlin 2.0 호환 안정 조합 |
| JDK | OpenJDK | 17 | AGP 8.x 요구 |
| 네이티브 | NDK + CMake | r26d / 3.22.1 | whisper.cpp 빌드 |
| UI | Jetpack Compose | BOM 2024.06.00 | Material3, AnimatedVisibility 등 |
| 비동기 | kotlinx.coroutines | 1.8.1 | 표준 |
| 네트워크 | OkHttp | 4.12.0 | 모델 다운로드 (스트리밍 진행률 용이) |
| Pref | androidx.datastore-preferences | 1.1.1 | SharedPreferences 대체 |
| Whisper | whisper.cpp | git submodule, 최신 stable tag (예: v1.7.4 — pin 시점에 검증) | `tiny.en` 단일. multilingual 변종은 후속 단계에도 도입 안 함 |
| minSdk | API 26 (Android 8.0) | — | NDK API26, 디바이스 커버리지 ~95% |
| targetSdk | API 34 (Android 14) | — | 포워드 호환으로 API 37 AVD에서 동작. 재승격 기준은 §2.3 참조 |
| compileSdk | API 34 | — | targetSdk와 일치, AGP 8.5.2 안전 범위 |

**ABI**: `arm64-v8a` (실기기), `x86_64` (에뮬레이터). `armeabi-v7a`/`x86`은 1단계 제외.

---

## 7. Cross-cutting Concerns

- **권한**: 런타임 RECORD_AUDIO. INTERNET은 매니페스트 선언만.
- **로깅**: `android.util.Log` + 디버그 빌드에서만 상세. PII(오디오 raw, 트랜스크립트)는 절대 로그에 쓰지 않음.
- **관측성**: 1단계 Crashlytics 없음(외부 의존 최소). 빌드별 BuildConfig flag로 디버그 통계만 표시.
- **보안**:
  - 오디오/트랜스크립트는 메모리/내부 저장소만 사용
  - HTTPS-only (모델 다운로드)
  - 모델 파일 SHA-256 검증
- **에러 복원**: 추론 코루틴은 `SupervisorJob`, 실패해도 ViewModel은 살아있어 재시도 가능
- **확장성(스케일)**: 단일 디바이스 앱 — 서버 측 스케일 고려 사항 없음

---

## 8. Build & Deployment

### 8.1 로컬 빌드 (WSL)
```
./gradlew :app:assembleDebug      # APK
./gradlew :app:installDebug       # adb install
```

### 8.2 에뮬레이터 배포 (Windows AVD ↔ WSL adb)
1. Windows에서 AVD 실행
2. (Windows powershell) `adb tcpip 5555`
3. (WSL) `adb connect 172.28.224.1:5555`
4. `adb devices`로 device 확인 후 install
   *상세는 Task #3 산출물에 따름*

### 8.3 환경 전략
1단계는 `debug` 단일 빌드 타입. `release`/Play 배포는 1단계 범위 외.

### 8.4 CI
1단계는 로컬만. 추후 GitHub Actions로 PR 시 `assembleDebug` 검증 추가.

---

## 9. Trade-offs & Risks

| # | 리스크 | 영향 | 완화 |
|---|---|---|---|
| R1 | NDK + whisper.cpp 빌드가 WSL에서 실패할 가능성 (cmake/툴체인) | 빌드 자체 막힘 | 공식 whisper.cpp `examples/whisper.android` CMake 그대로 가져와 시작. submodule + 자체 CMakeLists 최소화 |
| R2 | 에뮬레이터 마이크 패스스루 불안정 | 인식 검증 차단 | WAV 파일 입력 폴백 경로 + 실기기 smoke test |
| R3 | tiny.en 정확도가 기대 미달 (악센트/노이즈/원거리) | 사용자 가치 저하 | base.en/small.en 같은 영어 전용 상위 모델로 승격 검토 (multilingual 옵션은 정책상 배제) |
| R4 | 모델 다운로드(첫 실행) UX | 첫인상 저하 | 진행률 명확 표시, Wi-Fi 권장 안내, 재시도 버튼, 향후 tiny.en 번들 옵션 |
| R5 | 추론 중 ANR (UI 스레드 블록) | 앱 크래시/리뷰 | 모든 JNI 호출 `Dispatchers.Default` 코루틴, UI에서 분리 |

---

## 10. Implementation Roadmap

### Phase 1 (MVP, 본 설계 범위)
1. 프로젝트 스켈레톤 + 의존성 + 권한
2. whisper.cpp submodule + JNI 빌드 (CMake)
3. AudioRecorder + PCM 변환
4. ModelDownloader + ModelStore
5. WhisperEngine + JNI 바인딩
6. Compose UI + ViewModel 통합
7. 에뮬레이터 smoke test + 실기기 검증 (가능 시)

### Phase 2 (이후) — 언어 확장은 일체 포함하지 않음
- 슬라이딩 윈도우 준스트리밍 + VAD
- 트랜스크립트 영구 저장 (Room)
- 영어 전용 상위 모델 옵션 (`base.en`, `tiny.en-q5_1` 등). multilingual / 다국어 / 자동 언어 감지는 후속 단계에서도 도입하지 않는다.
- 백그라운드 녹음(전경 서비스)

---

## Appendix A. 의존성 목록 (Gradle catalog 초안)

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.21"
composeBom = "2024.06.00"
coroutines = "1.8.1"
okhttp = "4.12.0"
datastore = "1.1.1"
lifecycle = "2.8.4"
activityCompose = "1.9.2"
coreKtx = "1.13.1"

[libraries]
androidx-core-ktx           = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose   = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-vm-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-compose-bom        = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui         = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-material3  = { module = "androidx.compose.material3:material3" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
kotlinx-coroutines-android  = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
okhttp                      = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }

[plugins]
android-application         = { id = "com.android.application", version.ref = "agp" }
kotlin-android              = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler            = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

## Appendix B. AndroidManifest 권한

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature   android:name="android.hardware.microphone" android:required="true" />
```

## Appendix C. 핵심 BuildConfig 권장값

```
android {
    namespace    "com.hgkim.whisperandroid"
    compileSdk   34
    defaultConfig {
        applicationId "com.hgkim.whisperandroid"
        minSdk 26
        targetSdk 34
        ndk { abiFilters "arm64-v8a", "x86_64" }
        externalNativeBuild { cmake { arguments "-DANDROID_STL=c++_shared" } }
    }
    externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" ; version "3.22.1" } }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17 ; targetCompatibility JavaVersion.VERSION_17 }
    kotlinOptions  { jvmTarget = "17" }
    buildFeatures  { compose true ; buildConfig true }
}
```
