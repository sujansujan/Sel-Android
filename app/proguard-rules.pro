# Keep AccessibilityService, Quick Settings TileService, and Activities intact under R8
-keep public class io.github.selclip.SelAccessibilityService { *; }
-keep public class io.github.selclip.SelQuickTileService { *; }
-keep public class io.github.selclip.MainActivity { *; }
-keep public class io.github.selclip.ProcessTextActivity { *; }
-keep public class io.github.selclip.TriggerPopupActivity { *; }
