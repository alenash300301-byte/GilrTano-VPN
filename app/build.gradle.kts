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
        versionCode = 100
        versionName = "0.9.10"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    // Both Go-based AARs bundle libgojni.so; package one shared copy.
    packaging {
        jniLibs {
            pickFirsts += "**/libgojni.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.4")
    implementation(files("libs/teapod-core-1.7.7.aar"))
    // Loaded only by PortalMasqueVpnService in the dedicated :portal process.
    implementation(files("libs/libXray.aar"))
}
