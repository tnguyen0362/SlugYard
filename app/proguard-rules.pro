# Add project specific ProGuard rules here.

# ── Moshi ──────────────────────────────────────────────────────────────────────
# Keep Moshi-generated JsonAdapter classes
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
# Keep @JsonClass-annotated classes and their generated adapters
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <init>(...);
}

# ── Gson ───────────────────────────────────────────────────────────────────────
# Keep TypeToken generic signatures (used in AddonConfigServer/RepositoryConfigServer)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit ───────────────────────────────────────────────────────────────────
# Keep generic signatures for Retrofit service methods
-keepattributes Signature
# Keep Retrofit service interfaces (must preserve generic return types)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
# NOTE: allowobfuscation here is safe because Retrofit reconstructs these
# interfaces from their preserved generic signatures.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep all project API interfaces
-keep class com.sluggyard.tv.data.remote.api.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Data classes (DTOs) ────────────────────────────────────────────────────────
# Keep all DTO classes used with Moshi/Retrofit
-keep class com.sluggyard.tv.data.remote.dto.** { *; }
-keep class com.sluggyard.tv.domain.model.** { *; }

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Kotlin Metadata for reflection
-keepattributes RuntimeVisibleAnnotations

# ── Torrent streaming (TorrServer) ─────────────────────────────────────────────
-keep class com.sluggyard.tv.core.torrent.** { *; }

# ── ExoPlayer / Media3 ────────────────────────────────────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }

# Keep native interfaces and handles for the retained Media3 engine JNI
-keep class androidx.media3.exoplayer.upstream.DefaultAllocatorNative {
    native <methods>;
}
-keep class androidx.media3.exoplayer.source.SampleDataQueueNative {
    native <methods>;
}
-keep class androidx.media3.exoplayer.upstream.Allocation {
    <init>(java.nio.ByteBuffer, int, long);
    public long nativeHandle;
}

# Keep @Serializable classes and their generated serializers
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── General ────────────────────────────────────────────────────────────────────
# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MPV (native JNI callbacks)
# Native code reflects into multiple classes/methods under is.xyz.mpv,
# so keep the whole package to avoid JNI lookup crashes after R8.
-keep class is.xyz.mpv.** { *; }

-dontwarn javax.script.**
-dontwarn okhttp3.internal.sse.**
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
