# Media3 & ExoPlayer
-keep class androidx.media3.** { *; }

# OkHttp & Gson
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn okhttp3.**

# Native C++ JNI bridge
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.cachetune.app.core.decryptor.NativeDecryptor { *; }
