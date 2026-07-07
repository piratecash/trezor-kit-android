# trezor-kit-android

[![JitPack](https://jitpack.io/v/piratecash/trezor-kit-android.svg)](https://jitpack.io/#piratecash/trezor-kit-android)

Direct-USB Trezor client for Android. Talks to a Trezor hardware wallet over USB
(no Trezor Suite / deeplink required): reads device features and public keys, and
signs transactions on-device.

## Features

- USB transport and wire framing (Trezor protocol v1)
- Session management (open → `Initialize` → operation → close), serialized so USB
  operations run one at a time
- Device read: features, public keys / addresses
- On-device signing:
  - Bitcoin family (BTC, LTC, BCH, DOGE, DASH) — with previous-transaction streaming
  - Ethereum / EVM (legacy and EIP-1559)
  - Solana
  - Stellar

## Install

Add the JitPack repository (root `settings.gradle` or `build.gradle`):

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add the dependency:

```gradle
dependencies {
    implementation 'com.github.piratecash:trezor-kit-android:1.0.0'
}
```

## Usage

All device work happens inside a single `connect { }` session:

```kotlin
val client: ITrezorClient = /* provided by your USB transport wiring */

val features = client.connect { getFeatures() }

val signature = client.connect {
    signEthereum(unsignedEvmTx)
}
```

One `connect { }` call is one USB session; the client keeps operations globally
serialized so overlapping flows never clash on the device.

## Requirements

- Android device with USB host support
- A Trezor device connected by cable and set up (not in bootloader mode)

## License

[MIT](LICENSE) © 2026 PirateCash Foundation
