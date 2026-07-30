# Getting Started — Developer

This guide covers building the KeyMaster Android app from source.

## Prerequisites

- JDK 21
- Android SDK 35

The repository includes a `.java-version` file for version managers
such as `jenv`, `asdf`, and `mise`. Set `JAVA_HOME` to a JDK 21
installation when your environment does not use one of these tools.

## Build

```bash
git clone https://github.com/DarkWebDivingClub/club.dwdc.keymaster.android.git
cd club.dwdc.keymaster.android
./gradlew test assembleDebug
```

Gradle and Kotlin use a JDK 21 toolchain. Android Java and Kotlin
bytecode remain targeted at Java 17 for device compatibility.

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Project structure

```
app/src/main/java/club/dwdc/keymaster/
├── avatar/          Avatar relay connection (foreground service)
├── crypto/          Schnorr signing, key service
├── data/            Repositories (seed, session, permissions)
├── nip46/           NIP-46 remote signer + relay pool
├── nip55/           NIP-55 local signer (intents + content provider)
└── ui/
    ├── screens/     Setup, Home, Avatar scan, NIP-46 scan
    ├── components/  Reusable UI components
    ├── navigation/  Compose navigation
    └── theme/       Material theme
```

## Key dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| KeyVault Nostr | 0.2.0 | BIP-39/BIP-32 key derivation |
| KeyMaster Core | 0.5.0 | Identity CRUD, service handlers |
| BouncyCastle | 1.80 | Schnorr (BIP-340), NIP-44 crypto |
| CameraX | 1.4.1 | QR code scanning |
| OkHttp | 4.12.0 | WebSocket for NIP-46 relays |
| Security Crypto | 1.1.0-alpha06 | Encrypted SharedPreferences |

Internal Maven repository: `https://maven.398ja.xyz/releases`

## SDK configuration

```
compileSdk: 35 (Android 15)
minSdk: 26 (Android 8.0 Oreo)
targetSdk: 35
Kotlin: 2.1.0
AGP: 8.7.3
Compose BOM: 2024.12.01
```

## Technical notes

- JSON serialization must use
  `GsonBuilder().disableHtmlEscaping().create()` (issue #16)
- BIP-340 Schnorr signing uses manual BouncyCastle implementation
- Curve25519 seeds must be clamped before creating
  `X25519PrivateKeyParameters`
