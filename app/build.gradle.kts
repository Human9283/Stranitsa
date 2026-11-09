import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

// Ключ подписи: keystore/keystore.properties (не обязателен — без него release не подписывается).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// ---------- Общее ядро на Rust (../core) ----------
// Для телефона: cargo-ndk собирает libstranitsa_core.so под три архитектуры.
// Для компьютера: обычная сборка — из неё генерируются Kotlin-привязки и на ней идут тесты.
val coreDir = rootProject.file("core")
val ndkVer = "27.2.12479018"
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val uniffiOut = layout.buildDirectory.dir("generated/uniffi")
val hostLibDir = File(coreDir, "target/debug")

val cargoBuildHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Сборка ядра под этот компьютер (для привязок и тестов)."
    workingDir = coreDir
    commandLine("cargo", "build", "--features", "bindgen", "--bins", "--lib")
}

val generateUniffiKotlin by tasks.registering(Exec::class) {
    group = "rust"
    description = "Kotlin-привязки к ядру (UniFFI)."
    dependsOn(cargoBuildHost)
    workingDir = coreDir
    val out = uniffiOut.get().asFile
    doFirst { out.deleteRecursively() }
    commandLine(
        File(hostLibDir, "uniffi-bindgen").path, "generate",
        "--library", File(hostLibDir, "libstranitsa_core.so").path,
        "--language", "kotlin", "--no-format", "--out-dir", out.path,
    )
}

val cargoBuildAndroid by tasks.registering(Exec::class) {
    group = "rust"
    description = "Сборка ядра под телефоны: arm64-v8a, armeabi-v7a, x86_64."
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", File(android.sdkDirectory, "ndk/$ndkVer").path)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "--platform", "26",
        "-o", rustJniLibs.get().asFile.path,
        "build", "--release", "--lib",
    )
}

tasks.named("preBuild") { dependsOn(generateUniffiKotlin, cargoBuildAndroid) }

tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    // JNA находит libstranitsa_core.so, собранную под компьютер.
    systemProperty("jna.library.path", hostLibDir.path)
}

android {
    namespace = "app.stranitsa"
    compileSdk = 35
    ndkVersion = ndkVer

    defaultConfig {
        applicationId = "app.stranitsa"
        minSdk = 26
        targetSdk = 35
        // Версия задаётся при выпуске: ./gradlew -PappVersion=1.2.3 (из тега v1.2.3 в CI).
        val appVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"
        versionName = appVersion
        versionCode = appVersion.split('.').map { it.toIntOrNull() ?: 0 }.let { (it + 0 + 0).take(3) }
            .let { it[0] * 10000 + it[1] * 100 + it[2] }
        // Откуда приходят обновления (GitHub Releases); переопределяется -PupdateRepo=владелец/имя.
        buildConfigField("String", "UPDATE_REPO", "\"" + ((project.findProperty("updateRepo") as String?) ?: "Human9283/Stranitsa") + "\"")
        // Оставляем только свои языки — без переводов из библиотек AndroidX.
        resourceConfigurations += listOf("en", "ru")
        // Ядро собирается под эти три архитектуры (все телефоны с Android 8+ и эмуляторы x86_64).
        // Без фильтра JNA добавляет файлы для давно не существующих mips/armeabi.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    sourceSets["main"].java.srcDir(uniffiOut)
    sourceSets["main"].jniLibs.srcDir(rustJniLibs)

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    // Мост Kotlin ↔ Rust (UniFFI работает через JNA). Для телефона — aar с нативной частью,
    // для тестов на компьютере — обычный jar.
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    // Камера для сканирования QR (сам QR распознаёт ядро на Rust).
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("junit:junit:4.13.2")
}
