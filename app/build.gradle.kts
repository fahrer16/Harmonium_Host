plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.harmoniumhost"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The code's package stays com.example.harmoniumhost; this is the installed app's identity.
        applicationId = "io.github.fahrer16.harmoniumhost"
        minSdk = 27
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment (the GitHub release workflow decodes the keystore
    // from repository secrets). Without it, the release build is left unsigned.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}