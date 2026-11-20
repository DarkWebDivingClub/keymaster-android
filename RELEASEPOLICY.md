# Release Policy

## Versioning Scheme

This project uses **semantic versioning** (`0.x.y`) while in pre-production.

- `0.x.0` — **minor**: new features or significant changes
- `0.x.y` — **patch**: bug fixes within a release

No backwards-compatibility promises while on `0.x`. Once the project is stable and production-ready, it will move to `1.0.0`, after which:

- **major** (`x.0.0`) — breaking changes
- **minor** (`x.y.0`) — new features, backwards-compatible
- **patch** (`x.y.z`) — bug fixes

## When to Tag

Tag on **milestones** — when a meaningful set of features or fixes is ready, not on every commit. Multiple commits may land between tags.

## How to Tag

All tags are signed annotated tags:

```bash
git tag -s v<version> -m "<summary of what changed since last tag>"
```

## Release History

| Version | Description |
|---------|-------------|
| v0.2.0  | npub (bech32) display on home screen |
| v0.3.0  | Multi-account support, Gson HTML escaping fix (#16) |
| v0.4.0  | Multi-realm, service lifecycle |
| v0.4.1  | Android 13 fix: core 0.7.1 (Stream.toList) |
| v0.5.0  | X.509 keys served over the avatar channel: core 0.8.1, keyvault 0.5.0 (P-256 ECDSA); fallback point before the KeyVault 0.6.0 uplift |
| v0.6.0  | KeyVault 0.6.0 uplift: core 0.9.1, keyvault 0.6.0 (SLIP-10 keys; SSH, OpenPGP, MLS and X.509 keys change, Nostr unchanged); store repair replaces entries the vault refuses |
