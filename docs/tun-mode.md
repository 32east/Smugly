# TUN mode on Windows

Until now the desktop client was a local proxy plus the Windows system-proxy setting. That only
ever reached WinINET-aware software: games, most Electron apps and anything on raw sockets ignore
the setting and leave in the clear, which is a leak the user cannot see and cannot fix. TUN mode
puts a virtual adapter in front of the whole machine instead, so there is no opt-out.

```
every app  ->  Wintun adapter "Smugly"  ->  sing-box  --process rules--+--> direct (bypass)
                                                                       |
                                                                       +--> socks5 127.0.0.1:<engine>
                                                                             engine -> tunnel
```

## Why a second engine, and why this one

A TUN hands over raw IP packets, so something has to terminate TCP/UDP on them and speak SOCKS5.
A bare `tun2socks` does that much — but it could never do split tunnelling. By the time a
connection reaches our proxy the original process is gone: the socket belongs to tun2socks, not to
the app that sent the packet. Only a stack that still sees the packet's own source port can map it
back to a PID, so the per-app rules have to live in the same process as the TUN. That rules out
"tun2socks + rules in our code" and leaves an engine that has both, which is what sing-box is here.

The hand-off points at the **engine's** SOCKS listener, not at `MixedProxyServer`. The local proxy
exists to serve the Windows proxy setting and accepts CONNECT only; a TUN carries UDP, and routing
through it would silently drop QUIC, voice chat and games. The local proxy keeps running in TUN
mode purely as a manual escape hatch — no tunnelled packet goes through it.

One consequence worth knowing: **UDP works only as well as the engine does.** Xray, s3fu and cdnfu
implement SOCKS5 UDP ASSOCIATE; the Slipstream DNS tunnel does not, so in TUN mode over Slipstream
TCP works and UDP flows fail.

## Prerequisites

Both go next to the other engines (`engines/`, or wherever `SMUGLY_ENGINE_DIR` points):

| File | Where it comes from | Note |
|---|---|---|
| `sing-box.exe` | sing-box releases, a build with the `with_gvisor` tag | verified against 1.12.12 |
| `wintun.dll` | wintun.net (WireGuard) | must sit **next to** `sing-box.exe` |

sing-box is GPLv3 — worth checking before shipping it inside a release rather than asking the user
to drop it in.

TUN mode also needs **administrator rights**: creating the adapter and rewriting the routing table
are privileged operations. `DesktopTun.preflight()` checks all three up front and returns one
sentence naming whichever is missing, so a failed connect never has to be guessed at.

## Split tunnelling

The same `PerAppMode` and the same picker screen as Android, with one difference in what an "app"
is: Windows rules match an **executable name** (`chrome.exe`), so the picker lists running
processes rather than installed packages. Deduplicated per executable, because Chrome's dozen
processes are one app as far as routing is concerned.

Three rules are non-negotiable and are unit-tested:

- **Our own process and every engine are always direct.** They *are* the tunnel; routing them into
  it is a loop. The bypass rule is emitted before any user rule, so picking Smugly itself cannot
  tunnel it.
- **An empty allow list falls back to tunnelling everything.** A TUN nothing is routed into looks
  connected while every packet leaves in the clear.
- **DNS follows the same split.** Otherwise a bypassed app still resolves through the tunnel, and a
  tunnelled app resolves against the network it is hiding from.

## Verifying a change

The config schema belongs to sing-box, so the only honest check is the engine's own:

```bash
./gradlew :shared:desktopTest --tests 'app.smugly.desktop.TunConfigTest'
```

That writes `off` / `allowed` / `disallowed` / `api` variants to
`$TMPDIR/smugly-tun-configs/`. Hand each to the binary:

```bash
sing-box check -c tun-off.json
```

Exit 0 with no output is the pass. A warning there is a schema deprecation and will become an
error in a later sing-box, so treat it as a failure.

Live checks that unit tests cannot cover, in order:

1. `ipconfig` shows the `Smugly` adapter with `172.19.0.1`.
2. An app that ignores the system proxy (a game, `curl.exe`) reports the tunnel's exit IP — this
   is the whole point of the mode and the one thing proxy mode could never do.
3. With `DISALLOWED` + that app picked, the same check reports the real IP while everything else
   still reports the tunnel's.
4. UDP survives: a voice call or `nslookup` over the tunnel.
5. Disconnect returns the routing table to normal — killing sing-box is the entire teardown, since
   Wintun adapters and their routes belong to the process that created them.
