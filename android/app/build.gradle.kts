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

    // Release key: the workflow decodes the DOWNLOADOS_KEYSTORE_B64 secret into
    // downloados.jks and passes the passwords as env vars, so every new APK
    // installs over the previous one. Never commit the key or its password.
    val keystore = file("downloados.jks")
    signingConfigs {
        create("martinb") {
            storeFile = keystore
            storePassword = System.getenv("DOWNLOADOS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DOWNLOADOS_KEY_ALIAS") ?: "downloados"
            keyPassword = System.getenv("DOWNLOADOS_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Pull requests build without the secret: they get the debug key and are never published.
            signingConfig = signingConfigs.getByName(if (keystore.exists()) "martinb" else "debug")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/versions/9/previous-compilation-data.bin")
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

dependencies {
    // Claude copilot ("Ask Claude" in the header)
    implementation("com.anthropic:anthropic-java:2.34.0")

    // Every screen size check (test/ScreensTest.java), runs on the JVM with Robolectric
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
