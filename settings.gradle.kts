pluginManagement {
    repositories {
        // Maven Central first so that pure-JVM modules (:motion-core) resolve
        // without ever contacting Google's Maven. This matters in restricted
        // build environments where dl.google.com is unreachable.
        mavenCentral()
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "PrivateLock"

// ---------------------------------------------------------------------------
// :motion-core is a plain Kotlin/JVM module with no Android dependencies. It
// holds every piece of detection and policy logic, and it can be compiled and
// unit-tested on any machine with a JDK -- no Android SDK required.
// ---------------------------------------------------------------------------
include(":motion-core")

// ---------------------------------------------------------------------------
// :app is the Android module. Configuring it requires the Android Gradle Plugin
// (published only to Google's Maven) and an installed Android SDK. When neither
// is available the module is skipped so that `gradle :motion-core:test` still
// works. Override with -PincludeApp=true / -PincludeApp=false.
// ---------------------------------------------------------------------------
val androidSdkDetected: Boolean =
    System.getenv("ANDROID_HOME")?.isNotBlank() == true ||
        System.getenv("ANDROID_SDK_ROOT")?.isNotBlank() == true ||
        rootDir.resolve("local.properties").let { it.isFile && it.readText().contains("sdk.dir") }

val includeApp: Boolean =
    (settings.providers.gradleProperty("includeApp").orNull ?: androidSdkDetected.toString()).toBoolean()

if (includeApp) {
    include(":app")
} else {
    logger.lifecycle(
        """
        |
        |  NOTE: the :app module was not included in this build.
        |  No Android SDK was detected (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties).
        |  :motion-core still builds and tests normally -- it is pure Kotlin/JVM.
        |  Force inclusion with:  -PincludeApp=true
        |
        """.trimMargin()
    )
}
