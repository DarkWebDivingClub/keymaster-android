plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "club.dwdc.keymaster"
    compileSdk = 35

    defaultConfig {
        applicationId = "club.dwdc.keymaster"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/logback.xml"
        }
    }
}

configurations.all {
    exclude(group = "com.fasterxml.jackson.module", module = "jackson-module-blackbird")
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    exclude(group = "ch.qos.logback", module = "logback-classic")
}

dependencies {
    // KeyVault nostr: key derivation + KeyVaultIdentity (IIdentity) + NIP-44 support
    implementation("club.dwdc:club.dwdc.keyvault.nostr:0.2.0")
    // KeyMaster core: identity CRUD (KeyMasterController, KVMetaStore)
    implementation("club.dwdc:club.dwdc.keymaster.core:0.5.1")
    // BouncyCastle — needed for Schnorr (BIP-340) and ChaCha20 (NIP-44)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Security - encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // CameraX
    val cameraVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    // ML Kit barcode scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // OkHttp — WebSocket client for NIP-46 relay communication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
