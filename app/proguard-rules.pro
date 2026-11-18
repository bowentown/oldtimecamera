# Keep the custom views inflated from layout XML by name.
-keep class com.bowentown.oldtimecamera.FilmCameraView { *; }
-keep class com.bowentown.oldtimecamera.AspectFrameLayout { *; }
-keep class com.bowentown.oldtimecamera.ViewfinderOverlayView { *; }
-keep class com.bowentown.oldtimecamera.ShutterProgressView { *; }
-keep class com.bowentown.oldtimecamera.MeterStripView { *; }

# CameraX / Camera2 interop is driven by annotations and generics at runtime.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn org.jetbrains.annotations.**

# Keep the enum names used for persisted preference values (AspectRatio, PaperStyle...).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
