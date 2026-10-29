import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.cbor)
    api(libs.bouncycastle.bcprov)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The gigabyte measurement costs minutes and a gigabyte of disk, so it stays out of the ordinary run:
    // ./gradlew :vault-core:test --tests "*LargeFileRoundTripTest*" -Parcane.scale=on --rerun-tasks
    // The re-run flag is not ceremony: changing -P does not dirty the test task, so without it Gradle prints
    // the previous run's SKIPPED verdict and looks exactly like it honoured the switch.
    systemProperty(
        "arcane.scale",
        findProperty("arcane.scale") as String? ?: System.getProperty("arcane.scale") ?: "off",
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
