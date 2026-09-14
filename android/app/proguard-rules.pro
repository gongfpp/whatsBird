# MediaPipe Tasks (ObjectDetector) is driven reflectively from native code.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# LiteRT / TFLite interpreter entry points are resolved via JNI.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# AutoValue generated implementations for the Tasks option builders.
-keep class **AutoValue_* { *; }

# Keep our own model-mapping DTOs used with org.json reflection-free code (no-op safety).
-keep class com.whatsbird.species.** { *; }
