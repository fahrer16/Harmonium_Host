plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.harmoniumhost"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.harmoniumhost"
        minSdk = 27
        targetSdk = 37
        versionCode = 5
        versionName = "0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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