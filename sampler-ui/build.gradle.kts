import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "com.cloudsampler"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // activăm optimizările de compilare pentru performanță în procesarea UI
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)

    // ktor 3.x pentru comunicarea asincronă cu nodurile kubernetes
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")

    // corutine pentru gestionarea thread-urilor de audio playback
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            // generăm atât MSI (pentru instalare) cât și EXE (pentru rulare rapidă)
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)

            packageName = "CloudSampler"
            packageVersion = "1.0.0"
            description = "Cloud-Based Sound FX Engine with S3 Storage"
            copyright = "© 2026 CloudSampler Industrial"
            vendor = "CloudSampler Corp"

            windows {
                // setări specifice pentru integrarea în windows
                menu = true
                shortcut = true
                upgradeUuid = "550e8400-e29b-41d4-a716-446655440000"
            }

            // forțăm includerea modulelor necesare pentru lucrul cu rețeaua și json
            includeAllModules = true
        }
    }
}