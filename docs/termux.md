# Termux as an ingredient

Termux is useful on the phone, but it is not a bootstrap for a cold-boot
hotspot. This page separates the two so the roadmap does not rely on magic.

## What Termux can do

- Run an adb client (`pkg install android-tools`) against `127.0.0.1:5555` or
  the TLS port, with its own key.
- Pair once (`adb pair 127.0.0.1:<pair-port>`) and connect; `adb tcpip 5555`
  can be sent over a loopback/TLS connection **once a listener exists**.
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
- Use the phone's own USB transport. With the cable attached and USB debugging
  enabled, Termux's `adb devices` still shows no phone; `/dev/socket/adbd` is
  permission-denied too.
- Enable the hotspot itself from a normal app UID.

## Measured with USB attached

USB debugging being on does not give Termux a device. On 2026-11-27, with the
cable attached and no STA (`adb_wifi_enabled=0`):

```text
$ adb kill-server; adb devices
List of devices attached
emulator-5554   offline        # stale local port, not the phone

$ adb connect 127.0.0.1:5556
failed to connect to '127.0.0.1:5556': Connection refused

$ adb connect 127.0.0.1:5555    # Keeper had already pinned this
connected to 127.0.0.1:5555

$ ls /dev/socket/adbd
ls: cannot access '/dev/socket/adbd': Permission denied
```

The open port worked because Keeper had pinned it over the transient STA; the
closed port was refused. Termux can maintain and re-pin an existing listener
(`adb tcpip 5555` restarted adbd and killed the adb-forwarded SSH session), but
it cannot create the first one. See `docs/usb-ideas.md` and
[`short-bootstrap.md`](short-bootstrap.md).

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

- **#14 (answered):** the loopback pair/pin workflow works with `5555` already
  listening; Termux cannot restore it after a reboot because it cannot create
  the first listener. USB does not change that.
- **#15 (answered):** on a hotspot-only cold boot, Termux is not running, `ap0`
  is absent, no `5555` listener exists, and there is nothing for Termux:Boot to
  maintain. It can only log the missing preconditions; do not mask that with a
  retry loop.

The measured result is that Termux can only maintain the link. That is still
worth documenting, because it removes a misleading "Termux will fix it"
assumption from the roadmap.

See `docs/keeper.md`, `docs/hotspot.md`, `docs/usb-ideas.md`, and issues
#14/#15.
