# --- JNI surface -----------------------------------------------------------
# Native methods (Java_com_shaforostoff_..._nativeTranscodeVideo, etc.) are
# resolved by the static naming convention, so their class + names must survive.
# The default proguard-android-optimize.txt keeps `native <methods>`, but we
# pin the engine class explicitly to be safe.
-keep class com.shaforostoff.neonvideocompressor.engine.NativeConverter { *; }

# native_converter.c looks up onProgress(long) by name via GetMethodID on the
# runtime class of the callback object. R8 must not rename or strip it on any
# class that implements the callback (including anonymous/lambda classes).
-keep interface com.shaforostoff.neonvideocompressor.engine.NativeConverter$ProgressCallback { *; }
-keepclassmembers class * implements com.shaforostoff.neonvideocompressor.engine.NativeConverter$ProgressCallback {
    void onProgress(long);
}
