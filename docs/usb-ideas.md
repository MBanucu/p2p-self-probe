# USB ideas and the no-root boundary

Measured on 2026-11-27. This page records what USB can do for adb, and why the
"USB to itself / virtual USB port" idea is out of scope without root.

## What works now: USB is already a TCP transport

The phone enumerates as `rndis,adb`, with `rndis0` = `10.19.134.25/24` and the
laptop's `enp55s0u2` on the same link. Because `adbd` binds the wildcard
address, this works with no Wi-Fi at all:

```sh
adb connect 10.19.134.25:5555
adb -s 10.19.134.25:5555 shell echo ok
# IPv6 link-local also works:
adb connect '[fe80::9c9c:eff:fe9a:3d28%enp55s0u2]:5555'
```

`avahi-browse -rt _adb._tcp` resolves `Android.local:5555`, so the per-boot
IPv4 churn can be avoided. The TLS listener is also wildcard-bound and accepts
the existing key over the USB net when Wireless debugging is on.

The catch is the boot composition: `persist.sys.usb.config=adb`, so `rndis0` is
not guaranteed at boot. Restoring it is a host-side step:

```sh
adb tcpip 5555
adb shell svc usb setFunctions rndis,adb
adb connect <phone-rndis-ip>:5555
```

This is a cable, so it is the recovery path, not the no-external-hardware goal.

## Why "USB to itself" does not work

- The phone has one UDC (`11201000.usb0`) and one USB-C port. DRP means one
  role at a time; it cannot be host and device simultaneously.
- `dummy_hcd`, `raw-gadget`, `gadgetfs` and `usbip` are not enabled in the
  kernel; configfs is root-only. A phone-side virtual host controller would
  need a custom kernel/module and root.
- Even a successful in-phone loopback would not give the laptop a route to the
  phone, so it would not solve the actual problem.
- There is no Android behavior that enables legacy `5555` merely because USB is
  attached. `service.adb.tcp.port` is set by `adb tcpip` (a host-side adbd
  service request), not by the USB gadget state.
- `adb shell setprop service.adb.tcp.port 5555` and
  `setprop persist.adb.tcp.port ...` both fail as uid 2000. Root would fix it;
  root is out of scope by decision.

## No-root conclusion

USB can carry adb over TCP today, and it is useful for recovery and for the
one-time pin. It cannot be the automatic phone-hosted link after a cold boot
without a host on the other end, and it cannot make the Wireless debugging
toggle accept a hotspot/P2P interface. See issues #4, #5, #12, #14 and #15.

The root-only paths (USB loopback, USB Wi-Fi dongle, persistent property) are
closed in #6, #7 and #16 and are not part of the roadmap.
