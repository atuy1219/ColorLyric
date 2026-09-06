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
        versionCode = 4
        versionName = "0.4.0-native-unlocker"
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
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.2.0")
}
