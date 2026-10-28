import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun loadProps(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}

val version = loadProps("version.properties")
val keystore = loadProps("keystore.properties")

android {
    namespace = "com.intuitivesolns.bndraft"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.intuitivesolns.bndraft"
        minSdk = 26
        targetSdk = 35
        versionCode = version.getProperty("versionCode").toInt()
        versionName = version.getProperty("versionName")
        // Where the app looks for updates (GitHub "latest release" asset URLs).
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://github.com/yaniv-work/BN-Draft/releases/latest/download\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile", "keystore/bn-draft.jks"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
