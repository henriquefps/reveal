# Reveal R8 rules. Libraries (Compose, Room, Hilt, kotlinx.serialization) ship their own consumer rules, and so do
# our modules: the JNI rules of :core:image live in core/image/consumer-rules.pro.
# Add keep rules here only with the reason next to them.

# Readable stack traces from release builds (crash reports): keep line numbers, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
