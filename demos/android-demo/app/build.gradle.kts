plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun prop(name: String, default: String): String = (project.findProperty(name) as String?) ?: default

fun quoted(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.shengzhiai.yugu.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shengzhiai.yugu.demo"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"
        // Integration keys only. Production apps should get a token from their own backend.
        buildConfigField("String", "YUGU_APP_KEY", quoted(prop("yuguAppKey", "")))
        buildConfigField("String", "YUGU_SECRET_KEY", quoted(prop("yuguSecretKey", "")))
        buildConfigField("String", "YUGU_BASE_URL", quoted(prop("yuguBaseUrl", "https://open.shengzhiai.com")))
        buildConfigField("String", "YUGU_WS_BASE_URL", quoted(prop("yuguWsBaseUrl", "wss://open.shengzhiai.com")))
    }

    buildFeatures {
        buildConfig = true
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
    implementation("com.shengzhiai.yugu:yugu-android-sdk:2.0.0")
}
