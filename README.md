# p2p-self-probe

A throwaway Android app that answers, with evidence, whether a phone can use
Wi-Fi Direct (P2P) to give itself a link:

1. **Can the phone discover or connect to itself over P2P?**
2. **Does a P2P group-owner role coexist with a Wi-Fi client link?**

The answer so far: self-P2P is impossible, a P2P GO coexists with the STA link,
and the Android Wireless debugging toggle still requires a real Wi-Fi client
network. The app is small on purpose; the evidence lives in `docs/` and
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
- The P2P GO + laptop-as-station topology is the next link to test: the laptop
  joins the `DIRECT-…` SSID and adb runs over fixed port `5555`.
- The hotspot path works for adb (`10.31.180.89:5555` measured), but
  `adb_wifi_enabled` is reverted by `AdbDebuggingManager` without a client
  network, and `5555` does not survive a reboot without root/USB/temporary STA.

Read more: [`docs/findings.md`](docs/findings.md),
[`docs/hotspot.md`](docs/hotspot.md),
[`docs/direct-links.md`](docs/direct-links.md),
[`docs/keeper.md`](docs/keeper.md),
[`docs/termux.md`](docs/termux.md), and [`ROADMAP.md`](ROADMAP.md).

## Why

The laptop-local AP keeps the phone in **client mode**, which is what the
Wireless debugging toggle needs. A phone-hosted link (hotspot or P2P) is
attractive because it would survive a laptop reboot and need no external AP.
The open question is whether the phone can provide both ends of that link or at
least run a P2P group without losing its client link. This app is the cheap
empirical check.

## What it does, in order

1. Registers for P2P broadcasts and logs the P2P state.
2. `requestDeviceInfo` → logs the local P2P address (masked on this ROM).
3. `discoverPeers` for 8 s → logs every peer and marks the local address if it
   appears.
4. `connect()` to the local P2P address (or the host-supplied real MAC) → logs
   success or the failure reason.
5. `cancelConnect`, then `createGroup()` → logs owner, SSID, passphrase, group
   interface, clients, and interface addresses.
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
