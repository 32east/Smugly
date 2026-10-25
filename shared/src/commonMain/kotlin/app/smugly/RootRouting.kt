package app.smugly

enum class RootMode {
    OFF,
    TUN2SOCKS;

    val isActive: Boolean get() = this != OFF

    companion object {
        fun parse(raw: String?): RootMode =
            values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

data class UidRange(val first: Long, val last: Long) {
    val spec: String get() = "$first-$last"
}

object RootRouting {
    const val IFACE = "smugly0"
    const val TABLE = 4710
    const val PREF_RETURN = 6990
    const val PREF_LOCAL_RETURN = 6991
    const val PREF_TUNNEL = 7000
    const val DNS_CHAIN = "SMUGLY_DNS"
    const val FORWARD_CHAIN = "SMUGLY_FWD"
    const val GUARD_CHAIN = "SMUGLY_GUARD"
    const val NET_ID = 64000
    const val NETD_MAX_UID = 2147483647L
    const val TUN_ADDRESS_V4 = "10.255.0.2"
    const val TUN_ADDRESS_V6 = "fd00::2"
    const val LOCAL_NETWORK_TABLE = 97
    const val MAX_UID = 4294967294L
    const val PROTECTED_FROM_VPN_MASK = "0x0/0x20000"
    const val UNSELECTED_MASK = "0x0/0x30000"
    const val EXPLICIT_UNSET = "0x0/0x10000"
    const val STALE_TICKS = 8
    const val TICK_SECONDS = 2
    const val MAX_HEV_RESTARTS = 20

    val TETHER_IFACES: List<String> = listOf(
        "wlan1", "wlan2", "ap0", "swlan0", "softap0", "rndis0", "ncm0", "usb0", "bt-pan", "eth1"
    )

    data class Spec(
        val dir: String,
        val hevPath: String,
        val hevConfigPath: String,
        val tun: TunSettings,
        val uidRanges: List<UidRange>,
        val dnsTarget: String,
        val hotspot: Boolean,
        val dnsTcp: Boolean = true,
        val vpnIface: String? = null,
        val tetherIfaces: List<String> = TETHER_IFACES,
        val guardUid: Long? = null,
        val attachCommand: String? = null,
        val tunAddressV4: String = TUN_ADDRESS_V4,
        val tunAddressV6: String = TUN_ADDRESS_V6,
        val tunnelMask: String = PROTECTED_FROM_VPN_MASK,
        val mtu: Int? = null,
        val routeMtuV4: Int? = null
    )

    fun uidRanges(allowed: Collection<Long>, excluded: Collection<Long>): List<UidRange> {
        val skip = excluded.filter { it in 0..MAX_UID }.toSet()
        if (allowed.isNotEmpty()) {
            return merge(allowed.filter { it in 0..MAX_UID && it !in skip }.distinct().sorted())
        }
        val out = ArrayList<UidRange>()
        var next = 0L
        for (uid in skip.sorted()) {
            if (uid > next) out.add(UidRange(next, uid - 1))
            next = uid + 1
        }
        if (next <= MAX_UID) out.add(UidRange(next, MAX_UID))
        return out
    }

    fun dnsTarget(mapDnsAddress: String?, dnsServers: List<String>): String =
        mapDnsAddress
            ?: dnsServers.firstOrNull { TunPlan.parseIpv4(it) != null }
            ?: TunPlan.DEFAULT_DNS.first()

    fun setupCommands(spec: Spec): List<String> {
        val iface = spec.vpnIface ?: return tunnelSetupCommands(spec)
        val table = listOf(
            "VT=\$(grep ' $iface\$' /data/misc/net/rt_tables 2>/dev/null | head -1 | cut -d' ' -f1)",
            "[ -n \"\$VT\" ] || VT=\$((1000 + \$(cat /sys/class/net/$iface/ifindex 2>/dev/null || echo 0)))",
            "[ \"\$VT\" -gt 1000 ] || return 1"
        )
        return table + spec.tetherIfaces.flatMap {
            listOf(
                "ip rule add pref \$P iif $it lookup \$VT",
                "ip -6 rule add pref \$P iif $it lookup \$VT 2>/dev/null"
            )
        } + forwardCommands(iface)
    }

    private fun forwardCommands(iface: String): List<String> = listOf("iptables", "ip6tables").flatMap { tool ->
        val quiet = if (tool == "ip6tables") " 2>/dev/null" else ""
        listOf(
            "$tool -w -N \$FWD$quiet",
            "$tool -w -A \$FWD -o $iface -j ACCEPT$quiet",
            "$tool -w -A \$FWD -i $iface -j ACCEPT$quiet",
            "$tool -w -I FORWARD 1 -j \$FWD$quiet"
        )
    }

    private fun selectedSkip(spec: Spec, prefix: String): String =
        if (spec.tunnelMask == UNSELECTED_MASK) "$prefix $EXPLICIT_UNSET" else ""

    private fun withUidZero(ranges: List<UidRange>): List<UidRange> =
        if (ranges.any { 0L in it.first..it.last }) ranges else listOf(UidRange(0, 0)) + ranges

    private fun tunnelSetupCommands(spec: Spec): List<String> = buildList {
        val tun = spec.tun
        add("ip tuntap add dev \$IF mode tun || return 1")
        add("ip link set \$IF mtu ${spec.mtu ?: TunPlan.mtu(tun)} up || return 1")
        add("ip addr add ${spec.tunAddressV4}/32 dev \$IF 2>/dev/null")
        add("echo 0 > /proc/sys/net/ipv4/conf/\$IF/rp_filter 2>/dev/null")
        if (tun.ipv6) add("ip -6 addr add ${spec.tunAddressV6}/128 dev \$IF nodad 2>/dev/null")
        add(spec.attachCommand?.let { "$it || return 1" } ?: "start_hev || return 1")
        val v4Mtu = spec.routeMtuV4?.let { " mtu $it" }.orEmpty()
        TunPlan.ipv4Routes(tun).forEach {
            add("ip route replace $it dev \$IF table \$T$v4Mtu || return 1")
        }
        if (tun.ipv6) {
            TunPlan.ipv6Routes(tun).forEach {
                add("ip -6 route replace $it dev \$IF table \$T || return 1")
            }
        } else {
            add("ip -6 route replace unreachable ::/0 table \$T 2>/dev/null")
        }
        add("ip rule add pref $PREF_RETURN iif \$IF lookup $LOCAL_NETWORK_TABLE")
        add("ip rule add pref $PREF_LOCAL_RETURN iif \$IF lookup \$T")
        add("ip -6 rule add pref $PREF_RETURN iif \$IF lookup $LOCAL_NETWORK_TABLE 2>/dev/null")
        add("ip -6 rule add pref $PREF_LOCAL_RETURN iif \$IF lookup \$T 2>/dev/null")
        withUidZero(spec.uidRanges).forEach {
            add("ip rule add pref \$P to ${spec.dnsTarget}/32${selectedSkip(spec, " fwmark")} uidrange ${it.spec} lookup \$T")
        }
        spec.uidRanges.forEach {
            add("ip rule add pref \$P fwmark ${spec.tunnelMask} iif lo uidrange ${it.spec} lookup \$T || return 1")
            add("ip -6 rule add pref \$P fwmark ${spec.tunnelMask} iif lo uidrange ${it.spec} lookup \$T 2>/dev/null")
        }
        if (spec.hotspot) {
            val v6Action = if (spec.attachCommand != null) "unreachable" else "lookup \$T"
            spec.tetherIfaces.forEach {
                add("ip rule add pref \$P iif $it lookup \$T")
                add("ip -6 rule add pref \$P iif $it $v6Action 2>/dev/null")
            }
            addAll(forwardCommands("\$IF"))
        }
        add("SPLIT=")
        add("split_dns && SPLIT=1")
        add("echo \"dns split=\${SPLIT:-0}\"")
        add("iptables -w -t nat -N \$CHAIN")
        add("[ -n \"\$SPLIT\" ] && iptables -w -t nat -A \$CHAIN -m owner --uid-owner 0 -j RETURN")
        spec.uidRanges.forEach {
            add("iptables -w -t nat -A \$CHAIN -p udp --dport 53 -m owner --uid-owner ${it.spec}${selectedSkip(spec, " -m mark --mark")} -j DNAT --to-destination ${spec.dnsTarget}:53")
            if (spec.dnsTcp) {
                add("iptables -w -t nat -A \$CHAIN -p tcp --dport 53 -m owner --uid-owner ${it.spec}${selectedSkip(spec, " -m mark --mark")} -j DNAT --to-destination ${spec.dnsTarget}:53")
            }
        }
        add("iptables -w -t nat -I OUTPUT 1 -j \$CHAIN")
        if (spec.guardUid != null) {
            listOf("iptables", "ip6tables").forEach { tool ->
                val quiet = if (tool == "ip6tables") " 2>/dev/null" else ""
                add("$tool -w -N \$GUARD$quiet")
                add("$tool -w -I OUTPUT 1 -o lo -j \$GUARD$quiet")
            }
            add("GUARDED=-")
            add("guard_sync")
        }
    }

    fun netdRanges(ranges: List<UidRange>): List<UidRange> =
        ranges.filter { it.first <= NETD_MAX_UID }.map { UidRange(it.first, minOf(it.last, NETD_MAX_UID)) }

    fun resolverConfigCommand(netId: Int, servers: List<String>): String {
        fun string16(value: String) = 4 + ((value.length + 1) * 2 + 3) / 4 * 4
        val size = 4 + 7 * 4 + 4 + servers.sumOf { string16(it) } + 4 + string16("") + 4
        val args = buildList {
            add("i32 1")
            add("i32 $size")
            add("i32 $netId")
            listOf(1800, 25, 8, 64, 0, 0).forEach { add("i32 $it") }
            add("i32 ${servers.size}")
            servers.forEach { add("s16 $it") }
            add("i32 0")
            add("s16 \"\"")
            add("i32 0")
        }
        return "service call dnsresolver 3 " + args.joinToString(" ")
    }

    private fun splitDnsCommands(spec: Spec): List<String> {
        val ranges = netdRanges(spec.uidRanges)
        if (spec.vpnIface != null || ranges.isEmpty()) return listOf("return 1")
        val ok = "| grep -q '^200 ' || return 1"
        return buildList {
            add("ndc network create \$NETID vpn 0 $ok")
            add("service call dnsresolver 8 i32 \$NETID > /dev/null")
            add(resolverConfigCommand(NET_ID, listOf(spec.dnsTarget)) + " | grep -q 'Parcel(.00000000 ' || return 1")
            add("ndc network interface add \$NETID \$IF $ok")
            add("ndc network route add \$NETID \$IF ${spec.dnsTarget}/32 $ok")
            ranges.forEach { add("ndc network users add \$NETID ${it.spec} $ok") }
            add("for n in \$(dumpsys dnsresolver | grep -o 'NetId: [0-9]*' | cut -d' ' -f2); do")
            add("  [ \"\$n\" = \"\$NETID\" ] || service call dnsresolver 11 i32 \$n > /dev/null")
            add("done")
            add("return 0")
        }
    }

    fun daemonScript(spec: Spec): String {
        val setup = setupCommands(spec).joinToString("\n") { "  $it" }
        val split = splitDnsCommands(spec).joinToString("\n") { "  $it" }
        return """
            #!/system/bin/sh
            umask 022
            DIR=${quote(spec.dir)}
            HEV=${quote(spec.hevPath)}
            CONF=${quote(spec.hevConfigPath)}
            IF=$IFACE
            T=$TABLE
            P=$PREF_TUNNEL
            CHAIN=$DNS_CHAIN
            FWD=$FORWARD_CHAIN
            GUARD=$GUARD_CHAIN
            OWN=${spec.guardUid ?: ""}
            GUARDED=-
            NETID=$NET_ID
            STATUS="${'$'}DIR/status"
            STATS="${'$'}DIR/stats"
            PIDF="${'$'}DIR/rootd.pid"
            HEVPIDF="${'$'}DIR/hev.pid"
            HEVLOG="${'$'}DIR/hev.log"
            NOHEV=${if (spec.vpnIface != null || spec.attachCommand != null) "1" else ""}

            ours() {
              [ -n "${'$'}1" ] && grep -qa "${'$'}2" "/proc/${'$'}1/cmdline" 2>/dev/null
            }

            stop_daemon() {
              op=${'$'}(cat "${'$'}PIDF" 2>/dev/null)
              if ours "${'$'}op" rootd.sh; then
                kill "${'$'}op" 2>/dev/null
                n=0
                while [ ${'$'}n -lt 80 ] && ours "${'$'}op" rootd.sh; do
                  sleep 0.1
                  n=${'$'}((n+1))
                done
              fi
            }

            stop_hev() {
              hp=${'$'}(cat "${'$'}HEVPIDF" 2>/dev/null)
              if ours "${'$'}hp" libhevroot; then
                kill "${'$'}hp" 2>/dev/null
                n=0
                while [ ${'$'}n -lt 20 ] && ours "${'$'}hp" libhevroot; do
                  sleep 0.1
                  n=${'$'}((n+1))
                done
                ours "${'$'}hp" libhevroot && kill -9 "${'$'}hp" 2>/dev/null
              fi
              rm -f "${'$'}HEVPIDF"
            }

            start_hev() {
              stop_hev
              setsid "${'$'}HEV" "${'$'}CONF" > "${'$'}HEVLOG" 2>&1 < /dev/null &
              echo ${'$'}! > "${'$'}HEVPIDF"
              n=0
              while [ ${'$'}n -lt 50 ]; do
                ours "${'$'}(cat "${'$'}HEVPIDF")" libhevroot || return 1
                [ "${'$'}(cat /sys/class/net/${'$'}IF/carrier 2>/dev/null)" = 1 ] && return 0
                sleep 0.1
                n=${'$'}((n+1))
              done
              return 1
            }

            guard_ports() {
              grep -h ' 0A ' /proc/net/tcp /proc/net/tcp6 2>/dev/null | while read -r sl la ra st q t r u rest; do
                [ "${'$'}u" = "${'$'}OWN" ] && echo ${'$'}((0x${'$'}{la##*:}))
              done | sort -un
            }

            guard_sync() {
              [ -n "${'$'}OWN" ] || return 0
              gp=${'$'}(guard_ports)
              [ "${'$'}gp" = "${'$'}GUARDED" ] && return 0
              GUARDED=${'$'}gp
              for tool in iptables ip6tables; do
                ${'$'}tool -w -F ${'$'}GUARD 2>/dev/null || continue
                ${'$'}tool -w -A ${'$'}GUARD -m owner --uid-owner 0 -j RETURN
                ${'$'}tool -w -A ${'$'}GUARD -m owner --uid-owner ${'$'}OWN -j RETURN
                for p in ${'$'}gp; do
                  ${'$'}tool -w -A ${'$'}GUARD -p tcp --dport ${'$'}p -j REJECT --reject-with tcp-reset
                done
              done
            }

            split_dns() {
            __SPLIT__
            }

            clean() {
              for pr in ${'$'}P $PREF_RETURN $PREF_LOCAL_RETURN; do
                while ip rule del pref ${'$'}pr 2>/dev/null; do :; done
                while ip -6 rule del pref ${'$'}pr 2>/dev/null; do :; done
              done
              ip route flush table ${'$'}T 2>/dev/null
              ip -6 route flush table ${'$'}T 2>/dev/null
              ndc network destroy ${'$'}NETID > /dev/null 2>&1
              service call dnsresolver 9 i32 ${'$'}NETID > /dev/null 2>&1
              while iptables -w -t nat -D OUTPUT -j ${'$'}CHAIN 2>/dev/null; do :; done
              iptables -w -t nat -F ${'$'}CHAIN 2>/dev/null
              iptables -w -t nat -X ${'$'}CHAIN 2>/dev/null
              for tool in iptables ip6tables; do
                while ${'$'}tool -w -D FORWARD -j ${'$'}FWD 2>/dev/null; do :; done
                ${'$'}tool -w -F ${'$'}FWD 2>/dev/null
                ${'$'}tool -w -X ${'$'}FWD 2>/dev/null
                while ${'$'}tool -w -D OUTPUT -o lo -j ${'$'}GUARD 2>/dev/null; do :; done
                ${'$'}tool -w -F ${'$'}GUARD 2>/dev/null
                ${'$'}tool -w -X ${'$'}GUARD 2>/dev/null
              done
            }

            teardown() {
              clean
              stop_hev
              ip link set ${'$'}IF down 2>/dev/null
              ip tuntap del dev ${'$'}IF mode tun 2>/dev/null
              rm -f "${'$'}STATS"
            }

            setup() {
              teardown
            __SETUP__
              return 0
            }

            case "${'$'}1" in
              stop)
                stop_daemon
                teardown
                echo stopped > "${'$'}STATUS"
                rm -f "${'$'}PIDF"
                exit 0
                ;;
              start)
                stop_daemon
                echo ${'$'}${'$'} > "${'$'}PIDF"
                trap 'teardown; echo stopped > "${'$'}STATUS"; rm -f "${'$'}PIDF"; exit 0' TERM INT HUP
                echo starting > "${'$'}STATUS"
                if ! setup; then
                  echo "error: setup failed" > "${'$'}STATUS"
                  teardown
                  rm -f "${'$'}PIDF"
                  exit 1
                fi
                echo ready > "${'$'}STATUS"
                stale=0
                lasthb=
                restarts=0
                while :; do
                  if [ -z "${'$'}NOHEV" ]; then
                    hp=${'$'}(cat "${'$'}HEVPIDF" 2>/dev/null)
                    if ours "${'$'}hp" libhevroot; then
                      restarts=0
                    else
                      restarts=${'$'}((restarts+1))
                      if [ ${'$'}restarts -gt $MAX_HEV_RESTARTS ]; then
                        echo "error: tun2socks keeps exiting" > "${'$'}STATUS"
                        break
                      fi
                      start_hev
                    fi
                  fi
                  hb=${'$'}(cat "${'$'}DIR/alive" 2>/dev/null)
                  if [ -n "${'$'}hb" ] && [ "${'$'}hb" != "${'$'}lasthb" ]; then
                    stale=0
                    lasthb=${'$'}hb
                  else
                    stale=${'$'}((stale+1))
                    if [ ${'$'}stale -ge $STALE_TICKS ]; then
                      echo "stopped: app heartbeat lost" > "${'$'}STATUS"
                      break
                    fi
                  fi
                  guard_sync
                  if [ -e "/sys/class/net/${'$'}IF" ]; then
                    echo "${'$'}(cat /sys/class/net/${'$'}IF/statistics/rx_bytes 2>/dev/null) ${'$'}(cat /sys/class/net/${'$'}IF/statistics/tx_bytes 2>/dev/null)" > "${'$'}STATS.tmp" && mv "${'$'}STATS.tmp" "${'$'}STATS"
                  else
                    echo "0 0" > "${'$'}STATS.tmp" && mv "${'$'}STATS.tmp" "${'$'}STATS"
                  fi
                  sleep $TICK_SECONDS &
                  wait ${'$'}!
                done
                teardown
                rm -f "${'$'}PIDF"
                exit 0
                ;;
            esac
        """.trimIndent().replace("__SETUP__", setup).replace("__SPLIT__", split) + "\n"
    }

    fun launchCommand(scriptPath: String, logPath: String): String =
        "umask 022; setsid sh ${quote(scriptPath)} start > ${quote(logPath)} 2>&1 < /dev/null &"

    fun stopCommand(scriptPath: String): String = "sh ${quote(scriptPath)} stop"

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun merge(sorted: List<Long>): List<UidRange> {
        val out = ArrayList<UidRange>()
        for (uid in sorted) {
            val last = out.lastOrNull()
            if (last != null && uid == last.last + 1) {
                out[out.lastIndex] = UidRange(last.first, uid)
            } else {
                out.add(UidRange(uid, uid))
            }
        }
        return out
    }
}
