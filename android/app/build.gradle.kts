plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}
android {
    signingConfigs {
        create("stableDebug") {
            storeFile = file(System.getenv("BLACKOUT_KEYSTORE_PATH") ?: "blackout-debug.keystore")
            storePassword = System.getenv("BLACKOUT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("BLACKOUT_KEY_ALIAS")
            keyPassword = System.getenv("BLACKOUT_KEY_PASSWORD")
        }
    }
    namespace = "ua.blackout.app"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    defaultConfig {
        applicationId = "ua.blackout.app"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("BLACKOUT_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }
}
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
}
