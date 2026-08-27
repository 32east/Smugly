package app.smugly.desktop

import app.smugly.PerAppMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the sing-box configuration that turns the local proxy into a real system-wide tunnel.
 *
 * Why a separate engine at all: a TUN adapter hands us raw IP packets, so something has to
 * terminate TCP/UDP on them and speak SOCKS5 to our own listener. A bare tun2socks would do that
 * much — but it could never do split tunnelling, because by the time a connection reaches our
 * proxy the original process is gone: the socket belongs to tun2socks, not to the app that sent
 * the packet. Only a stack that sees the packet's own source port can map it back to a PID, so
 * the per-app rules have to live in the same process as the TUN. That is what this config drives.
 *
 * ```
 *   every app  ->  Wintun ("Smugly")  ->  sing-box  --process rules--+--> direct (bypass)
 *                                                                    |
 *                                                                    +--> socks5 127.0.0.1:<engine>
 *                                                                          engine -> tunnel
 * ```
 *
 * Note where the SOCKS hand-off points: the **engine**, not [MixedProxyServer]. The local proxy
 * exists to serve the Windows proxy setting and only ever accepts CONNECT, which is fine for a
 * browser and useless for a TUN — a TUN carries UDP, and dropping it would take QUIC, voice chat
 * and games with it. In TUN mode the local proxy keeps running as a manual escape hatch, but no
 * tunnelled packet goes through it.
 *
 * Pure on purpose: the whole file is one string, so the rules that decide who leaves through the
 * tunnel are unit-testable without a TUN, an administrator token or a network.
 */
object TunConfig {
    /** Adapter name the user sees in `ipconfig` / Network Connections. */
    const val INTERFACE_NAME = "Smugly"

    /**
     * A /30 is all a point-to-point TUN needs, and picking an obscure range matters: the addresses
     * become routes on the host, so anything commonplace (10.0.0.x, 192.168.1.x) risks colliding
     * with the user's own LAN and blackholing it.
     */
    private const val TUN_ADDRESS_V4 = "172.19.0.1/30"
    private const val TUN_ADDRESS_V6 = "fdfe:dcba:9876::1/126"

    /**
     * Larger than the 1500 the physical link uses. The TUN is not a wire — sing-box reassembles
     * and re-emits through the SOCKS connection, so a big MTU only means fewer, larger reads.
     */
    private const val MTU = 9000

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"

    /**
     * Processes that must never be routed into the tunnel, whatever the user picked.
     *
     * Every one of them is part of the tunnel: our own UI process opens the engine's sockets,
     * the engines dial the real server, and sing-box itself carries what the TUN captured. Route
     * any of them back through the TUN and the packet re-enters the stack it just came out of.
     */
    val ALWAYS_DIRECT: List<String> = listOf(
        "sing-box.exe",
        "slipstream.exe",
        "slipstream-client.exe",
        "s3fu.exe",
        "cdnfu.exe",
        "xray.exe"
    )

    /**
     * @param socksPort the engine's own SOCKS5 listener — **not** [MixedProxyServer], which
     *        accepts CONNECT only. A TUN carries UDP (QUIC, voice, games) and that needs UDP
     *        ASSOCIATE, which the engines speak and the system-proxy listener deliberately does
     *        not.
     * @param clashApiPort loopback port for sing-box's control API, or 0 to leave it off. It is
     *        the only way to know how many bytes the tunnel actually moved once the local proxy
     *        is no longer in the path.
     * @param clashApiSecret bearer token for that API
     * @param mode split tunnelling mode; see [PerAppMode]
     * @param processes executables the mode applies to, e.g. `chrome.exe`
     * @param ownProcesses this app's own executable(s) — `Smugly5.exe` when packaged, the JVM
     *        launcher in a dev run. Added to [ALWAYS_DIRECT]; getting it wrong is a routing loop,
     *        so it is passed in rather than guessed here.
     */
    fun build(
        socksPort: Int,
        mode: PerAppMode,
        processes: Set<String>,
        ownProcesses: List<String>,
        logLevel: String = "warn",
        clashApiPort: Int = 0,
        clashApiSecret: String = ""
    ): String {
        val bypass = (ALWAYS_DIRECT + ownProcesses)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        val picked = normalizeProcesses(processes)
        // An empty allow list would route nothing through the tunnel while the UI says
        // "connected" — the same trap PerAppFilter guards on Android, and the same answer:
        // fall back to tunnelling everything rather than silently protecting nothing.
        val effectiveMode = if (mode == PerAppMode.ALLOWED && picked.isEmpty()) PerAppMode.OFF else mode
        // ALLOWED inverts the default: everything is direct except what the user picked.
        val finalOutbound = if (effectiveMode == PerAppMode.ALLOWED) TAG_DIRECT else TAG_PROXY

        val routeRules = JSONArray().apply {
            // Sniffing is what lets a rule match a domain at all; since 1.11 it is an action
            // rather than an inbound flag.
            put(JSONObject().put("action", "sniff"))
            // DNS is answered by the dns block below instead of being forwarded as UDP/53.
            put(
                JSONObject()
                    .put("protocol", "dns")
                    .put("action", "hijack-dns")
            )
            put(
                JSONObject()
                    .put("process_name", JSONArray(bypass))
                    .put("outbound", TAG_DIRECT)
            )
            // LAN, loopback and link-local stay on the LAN. Without this a printer, a NAS or the
            // router's own web UI would be answered from the other side of the tunnel.
            put(JSONObject().put("ip_is_private", true).put("outbound", TAG_DIRECT))
            if (picked.isNotEmpty() && effectiveMode != PerAppMode.OFF) {
                put(
                    JSONObject()
                        .put("process_name", JSONArray(picked))
                        .put(
                            "outbound",
                            if (effectiveMode == PerAppMode.ALLOWED) TAG_PROXY else TAG_DIRECT
                        )
                )
            }
        }

        // DNS has to follow the same split, or a bypassed app still leaks its lookups through
        // the tunnel (and a tunnelled app resolves against the local network it is hiding from).
        val dnsRules = JSONArray().apply {
            put(
                JSONObject()
                    .put("process_name", JSONArray(bypass))
                    .put("server", "dns-local")
            )
            if (picked.isNotEmpty() && effectiveMode != PerAppMode.OFF) {
                put(
                    JSONObject()
                        .put("process_name", JSONArray(picked))
                        .put(
                            "server",
                            if (effectiveMode == PerAppMode.ALLOWED) "dns-remote" else "dns-local"
                        )
                )
            }
        }

        val config = JSONObject()
            .put("log", JSONObject().put("level", logLevel).put("timestamp", true))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("tag", "dns-remote")
                                    .put("type", "tcp")
                                    .put("server", "1.1.1.1")
                                    .put("detour", TAG_PROXY)
                            )
                            .put(
                                JSONObject()
                                    .put("tag", "dns-local")
                                    .put("type", "local")
                                    .put("detour", TAG_DIRECT)
                            )
                    )
                    .put("rules", dnsRules)
                    .put("final", if (effectiveMode == PerAppMode.ALLOWED) "dns-local" else "dns-remote")
                    // The tunnel carries IPv4; handing an app a AAAA it cannot reach is a
                    // multi-second stall per connection before it falls back.
                    .put("strategy", "ipv4_only")
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("interface_name", INTERFACE_NAME)
                        .put("address", JSONArray().put(TUN_ADDRESS_V4).put(TUN_ADDRESS_V6))
                        .put("mtu", MTU)
                        // auto_route writes the default routes; strict_route additionally forces
                        // *everything* through them with firewall rules, which on Windows breaks
                        // LAN discovery and hotspot sharing for little gain.
                        .put("auto_route", true)
                        .put("strict_route", false)
                        // gVisor's userspace stack rather than the host's: slower per packet, but
                        // it does not depend on the OS accepting foreign source addresses, which
                        // is what makes the system stack fragile on Windows.
                        .put("stack", "gvisor")
                )
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "socks")
                            .put("tag", TAG_PROXY)
                            .put("server", "127.0.0.1")
                            .put("server_port", socksPort)
                            .put("version", "5")
                    )
                    .put(JSONObject().put("type", TAG_DIRECT).put("tag", TAG_DIRECT))
            )
            .put(
                "route",
                JSONObject()
                    // Without this sing-box cannot tell which adapter "direct" should leave by
                    // once its own routes own the default.
                    .put("auto_detect_interface", true)
                    // Where a *dialled* domain is resolved — a sniffed host on the direct path.
                    // Locally, since that traffic is bypassing the tunnel by definition. Required
                    // from 1.14; without it 1.12 only warns, and the default it falls back to is
                    // not something to leave to chance.
                    .put(
                        "default_domain_resolver",
                        JSONObject().put("server", "dns-local").put("strategy", "ipv4_only")
                    )
                    .put("rules", routeRules)
                    .put("final", finalOutbound)
            )
        if (clashApiPort in 1..65535) {
            config.put(
                "experimental",
                JSONObject().put(
                    "clash_api",
                    JSONObject()
                        // Loopback and token-guarded: the API can rewrite routing at runtime, so
                        // it must not be reachable from the LAN or by another local process that
                        // merely guessed the port.
                        .put("external_controller", "127.0.0.1:$clashApiPort")
                        .put("secret", clashApiSecret)
                )
            )
        }
        return config.toString(2)
    }

    /**
     * Executable names as sing-box matches them: base name, lower-cased, `.exe` implied.
     *
     * The picker stores whatever Windows reported, and a user editing the list by hand will type
     * `chrome` as readily as `C:\...\chrome.exe`; all three have to mean the same rule.
     */
    fun normalizeProcesses(processes: Collection<String>): List<String> =
        processes.asSequence()
            .map { it.trim().trimEnd('"').trimStart('"') }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('\\').substringAfterLast('/') }
            .map { it.lowercase() }
            .map { if (it.endsWith(".exe")) it else "$it.exe" }
            .distinct()
            .sorted()
            .toList()
}
