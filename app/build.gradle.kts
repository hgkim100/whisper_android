import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

/**
 * Resolve the release signing material from one of two sources, in priority order:
 *
 *  1. A local `~/.android/whisper-keystore.properties` file with the keys
 *     `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Recommended for
 *     local dev — `chmod 600` it and never commit it (`.gitignore` already
 *     lists the `whisper-keystore.properties` glob).
 *
 *  2. CI environment variables: `WHISPER_KEYSTORE_PATH`,
 *     `WHISPER_KEYSTORE_PASSWORD`, `WHISPER_KEYSTORE_ALIAS` (default
 *     `whisper-android`), `WHISPER_KEY_PASSWORD`. Used by the GitHub Actions
 *     release workflow after restoring the keystore from a base64 secret.
 *
 * If neither is present we leave [signingConfig] unconfigured; `assembleDebug`
 * is unaffected, and `assembleRelease` will fail with a clear AGP error
 * ("Keystore file '' not found"). See README §Building a release APK.
 */
data class ReleaseSigningMaterial(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun resolveReleaseSigning(): ReleaseSigningMaterial? {
    val home = System.getProperty("user.home") ?: return null
    val propsFile = File("$home/.android/whisper-keystore.properties")

    if (propsFile.exists()) {
        val props = Properties().apply { propsFile.inputStream().use(::load) }
        val store = props.getProperty("storeFile")
            ?: System.getenv("WHISPER_KEYSTORE_PATH")
            ?: return null
        return ReleaseSigningMaterial(
            storeFile = File(store),
            storePassword = props.getProperty("storePassword") ?: return null,
            keyAlias = props.getProperty("keyAlias")
                ?: System.getenv("WHISPER_KEYSTORE_ALIAS")
                ?: "whisper-android",
            keyPassword = props.getProperty("keyPassword") ?: return null,
        )
    }

    val envPath = System.getenv("WHISPER_KEYSTORE_PATH")
    val envStorePass = System.getenv("WHISPER_KEYSTORE_PASSWORD")
    val envKeyPass = System.getenv("WHISPER_KEY_PASSWORD")
    if (envPath != null && envStorePass != null && envKeyPass != null) {
        return ReleaseSigningMaterial(
            storeFile = File(envPath),
            storePassword = envStorePass,
            keyAlias = System.getenv("WHISPER_KEYSTORE_ALIAS") ?: "whisper-android",
            keyPassword = envKeyPass,
        )
    }

    return null
}

val releaseSigning: ReleaseSigningMaterial? = resolveReleaseSigning()

android {
    namespace = "com.hgkim.whisperandroid"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.hgkim.whisperandroid"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += "-std=c++17"
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            releaseSigning?.let { mat ->
                storeFile = mat.storeFile
                storePassword = mat.storePassword
                keyAlias = mat.keyAlias
                keyPassword = mat.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only attach the signing config when we actually have credentials —
            // otherwise AGP would fail-fast on every Gradle invocation that
            // touches the release variant (including assembleDebug task graph
            // resolution on some AGP versions).
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.vm.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- JVM unit tests (src/test) ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)

    // --- Android instrumentation tests (src/androidTest) ---
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
