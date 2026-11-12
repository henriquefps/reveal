# LiteRT (docs/adr/0021 "M4/C"): its JNI libraries (liblitert_jni.so, libLiteRt.so) look classes, constructors and
# fields up by name (FindClass / GetMethodID: TensorBuffer, CompiledModel, the options, LiteRtException, the
# org.tensorflow.lite interpreter classes), and the AAR ships no rules for them. Keep both packages whole: they are
# ~30 KB of bytecode, and a renamed member only fails at runtime in a minified build.
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn org.tensorflow.lite.**
