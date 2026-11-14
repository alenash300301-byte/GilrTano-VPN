plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gilrtano.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gilrtano.vpn"
        minSdk = 29
        targetSdk = 35
        versionCode = 75
        versionName = "0.7.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Native AAR intentionally not declared until the upstream source is fetched,
    // its exact API is inspected, and socket protection is confirmed/implemented.
}
