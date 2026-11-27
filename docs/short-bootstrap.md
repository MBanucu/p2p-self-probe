# Short bootstrap: enable Wireless debugging once, keep legacy 5555

Measured on 2026-11-27 on the XPLORE 2 (Android 15, unrooted) with the laptop as
the adb client. The goal was narrower than a permanent STA: turn Wireless
debugging on for a short time, let the **phone** pin legacy `5555` with its own
TLS key, then remove the client link. The phone-hosted hotspot is enough after
that, and `5555` lasts until the next reboot.

This works. The external AP is needed only until `5555` is listening; it does
not have to stay connected.

## What ran

1. The laptop hosted a temporary 5 GHz AP (`PhoneLink`, 5745 MHz) and the phone
   joined it as a normal station (`wlan0` = `192.168.77.232/24`). USB adb was
   attached as the lifeline.
2. Keeper (`io.github.mbanucu.wirelessadbkeeper`, `WRITE_SECURE_SETTINGS`) saw
   the `TRANSPORT_WIFI` network and set `adb_wifi_enabled=1` and
   `development_settings_enabled=1`. adbd started a TLS listener on a random
   port (34019 in this run) and registered `_adb-tls-connect._tcp`.
3. The laptop sent `adb usb` and adbd restarted in USB mode. Keeper's
   `PinnedPort` loop noticed that `5555` was closed and sent `tcpip:5555` over
   its trusted TLS key. adbd reopened `5555` and Keeper recorded it.
4. The temporary AP was turned off. The phone lost the STA link,
   `adb_wifi_enabled` reverted to `0`, and the TLS listener died — but
   `service.adb.tcp.port` stayed `5555` and the legacy listener stayed up.
5. The phone hotspot was enabled, the laptop joined it, and
   `adb connect <ap0-ip>:5555` worked with `adb_wifi_enabled=0` and no STA.

## Evidence

Keeper's own log is the important part: the phone, not the host, asked adbd for
the fixed port.

```text
11-27 19:18:58.442  adbd      adbwifi started on port 37193
11-27 19:19:00.685  WifiDebug info pin.tls_ok asked adbd over TLS to listen on 5555
11-27 19:19:00.689  WifiDebug pin check: closed (asked the app's TLS client)
11-27 19:19:00.728  adbd      adbwifi started on port 34019
11-27 19:19:10.341  WifiDebug pin.state listening on 5555
```

After the STA went away:

```text
11-27 19:20:02.219  adbd  ADB wifi device disconnected
$ settings get global adb_wifi_enabled   -> 0
$ getprop service.adb.tcp.port           -> 5555
$ ss -ltn | grep 5555                    -> LISTEN *:5555
```

Over the phone hotspot, with no client link:

```text
$ adb connect 10.231.103.217:5555
connected to 10.231.103.217:5555
$ adb -s 10.231.103.217:5555 shell id
uid=2000(shell) ...
$ settings get global adb_wifi_enabled   -> 0
$ ip -4 -o addr show ap0                 -> 10.231.103.217/24
```

The TLS path also accepts the existing laptop key without a new pairing:

```text
$ adb connect 192.168.77.232:34019
connected to 192.168.77.232:34019
```

The full curated transcript is `tests/fixtures/short-bootstrap.log`.

## After a reboot: measured timings

A second run on the same phone (19:54–20:04) measured when the port comes back.
Host and phone clocks were verified identical, and no host adb command was sent.

| Time | Event |
|---|---|
| 19:54:30 | `adb reboot` |
| 19:55:24 | `sys.boot_completed=1`; toggle `0`, port empty, no `ap0`, Keeper pid 2742 |
| 19:56:24 | Keeper: `pin.no_result ... exit=1 err=-1` — no Wi-Fi, nothing to pin |
| 19:59:37 | laptop AP up; the phone auto-joins its trusted `PhoneLink` |
| 19:59:46.7 | associated (BSSID `f4:d1:08:3e:7f:0b`, 5745 MHz); Keeper writes `adb_wifi_enabled=1` |
| 19:59:47.5 | toggle reads `1`; adbd starts its TLS server and advertises `_adb-tls-connect._tcp` |
| 20:00:02.2 | Keeper's paired TLS client connects (`adbd_wifi_secure_connect: connected host-41`) and sends `tcpip:5555` |
| 20:00:02.4 | adbd restarts: `TlsServer running on port 40733`, legacy `5555` listening |
| 20:00:04 | `service.adb.tcp.port=5555`, one LISTEN socket |
| 20:00:12.0 | Keeper: `pin.state listening on 5555` |

Two conclusions:

- **Without a STA, `5555` never appears.** Four minutes of two-second samples,
  plus the earlier cold boot, showed no port, no toggle and no TLS server.
- **With a trusted STA the phone rebuilds it in about 17 s on its own.** `adb
  usb` is not needed; the pin itself restarts adbd.

Keeper is the writer of the toggle, not just a reader: with the STA up,
`settings put global adb_wifi_enabled 0` is undone in under a second
(`WifiDebug: enabled wireless debugging`). Removing the STA behaves as before:
`AdbDebuggingManager: Network disconnected. Disabling adbwifi.`, the toggle goes
to `0` after ~5 s, Keeper logs `ensure: no Wi-Fi, skipping` (remembered SSID
`PhoneLink`), and `5555` keeps listening.

Curated transcript: `tests/fixtures/cold-boot-5555-timeline.log`.

## The hotspot address is not stable

The hotspot subnet changed between boots. On the earlier boot it was
`10.31.180.0/24`; after the reboot it was `10.231.103.0/24`. The laptop got
`10.231.103.149`, the phone `10.231.103.217`. A hotspot off/on in the same boot
kept the same subnet, so read the address every boot:

```sh
ip=$(adb shell ip -4 -o addr show ap0 | awk '{print $4}' | cut -d/ -f1)
adb connect "$ip:5555"
```

Do not hardcode `10.31.180.89` or `192.168.49.1`.

## Boundaries

- **One boot for `5555`.** After a reboot `service.adb.tcp.port` is empty,
  `5555` is gone, `adb_wifi_enabled` is `0`, and the hotspot is off. Nothing
  recreates the port until a **trusted Wi-Fi client network** appears; then
  Keeper re-arms the toggle and re-pins the port in about 17 s, with no host
  command (see above). Cold-boot automation therefore reduces to "get a STA
  up", not "run adb". Details: `docs/hotspot.md`,
  `tests/fixtures/cold-boot.log`.
- **The bootstrap needs a real STA once.** USB does not satisfy the
  `AdbDebuggingManager` gate. With USB attached and no STA,
  `settings put global adb_wifi_enabled 1` still reads back `0`:
  `Not connected to any wireless network. Not enabling adbwifi.`
- **Termux needs an existing listener.** It can connect to `127.0.0.1:5555` and
  re-send `tcpip:5555` once Keeper has pinned the port, but it cannot create the
  first listener from a cold boot. A closed port is refused; USB does not give
  Termux an adb transport. See `docs/termux.md`.
- **The P2P group is not part of this bootstrap.** The hotspot and P2P cannot be
  up together on this HAL (`Failed to setup interface for P2P`), and the default
  GO ran on 6 GHz, invisible to the laptop's 8265. The hotspot is the simpler
  phone-hosted link.
- **Secrets.** This page deliberately omits the Wi-Fi passphrase, the P2P
  passphrase and any pairing PIN. The fixtures redact them.

## Reproduce

Keep USB adb attached and keep a rollback AP profile ready. The laptop AP is
only needed for step 1.

```sh
# 1. Temporary STA. The phone auto-joins the saved network; Keeper enables the
#    toggle. The explicit connect is only needed if it does not auto-join.
nmcli connection up phonelink                 # laptop: 5 GHz AP, 192.168.77.1
adb shell settings get global adb_wifi_enabled  # expect 1 within ~10 s
# adb shell cmd wifi connect-network "PhoneLink" wpa2 "$PSK"   # only if needed

# 2. Wait for the phone to pin 5555 over its own TLS key (no host command).
adb shell 'for i in $(seq 1 40); do [ "$(getprop service.adb.tcp.port)" = 5555 ] && break; sleep 1; done'
adb shell getprop service.adb.tcp.port          # expect 5555

# 3. Remove the STA and turn on the phone hotspot.
nmcli connection down phonelink
adb shell am start -a android.settings.TETHER_SETTINGS
# toggle Personal hotspot on; then read the new address

# 4. Connect over the phone-hosted link.
ip=$(adb shell ip -4 -o addr show ap0 | awk '{print $4}' | cut -d/ -f1)
adb connect "$ip:5555"
adb -s "$ip:5555" shell 'getprop service.adb.tcp.port; settings get global adb_wifi_enabled'
```

The pin survives hotspot off/on and STA loss. It does not survive a reboot, but
it is rebuilt about 17 s after the phone rejoins a trusted Wi-Fi network.

## What this changes

- The one-time requirement is now explicit: a **temporary STA**, not a
  permanent AP. After the pin, the phone hosts the link and the laptop is just
  the adb client.
- Keeper is doing exactly the job it was designed for: setting the toggle and
  pinning `5555` over its own TLS key. A "Keeper Lite" that assumes a phone-hosted
  link is only needed for the cold boot, not for this step.
- The remaining automation gap is the cold boot: `5555`, the hotspot and the
  STA are all gone, so a reboot still needs a bootstrap. See
  `ROADMAP.md` and issues #3, #12, #14, #15.
