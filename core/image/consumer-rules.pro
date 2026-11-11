# JNI: the native layer looks these up by name.
-keep class dev.hfps.image.raw.LibRawException { *; }
-keep class dev.hfps.image.raw.RawNativeInfo { *; }
-keep class dev.hfps.image.raw.RawProgressListener { *; }
-keepclasseswithmembernames class dev.hfps.image.raw.RawNative { native <methods>; }
