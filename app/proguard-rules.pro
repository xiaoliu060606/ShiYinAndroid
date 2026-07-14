# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep WebView JavaScript Interface - 必须保留类名和方法
-keep class com.shiyin.music.WebAppInterface { *; }
-keepclassmembers class com.shiyin.music.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep NativeAudioPlayer callback and data classes
-keep class com.shiyin.music.NativeAudioPlayer$AudioPlayerCallback { *; }
-keep class com.shiyin.music.WebAppInterface$SongInfo { *; }

# Keep MediaSession classes
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Kotlin
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**

# Remove debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Keep Service declared in AndroidManifest
-keep class com.shiyin.music.MediaPlaybackService { *; }

# Keep data classes used in JSON serialization
-keep class com.shiyin.music.GeQuQieHuanManager$BoFangLieBiaoXiang { *; }
-keep class com.shiyin.music.QuanJuYiChangChuLi$BaoCunDeBoFangZhuangTai { *; }
