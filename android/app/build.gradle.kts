plugins {
    id("com.android.application")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.martinb.downloados"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.martinb.downloados"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Personal build key: decoded from downloados.jks.b64 by the workflow,
    // so every new DownloadOS APK installs over the previous one.
    signingConfigs {
        create("martinb") {
            storeFile = file("downloados.jks")
            storePassword = "downloados2026"
            keyAlias = "downloados"
            keyPassword = "downloados2026"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("martinb")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
