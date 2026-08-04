# Building Private Lock

## Layout

| Module | Kind | Needs Android SDK? |
|---|---|---|
| `:motion-core` | plain Kotlin/JVM | **no** |
| `:app` | Android application | yes |

All detection and policy logic lives in `:motion-core`. It has no `android.*`
imports and no Android dependencies, so it compiles and unit-tests on any
machine with a JDK 17+:

```
./gradlew :motion-core:test
```

`:app` is a thin adapter: it reads sensors, persists settings, draws UI and
performs the lock. Building it needs the Android Gradle Plugin (published only
to Google's Maven) plus an installed Android SDK:

```
./gradlew :app:assembleFossDebug
```

## Automatic module skipping

`settings.gradle.kts` only includes `:app` when it detects an Android SDK, via
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or a `local.properties` containing
`sdk.dir`. Android Studio always writes `local.properties`, so in normal
development `:app` is always present.

Without an SDK the build prints a notice and configures only `:motion-core`, so
the core logic and its tests still run in restricted CI. Force either way with:

```
./gradlew build -PincludeApp=true
./gradlew build -PincludeApp=false
```

The root `build.gradle.kts` deliberately does **not** declare the Android
Gradle Plugin, not even with `apply false` — doing so forces it onto the root
buildscript classpath and makes even a `:motion-core`-only build reach out to
Google's Maven.

## Repository order

Both `pluginManagement` and `dependencyResolutionManagement` list
`mavenCentral()` **before** `google()`, and `google()` is content-filtered to
`com.android.*`, `com.google.*` and `androidx.*`. Gradle treats a network
failure while querying a repository as fatal for that dependency, so putting
Maven Central first means a `:motion-core` build never contacts Google's Maven
at all — it finds everything it needs before reaching that repository.

## Known environment limitation

If your build environment blocks `dl.google.com` (some corporate proxies and
sandboxes do), `:app` **cannot** be built there:

* the Android SDK command-line tools are downloaded from that host;
* `maven.google.com` redirects to it, so AGP and every AndroidX artifact are
  unreachable;
* Maven Central only mirrors AGP up to 2.3.0 (2017), which is far too old.

There is no workaround short of allowing the host. `:motion-core` is
unaffected, which is a large part of why the detection logic was factored out
of the Android module in the first place: the interesting code stays testable
in environments where the Android toolchain is not available.

## Toolchain versions

| Tool | Version |
|---|---|
| Gradle | 8.14.3 |
| AGP | 8.10.1 |
| Kotlin | 2.1.20 |
| JDK (source/target) | 17 |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 28 (Android 9) |

`minSdk` moved from 17 to 28. Android 9 is the floor at which the whole design
holds together: foreground-service semantics, runtime `ACTIVITY_RECOGNITION`,
and `AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN` (API 28) all land at or
below it. Retaining API 17 would have meant keeping several
`Build.VERSION.SDK_INT` ladders alive for devices that cannot run the primary
lock method anyway.

## Product flavours

| Flavour | Google Play Services | Notes |
|---|---|---|
| `foss` (default) | none on the classpath | F-Droid build |
| `play` | `play-services-location` | optional Activity Recognition, off by default |

## Build-time privacy guarantee

`app/build.gradle.kts` registers `verifyNoInternetPermission<Variant>`, wired
into `assemble`. It parses the **merged** manifest and fails the build if
`android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`
appears — including when a transitive dependency introduces it. A unit test can
be skipped; this cannot be, without editing the build file.
