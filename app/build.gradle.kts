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
        versionCode = 12
        versionName = "1.2.0-api102"
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
}
