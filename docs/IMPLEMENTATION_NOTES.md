# Implementation Notes — Task #5 게이트 답변

작성: architect@whisper-android-team
일자: 2026-05-09
대상: developer (Task #5 진입), reviewer (Task #11 테스트 인프라)

ARCHITECTURE.md v0.3을 보조하는 구현 수준 명세. 5개 질문에 대한 확정 답변.

---

## 1. whisper.cpp tag pin

**확정**: `third_party/whisper.cpp`는 git submodule로 추가하고, **v1.7.x 라인의 가장 최근 stable tag**에 pin.

**선택 절차 (developer가 #5 시작 시 1회 실행)**:
```bash
git ls-remote --tags https://github.com/ggerganov/whisper.cpp \
  | awk -F/ '{print $NF}' | grep -E '^v1\.7\.[0-9]+$' | sort -V | tail -5
```
- 출력의 가장 마지막 tag(예상: `v1.7.4` 또는 그 이후 1.7.x)를 사용.
- **금지**: `master`/`main` 추종, `v1.8.x` 이상 (API/CMake 변경 위험), `v1.6.x` 이하 (구버전).

**pin 명령**:
```bash
git submodule add https://github.com/ggerganov/whisper.cpp third_party/whisper.cpp
cd third_party/whisper.cpp && git checkout vX.Y.Z && cd ../..
git add .gitmodules third_party/whisper.cpp
```

**문서화**: pin한 정확한 tag(vX.Y.Z)를 ARCHITECTURE.md §6 행과 README의 "Dependencies" 섹션에 기재. 이후 PR description에도 명시.

**근거**: v1.7.x는 GGML 바이너리 포맷 안정, Android NDK 빌드 path가 잘 다져진 라인. 메이저 버전 변경 없이 tiny.en으로 수렴 가능.

---

## 2. `assets/models.json` 스키마

**확정 JSON 구조**:
```json
{
  "schema_version": 1,
  "default_model_id": "ggml-tiny.en",
  "models": [
    {
      "id": "ggml-tiny.en",
      "filename": "ggml-tiny.en.bin",
      "url": "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
      "size_bytes": 77704715,
      "sha256": "REPLACE_WITH_ACTUAL_HASH_FROM_sha256sum"
    }
  ]
}
```

**필드 의미**:
| 필드 | 타입 | 설명 |
|---|---|---|
| `schema_version` | Int | 1 고정 (1단계). 향후 변경 시 마이그레이션 핸들 |
| `default_model_id` | String | `models[*].id` 중 하나. 코드는 이 값으로 lookup |
| `models` | Array | **1단계 정확히 1 엔트리** (§A 정책). 2개 이상 시 코드가 거부하거나 첫 번째만 사용해야 함 |
| `models[].id` | String | stable 키. URL 변경되어도 유지. 파일 시스템 캐시 키로도 사용 |
| `models[].filename` | String | filesDir/models/ 아래에 저장될 파일명 |
| `models[].url` | String | HTTPS 다운로드 소스. http는 거부 |
| `models[].size_bytes` | Long | HEAD/Content-Length로 1차 검증. ~5% 오차 허용 |
| `models[].sha256` | String | lowercase 64-char hex. 다운로드 직후 검증, 불일치 시 `.part` 삭제 |

**SHA-256 채우는 절차** (developer가 Task #6 시작 시 1회):
```bash
curl -L https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin \
  -o /tmp/ggml-tiny.en.bin
sha256sum /tmp/ggml-tiny.en.bin   # 64-char hex 출력
ls -la /tmp/ggml-tiny.en.bin      # size_bytes 갱신용
```
출력값을 `assets/models.json`의 `sha256` 필드와 `size_bytes` 필드에 박는다. **코드 상수에 박지 말 것.**

**Kotlin 매핑** (kotlinx.serialization 사용 시):
```kotlin
@Serializable
data class ModelManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("default_model_id") val defaultModelId: String,
    val models: List<ModelEntry>,
)

@Serializable
data class ModelEntry(
    val id: String,
    val filename: String,
    val url: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)
```
의존성: `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`(이미 Appendix A에 추가 필요 — Gradle catalog 보강 항목). kotlinx 의존성 도입이 부담스러우면 `org.json` 표준 라이브러리로도 5분 내 파싱 가능 — 1 모델뿐이라 무거운 라이브러리 도입은 선택.

**로딩 위치**: `ModelStore.loadManifest(context)` — `context.assets.open("models.json")` → 파싱.

---

## 3. JNI 인터페이스 시그니처

**Kotlin side** (`com.hgkim.whisperandroid.whisper.WhisperJni`):
```kotlin
package com.hgkim.whisperandroid.whisper

internal object WhisperJni {
    init { System.loadLibrary("whisper_jni") }

    /**
     * 모델을 메모리에 로드하고 native context handle을 반환.
     * @return jlong context. 0 = 실패 (모델 파일 손상/메모리 부족).
     * blocking — 호출자가 Dispatchers.Default 코루틴 내에서 부를 것.
     */
    @JvmStatic external fun init(modelPath: String): Long

    /**
     * PCM 입력을 영어로 인식하여 텍스트 반환.
     * language="en" 하드코드 (§A). 외부 인자로 노출 안 됨.
     * @param ctx init()이 반환한 handle (0이면 ISE)
     * @param pcm float32 [-1, 1] 정규화된 16kHz mono PCM
     * @return 인식 결과. 빈 문자열일 수도 있음(무음 입력).
     * blocking — Dispatchers.Default 권장.
     */
    @JvmStatic external fun transcribe(ctx: Long, pcm: FloatArray): String

    /**
     * Native 컨텍스트 해제. 호출 후 ctx는 무효.
     * 멱등성 보장 X — 호출자가 한 번만 부를 것.
     */
    @JvmStatic external fun release(ctx: Long)
}
```

**C++ side** (`app/src/main/cpp/whisper_jni.cpp` 진입점 시그니처):
```cpp
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hgkim_whisperandroid_whisper_WhisperJni_init
    (JNIEnv* env, jclass clazz, jstring modelPath);

JNIEXPORT jstring JNICALL
Java_com_hgkim_whisperandroid_whisper_WhisperJni_transcribe
    (JNIEnv* env, jclass clazz, jlong ctx, jfloatArray pcm);

JNIEXPORT void JNICALL
Java_com_hgkim_whisperandroid_whisper_WhisperJni_release
    (JNIEnv* env, jclass clazz, jlong ctx);

}  // extern "C"
```

**구현 가이드**:
- `init()` 내부: `whisper_init_from_file_with_params(path, whisper_context_default_params())` 호출, 결과 포인터를 `reinterpret_cast<jlong>` 으로 변환. 실패 시 0 반환.
- `transcribe()` 내부:
  ```cpp
  whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  p.language = "en";          // 하드코드 (§A)
  p.translate = false;
  p.print_progress = false;
  p.print_realtime = false;
  p.print_timestamps = false;
  // env->GetFloatArrayElements(pcm, ...) → whisper_full(...) → segment 순회
  // → 한 큰 std::string으로 join → env->NewStringUTF
  ```
- `release()` 내부: `whisper_free(reinterpret_cast<whisper_context*>(ctx))`.
- 모든 함수에서 JNI 예외 처리: ctx==0 시 NewStringUTF("") 또는 ISE 던지기. 일관성 유지.

**왜 `object` (singleton) 인가**:
- 한 번에 한 모델만 로드. 멀티 인스턴스 필요 없음.
- `@JvmStatic` + `external` 으로 JNI 심볼 이름이 단순해져 (`Java_..._WhisperJni_init`) C++ 측 작성 부담 ↓.
- Kotlin 인스턴스 메서드로 갈 경우 JNI 심볼 이름이 `Java_..._WhisperJni_00024Companion_init` 류로 길어짐.

---

## 4. WhisperEngine 인터페이스 + Fake 주입 패턴

**인터페이스 분리** (Task #8 fake 주입 + Task #11 테스트 모두 이걸로 통일):

```kotlin
package com.hgkim.whisperandroid.whisper

interface Transcriber {
    /** 모델을 메모리에 로드. 실패 시 throw. */
    suspend fun load()

    /** PCM(float32, 16kHz mono, [-1,1])을 영어 텍스트로 인식. 빈 문자열 가능. */
    suspend fun transcribe(pcm: FloatArray): String

    /** 리소스 해제. 멱등성 권장 (구현은 보장 X). */
    fun release()
}
```

**프로덕션 구현**:
```kotlin
class WhisperEngine(
    private val modelPath: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Transcriber {
    @Volatile private var ctx: Long = 0L

    override suspend fun load() = withContext(dispatcher) {
        require(ctx == 0L) { "Engine already loaded" }
        ctx = WhisperJni.init(modelPath)
        check(ctx != 0L) { "Failed to load model: $modelPath" }
    }

    override suspend fun transcribe(pcm: FloatArray): String = withContext(dispatcher) {
        check(ctx != 0L) { "Engine not loaded" }
        WhisperJni.transcribe(ctx, pcm)
    }

    override fun release() {
        if (ctx != 0L) {
            WhisperJni.release(ctx)
            ctx = 0L
        }
    }
}
```

**Fake 구현 (Task #8 더미 모드 + Task #11 ViewModel 테스트용)**:
```kotlin
class FakeTranscriber(
    private val response: (FloatArray) -> String = { "Hello world." },
    private val loadDelayMs: Long = 0L,
    private val transcribeDelayMs: Long = 50L,
    private val failOnLoad: Boolean = false,
    private val failOnTranscribe: Boolean = false,
) : Transcriber {
    override suspend fun load() {
        if (loadDelayMs > 0) delay(loadDelayMs)
        if (failOnLoad) error("Fake: load() failed")
    }
    override suspend fun transcribe(pcm: FloatArray): String {
        if (transcribeDelayMs > 0) delay(transcribeDelayMs)
        if (failOnTranscribe) error("Fake: transcribe() failed")
        return response(pcm)
    }
    override fun release() {}
}
```

**같은 패턴으로 AudioRecorder도 인터페이스화**:
```kotlin
interface AudioSource {
    /** start() → 청크 단위 short[] 흐름. */
    fun start(): Flow<ShortArray>
    /** stop() 후 누적 PCM을 float[-1,1]로 반환. */
    suspend fun stop(): FloatArray
}

class AudioRecorderImpl(/* AudioRecord 셋업 */) : AudioSource { /* Task #7 */ }

class FakeAudioSource(
    private val emitChunks: List<ShortArray> = emptyList(),
    private val finalPcm: FloatArray = FloatArray(0),
) : AudioSource {
    override fun start() = flow { emitChunks.forEach { emit(it); delay(10) } }
    override suspend fun stop() = finalPcm
}
```

**ViewModel 의존성 주입 (Hilt 미사용 경량 패턴)**:
```kotlin
class MainViewModel(
    private val transcriber: Transcriber,
    private val audio: AudioSource,
    private val modelStore: ModelStore,
    private val downloader: ModelDownloader,
) : ViewModel() { /* ... */ }

// Application 레벨 service container
class WhisperApp : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    val modelStore = ModelStore(context)
    val downloader = ModelDownloader(OkHttpClient())
    fun newTranscriber(modelPath: String): Transcriber = WhisperEngine(modelPath)
    fun newAudioSource(): AudioSource = AudioRecorderImpl(/*...*/)
}

// MainActivity에서
private val viewModel: MainViewModel by viewModels {
    viewModelFactory {
        initializer {
            val app = (this[APPLICATION_KEY] as WhisperApp).container
            MainViewModel(
                transcriber = app.newTranscriber(app.modelStore.path("ggml-tiny.en")),
                audio = app.newAudioSource(),
                modelStore = app.modelStore,
                downloader = app.downloader,
            )
        }
    }
}
```

**테스트 측 사용 예** (Task #11 reviewer):
```kotlin
@Test
fun `transcribe success transitions Idle → Transcribing → Result`() = runTest {
    val vm = MainViewModel(
        transcriber = FakeTranscriber(response = { "test text" }),
        audio = FakeAudioSource(finalPcm = FloatArray(16000)),
        modelStore = FakeModelStore(modelExists = true),
        downloader = FakeDownloader(),
    )
    vm.uiState.test {
        assertEquals(UiState.Idle, awaitItem())
        vm.onMicTap(); vm.onMicTap()    // start + stop
        assertEquals(UiState.Recording, awaitItem())
        assertEquals(UiState.Transcribing, awaitItem())
        assertEquals(UiState.Result("test text"), awaitItem())
    }
}
```

---

## 5. CMakeLists.txt 위치 및 구조

**위치**: `app/src/main/cpp/CMakeLists.txt`. **whisper.cpp의 `examples/whisper.android` 내용은 가져오지 않는다** — `add_subdirectory`로 whisper.cpp 라이브러리를 빌드해 링크만 한다.

**구조**:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES CXX)

# whisper.cpp는 example/test 빌드 끄고 라이브러리만 사용
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)  # NDK OpenMP 호환 회피

# repo 루트의 third_party/whisper.cpp 를 가져옴
# CMAKE_SOURCE_DIR = app/src/main/cpp
add_subdirectory(
    ${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp
    ${CMAKE_BINARY_DIR}/whisper_cpp_build
)

# JNI bridge 라이브러리
add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_compile_features(whisper_jni PRIVATE cxx_std_17)

target_link_libraries(whisper_jni
    PRIVATE
    whisper        # whisper.cpp가 export하는 static lib target
    log            # __android_log_print
)
```

**`app/build.gradle.kts` 측**:
```kotlin
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += "-std=c++17"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

**검증 명령** (developer가 #5 끝낼 때):
```bash
./gradlew :app:assembleDebug 2>&1 | grep -E '(whisper|cmake|ninja|error)'
# build/intermediates/cmake/debug/obj/{arm64-v8a,x86_64}/libwhisper_jni.so 생성 확인
find app/build -name 'libwhisper_jni.so' -ls
```

**자주 막히는 포인트**:
1. whisper.cpp가 `WHISPER_OPENBLAS`/`WHISPER_CUBLAS`/`GGML_METAL` 등 Android에 부적합한 옵션을 default ON으로 둘 가능성 → 위 `set(... OFF CACHE BOOL "" FORCE)` 추가.
2. 실패 시 `CMAKE_VERBOSE_MAKEFILE=ON` 으로 풀 로그 확인.
3. arm64 NEON은 default ON, x86_64 AVX는 emulator 호환을 위해 신중히 — 우선 default 사용 후 emulator 실행 안 되면 `WHISPER_NO_AVX=ON` 추가.
4. NDK side-by-side: `local.properties`에 `ndk.dir` 또는 `gradle.properties`에 `android.ndkVersion=26.x.xxxxxxx`. AGP가 자동 감지 못 하는 경우 명시.

---

## 부록: Task #5 ↔ #6 ↔ #11 의존 관계

- Task #5 (JNI/CMake): 위 §1, §3, §5 답변 사용.
- Task #6 (모델 다운로더): 위 §2 (`models.json` 스키마) 사용.
- Task #8 (UI/ViewModel): 위 §4 (인터페이스/fake) 사용.
- Task #11 (테스트 인프라): 위 §4 fake 패턴 사용. 추가로 `kotlinx-coroutines-test`, `app.cash.turbine:turbine`, `androidx.test.ext:junit` 권장 의존성.

추가로 Appendix A(Gradle catalog)에 추가 필요한 항목 (Task #4 머지 후 보강):
```toml
[versions]
kotlinxSerialization = "1.7.3"
turbine = "1.1.0"
mockwebserver = "4.12.0"
coroutinesTest = "1.8.1"
junit = "4.13.2"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
turbine                    = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
okhttp-mockwebserver       = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockwebserver" }
kotlinx-coroutines-test    = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutinesTest" }
junit                      = { module = "junit:junit", version.ref = "junit" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

(kotlinx-serialization 도입이 부담스러우면 `org.json` 표준으로 대체 가능 — 1 모델 manifest라 무거운 의존성 회피도 합리적.)
