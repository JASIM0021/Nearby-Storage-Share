import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-neutral core of the same-network (LAN) connection method: wire protocol, TLS pairing, discovery,
// host server and guest client. No Android classes, so the Android app and the Windows app share it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // org.json is part of Android; the desktop app supplies the android-json copy at runtime.
    compileOnly(libs.android.json)
    // Only the JVM (desktop/test) identity generator needs this; Android uses the Keystore instead.
    compileOnly(libs.bouncycastle.pkix)

    // the in-memory host used by tests here and in the desktop module
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesCompileOnly(libs.android.json)
    testFixturesCompileOnly(libs.bouncycastle.pkix)
    testFixturesRuntimeOnly(libs.android.json)
    testFixturesRuntimeOnly(libs.bouncycastle.pkix)

    testImplementation(libs.android.json)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

// Try a guest app by hand against an in-memory host: .\gradlew :lan-core:demoHost
tasks.register<JavaExec>("demoHost") {
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("com.nearbyshare.lan.DemoHostKt")
}