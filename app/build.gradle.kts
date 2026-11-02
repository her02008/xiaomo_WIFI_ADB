plugins {
    id("com.android.application")
}

android {
    namespace = "com.workbuddy.wifiadb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.workbuddy.wifiadb"
        minSdk = 21
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
}
