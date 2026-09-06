plugins {
    id("com.android.application")
}

android {
    namespace = "com.atuy.colorlyric"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atuy.colorlyric"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0-spotify-only"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
