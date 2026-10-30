plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mockloc"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("mockloc.keystore")
            storePassword = "mockloc123"
            keyAlias = "mockloc"
            keyPassword = "mockloc123"
        }
    }

    defaultConfig {
        applicationId = "com.example.mockloc"
        minSdk = 26
        targetSdk = 34
        // Goes up by one on every GitHub build so each APK counts as an update
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
