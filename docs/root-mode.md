# Root mode on Android

By default the app builds a `VpnService` tunnel. That is a system VPN: the key icon shows up, the
network carries the `VPN` transport, and any app that asks Android about it can see it. Root mode
does the same job without registering anything with the system, so there is nothing to ask about.

```
app traffic -> ip rule (uid, fwmark) -> table 4710 -> smugly0 -> hevroot -> socks5 127.0.0.1:<engine>
                                                                              engine -> tunnel
```

Settings -> Advanced -> Root -> "Without a system VPN". The switch asks for `su` once; if the
grant is refused it flips back off.

## What runs where

| Piece | Runs as | Job |
|---|---|---|
| Engine (Slipstream, S3FU, CDNFU, Xray) | the app | exposes the SOCKS5 listener, unchanged |
| `libhevroot.so` | root | hev-socks5-tunnel as a program: opens `smugly0`, turns IP packets into SOCKS5 |
| `rootd.sh` | root | builds the rules, restarts hev if it dies, tears everything down |
| `RootTunnel` | the app | writes the script and the hev config, launches and stops the daemon, reads stats |

`libhevroot.so` is not a library. It is the same hev sources as the in-process tunnel, built by
`buildHevRoot` (`app/src/main/cpp/root/Android.mk`) and shipped under the `.so` name so that the
packager extracts it next to the other native libraries, where root can execute it.

## Routing

The whole thing is policy routing, no packet marking, so Android's own marks are left alone.

- **Tunnelled uids** get `ip rule add pref 7000 fwmark 0x0/0x20000 iif lo uidrange A-B lookup 4710`.
  This is the shape the platform uses for its own VPN: `iif lo` limits it to locally generated
  traffic, and `0x20000` is the "protected from VPN" bit, so sockets that Android itself exempts
  from a VPN stay exempt here too.
- **Our own uid is never in a range.** Every engine dials its server from the app process, so
  routing it back into the tunnel would be a loop. Per-app lists (`PerAppFilter`) become uid
  ranges: allow list = the picked uids, deny list = everything except the picked uids and us.
- **Table 4710** holds the same routes the `VpnService` builder gets (`TunPlan.ipv4Routes`), so
  "keep the local network out of the tunnel" behaves identically. With IPv6 off the table carries
  an `unreachable ::/0` so v6 fails fast instead of leaking.
- **Return path.** `iif smugly0 lookup 97` (local_network) lets replies reach tethered clients,
  `iif smugly0 lookup 4710` keeps reverse-path filtering satisfied.

## DNS

Android resolves names in netd, and netd sends every query as uid 0, whichever app asked
(measured on Android 16 with per-uid counters). Its cache is per network, not per app. So a
uid-based redirect cannot tell apps apart: before this was handled, the fake-IP answers meant for
tunnelled apps also reached our own process and the excluded apps, which then dialled
`198.18.x.x` directly and failed.

The split is done the way the platform does it for a VPN, only without telling
ConnectivityService:

- `ndc network create 64000 vpn 0` makes a hidden bypassable VPN network in netd, `smugly0` is
  added to it with a single route to the tunnel resolver, and the tunnelled uid ranges become its
  users (capped at 2^31-1, netd's limit).
- `service call dnsresolver 8` and `3` create its resolver cache and give it one server: hev's
  fake-IP DNS (`10.255.0.1`) for S3FU and CDNFU, the first IPv4 tunnel resolver (default
  `1.1.1.1`) for Slipstream and Xray. The second call writes a `ResolverParamsParcel` by hand up to
  `tlsServers`; the size header lets the stable-AIDL reader default the rest.
- netd now resolves tunnelled uids on network 64000 (queries carry its mark `0xfa00`) and
  everyone else on the real default network with the carrier's servers and a separate cache.
  Every other network's cache is flushed once at setup to drop answers from before.
- `SMUGLY_DNS` (`nat OUTPUT`) skips uid 0 and still redirects port 53 sent by tunnelled apps
  themselves, for apps with their own resolver.

ConnectivityService never learns about network 64000, so apps see no VPN. Teardown runs
`ndc network destroy` and `dnsresolver 9`. If any step fails, `dns split=0` goes to `rootd.log`
and uid 0 is redirected as before: the tunnel works, with the old leak.

`to <resolver>/32 uidrange … lookup 4710` still routes redirected packets into the tunnel. It
carries the tunnelled ranges plus uid 0, never our own uid: a Slipstream resolver or an Xray direct
dial to the same address would otherwise loop back into the tunnel.

Limits: the kernel has no `ip6tables` nat here, so an app's own DNS to an IPv6 resolver is not
redirected. Queries that go through netd are not affected.

## Local proxy guard

Every engine listens on `127.0.0.1` (SOCKS5, plus the Slipstream port and any inbound a profile
adds). Any app can scan loopback, find the port and learn that a proxy runs here, or use it to
reach the exit address. Hiding the VPN network is pointless while that stays open, so the daemon
keeps a filter chain `SMUGLY_GUARD`, hooked at the top of `OUTPUT -o lo` for IPv4 and IPv6:

- uid 0 (hevroot) and our own uid pass;
- everyone else gets a TCP reset on each port the app is listening on.

The port list is read from `/proc/net/tcp{,6}` (state `0A`, our uid) on setup and on every tick,
so a listener the app opens later is covered within one tick. The chain is rebuilt only when the
list changes. UDP is not filtered: a SOCKS UDP relay only exists for an association that already
passed the guarded TCP control connection.

The "Protect local SOCKS" login and password now apply to every engine (Slipstream bridge, S3FU,
CDNFU and the Xray SOCKS inbound), in both modes; hev, the cabinet proxy and the subscription and
update fetchers present them. In root mode the Xray HTTP inbound is not opened at all, because no
system proxy is published there.

## Lifecycle and failure behaviour

- **Fail-closed while running.** `smugly0` is a persistent tun (`ip tuntap add`), so rules and
  routes survive a hev crash and traffic is dropped, not leaked. The daemon restarts hev, and gives
  up (tearing down, so traffic flows directly again) after 20 quick failures.
- **Dead man's switch.** The app touches `files/root/alive` every two seconds. If the daemon sees
  it unchanged for eight ticks it tears everything down. Counting ticks rather than wall-clock
  time keeps a Doze sleep from looking like a dead app. The app side measures the daemon's
  liveness in `uptimeMillis` for the same reason: after a suspend the wall clock has jumped, and
  the first diagnostics tick used to see a "dead" daemon and stop the tunnel.
- **Stop waits for the daemon.** The daemon sleeps with `sleep 2 & wait`, so `TERM` runs its trap
  at once, and `stop` waits for it to exit before its own teardown. Otherwise the trap could fire
  after `stop` had returned and tear down the next session.
- **Process checks read `/proc/<pid>/cmdline` with `grep -qa`.** Toybox `tr` never returns when
  its input is the cmdline of a process that has already exited, which left `rootd.sh stop` and
  a `tr` spinning for an hour on a test phone.
- **Teardown is idempotent**: rules by priority, `flush` the table, remove the chain, stop hev only
  if `/proc/<pid>/cmdline` is ours, delete the device. The same function runs before every start.
- **Boot.** "Connect when the device starts" works without the VPN consent step in this mode.
- **Hotspot.** The "Share the tunnel over hotspot" switch adds `iif <tether iface> lookup 4710`
  for the usual tether interface names, and a `SMUGLY_FWD` chain at the top of `FORWARD` that
  accepts traffic into and out of the tunnel interface: Android's `tetherctrl_FORWARD` ends in
  `-j DROP` for everything but the upstream it picked itself. With the normal `VpnService` the
  same chain is built for the system VPN interface.

## Verifying on a device

```bash
adb shell su -c 'ip rule | grep -E "^(699|700)"; ip route show table 4710; iptables -t nat -S SMUGLY_DNS'
adb shell su -c 'cat /data/data/app.smugly/files/root/status /data/data/app.smugly/files/root/stats'
adb shell su -c 'ip route get 1.1.1.1 uid <app uid> mark 0 iif lo'
```

Connecting should leave no entry from `dumpsys connectivity | grep VPN` that belongs to Smugly.
