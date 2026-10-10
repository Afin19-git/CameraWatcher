import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Own signing key: if keystore.properties exists (see docs/SETUP.en.md), both release and debug builds
// are signed with it, so the SHA-1 is the same and Google Cloud needs only one OAuth client.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasOwnKey = keystoreProps.isNotEmpty()

android {
    namespace = "com.camerawatcher"
    // 36: этого требует androidx.core:core 1.18.0, который библиотека USB-камеры тянет за собой
    // транзитивно (сам AAR собран под 36 и настаивает на этом у потребителя). AGP ниже 8.9.1
    // собирать против compileSdk 36 не умеет — поэтому ниже версия AGP тоже поднята.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.camerawatcher"
        minSdk = 24
        targetSdk = 33
        versionCode = 6
        versionName = "1.2.2"
    }

    signingConfigs {
        create("own") {
            if (hasOwnKey) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasOwnKey) signingConfig = signingConfigs.getByName("own")
        }
        debug {
            if (hasOwnKey) signingConfig = signingConfigs.getByName("own")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Вход в Google и токен для Drive (Google Identity Services)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    // USB-веб-камера (UVC) по OTG: https://github.com/WojciechCzeronko/AndroidUSBCamera
    implementation("com.github.WojciechCzeronko.AndroidUSBCamera:libausbc:3.6.0-lowlatency1")
    // libausbc подключает этот модуль у себя как implementation, а не api, поэтому классы libuvc
    // (в т.ч. USBMonitor, который используется в UsbCameraSource.kt) не передаются транзитивно —
    // нужно подключать явно, отдельной строкой.
    implementation("com.github.WojciechCzeronko.AndroidUSBCamera:libuvc:3.6.0-lowlatency1")
}
