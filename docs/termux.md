# Termux as an ingredient

Termux is useful on the phone, but it is not a bootstrap for a cold-boot
hotspot. This page separates the two so the roadmap does not rely on magic.

## What Termux can do

- Run an adb client (`pkg install android-tools`) against `127.0.0.1:5555` or
  the TLS port, with its own key.
- Pair once (`adb pair 127.0.0.1:<pair-port>`) and connect; `adb tcpip 5555`
  can be sent over a loopback/TLS connection.
- Run Keeper's `pin-adb-port.sh`, `adb-keepalive.sh`, `discover.sh` and
  `scan_ports.py`; Keeper already has a `RUN_COMMAND` fallback.
- Keep a wake lock, reconnect, and log a `RESULT` contract.
- Start a script at boot with Termux:Boot, subject to Android 15 background
  limits and battery optimization.

## What Termux cannot do

- Write `adb_wifi_enabled` (no `WRITE_SECURE_SETTINGS`; that is Keeper's app
  permission).
- Make `AdbDebuggingManager` accept a hotspot/P2P interface as a Wi-Fi network.
- Set `service.adb.tcp.port` / `persist.adb.tcp.port` (shell/Shizuku uid 2000
  gets `Failed to set property`).
- Start adbd or load a kernel module.
- Connect to adbd over loopback when no TCP listener exists. After a cold boot
  with hotspot-only, `adb connect 127.0.0.1:5555` is refused; USB-only adbd is
  not a TCP socket.
- Enable the hotspot itself from a normal app UID.

## The useful no-root pattern

1. A listener already exists (pinned `5555`, or Wireless debugging on).
2. Termux holds it: `adb connect 127.0.0.1:5555`, reconnects, `adb tcpip 5555`
   when needed, and logs the result.
3. Keeper's app-side monitor keeps `adb_wifi_enabled` when a real STA exists;
   Termux is the fallback adb client, not the setting writer.
4. On the laptop, `adb connect <phone-hotspot-ip>:5555`.

This is a real improvement for a phone that reboots rarely and has been pinned
once. It does not close the cold-boot gap.

## Experiments

- **#14:** prove the loopback pair/pin workflow with `5555` already listening;
  measure whether Termux alone can restore it after a reboot.
- **#15:** Termux:Boot timeline on a hotspot-only cold boot: wait for `ap0`,
  probe `5555`, try TLS discovery, log exactly what is missing. If nothing is
  listening, record that and stop; do not mask it with a retry loop.

The honest result may be that Termux can only maintain the link. That is still
worth documenting, because it removes a misleading "Termux will fix it"
assumption from the roadmap.

See `docs/keeper.md`, `docs/hotspot.md`, and issues #14/#15.
