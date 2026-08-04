plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wesaphzt.privatelock"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.wesaphzt.privatelock"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 4
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No resource shrinking surprises for a small app; keep the APK legible
        // for anyone auditing an F-Droid build.
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // ------------------------------------------------------------------
    // Flavour dimension "classifier".
    //
    //   foss -- no Google Play Services dependency of any kind. This is the
    //           default and the F-Droid build. Activity Recognition simply
    //           reports "unavailable" and the local classifier runs alone.
    //
    //   play -- adds an OPTIONAL Activity Recognition Transition API provider.
    //           The dependency is present, but the feature stays off until the
    //           user enables it in settings and grants ACTIVITY_RECOGNITION.
    //
    // The interface lives in :motion-core, so :app code never references a
    // Google class outside src/play.
    // ------------------------------------------------------------------
    flavorDimensions += "classifier"
    productFlavors {
        create("foss") {
            dimension = "classifier"
            isDefault = true
            buildConfigField("boolean", "HAS_PLAY_ACTIVITY_RECOGNITION", "false")
        }
        create("play") {
            dimension = "classifier"
            versionNameSuffix = "-play"
            buildConfigField("boolean", "HAS_PLAY_ACTIVITY_RECOGNITION", "true")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
        getByName("foss") { java.srcDirs("src/foss/kotlin") }
        getByName("play") { java.srcDirs("src/play/kotlin") }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // The app deliberately has no INTERNET permission; make regressions loud.
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":motion-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)

    // Play Services only on the "play" flavour. The FOSS build has no Google
    // code on its classpath at all.
    "playImplementation"(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// ---------------------------------------------------------------------------
// Hard guarantee: this app must never ship with INTERNET permission.
//
// A unit test can be deleted or skipped; this check runs as part of assembling
// the APK and fails the build. It inspects the *merged* manifest, so a
// permission pulled in transitively by a library also trips it.
// ---------------------------------------------------------------------------
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register("verifyNoInternetPermission$variantName") {
            group = "verification"
            description = "Fails if android.permission.INTERNET appears in the merged manifest."
            val manifests = variant.artifacts.get(
                com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST,
            )
            inputs.file(manifests).withPropertyName("mergedManifest")
            doLast {
                val text = manifests.get().asFile.readText()
                val offenders = listOf(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                ).filter { it in text }
                if (offenders.isNotEmpty()) {
                    throw GradleException(
                        "Merged manifest for variant '${variant.name}' requests network " +
                            "permissions that Private Lock must never hold: $offenders. " +
                            "Check any newly added dependency's manifest.",
                    )
                }
            }
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { dependsOn(check) }
    }
}
