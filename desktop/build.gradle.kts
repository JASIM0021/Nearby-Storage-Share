import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Windows app (runs on any desktop JVM): connects to a host over the local network using the same lan-core code as Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":lan-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.android.json)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
    testImplementation(libs.android.json)
    testImplementation(testFixtures(project(":lan-core")))
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

compose.desktop {
    application {
        mainClass = "com.nearbyshare.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "NearbyStorageShare"
            packageVersion = "1.0.0"
            description = "Share and use storage on nearby phones and PCs"
            vendor = "Todayintech Solution"
            copyright = "Copyright (c) 2026 Sk Jasimudin (Todayintech Solution)"
            // A trimmed runtime: TLS with elliptic-curve keys needs jdk.crypto.ec, which a minimal runtime would otherwise leave out.
            modules("jdk.crypto.ec", "jdk.unsupported")
            windows {
                menuGroup = "Nearby Storage Share"
                shortcut = true
                iconFile.set(project.file("icons/app.ico"))
            }
        }
    }
}
