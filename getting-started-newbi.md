# Getting Started — Newbie

This guide walks you through setting up KeyMaster on your Android
phone and connecting it to the desktop Avatar.

## What You Need

- An Android phone (Android 10+)
- A desktop with the KeyMaster Avatar installed — see
  [keymaster-avatar](https://github.com/DarkWebDivingClub/keymaster-avatar)
  and its
  [getting-started guide](https://github.com/DarkWebDivingClub/keymaster-avatar/blob/master/getting-started-newbi.md).
  `sudo apt install keymaster-avatar` pulls in the relay (`strfry`) and
  the Bluetooth bridge (`rfcomm-relay`) too.
- **The phone paired with the desktop over Bluetooth.** That is the
  only link needed — the phone talks RFCOMM, not IP, so the two do not
  have to share a WiFi network and nothing is exposed to one.

## Step 1: Install the App

There is **no published APK yet**, so build it from source and install
the result over `adb`. Follow
[getting-started-developer.md](getting-started-developer.md) — it has
the full build order, which starts with two patched forks and takes
four repositories in sequence.

```bash
# after the build described in getting-started-developer.md
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs and runs fine. A signed, published APK is
keymaster mission 45; until then this is the supported route.

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

RFCOMM reconnection is handled by the Bluetooth stack, so in the
normal case the phone dials back on its own and the app reconnects
with no QR scan. The laptop's relay also restarts itself on resume (a
systemd sleep hook), because its websocket listener can freeze across a
long suspend.

If keys are still missing a minute after waking, on the laptop:

```bash
sudo systemctl restart rfcomm-relay
```

That drops the phone's link, which makes it dial back within seconds.
It is the cure for a session that has gone one-sided — where the phone
believes it is attached but the Avatar has no session, which happens if
`km-avatar` was restarted on its own (for instance by a package
upgrade).

### After phone reboot

The foreground service restores the session automatically
(`START_STICKY`). If the relay is reachable, the app reconnects
without intervention.

### After app force-stop

You need to re-attach by opening the app and scanning the QR
again.

## Connectivity

The phone connects over **RFCOMM** — a serial stream over Bluetooth
Classic. There is no IP layer: no DHCP, no addresses, no WiFi, and
nothing listening on the network.

```
Phone  --RFCOMM ch 3-->  rfcomm-relay  --websocket-->  strfry :7777
                                                            ^
                                                            |
                                                        km-avatar
```

`rfcomm-relay` on the laptop accepts the Bluetooth connection and
bridges it to the local relay; the Avatar is a client of that same
relay. The laptop advertises which adapter and channel to dial in the
attach QR code, so you never type an address.

Bluetooth PAN (`bt-nap`, addresses in `10.44.0.0/24`) was the earlier
transport and is being retired. You do not need it, and it should not
be installed.

### If you upgrade keymaster-avatar

Restart the user services afterwards — they keep running the previous
binaries otherwise, and signing fails with confusing errors:

```bash
systemctl --user restart km-ssh-sa km-gpg-sa km-nostr-sa
```

## Troubleshooting

### "Attach failed: Connection to relay timed out"

The phone can't reach the relay. On the laptop, check in this order:

- Is the bridge listening? `journalctl -u rfcomm-relay -n 5` should
  show `Listening on <adapter> channel 3`
- Did the phone actually dial in? The same log shows
  `Connection from [<phone MAC>]` when it does, then `RFCOMM -> WS`
  lines once traffic flows. No `Connection from` means the phone is not
  reaching Bluetooth at all — check pairing with
  `bluetoothctl info <phone MAC>`, and that it says `Trusted: yes`
- Is the relay running? `systemctl status strfry`
- Is the Avatar running? `systemctl status km-avatar`

There is no IP to ping — if you find yourself looking for an address,
the guide you are following is out of date.

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

### Bluetooth says connected, but nothing happens

The Bluetooth link and the RFCOMM session are different things — the
phone can show `Connected: yes` with no RFCOMM stream at all. Check the
laptop's `journalctl -u rfcomm-relay` for a `Connection from` line. If
there is none, restart the bridge:

```bash
sudo systemctl restart rfcomm-relay
```

### `ssh-add -l` lists keys but signing fails

That listing is cached public keys and does not prove the phone is
reachable. Test with something that needs a real signature:

```bash
echo x | gpg --clearsign
```
