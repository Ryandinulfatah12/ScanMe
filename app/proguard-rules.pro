# OpenCV's native library calls back into its Java classes by name (JNI), so they must not be renamed or removed.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
