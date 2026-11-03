import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.capstone.stethoscope"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.capstone.stethoscope"
        minSdk = 26
        targetSdk = 36
        // CI passes the GitHub run number so every published build is a newer version
        // Android will install over the last one; local builds stay at 1.
        val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // The release key never enters the repo. CI supplies it through environment variables
    // (GitHub secrets); locally, keystore.properties (gitignored) points at the .jks. Every
    // published APK must carry this same key or phones refuse to update over it, so losing
    // the key means every user has to uninstall — and lose their recordings — to upgrade.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(env: String, prop: String): String? =
        System.getenv(env) ?: keystoreProps.getProperty(prop)

    val releaseStoreFile = signingValue("SIGNING_STORE_FILE", "storeFile")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The app ships the board's firmware so it can reflash a stethoscope whose version
    // does not match (see usb/StethoscopeFirmware.kt). The asset is generated from
    // firmware/main.py by syncFirmwareAsset below rather than checked in twice, because
    // two copies of a file the app flashes onto hardware is exactly the kind of drift
    // that ends with the app confidently installing last month's firmware.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/firmwareAssets"))
}

/** Copies the canonical firmware source into the APK's assets. */
val syncFirmwareAsset by tasks.registering(Copy::class) {
    val source = rootProject.file("firmware/main.py")
    from(source)
    into(layout.buildDirectory.dir("generated/firmwareAssets/firmware"))
    doFirst {
        require(source.exists()) {
            "firmware/main.py is missing — the app cannot ship firmware it does not have."
        }
    }
}

// Every variant's asset-merge step, so debug and release both get it — plus the lint
// tasks, which read the asset dirs too (release builds run lint-vital and fail otherwise).
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(syncFirmwareAsset)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Local JVM tests, used for the PCG signal-processing pipeline — pure Kotlin maths
    // with no Android dependencies, so it runs without a device.
    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
