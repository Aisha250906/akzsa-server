import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nefeon.akzsa"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nefeon.akzsa"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Real phones are arm64. Dropping x86/32-bit native libraries makes the APK much smaller.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing comes from AKZSA-Android/keystore.properties (git-ignored) or the environment. Secrets are
    // never committed. Without them the release build is produced UNSIGNED and cannot be installed or uploaded.
    val keystoreProps = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
    fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
    val releaseStoreFile = signingValue("storeFile", "AKZSA_KEYSTORE_FILE")
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "AKZSA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "AKZSA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "AKZSA_KEY_PASSWORD")
            }
        }
    }

    // `play` = Google Play build: no MANAGE_EXTERNAL_STORAGE; File AI uses folders the user picks (SAF).
    // `full` = sideload/enterprise build that may use Android's all-files access.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
        create("full") {
            dimension = "distribution"
            // Same application id as earlier AKZSA debug builds, so installing fullDebug UPDATES them in place
            // (keyboard stays enabled, all-files permission and the file index are kept).
            versionNameSuffix = "-full"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    lint {
        abortOnError = false          // reports are reviewed in the release gate; see docs/RELEASE_CHECKLIST.md
        warningsAsErrors = false
        checkReleaseBuilds = true
        htmlReport = true
        xmlReport = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true   // android.* constants/stubs used by pure-logic tests
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Bundled on-device OCR models: Latin for English/invoice fields and Devanagari for Hindi text.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    // Bundled on-device image labels: no per-image API calls or cloud uploads.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // Light photo-search model runtime (MobileCLIP2-S0 ONNX).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Extract text from text-layer PDFs locally; scanned PDFs fall back to on-device ML Kit OCR.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
