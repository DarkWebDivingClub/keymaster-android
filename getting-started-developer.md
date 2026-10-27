# Getting Started — Developer

This guide covers building the KeyMaster Android app from source.

## Prerequisites

- JDK 21
- Android SDK 35
- Maven 3.9+ (for the dependency chain below)
- Gradle on your PATH (for step 1 below — bitcoinj ships no wrapper)

The repository includes a `.java-version` file for version managers
such as `jenv`, `asdf`, and `mise`. Set `JAVA_HOME` to a JDK 21
installation when your environment does not use one of these tools.

JDK 21 is not just a recommendation: JDK 25 is the default `java` on
current Ubuntu, and Gradle 8.13 cannot parse its class files. Building
against it fails with `Unsupported class file major version 69`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

## Build the dependency chain first

The app depends on `keymaster.core` and `keyvault.nostr`, which are
**not published to the internal Maven repository yet**. Gradle resolves
them from `mavenLocal()`, so all three upstream repos must be built and
installed into `~/.m2` first, in this order. A clean clone will not
build until you have done this.

| Order | Repo | Command | Full instructions |
|---|---|---|---|
| 0 | `https://github.com/DarkWebDivingClub/nostr-java.git` (fork) | `mvn install -DskipTests` | [`FORK.md`](https://github.com/DarkWebDivingClub/nostr-java/blob/main/FORK.md) |
| 1 | `https://github.com/DarkWebDivingClub/bitcoinj.git` (fork) | `gradle publishToMavenLocal` | [`FORK.md`](https://github.com/DarkWebDivingClub/bitcoinj/blob/master/FORK.md) |
| 2 | `https://github.com/DarkWebDivingClub/keyvault.git` | `mvn install` | [README](https://github.com/DarkWebDivingClub/keyvault#readme) |
| 3 | `https://github.com/DarkWebDivingClub/keymaster.git` | `mvn install` | [README](https://github.com/DarkWebDivingClub/keymaster#build) |

All of these repositories are public, so they clone over HTTPS with no
credentials and no SSH key — useful on a fresh machine. Use the
`git@github.com:` form once you have a key registered and need to push.

Steps 0 and 1 are patched forks whose artifacts are on no public Maven
repository, which is why they are source builds rather than
dependencies. Skipping step 0 is the easy mistake: the upstream
`xyz.tcheeric` coordinate resolves fine, so the build fails later with a
*missing class* (`nostr.id.IIdentity`) rather than a missing dependency.
Each fork's `FORK.md` says what is patched and why.

## Build

```bash
git clone https://github.com/DarkWebDivingClub/keymaster-android.git
cd keymaster-android
./gradlew test assembleDebug
```

`./gradlew test` currently runs no tests — the app has no
`app/src/test` sources yet — so `assembleDebug` succeeding is the real
signal.

If dependency resolution fails after you have bumped a version
upstream, add `--refresh-dependencies`.

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
| KeyVault Nostr | 0.4.0 | BIP-39/BIP-32 key derivation |
| KeyMaster Core | 0.7.3 | Identity CRUD, service handlers |
| BouncyCastle | 1.80 | Schnorr (BIP-340), NIP-44 crypto — resolves up to 1.84, pulled in by bitcoinj |
| CameraX | 1.4.1 | QR code scanning |
| OkHttp | 4.12.0 | WebSocket for NIP-46 relays |
| Security Crypto | 1.1.0-alpha06 | Encrypted SharedPreferences |

Internal Maven repository: `https://maven.398ja.xyz/releases`

KeyVault and KeyMaster Core are not published there yet and resolve from
`mavenLocal()` — see [Build the dependency chain
first](#build-the-dependency-chain-first). When you bump either version
here, it must already be installed in `~/.m2`.

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
