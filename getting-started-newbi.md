# Getting Started with KeyMaster Android

KeyMaster keeps your private keys on your phone and signs remotely
for desktop applications. This guide takes you from install to a
working connection with the desktop Avatar.

## 1. Requirements

- Android 8.0 (Oreo) or later
- Camera (for scanning QR codes)
- Network access to the desktop's Nostr relay (USB cable, WiFi,
  or remote tunnel)

## 2. Install the APK

Pre-built APKs will be available from GitHub Releases in a future
release. For now, build from source and sideload.

```bash
git clone https://github.com/DarkWebDivingClub/club.dwdc.keymaster.android.git
cd club.dwdc.keymaster.android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

You should see the KeyMaster icon in the app drawer.

## 3. Set up your seed

Open the app. On first launch you see three options:

**Generate a new seed:**
1. Tap **Generate New Seed**
2. The app shows 24 words in a numbered grid
3. **Write these words down on paper.** If you lose them, you lose
   all derived keys permanently. There is no recovery.
4. Optionally enter a passphrase (the "25th word")
5. Tap **Continue**

**Import an existing seed:**
1. Tap **Import Seed Phrase**
2. Type or paste your 12 or 24 BIP-39 words
3. Optionally enter the passphrase
4. Tap **Continue**

**Scan a seed backup QR:**
1. Tap **Scan QR Code**
2. Point the camera at a QR-encoded seed backup

After any of these, the app creates a default identity and takes
you to the home screen.

## 4. Create an identity

Each identity has its own Nostr, SSH, and GPG keys, all derived
from your seed.

1. Swipe right past existing identities to the "+" page
2. Tap **Create New Account**
3. Enter an identity label, display name, and email
   (e.g. `alice`, `Alice`, `alice@atlanta.com`)
4. The new identity appears with its public keys

## 5. Home screen

The home screen shows one identity at a time. Swipe left/right to
switch between identities.

- **Identity card** — npub (bech32) and hex public key, with copy
  buttons
- **Derived Keys card** — SSH (ED25519), GPG (certification +
  signing), Nostr key
- **Avatar card** — connection status and Attach/Detach buttons

| Avatar status | Meaning |
|---------------|---------|
| Green dot, "Connected" | Attached and working |
| Amber dot, "Reconnecting..." | Auto-retrying, wait 10-30 seconds |
| Red dot, "Disconnected" | Manual action needed |
| "Not connected" | No active session |

- **App Permissions card** — NIP-55 apps that requested signing
  access
- **NIP-46 Remote Sessions card** — connected remote Nostr clients

## 6. Connect to the desktop Avatar

Set up the desktop side first — see the
[Avatar Getting Started](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar/blob/master/getting-started-newbi.md).

### Make the relay reachable

**USB (recommended):** Connect the phone via USB with USB debugging
enabled. On the desktop:

```bash
adb reverse tcp:7777 tcp:7777
```

**Same WiFi:** Configure the Avatar to use the desktop's LAN IP.
See the Avatar guide for details.

**Traveling:** If you cannot use USB, forward port 7777 through
an SSH tunnel to a server the phone can reach. See the
"Traveling" section in the Avatar guide.

### Scan the QR code

1. On the desktop:

```bash
qrencode -t UTF8 < /run/keymaster-avatar/descriptor.json
```

2. On the phone, tap **Attach to Avatar** on the Avatar card
3. Point the camera at the QR code
4. Select your identity (e.g. `alice@atlanta.com`)
5. Tap **Attach**

You should see a green dot and "Connected". The notification bar
shows "Attached to relay".

On the desktop, verify: `ssh-add -l` should list your key.

## 7. Using NIP-55 (local Nostr signing)

NIP-55 lets Nostr apps on the phone use KeyMaster for signing.

1. Open a NIP-55-compatible Nostr app
2. Choose "Login with Signer" or "External Signer"
3. Select KeyMaster from the signer picker
4. Tap **Allow** on the first permission dialog
5. Manage permissions from the App Permissions card

## 8. Using NIP-46 (remote Nostr signing)

NIP-46 lets remote Nostr clients request signatures from the phone.

1. In the remote client, choose NIP-46 login — it shows a QR code
2. On the phone, tap **Connect New Client** on the NIP-46 card
3. Scan the `nostrconnect://` QR code
4. The session appears in the list. Tap **Disconnect** when done.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Camera permission denied | Settings > Apps > KeyMaster > Permissions > Camera |
| "Reconnecting..." stays amber | USB: re-run `adb reverse tcp:7777 tcp:7777`. WiFi: check IP and firewall. |
| Attach fails or times out | Check relay: `sudo systemctl status strfry` on desktop |
| No keys after seed import | Re-import the seed from the setup screen |
| Connection drops after sleep | Auto-reconnects in 10-30s. If not, see the [Reconnect Guide](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar/blob/master/doc/RECONNECT.md). |
