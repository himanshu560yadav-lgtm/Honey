# ----------------------------------------------------------------------------
# 1. General Android & Debugging
# ----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod

# ----------------------------------------------------------------------------
# 2. YOUR DATA MODELS (CRITICAL)
# ----------------------------------------------------------------------------
-keep class com.blurr.voice.data.** { *; }
-keep class com.blurr.voice.v2.message_manager.models.** { *; }
-keep class com.blurr.voice.v2.llm.models.** { *; }

# ----------------------------------------------------------------------------
# 3. Serializers (Gson, Moshi, Kotlinx)
# ----------------------------------------------------------------------------
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.examples.android.model.** { *; }
-keep class com.google.gson.** { *; }

-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepnames class com.squareup.moshi.** { *; }

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.KSerializer {
    static kotlinx.serialization.KSerializer getSerializer(...);
}

# ----------------------------------------------------------------------------
# 4. Networking & Coroutines
# ----------------------------------------------------------------------------
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# ----------------------------------------------------------------------------
# 5. UI Automator
# ----------------------------------------------------------------------------
-dontwarn androidx.test.uiautomator.**