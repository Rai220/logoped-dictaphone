plugins {
    id("com.android.application")
}

android {
    namespace = "ru.giga.dictaphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.giga.dictaphone"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("dictaphone.jks")
            storePassword = "dictaphone"
            keyAlias = "dictaphone"
            keyPassword = "dictaphone"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
