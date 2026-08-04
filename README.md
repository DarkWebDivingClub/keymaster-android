# KeyMaster Android

KeyMaster is a cryptographic key management app that keeps your
private keys on your phone. It stores a BIP-39 seed phrase, derives
SSH, GPG, and Nostr keys from it, and signs remotely for desktop
applications through a Nostr relay. Your keys never leave the phone.

The app supports three signing modes:

- **Avatar** — connect to a desktop Avatar so that SSH, GPG, and
  Nostr clients on the desktop use the phone's keys
- **NIP-55** — sign locally for other Nostr apps on the same phone
- **NIP-46** — sign remotely for Nostr clients on other devices

## Getting Started

- **[Getting Started — Newbie](getting-started-newbi.md)** —
  Install the app, set up your seed, create an identity, and
  connect to the desktop. Start here.
- **[Getting Started — Developer](getting-started-developer.md)** —
  Build from source, project structure, dependencies.
- **[KeyMaster Avatar](https://github.com/DarkWebDivingClub/club.dwdc.keymaster.avatar)** —
  The desktop side (needed for SSH/GPG/Nostr bridging).

## License

This project is licensed under the GNU General Public License v3.0
only (`GPL-3.0-only`). See [LICENSE](LICENSE).
