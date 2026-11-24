# Keep AccessibilityService, TileService, and Activities intact under R8
-keep public class io.github.selclip.SelAccessibilityService { *; }
-keep public class io.github.selclip.SelTileService { *; }
-keep public class io.github.selclip.MainActivity { *; }
-keep public class io.github.selclip.ProcessTextActivity { *; }
-keep public class io.github.selclip.ScreenGrabTriggerActivity { *; }

# Keep Google Play Services ML Kit entry points (supplementing consumer rules)
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
