plugins {
    id("com.android.application")
}

android {
    lint {
        // UI strings are intentionally inline (single-language private app).
        disable += setOf("SetTextI18n", "HardcodedText")
        // Pinned to the API level of the target phone (Android 16); not chasing newer SDKs.
        disable += setOf("OldTargetApi", "GradleDependency")
        warningsAsErrors = true
    }

    namespace = "dev.tgprep"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tgprep"
        minSdk = 33
        targetSdk = 36
        // CI passes its run number so every build installs over the previous one.
        val build = providers.environmentVariable("BUILD_NUMBER").orNull?.toInt() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // A fixed release key (from CI secrets) keeps the signature stable across builds, so updates install
    // in place. Local builds without it use the debug key.
    val keystore = providers.environmentVariable("TGPREP_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("TGPREP_KEYSTORE_PASSWORD").get()
                keyAlias = "tgprep"
                keyPassword = providers.environmentVariable("TGPREP_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
