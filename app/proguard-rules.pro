# Keep AccessibilityService and Activities intact under R8
-keep public class io.github.selclip.SelAccessibilityService { *; }
-keep public class io.github.selclip.MainActivity { *; }
-keep public class io.github.selclip.ProcessTextActivity { *; }
