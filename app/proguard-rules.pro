# Мост к Rust-ядру: JNA и сгенерированные UniFFI-классы вызываются через рефлексию и из нативного кода.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class app.stranitsa.core.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
