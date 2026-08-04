# Getting Started — Newbie

This guide walks you through setting up KeyMaster on your Android
phone and connecting it to the desktop Avatar.

## What You Need

- An Android phone (Android 10+)
- A desktop with the KeyMaster Avatar installed
  ([keymaster-avatar](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar))
- Network connectivity between phone and desktop (WiFi or
  Bluetooth PAN)
- The relay (strfry) running on the desktop

## Step 1: Install the App

Install the KeyMaster APK on your phone:

```bash
adb install app-debug.apk
```

Or build from source (see [getting-started-developer.md](getting-started-developer.md)).

## Step 2: Set Up Your Seed Phrase

On first launch, the app shows the setup screen. You have three
options:

- **Generate New Seed** — creates a fresh 24-word BIP-39 mnemonic.
  Write it down and store it safely. This is the only way to
  recover your keys.
- **Import Seed Phrase** — paste or type an existing 24-word
  mnemonic.
- **Scan QR Code** — scan a QR containing a BIP-39 mnemonic.

Optionally set a passphrase ("25th word"). A different passphrase
produces completely different keys. If you set one, you must
remember it.

After storing the seed, the app creates a default identity
automatically.

## Step 3: Create an Identity

If you need additional identities beyond the default:

1. Swipe to the last page on the home screen
2. Tap **Create New Account**
3. Enter a name, email, and optionally a custom identity string
4. The app derives SSH, GPG, and Nostr keys for this identity

## Step 4: Attach to the Desktop Avatar

### On the desktop

The avatar generates a descriptor QR at startup. Find it at:

```bash
cat /run/keymaster-avatar/descriptor.json
```

Display the QR using any QR tool, or show it in a terminal:

```bash
qrencode -t UTF8 < /run/keymaster-avatar/descriptor.json
```

### On the phone

1. Open the account you want to attach
2. Tap **Attach to Avatar**
3. Scan the QR code (or tap **Paste JSON instead** to paste the
   descriptor manually)
4. Confirm the relay URL and select the identity
5. If you have multiple identities, check **Also attach** for
   additional ones
6. Tap **Attach**

The notification bar shows "Attached to ws://..." when connected.
SSH, GPG, and Nostr keys from the phone are now available on the
desktop.

### Verify on the desktop

```bash
# SSH keys
SSH_AUTH_SOCK=/run/user/$(id -u)/keymaster-ssh-agent.sock ssh-add -l

# GPG signing
GNUPGHOME=/run/user/$(id -u)/gnupg-keymaster gpg --clearsign <<< "test"
```

## Step 5: Daily Use

### After laptop sleep/wake

When the laptop wakes from sleep, BT PAN drops and the relay
connection dies. The phone shows a "Bluetooth connection lost"
notification. To recover:

1. On the phone, open Bluetooth settings (tap the notification or
   the in-app dialog)
2. Toggle **Internet access** off then on for the paired laptop
3. The app auto-reconnects — no QR scan needed

### After phone reboot

The foreground service restores the session automatically
(`START_STICKY`). If the relay is reachable, the app reconnects
without intervention.

### After app force-stop

You need to re-attach by opening the app and scanning the QR
again.

## Connectivity

The phone connects to the desktop relay over Bluetooth PAN — a
dedicated BT link independent of WiFi.

```
Phone (10.44.0.3) --BT PAN--> Laptop (10.44.0.1) --> strfry :7777
Laptop WiFi                --> internet
```

### Prerequisites

- Phone and laptop paired via Bluetooth
- Laptop running bt-nap service (NAP server on `bt-nap-br` bridge)
- strfry relay listening on the BT PAN interface

### Establishing BT PAN

From the phone: BT settings → paired laptop → tap **CONNECT**.
The phone gets an IP via DHCP (e.g. 10.44.0.3). Verify from the
laptop:

```bash
ping 10.44.0.3
```

### BT PAN troubleshooting

See [Advanced BT Troubleshooting](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar/blob/master/doc/advanced-bt-troubleshooting.md)
for common pitfalls — interface naming, connection direction,
stale PAN state, and sleep/wake recovery.

## Troubleshooting

### "Attach failed: Connection to relay timed out"

The phone can't reach the relay. Check:
- Is the relay running? `systemctl status strfry`
- Can the phone reach the relay IP? Ping from phone:
  `adb shell ping <relay-ip>`
- Is BT PAN up? `ip link show master bt-nap-br` on the laptop

### SSH/GPG not working after sleep/wake

Check the phone logs:

```bash
adb logcat -s AvatarService
```

If you see "Network available: transport=BLUETOOTH" followed by
"Re-attached", the phone side is fine. Check the desktop:

```bash
# Avatar relay connection
journalctl --user -u km-avatar --since "5 minutes ago"

# SSH service avatar
journalctl --user -u km-ssh-sa --since "5 minutes ago"
```

### Phone shows "Reconnect failed"

The app tried 5 times to reconnect and gave up. The relay was
unreachable during all attempts. Re-establish network connectivity
and re-attach manually (open app → Attach to Avatar → scan QR).

### BT PAN up but can't ping

See [Advanced BT Troubleshooting](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar/blob/master/doc/advanced-bt-troubleshooting.md)
— common causes: stale PAN state (clean BT cycle needed), wrong
BNEP interface name (check `ip link show master bt-nap-br`),
or ARP not resolving (restart bt-nap service).
