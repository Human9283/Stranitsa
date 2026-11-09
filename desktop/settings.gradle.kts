// ПК-версия (Windows, macOS, Linux) — отдельная сборка Gradle: Compose Multiplatform для компьютера.
// Ядро на Rust общее с Android (../core), связь через те же Kotlin-привязки UniFFI (JNA).
pluginManagement {
    repositories {
        // Зеркало Maven Central: прямой Maven Central иногда отвечает 429.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        google()
    }
}
rootProject.name = "stranitsa-desktop"
