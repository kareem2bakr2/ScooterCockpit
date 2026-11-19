plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.scooter.cockpit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scooter.cockpit"
        minSdk = 15
        targetSdk = 15
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = false
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:5.6.5")

    implementation("com.github.mik3y:usb-serial-for-android:3.4.6") // Supports minSdk 15+ (CP210x, CH340, FTDI, PL2303)
}