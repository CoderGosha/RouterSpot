plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.routerspot"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.routerspot"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        aidl = true
        compose = true
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
    packaging {
        // xray запускается как исполняемый файл, поэтому библиотеки должны лежать распакованными.
        jniLibs.useLegacyPackaging = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR без сервисов Google
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation(files("libs/hev-socks5-tunnel.aar")) // tun2socks, scripts/fetch-deps.sh
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1") // сертификат для ADB
    implementation("org.conscrypt:conscrypt-android:2.5.3") // TLS 1.3 для сопряжения
}
