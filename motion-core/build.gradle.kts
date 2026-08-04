import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Java 17 bytecode: consumable by AGP/D8 without desugaring surprises, and
// buildable on any JDK 17+ without requiring a toolchain download.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // No android.* on the classpath at all -- enforced by having no Android
        // dependencies. Keep it that way; this module must stay unit-testable
        // on a bare JVM.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
