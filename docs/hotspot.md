# Hotspot: the phone as AP, and the "connect to itself" idea

Measured on the XPLORE 2 on 2026-11-27. The phone was hosting its own hotspot
(`XPLORE 2`), the NixOS laptop was joined as a client, and no external AP was
involved.

## What works

| Item | Value |
|---|---|
| Phone hotspot interface | `ap0` = `10.31.180.89/24` |
| Laptop client | `wlp110s0` = `10.31.180.149/24` |
| adbd listener | `[::]:5555` (`service.adb.tcp.port=5555`) |
| `adb connect 10.31.180.89:5555` | works, uid 2000(shell) |
| Phone `adb_wifi_enabled` | `0` (Wireless debugging toggle off) |

This is the practical no-external-AP result: the phone hosts the link, the
laptop joins it, and legacy adb works over `5555` even though the phone is not
a Wi-Fi client and the Wireless debugging toggle is off.

The catch is that `5555` is volatile. It survives hotspot/P2P transitions but
not a reboot; `persist.adb.tcp.port` is empty, and the shell cannot set either
property:

```text
$ adb shell setprop service.adb.tcp.port 5555
Failed to set property 'service.adb.tcp.port' to '5555'.

$ adb shell setprop persist.adb.tcp.port ''
Failed to set property 'persist.adb.tcp.port' to ''.
```

## Why the phone cannot join its own hotspot

`STA + AP Concurrency Supported: true` is real, but it means **STA to an external
AP + SoftAP**, not STA to the SoftAP on the same device. AP and STA must share a
channel in one band, i.e. one radio, and a radio cannot receive its own beacon,
auth or association frames. Two-STA on this chip is band-split
(`mIsMultiInternetSameBandConnectionAllowed=false`, no EHT/MLO); retuning the
STA to the AP's channel puts both virtual interfaces on the same radio.

Android itself has no self-SSID/self-BSSID check: `WifiServiceImpl.connect()` only checks privilege, `ClientModeImpl` and `WifiConnectivityManager` do not reference SoftAP, and a client connect does not stop the hotspot under STA+AP. The blocker is the radio. The STA's scan cache lists nearby APs but never the phone's own BSSID; the BSSID exists only in the SoftAP config. A CPython/`ip route get 10.31.180.89` check shows `local ... dev lo`, so the phone can reach its own hotspot address at L3; only the framework's client-network gate cares about the association.

The HAL dump confirms the combinations:

```text
{maxIfaces=2, types=[0]}                            -> 2 x STA
{maxIfaces=1, types=[2]}                            -> 1 x P2P
{maxIfaces=1, types=[0]}, {maxIfaces=1, types=[1]}  -> STA + AP
{maxIfaces=1, types=[0]}, {maxIfaces=1, types=[3]}  -> STA + NAN
{maxIfaces=1, types=[0]}, {maxIfaces=1, types=[4]}  -> STA + AP_BRIDGED
```

There is no AP + AP, no AP + P2P, no 2-STA + AP, and no same-device GO + GC.
`cmd wifi` cannot help either: `start-softap`, `set-multi-internet-mode` and
`instant-tethering` exist but are gated to `uid == ROOT_UID`, and this build has
no `su`. Android does not block the attempt (a `WifiNetworkSpecifier` with an
explicit BSSID or `cmd wifi connect-network ... -b <own-bssid>` is shell-legal),
so the limit is the PHY/firmware, not a permission. A test will simply time out.

## The Wireless debugging gate is also real

With hotspot-only, `adb_wifi_enabled` cannot be forced on. Writing it is
reverted within seconds:

```text
$ adb shell settings put global adb_wifi_enabled 1
$ adb shell settings get global adb_wifi_enabled
0
```

The platform log explains why:

```text
AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.
```

Keeper logs `enabled wireless debugging`, then observes the setting turned off
again. The gate is a real `TRANSPORT_WIFI` client network, not a UI quirk. This
is why "hotspot + wireless debugging" cannot replace the current AP for the
toggle; only legacy `5555` works over the hotspot.

## Safe falsification of the self-connect idea (issue #2)

Keep USB adb attached and the rollback ready. This changes two settings and is
reversible.

1. Enable the hotspot (2.4 GHz is a different band from the current link).
2. Record `cmd wifi status`, `dumpsys wifi`, `ip -o link`, and
   `cmd wifi list-scan-results`.
3. Try joining the phone's own SSID:
   `cmd wifi connect-network "<ssid>" wpa2 "<pass>" -d -b <own-bssid>`
   (`-d` disables autojoin so the attempt is explicit; pin the BSSID from the
   SoftAP config).
4. Watch `dumpsys wifi` and logcat for `CMD_START_CONNECT`, `ASSOCIATED`,
   `NETWORK_CONNECTION_FAILURE`. Expect retries and no association.
5. Roll back: `cmd wifi forget-network <id>` if a profile was added, then
   rejoin the previous network and run `phone-doctor`.

If it ever associates and gets an IP, the physics argument is wrong; record the
interface, IP and whether `adb connect` works. Otherwise close #2 as ruled out.

## Cold-boot conclusion (no root)

The hotspot link itself is proven; the missing piece is the listener after a
reboot. Without root, USB, or a temporary STA link, there is no way to restore
`5555` or turn on Wireless debugging from the hotspot alone. See
`ROADMAP.md` and issues #11, #12, #14, #15. Do not call a one-pin-per-boot
workflow automatic.
