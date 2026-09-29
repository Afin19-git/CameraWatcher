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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.camerawatcher"
        minSdk = 24
        targetSdk = 33
        versionCode = 3
        versionName = "1.0.0"
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
}
