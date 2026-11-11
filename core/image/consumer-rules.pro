# :core:image R8 rules, applied to every app that depends on this library.
# JNI: reveal_image_jni.cpp looks these up by name (FindClass / GetMethodID) and calls the listener's onProgress.
-keep class dev.hfps.image.raw.LibRawException { <init>(int, java.lang.String); *; }
-keep class dev.hfps.image.raw.RawNativeInfo { <init>(double[], java.lang.String[]); *; }
-keep interface dev.hfps.image.raw.RawProgressListener { *; }
-keep class * implements dev.hfps.image.raw.RawProgressListener { boolean onProgress(int, int, int); }
# Native methods are bound by their Java names (Java_dev_hfps_image_raw_RawNative_*).
-keepclasseswithmembernames,includedescriptorclasses class dev.hfps.image.raw.RawNative { native <methods>; }
