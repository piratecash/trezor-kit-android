# Proto source

Files in this directory are copied verbatim from the Trezor firmware repository:

- Source: https://github.com/trezor/trezor-firmware
- Path: `common/protob`
- Tag used: `core/v2.12.1` (commit `64698d0d844e3322eca435beaae3df602aa08b10`)

## Files included

- `messages.proto` — top-level `MessageType` enum
- `messages-common.proto` — shared message types
- `messages-management.proto` — device management (includes `internal_model` field and
  `SafetyCheckLevel`, used by Trezor Safe 3 models T3B1/T3W1)
- `messages-bitcoin.proto` — Bitcoin-family messages
- `messages-ethereum.proto` — Ethereum messages
- `messages-solana.proto` — Solana messages
- `messages-stellar.proto` — Stellar messages
- `options.proto` — custom wire options (`wire_in`/`wire_out`/etc.) used by the files above

Files for unrelated coins/features (Monero, Cardano, EOS, NEM, Tezos, Ripple, Binance, Lisk,
WebAuthn, bootloader, debug, etc.) were intentionally left out since nothing in this module
imports them.
