plugins {
    id("com.android.application")
}

android {
    namespace = "com.dsh.ramwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.ramwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/ramwatch.p12")
            storePassword = "minesweeper"
            keyAlias = "minesweeper"
            keyPassword = "minesweeper"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
