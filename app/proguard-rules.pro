# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep WebView JavaScript Interface
-keepclassmembers class com.shiyin.music.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep MediaSession classes
-keep class androidx.media.** { *; }

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

# 保留任意类中带 @JavascriptInterface 注解的方法（JS 桥，不止 WebAppInterface）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 MediaBrowserService 子类（媒体播放服务可能被系统/Android Auto 反射实例化）
-keep class * extends androidx.media.MediaBrowserServiceCompat { *; }

# 崩溃栈可读：保留源文件名与行号
-keepattributes SourceFile,LineNumberTable

# 保留 Parcelable 的 CREATOR（跨进程/Intent 传递需要）
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留 Serializable 序列化字段（状态保存/恢复需要）
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
