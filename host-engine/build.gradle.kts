import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The storage engine for hosting from a PC: quotas, per-device permissions, pairing approval, private or visible-folder
// storage and the activity log. Plain JVM so it can be tested without any window; the Windows app puts a screen on top.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":lan-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    compileOnly(libs.android.json)

    testImplementation(libs.junit)
    testImplementation(libs.android.json)
    testImplementation(libs.bouncycastle.pkix)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}
