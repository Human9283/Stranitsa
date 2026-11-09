# Сжатие программы: убираем неиспользуемые классы библиотек. Имена не запутываем и код не
# «оптимизируем» — нужен только размер, а работоспособность проверяется запуском.
-dontobfuscate
-dontoptimize

# Наш код и привязки к ядру на Rust (UniFFI): JNA находит поля и методы по именам.
-keep class app.stranitsa.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * extends com.sun.jna.Callback { *; }
-dontwarn com.sun.jna.**

# Поддержка отрисовки и ввода.
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-dontwarn org.jetbrains.skiko.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.jspecify.**
-dontwarn javax.**
-ignorewarnings
