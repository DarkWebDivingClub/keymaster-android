# KeyMaster Android

KeyMaster is a cryptographic key management app that keeps your
private keys on your phone. It stores a BIP-39 seed phrase, derives
SSH, GPG, and Nostr keys from it, and signs remotely for desktop
applications through a Nostr relay. Your keys never leave the phone.

The app supports three signing modes:

- **Avatar** -- connect to a desktop Avatar so that SSH, GPG, and
  Nostr clients on the desktop use the phone's keys
- **NIP-55** -- sign locally for other Nostr apps on the same phone
- **NIP-46** -- sign remotely for Nostr clients on other devices

## Requirements

- Android 8.0 (Oreo) or later
- Camera (for scanning QR codes)
- Network access to the desktop's Nostr relay (USB cable with ADB,
  or same WiFi network)

## Install

Pre-built APKs will be available from GitHub Releases in a future
release. For now, build from source and sideload.

### Build from source

Clone the repository and build a debug APK:

```bash
git clone https://github.com/DarkWebDivingClub/club.dwdc.keymaster.android.git
cd club.dwdc.keymaster.android
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

### Install on the phone

Connect the phone via USB with USB debugging enabled:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

You should see the KeyMaster icon in the app drawer.

## First Launch -- Set Up Your Seed

Open the app. On first launch, you see the setup screen with three
options:

### Generate a new seed

1. Tap **Generate New Seed**
2. The app shows 24 words in a numbered grid
3. **Write these words down on paper.** This is your only backup.
   If you lose them, you lose all derived keys permanently.
4. Optionally enter a passphrase (the "25th word") for extra
   protection
5. Tap **Continue**

### Import an existing seed

1. Tap **Import Seed Phrase**
2. Type or paste your 12 or 24 BIP-39 words
3. Optionally enter the passphrase if you used one
4. Tap **Continue**

### Scan a seed backup QR

1. Tap **Scan QR Code**
2. Point the camera at a QR-encoded seed backup

After any of these, the app creates a default identity and takes
you to the home screen.

## Create an Identity

Each identity has its own Nostr, SSH, and GPG keys, all derived
from your seed. To create a new identity:

1. Swipe right past your existing identities to the "+" page
2. Tap **Create New Account**
3. Enter an identity label, display name, and email address
   (e.g. `alice`, `Alice`, `alice@atlanta.com`)
4. The new identity appears with its public keys and derived keys

## Home Screen

The home screen shows one identity at a time. Swipe left and right
to switch between identities.

### Identity card

Shows the identity label, the Nostr public key in bech32 format
(`npub1...`) and hex format. Tap the copy buttons to copy either
format to the clipboard.

### Derived Keys card

Shows the derived keys for this identity:

- **SSH** -- ED25519 key for SSH authentication
- **GPG** -- Certification and signing keys for OpenPGP
- **Nostr** -- Nostr signing key

### Avatar card

Shows the connection to the desktop Avatar:

| Status | Meaning |
|--------|---------|
| Green dot, "Connected" | Attached and working |
| Amber dot, "Reconnecting..." | Temporarily disconnected, auto-retrying |
| Red dot, "Disconnected" | Connection lost, tap Attach to reconnect |
| "Not connected" | No active session |

Buttons: **Attach to Avatar** (scan QR), **Detach** (disconnect).

### App Permissions card

Lists Nostr apps on the phone that have requested NIP-55 signing
access, with allowed/denied status and a revoke button for each.

### NIP-46 Remote Sessions card

Lists connected remote Nostr clients. Tap **Connect New Client** to
scan a `nostrconnect://` QR code from a remote Nostr client.

## Connect to the Desktop Avatar

This connects the phone to a desktop running the KeyMaster Avatar,
so that SSH, GPG, and Nostr clients on the desktop use the phone's
keys. See the
[Avatar README](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar)
for setting up the desktop side.

### Make the relay reachable from the phone

The phone must reach the Nostr relay running on the desktop.

**Method A: USB via ADB reverse (recommended)**

Connect the phone via USB cable with USB debugging enabled. On the
desktop:

```bash
adb reverse tcp:7777 tcp:7777
```

The default relay address `ws://localhost:7777` now works on the
phone. Re-run this command after USB disconnect or phone reboot.

**Method B: Same WiFi network**

If both devices are on the same WiFi, configure the Avatar to use
the desktop's LAN IP instead of `localhost`. See the Avatar README
for instructions.

### Scan the QR code

1. On the desktop, display the Avatar descriptor as a QR code:

```bash
qrencode -t UTF8 < /run/keymaster-avatar/descriptor.json
```

2. On the phone, tap **Attach to Avatar** on the Avatar card
3. Point the camera at the QR code on the desktop screen
4. In the confirmation dialog, select which identity to attach
   (e.g. `alice@atlanta.com`)
5. Tap **Attach**

You should see:
- The Avatar card shows a green dot and "Connected"
- The notification bar shows "Attached to relay"

On the desktop, verify with:

```bash
ssh-add -l
```

You should see your SSH key listed.

## Using NIP-55 (Local Nostr Signing)

NIP-55 lets other Nostr apps on the phone use KeyMaster for signing
without exposing your private keys to those apps.

1. Open a NIP-55-compatible Nostr app on the phone
2. Choose "Login with Signer" or "External Signer"
3. The system shows KeyMaster as a signer option -- select it
4. The first request shows a permission dialog -- tap **Allow**
5. Manage permissions from the App Permissions card on the home
   screen

## Using NIP-46 (Remote Nostr Signing)

NIP-46 lets remote Nostr clients (on a laptop browser, for example)
request signatures from the phone over a relay.

1. In the remote Nostr client, choose NIP-46 login. The client
   displays a `nostrconnect://` QR code.
2. On the phone, tap **Connect New Client** on the NIP-46 Remote
   Sessions card
3. Scan the `nostrconnect://` QR code
4. The session appears in the NIP-46 Remote Sessions list
5. Tap **Disconnect** to end the session when done

## Troubleshooting

### Camera permission denied

Go to Settings > Apps > KeyMaster > Permissions and enable Camera.

### "Reconnecting..." stays amber

The phone cannot reach the relay. Check:
- **USB:** Run `adb reverse tcp:7777 tcp:7777` again
- **WiFi:** Verify the desktop's IP has not changed and port 7777
  is not blocked

### Attach fails or times out

- Verify the relay is running on the desktop:
  `sudo systemctl status strfry`
- Verify the descriptor is valid:
  `cat /run/keymaster-avatar/descriptor.json`
- Check that the QR code is sharp and fully visible to the camera

### No keys shown after seed import

The seed phrase may not have been stored. Go back to the setup
screen and re-import.

### After sleep/wake, the connection drops

The phone auto-reconnects after the desktop wakes from sleep. Wait
10-30 seconds for the amber "Reconnecting..." to turn green. If it
does not reconnect, see the
[Reconnect Guide](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar/blob/master/doc/RECONNECT.md).

## Development

### Requirements

- JDK 21
- Android SDK 35

The repository includes a `.java-version` file for version managers
such as `jenv`, `asdf`, and `mise`. Set `JAVA_HOME` to a JDK 21
installation when your environment does not use one of these tools.

### Build

```bash
./gradlew test assembleDebug
```

Gradle and Kotlin use a JDK 21 toolchain. Android Java and Kotlin
bytecode remain targeted at Java 17 for device compatibility.

## License

This project is licensed under the GNU General Public License v3.0
only (`GPL-3.0-only`). See [LICENSE](LICENSE).
