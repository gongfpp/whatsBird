import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.whatsbird"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.whatsbird"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("zh", "en")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MediaPipe and LiteRT ship four ABIs (~60 MB of native code). Real phones are arm64 or
        // armeabi-v7a, and the Apple-silicon emulator is arm64 too, so the x86 payload is dead
        // weight in the download.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * Release signing. Never falls back to the debug keystore: a debug-signed "release" cannot be
     * upgraded in place, cannot be published, and makes the app's identity impossible to verify.
     *
     * Provide the keystore locally (never committed) via ~/.gradle/gradle.properties or the
     * environment:
     *   WHATSBIRD_KEYSTORE=/abs/path/release.jks   (or a path relative to the project)
     *   WHATSBIRD_KEYSTORE_PASSWORD=...
     *   WHATSBIRD_KEY_ALIAS=...
     *   WHATSBIRD_KEY_PASSWORD=...
     * Without them the release build fails loudly instead of silently shipping a debug signature.
     */
    signingConfigs {
        create("release") {
            val keystorePath = (project.findProperty("WHATSBIRD_KEYSTORE")
                ?: System.getenv("WHATSBIRD_KEYSTORE"))?.toString()
            val storePassword = (project.findProperty("WHATSBIRD_KEYSTORE_PASSWORD")
                ?: System.getenv("WHATSBIRD_KEYSTORE_PASSWORD"))?.toString()
            val keyAlias = (project.findProperty("WHATSBIRD_KEY_ALIAS")
                ?: System.getenv("WHATSBIRD_KEY_ALIAS"))?.toString()
            val keyPassword = (project.findProperty("WHATSBIRD_KEY_PASSWORD")
                ?: System.getenv("WHATSBIRD_KEY_PASSWORD"))?.toString()

            if (keystorePath != null && storePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = rootProject.file(keystorePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Keep .tflite uncompressed so LiteRT can mmap the model straight out of the APK.
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    lint {
        // The review flagged that the single Lint error was being hidden by this flag. With the
        // NonObservableLocale error fixed, errors block the build again so a regression cannot slip
        // back in unnoticed. The 35 warnings are mostly dependency-upgrade advice and are left as-is.
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Do NOT try to slim this down by excluding com.google.android.datatransport. tasks-core touches
    // TransportRuntime while creating a task, so excluding it fails on device with
    // `NoClassDefFoundError: Lcom/google/android/datatransport/runtime/TransportRuntime;` and the
    // pipeline never reaches READY. Offline is enforced in AndroidManifest.xml instead, where we
    // strip INTERNET / ACCESS_NETWORK_STATE with tools:node="remove" — the telemetry can then only
    // log a harmless ACCESS_NETWORK_STATE warning and never reach the network.
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.litert)

    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented test that runs the real classifier on the real device runtime. Robolectric cannot
    // load the LiteRT native library, so the model has to be exercised on hardware.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
