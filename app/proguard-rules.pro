# LuaJ uses reflection to instantiate its compiler/helper classes.
-keep class org.luaj.** { *; }
-dontwarn java.awt.**
-dontwarn javax.swing.**
-keep class com.google.mediapipe.** { *; }
