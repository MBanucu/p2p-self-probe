# p2p-self-probe

A throwaway Android app that answers, with evidence, whether a phone can use
Wi-Fi Direct (P2P) to give itself a link:

1. **Can the phone discover or connect to itself over P2P?**
2. **Does a P2P group-owner role coexist with a Wi-Fi client link?**

The answer so far: self-P2P is impossible, a P2P GO coexists with a **client**
STA link but not with the hotspot, and the Android Wireless debugging toggle
still requires a real Wi-Fi client network. A short-lived STA can bootstrap
legacy `5555`, after which the phone-hosted hotspot carries adb without any AP.
The app is small on purpose; the evidence lives in `docs/` and
`tests/fixtures/`.

## Findings

- The framework masks the local P2P address as `02:00:00:00:00:00`; the host
  must pass the real `p2p0` MAC (readable from `ip -o link`, not from sysfs on
  this build).
- Discovery returns 0 peers and never lists the phone itself.
- `connect(self)` with the real MAC fails `ERROR`; `connect(own group)` fails
  `ERROR`; `createGroup()` succeeds as GO with 0 clients.
- `STA + AP`/`STA + P2P` concurrency is real for an **external** peer; the
  phone cannot be a client of its own AP/GO (one radio, half-duplex).
- The hotspot and a P2P group are mutually exclusive on this HAL
  (`Failed to setup interface for P2P`). The default GO ran on **6 GHz**, which
  the laptop's 8265 cannot see, but forcing 5 GHz works: the GO came up on
  **5180 MHz**, the laptop joined `DIRECT-…` as a plain station, got
  `192.168.49.149` and `adb connect 192.168.49.1:5555` ran with the toggle off.
  That is a phone-hosted adb link with no AP, no hotspot and no cable.
- The hotspot path works for adb, but the subnet is randomized per boot
  (`10.31.180.89`, then `10.231.103.217`). Read `ap0`; do not hardcode it.
- **Short bootstrap works:** a temporary STA lets Keeper set
  `adb_wifi_enabled=1` and pin `5555` over TLS; the STA can then go away. The
  toggle reverts to `0`, but `5555` keeps listening and the hotspot carries
  `adb connect <ap0>:5555`. A reboot clears it, but the phone rebuilds it in
  ~17 s on its own once a trusted Wi-Fi network is back (measured; no host adb
  command).
- USB is not a shortcut: with the cable attached and no STA the toggle still
  reverts, Termux sees no adb device, and faking the USB attach is root-only.

Read more: [`docs/short-bootstrap.md`](docs/short-bootstrap.md),
[`docs/findings.md`](docs/findings.md),
[`docs/hotspot.md`](docs/hotspot.md),
[`docs/direct-links.md`](docs/direct-links.md),
[`docs/keeper.md`](docs/keeper.md),
[`docs/termux.md`](docs/termux.md),
[`docs/usb-ideas.md`](docs/usb-ideas.md), and [`ROADMAP.md`](ROADMAP.md).

## Why

The laptop-local AP keeps the phone in **client mode**, which is what the
Wireless debugging toggle needs. A phone-hosted link (hotspot or P2P) is
attractive because it would survive a laptop reboot and need no external AP.
The question was whether the phone can provide both ends of that link. It
cannot, but it does not need to: a temporary STA bootstraps `5555`, and the
phone hotspot then carries adb with no AP.

## Bootstrap 5555 (one temporary STA)

The external AP is needed only until Keeper pins the port. Full detail and
evidence: [`docs/short-bootstrap.md`](docs/short-bootstrap.md).

```sh
# 1. Temporary STA; the phone auto-joins and Keeper sets adb_wifi_enabled=1.
nmcli connection up phonelink            # laptop 5 GHz AP, 192.168.77.1
# adb shell cmd wifi connect-network "PhoneLink" wpa2 "$PSK"   # only if needed

# 2. Wait for the phone to pin 5555 over its own TLS key (no host command).
adb shell 'for i in $(seq 1 40); do [ "$(getprop service.adb.tcp.port)" = 5555 ] && break; sleep 1; done'
adb shell getprop service.adb.tcp.port   # 5555

# 3. Remove the STA and turn on the phone hotspot.
nmcli connection down phonelink
# toggle Personal hotspot on

# 4. Read the randomized address; adb over the phone-hosted link.
ip=$(adb shell ip -4 -o addr show ap0 | awk '{print $4}' | cut -d/ -f1)
adb connect "$ip:5555"
```

`adb_wifi_enabled` goes back to `0` when the STA disappears, but the legacy
listener stays. A reboot clears `5555` and turns the hotspot off; bringing the
STA back lets Keeper rebuild `5555` by itself in about 17 s, then the hotspot
can take over again.

## What it does, in order

1. Registers for P2P broadcasts and logs the P2P state.
2. `requestDeviceInfo` → logs the local P2P address (masked on this ROM).
3. `discoverPeers` for 8 s → logs every peer and marks the local address if it
   appears.
4. `connect()` to the local P2P address (or the host-supplied real MAC) → logs
   success or the failure reason.
5. `cancelConnect`, then `createGroup()` → logs owner, SSID, passphrase, group
   interface, clients, and interface addresses. Extras:
   `--es address <real p2p MAC>`, `--ei hold <ms>`, and for a forced-frequency
   group `--ei freq 5180 --es ssid DIRECT-... --es psk <8-63 chars>` (the
   builder rejects a bare frequency).
6. Optionally holds the group (`--ei hold <ms>`) so a host can inspect it.
7. `connect()` to the group owner's address (the phone itself) → logs the
   result.
8. `removeGroup()` → logs that the group is gone and whether the STA link is
   still connected.

Everything goes to logcat with tag `P2PSelfTest` and to
`Android/data/org.phonelink.p2pprobe/files/result.txt`. The probe never
disconnects Wi-Fi and removes the group it creates.

## Build

No Gradle, no AndroidX; the flake supplies the SDK and JDK:

```sh
nix develop --command ./build.sh
# build/p2p-self-probe.apk
```

or hermetically:

```sh
nix build .#   # result/p2p-self-probe.apk
```

Tests (plain JDK, no JUnit):

```sh
nix develop --command ./tests/run-tests.sh
# or: nix flake check
```

## Run

```sh
adb install -r build/p2p-self-probe.apk
adb shell pm grant org.phonelink.p2pprobe android.permission.NEARBY_WIFI_DEVICES

# The framework masks its own P2P address, so pass the real p2p0 MAC.
mac=$(adb shell ip -o link | awk '/: p2p0:/{for(i=1;i<=NF;i++) if($i=="link/ether") print $(i+1)}')
adb logcat -c
adb shell am start -n org.phonelink.p2pprobe/.MainActivity --es address "$mac"
# Optional: keep the group for 10 s so the host can inspect it.
adb shell am start -n org.phonelink.p2pprobe/.MainActivity --es address "$mac" --ei hold 10000
adb logcat -d -s P2PSelfTest
```

The phone must be connected to a Wi-Fi network before step 5 if you want the
coexistence answer. `WifiInfo` SSID is redacted without a location permission;
the probe uses `ConnectivityManager` + `TRANSPORT_WIFI` for the STA signal.

## Cost

Creating a P2P group can make the Wi-Fi driver switch channel and briefly drop
the STA link. Run this with USB adb attached so a rejoin watchdog can restore
the link, and expect a few seconds of interruption. The app removes its group at
the end. Do not run it while the phone is your only uplink and you have no
out-of-band access.

## Layout

- `src/org/phonelink/p2pprobe/MainActivity.java` — the probe.
- `src/org/phonelink/p2pprobe/ProbeLogic.java` — mask/self-match/STA/verdict,
  testable without Android.
- `tests/` — `ProbeLogic` tests and the run-3 fixture.
- `docs/` — findings, hotspot, direct links, Keeper, Termux, USB.
- `ROADMAP.md` — tracks, decisions and open issues.
- `build.sh` — aapt2 + javac + d8 + zipalign + apksigner, no Gradle.
- `default.nix`, `flake.nix` — the SDK shell, APK build and test checks.

## License

MIT. See `LICENSE`.
