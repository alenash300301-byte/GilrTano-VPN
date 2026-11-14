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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

val mihomoWrapperVersion = "0.3.13"
val mihomoAar = layout.buildDirectory.file("libs/libmihomo-android-v$mihomoWrapperVersion.aar")
val downloadMihomoAar by tasks.registering {
    outputs.file(mihomoAar)
    doLast {
        val target = mihomoAar.get().asFile
        target.parentFile.mkdirs()
        val url = "https://github.com/oviron/libmihomo-android/releases/download/v${mihomoWrapperVersion}/libmihomo-android-v${mihomoWrapperVersion}.aar"
        target.outputStream().use { out ->
            java.net.URL(url).openStream().use { input -> input.copyTo(out) }
        }
    }
}

dependencies {
    implementation(files(mihomoAar).builtBy(downloadMihomoAar))
    implementation("org.yaml:snakeyaml:2.4")
    implementation(files("libs/teapod-core-1.7.7.aar"))
}
