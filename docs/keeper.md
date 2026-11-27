# How Wireless ADB Keeper switches wireless debugging on

Reference: [MBanucu/wireless-adb-keeper](https://github.com/MBanucu/wireless-adb-keeper),
read at commit from 2026-11-27. It is an unrooted Android app plus a Termux
companion; this page records the mechanism and the parts that matter for a
phone-hosted link.

## The mechanism

1. **Permission.** The install grants `WRITE_SECURE_SETTINGS` to the app:
   ```sh
   adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.WRITE_SECURE_SETTINGS
   ```
   A normal app cannot write `Settings.Global`; this grant is what makes the
   rest possible. It is not root.

2. **The setting write.** `Ensurer.ensure()` in
   `src/io/github/mbanucu/wirelessadbkeeper/Ensurer.java`:
   - checks `WRITE_SECURE_SETTINGS`;
   - checks `isWifiConnected()` across **all** `ConnectivityManager` networks
     (not just the default route), looking for `TRANSPORT_WIFI`;
   - writes `development_settings_enabled=1` if needed;
   - writes `adb_wifi_enabled=1` if it is off;
   - sets a 3 s quiet window so its own content-observer notification cannot
     cause a write/notify loop that restarts adbd repeatedly.
   The platform then starts the TLS listener and advertises
   `_adb-tls-connect._tcp` via mDNS.

3. **Keeping it on.** `MonitorService` is a foreground service with a
   `NetworkCallback`, a `ContentObserver` on the setting, a boot receiver and a
   periodic job. When Wi-Fi returns, the setting is flipped back on; when the
   user or the system turns it off, the observer re-applies it.

   Measured after a cold boot (`tests/fixtures/cold-boot-5555-timeline.log`):
   with no Wi-Fi, nothing is written for four minutes; when a trusted STA
   appears the toggle is written within ~1 s of association; forcing it off
   while the STA is up is undone in under a second.

   ```text
   11-27 20:03:09  $ settings put global adb_wifi_enabled 0
   11-27 20:03:10  $ settings get global adb_wifi_enabled  -> 1
   11-27 20:03:10.344  WifiDebug: enabled wireless debugging
   11-27 20:03:10.359  WifiDebug: ensure.changed setting turned off: Enabled wireless debugging
   ```

   Removing the STA flips it back off and Keeper stops fighting: `Network
   disconnected. Disabling adbwifi.` then `ensure: no Wi-Fi, skipping`.

4. **Fixed port 5555.** Android gives adbd a new random TLS port on every
   restart. `PinnedPort` probes `127.0.0.1:5555` every 10 s; when it is closed,
   it sends the adb service request `tcpip:5555` over the app's own trusted TLS
   key (`TlsPinTransport`), or asks Termux to run `pin-adb-port.sh`. Once
   pinned, both the TLS port and `5555` listen; the fixed port survives until
   adbd restarts or the phone reboots. On a cold boot the whole chain takes about
   17 s from association: toggle at `19:59:47`, `tcpip:5555` at `20:00:02`,
   `5555` listening at `20:00:04` (next 10 s poll).

5. **Wi-Fi control.** `WifiSuggestion` registers one `WifiNetworkSuggestion` per
   saved profile, so Android rejoins the AP silently after a reboot.
   `WifiRadio` turns the radio on with `svc wifi enable` over the app's TLS key
   (or Termux), because apps lost `setWifiEnabled` in Android 10.

6. **Termux companion.** `adb-keepalive.sh`, `discover.sh`/`scan_ports.py`,
   `pin-adb-port.sh`, `wifi-on.sh` and `pair-local.sh` provide the on-device adb
   client, mDNS discovery, port scan, pinning and one-time pairing. Termux is a
   fallback for the app's TLS transport, not a replacement for it.

## The hard gate

`Ensurer.ensure()` deliberately refuses to write the setting when
`isWifiConnected()` is false. That is not just caution: `AdbDebuggingManager`
enforces the same rule. On this phone, hotspot-only:

```text
$ adb shell settings put global adb_wifi_enabled 1
$ adb shell settings get global adb_wifi_enabled
0
AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.
```

Keeper's own log shows the race: `enabled wireless debugging`, then the setting
is observed off. A SoftAP or P2P GO does not create a `TRANSPORT_WIFI` network,
so the toggle cannot be enabled in that topology. The legacy `5555` listener is
different: it is reachable over the hotspot if it already exists, with the
toggle off.

## Measured pin, and what happens when the STA goes away

With a real STA present, Keeper set the toggle and `PinnedPort` recreated `5555`
after `adb usb` restarted adbd:

```text
WifiDebug: info pin.tls_ok asked adbd over TLS to listen on 5555
WifiDebug: pin check: closed (asked the app's TLS client)
WifiDebug: pin.state listening on 5555
```

The phone did this over its own TLS key; the host only restarted adbd. After the
AP was turned off, the platform reverted `adb_wifi_enabled` to `0` and the TLS
listener stopped, but `service.adb.tcp.port` stayed `5555` and the legacy
listener stayed up:

```text
11-27 19:20:02.219 adbd ADB wifi device disconnected
$ settings get global adb_wifi_enabled -> 0
$ getprop service.adb.tcp.port       -> 5555
$ ss -ltn | grep 5555                -> LISTEN *:5555
```

That is the useful split: the toggle is per-STA, the pinned legacy port is
per-adbd-life, and the latter survives losing the STA. A cold boot resets both.
See [`short-bootstrap.md`](short-bootstrap.md).

## What this means for this project

- Keeper is the right **setting/pin/keepalive** layer. Reuse it rather than
  reimplementing `WRITE_SECURE_SETTINGS`, mDNS, `tcpip:5555` and the boot
  receivers.
- Keeper is not a **link**. It needs a real STA link (the laptop AP or a router)
  for the toggle, or an already-pinned `5555` for the transport. The STA can go
  away after the pin; it is only needed to enable the toggle and let Keeper send
  `tcpip:5555`.
- A phone-hosted hotspot/P2P link therefore needs a Keeper Lite variant only for
  the cold boot:
  - keep the existing `Ensurer`/`MonitorService` behavior when a real AP exists;
  - do not pretend the toggle can be enabled on a hotspot/P2P GO;
  - keep and expose legacy `5555` when it is already pinned, including after the
    STA disappears;
  - document the cold-boot gap (issue #12).
- Termux can run the client and watchdog once a listener exists, but cannot
  create the listener on a cold boot. See `docs/termux.md`.

## Useful commands

```sh
# install and grant (the APK must declare the permission)
adb install -r wireless-adb-keeper.apk
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.ACCESS_FINE_LOCATION
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.POST_NOTIFICATIONS

# inspect
adb shell settings get global adb_wifi_enabled
adb shell dumpsys package io.github.mbanucu.wirelessadbkeeper | grep WRITE_SECURE_SETTINGS
adb shell getprop service.adb.tcp.port

# on-device Termux fallback (once a listener exists)
adb connect 127.0.0.1:5555
adb tcpip 5555
```

Do not echo or store the phone's pairing PIN. The Keeper UI and docs handle
pairing; this repository only needs the mechanism, not the secret.
