# Whisper Android — release ProGuard / R8 rules
#
# Scope: only the rules that the AGP defaults (proguard-android-optimize.txt
# + AGP-bundled androidx/Compose rules + library consumer rules) do **not**
# already cover for this codebase.
#
# Verification recipe (without a release keystore):
#   1. Temporarily flip `debug { isMinifyEnabled = true }` in app/build.gradle.kts
#   2. ./gradlew :app:assembleDebug --no-daemon
#   3. adb install -r app-debug.apk + run a full record/transcribe cycle
#   4. Revert the buildTypes change.

# ---------------------------------------------------------------------------
# 1. JNI bridge
# ---------------------------------------------------------------------------
# WhisperJni is the only Kotlin entry into libwhisper_jni.so. R8 mustn't rename
# the class or the three external functions, otherwise the native symbol
# lookup (`Java_com_hgkim_whisperandroid_whisper_WhisperJni_init` …) breaks
# at runtime with UnsatisfiedLinkError.
-keep,includedescriptorclasses class com.hgkim.whisperandroid.whisper.WhisperJni {
    public static <fields>;
    public static <methods>;
}

# Belt-and-braces generic rule: any class with native methods keeps both the
# class name and the native method names. Cheap insurance if a future JNI
# class is added without remembering to update this file.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# 2. Manifest data classes
# ---------------------------------------------------------------------------
# Parsing is hand-rolled (JSONObject), so reflection isn't currently used —
# but keeping the data class shape makes future Moshi/kotlinx.serialization
# adoption a one-liner and shields us from R8 stripping a no-op constructor
# we may later need for tests.
-keep class com.hgkim.whisperandroid.model.ModelManifest { *; }
-keep class com.hgkim.whisperandroid.model.ModelEntry { *; }

# ---------------------------------------------------------------------------
# 3. androidx.lifecycle.compose
# ---------------------------------------------------------------------------
# Lifecycle 2.8.x ships a new `androidx.lifecycle.compose.LocalLifecycleOwner`
# (the older `androidx.compose.ui.platform.LocalLifecycleOwner` is now
# deprecated). The new value is wired up via top-level helpers in
# `LocalLifecycleOwnerKt`; without an explicit keep R8 strips the static
# field initialisation and the app crashes at first composition with
# `IllegalStateException: CompositionLocal LocalLifecycleOwner not present`.
# Tracking: https://issuetracker.google.com/issues/336842920
-keep class androidx.lifecycle.compose.LocalLifecycleOwnerKt { *; }
-keep class androidx.lifecycle.compose.** { *; }
-keep class androidx.lifecycle.runtime.compose.** { *; }
-keep class androidx.lifecycle.viewmodel.compose.** { *; }
# activity-compose 1.9.x is what actually provides the new
# androidx.lifecycle.compose.LocalLifecycleOwner via a
# CompositionLocalProvider inside ComponentActivity.setContent. Without
# keeping ComponentActivityKt (and the kt suffix variants) R8 inlines /
# strips the lambda that issues the `provides`, leaving the lifecycle
# CompositionLocal unprovided at runtime.
-keep class androidx.activity.compose.** { *; }
-keep class androidx.activity.ComponentActivity { *; }

# ---------------------------------------------------------------------------
# 4. Kotlin metadata
# ---------------------------------------------------------------------------
# Required for any reflective Kotlin tooling (kotlin-reflect, Moshi codegen,
# Compose tooling in some configs). Cheap to keep.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisible*Annotations

# ---------------------------------------------------------------------------
# 5. Stack-trace usability
# ---------------------------------------------------------------------------
# Keep file:line info so crashes from the field map back to source. The
# default optimize file already does -renamesourcefileattribute SourceFile.
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# AGP-bundled rules already cover (no entries needed here):
#   - Compose runtime / compiler / material3
#   - androidx.lifecycle (ViewModel, runtime)
#   - androidx.activity / activity-compose
#   - kotlinx.coroutines (consumer rules in the AAR)
#   - OkHttp (consumer rules in the AAR — Conscrypt/Bouncy Castle warnings
#     are documented as ignorable; we don't pull those transitive providers)
#   - androidx.datastore-preferences
# ---------------------------------------------------------------------------
