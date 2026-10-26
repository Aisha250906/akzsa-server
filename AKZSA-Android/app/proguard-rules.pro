# kotlinx.serialization generates serializers; keep metadata required for release serialization.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# PdfBox-Android can use this optional decoder for rare JPEG2000 PDF streams. Standard PDF text extraction does not require it.
-dontwarn com.gemalto.jp2.**

# ONNX Runtime uses JNI and reflection.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit uses generated clients and dynamically loaded model components. ONNX Runtime and ML Kit call back
# into Kotlin/Java from JNI. If a release build crashes in native code that works in debug, these rules are the
# first suspect (see docs/RELEASE_CHECKLIST.md: test a SIGNED release build on a real device, not only debug).
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.label.** { *; }
-dontwarn com.google.mlkit.**
-keepclasseswithmembernames class * { native <methods>; }

# PDFBox Android references some stream/filter implementations dynamically.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Never keep verbose logging calls in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
