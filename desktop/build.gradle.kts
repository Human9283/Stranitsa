import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "io.stranitsa"
// Версия выпуска: ./gradlew -PappVersion=1.2.3 (в CI — из тега v1.2.3).
val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"
version = appVersion

// ---------- Ядро на Rust (../core) ----------
// Собираем библиотеку под этот компьютер, генерируем Kotlin-привязки и кладём библиотеку в ресурсы
// по пути, где её ищет JNA (linux-x86-64/, win32-x86-64/, darwin-aarch64/ …).
val coreDir = rootProject.file("../core")
val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase().let { if (it == "amd64" || it == "x86_64") "x86-64" else if (it == "aarch64" || it == "arm64") "aarch64" else it }
val jnaOs = if (os.contains("win")) "win32" else if (os.contains("mac")) "darwin" else "linux"
val libName = if (os.contains("win")) "stranitsa_core.dll" else if (os.contains("mac")) "libstranitsa_core.dylib" else "libstranitsa_core.so"
val nativeOut = layout.buildDirectory.dir("native")
val uniffiOut = layout.buildDirectory.dir("generated/uniffi")
// Привязки генерируются из отладочной сборки: в release-сборке (strip) нет метаданных UniFFI.
val bindgen = File(coreDir, "target/debug/" + if (os.contains("win")) "uniffi-bindgen.exe" else "uniffi-bindgen")

val cargoBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Ядро под этот компьютер (release) и генератор привязок."
    workingDir = coreDir
    commandLine("cargo", "build", "--release", "--lib")
}

val cargoBuildDebug by tasks.registering(Exec::class) {
    group = "rust"
    description = "Отладочная сборка ядра и генератора привязок."
    workingDir = coreDir
    commandLine("cargo", "build", "--lib", "--features", "bindgen", "--bin", "uniffi-bindgen")
}

val generateUniffi by tasks.registering(Exec::class) {
    group = "rust"
    dependsOn(cargoBuildDebug)
    workingDir = coreDir
    val out = uniffiOut.get().asFile
    doFirst { out.deleteRecursively() }
    commandLine(
        bindgen.path, "generate", "--library", File(coreDir, "target/debug/$libName").path,
        "--language", "kotlin", "--no-format", "--out-dir", out.path,
    )
}

val copyNative by tasks.registering(Copy::class) {
    dependsOn(cargoBuild)
    from(File(coreDir, "target/release/$libName"))
    into(nativeOut.map { it.dir("$jnaOs-$arch") })
}

sourceSets["main"].kotlin.srcDir(uniffiOut)
sourceSets["main"].resources.srcDir(nativeOut)
tasks.named("compileKotlin") { dependsOn(generateUniffi) }
tasks.named("processResources") { dependsOn(copyNative) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "app.stranitsa.desktop.MainKt"
        // Версия и источник обновлений — внутрь программы.
        val updateRepo = (findProperty("updateRepo") as String?) ?: "Human9283/Stranitsa"
        jvmArgs += listOf("-Dstranitsa.version=$appVersion", "-Dstranitsa.updateRepo=$updateRepo")
        nativeDistributions {
            // Каждый формат собирается только на своей системе: .msi/.exe — на Windows, .dmg — на macOS.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Stranitsa"
            packageVersion = appVersion
            description = "Stranitsa: one long list, worked page by page (method by Mark Forster)"
            vendor = "Stranitsa"
            modules("java.prefs", "jdk.unsupported")
            linux { iconFile.set(project.file("icon/stranitsa.png")) }
            macOS { iconFile.set(project.file("icon/stranitsa.icns")); bundleID = "app.stranitsa.desktop" }
            windows { iconFile.set(project.file("icon/stranitsa.ico")); shortcut = true; dirChooser = true; perUserInstall = true; menuGroup = "Stranitsa"; upgradeUuid = "dc195755-2276-4fee-bd5a-dc2daf48d6fd" }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
