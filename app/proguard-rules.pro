# ── Lumi ProGuard Rules ──────────────────────────────────────────────────────

# Kotlin
-keepclassmembers class **$WhenMappings { *; }
-keep class kotlin.Metadata { *; }

# Hilt / Dagger
-keepclasseswithmembers class * { @dagger.* <fields>; }
-keepclasseswithmembers class * { @javax.inject.* <fields>; }
-keepclasseswithmembers class * { @androidx.hilt.* <fields>; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Moshi
-keepclasseswithmembers class * { @com.squareup.moshi.* <methods>; }
-keep @com.squareup.moshi.JsonClass class *

# Accessibility Service
-keep class ai.lumi.accessibility.LumiAccessibilityService { *; }

# Services (must survive minification)
-keep class ai.lumi.overlay.OverlayService { *; }
-keep class ai.lumi.voice.VoiceService { *; }
-keep class ai.lumi.screencapture.ScreenCaptureService { *; }

# WorkManager workers
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# JNI — whisper.cpp
-keep class ai.lumi.voice.WhisperJNI { *; }
-keepclasseswithmembers class ai.lumi.voice.WhisperJNI {
    native <methods>;
}

# GenieX SDK native methods (if present)
-keep class ai.lumi.inference.GenieXRuntimeFactory { *; }

# Inference data classes (serialised via Moshi)
-keep class ai.lumi.inference.InferenceResult { *; }
-keep class ai.lumi.uimap.GuidancePlanDto { *; }
-keep class ai.lumi.uimap.GuidanceStepDto { *; }
-keep class ai.lumi.uimap.BundleMapFile { *; }
-keep class ai.lumi.uimap.BundleScreen { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Retrofit
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }

# DataStore
-keep class androidx.datastore.** { *; }
