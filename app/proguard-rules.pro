# Default ProGuard rules. Room and Compose provide their own consumer rules.
# Keep the classifier contract intact so a swapped-in implementation is not stripped.
-keep interface com.capstone.stethoscope.analysis.HeartbeatClassifier { *; }
-keep class com.capstone.stethoscope.analysis.** { *; }
