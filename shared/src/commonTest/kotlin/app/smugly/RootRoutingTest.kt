package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootRoutingTest {

    private fun spec(
        ranges: List<UidRange> = listOf(UidRange(0, 10172), UidRange(10174, RootRouting.MAX_UID)),
        tun: TunSettings = TunSettings(),
        hotspot: Boolean = false,
        dnsTarget: String = "10.255.0.1",
        dnsTcp: Boolean = false,
        guardUid: Long? = 10173
    ) = RootRouting.Spec(
        dir = "/data/user/0/app.smugly/files/root",
        hevPath = "/data/app/x/lib/arm64/libhevroot.so",
        hevConfigPath = "/data/user/0/app.smugly/files/root/hev.yml",
        tun = tun,
        uidRanges = ranges,
        dnsTarget = dnsTarget,
        hotspot = hotspot,
        dnsTcp = dnsTcp,
        guardUid = guardUid
    )

    @Test
    fun everyoneButOurselvesWhenNothingIsExcluded() {
        val ranges = RootRouting.uidRanges(emptyList(), listOf(10173L))
        assertEquals(listOf(UidRange(0, 10172), UidRange(10174, RootRouting.MAX_UID)), ranges)
    }

    @Test
    fun excludedAppsPunchHolesInTheRange() {
        val ranges = RootRouting.uidRanges(emptyList(), listOf(10173L, 10100L, 10101L, 10300L))
        assertEquals(
            listOf(
                UidRange(0, 10099),
                UidRange(10102, 10172),
                UidRange(10174, 10299),
                UidRange(10301, RootRouting.MAX_UID)
            ),
            ranges
        )
    }

    @Test
    fun excludingTheEdgesLeavesNoEmptyRange() {
        val ranges = RootRouting.uidRanges(emptyList(), listOf(0L, RootRouting.MAX_UID))
        assertEquals(listOf(UidRange(1, RootRouting.MAX_UID - 1)), ranges)
    }

    @Test
    fun allowedAppsBecomeMergedRangesWithoutOurself() {
        val ranges = RootRouting.uidRanges(listOf(10102L, 10100L, 10101L, 10173L, 10200L), listOf(10173L))
        assertEquals(listOf(UidRange(10100, 10102), UidRange(10200, 10200)), ranges)
    }

    @Test
    fun noRangeEverContainsTheExcludedUid() {
        val own = 10173L
        val ranges = RootRouting.uidRanges(emptyList(), listOf(own, 10050L))
        assertTrue(ranges.none { own in it.first..it.last })
        assertTrue(ranges.none { 10050L in it.first..it.last })
        assertTrue(ranges.any { 10049L in it.first..it.last })
        assertTrue(ranges.any { 10051L in it.first..it.last })
    }

    @Test
    fun dnsTargetPrefersMapDnsThenFirstIpv4Resolver() {
        assertEquals("10.255.0.1", RootRouting.dnsTarget("10.255.0.1", listOf("9.9.9.9")))
        assertEquals("9.9.9.9", RootRouting.dnsTarget(null, listOf("2606:4700::1111", "9.9.9.9")))
        assertEquals("1.1.1.1", RootRouting.dnsTarget(null, listOf("2606:4700::1111")))
        assertEquals("1.1.1.1", RootRouting.dnsTarget(null, emptyList()))
    }

    @Test
    fun uidRulesCarryTheVpnBypassMarkAndOnlyMatchLocalTraffic() {
        val lines = RootRouting.setupCommands(spec())
        val v4 = lines.filter { it.startsWith("ip rule add pref \$P fwmark") }
        assertEquals(2, v4.size)
        assertTrue(v4.all { "iif lo" in it && "0x0/0x20000" in it })
        assertTrue(v4.any { "uidrange 0-10172" in it })
        assertTrue(v4.any { "uidrange 10174-${RootRouting.MAX_UID}" in it })
        assertEquals(2, lines.count { it.startsWith("ip -6 rule add pref \$P fwmark") })
    }

    @Test
    fun ourOwnUidIsNeverInsideAnyRule() {
        val joined = RootRouting.setupCommands(spec()).joinToString("\n")
        assertFalse("10173" in joined)
    }

    @Test
    fun routesFollowTheLanBypassSetting() {
        val all = RootRouting.setupCommands(spec(tun = TunSettings(bypassLan = false, ipv6 = true)))
        assertTrue(all.any { it == "ip route replace 0.0.0.0/0 dev \$IF table \$T || return 1" })
        assertTrue(all.any { it == "ip -6 route replace ::/0 dev \$IF table \$T || return 1" })

        val bypass = RootRouting.setupCommands(spec(tun = TunSettings(bypassLan = true, ipv6 = true)))
        assertFalse(bypass.any { it.startsWith("ip route replace 0.0.0.0/0 ") })
        assertFalse(bypass.any { it.startsWith("ip route replace 192.168.") })
        assertTrue(bypass.any { it.startsWith("ip route replace 10.255.0.0/24 ") })
        assertTrue(bypass.any { it.startsWith("ip -6 route replace 2000::/3 ") })
    }

    @Test
    fun withoutIpv6TheTunnelBlackholesV6InsteadOfLeaking() {
        val lines = RootRouting.setupCommands(spec(tun = TunSettings(ipv6 = false)))
        assertTrue(lines.any { it.startsWith("ip -6 route replace unreachable ::/0 table \$T") })
        assertFalse(lines.any { "fd00::2" in it })
        assertFalse(lines.any { it.startsWith("ip -6 route replace ::/0 dev") })
    }

    @Test
    fun dnsIsRedirectedOnlyForTunnelledUids() {
        val lines = RootRouting.setupCommands(spec(dnsTcp = false))
        val dnat = lines.filter { "DNAT" in it }
        assertEquals(2, dnat.size)
        assertTrue(dnat.all { "-p udp" in it && "--to-destination 10.255.0.1:53" in it })
        assertTrue(dnat.any { "--uid-owner 0-10172" in it })
        assertTrue(dnat.none { "10173" in it })
    }

    @Test
    fun plainResolverGetsTcpDnsToo() {
        val lines = RootRouting.setupCommands(spec(dnsTarget = "1.1.1.1", dnsTcp = true))
        assertEquals(4, lines.count { "DNAT" in it })
        assertEquals(2, lines.count { "-p tcp --dport 53" in it })
    }

    @Test
    fun dnsTargetRuleSendsRedirectedQueriesIntoTheTunnelRegardlessOfMarks() {
        val lines = RootRouting.setupCommands(spec(dnsTarget = "1.1.1.1"))
        val to = lines.filter { " to 1.1.1.1/32 " in it }
        assertEquals(
            listOf(
                "ip rule add pref \$P to 1.1.1.1/32 uidrange 0-10172 lookup \$T",
                "ip rule add pref \$P to 1.1.1.1/32 uidrange 10174-${RootRouting.MAX_UID} lookup \$T"
            ),
            to
        )
        assertTrue(to.none { "fwmark" in it })
    }

    @Test
    fun ourOwnTrafficToTheDnsTargetStaysOutOfTheTunnel() {
        val joined = RootRouting.setupCommands(spec(dnsTarget = "1.1.1.1")).joinToString("\n")
        assertFalse("ip rule add pref \$P to 1.1.1.1/32 lookup" in joined)
        assertFalse("10173" in joined)
    }

    @Test
    fun allowListStillCatchesRedirectedQueriesRoutedAsRoot() {
        val lines = RootRouting.setupCommands(spec(ranges = listOf(UidRange(10100, 10102)), dnsTarget = "1.1.1.1"))
        val to = lines.filter { " to 1.1.1.1/32 " in it }
        assertEquals(2, to.size)
        assertTrue(to.any { "uidrange 0-0 " in it })
        assertTrue(to.any { "uidrange 10100-10102 " in it })
    }

    @Test
    fun hotspotRulesAppearOnlyWhenAsked() {
        val off = RootRouting.setupCommands(spec(hotspot = false))
        assertTrue(off.none { it.contains("iif ap0") || it.contains("iif wlan1") })
        val on = RootRouting.setupCommands(spec(hotspot = true))
        assertTrue(on.any { it == "ip rule add pref \$P iif ap0 lookup \$T" })
        assertTrue(on.any { it.startsWith("ip -6 rule add pref \$P iif swlan0 ") })
    }

    @Test
    fun hotspotForwardingIsAcceptedPastTheTetherDropRule() {
        val off = RootRouting.setupCommands(spec(hotspot = false))
        assertTrue(off.none { "FORWARD" in it || "\$FWD" in it })
        val on = RootRouting.setupCommands(spec(hotspot = true))
        assertTrue(on.any { it == "iptables -w -A \$FWD -o \$IF -j ACCEPT" })
        assertTrue(on.any { it == "iptables -w -A \$FWD -i \$IF -j ACCEPT" })
        assertTrue(on.any { it == "iptables -w -I FORWARD 1 -j \$FWD" })
        assertTrue(on.any { it.startsWith("ip6tables -w -I FORWARD 1 -j \$FWD") })
        assertTrue(on.indexOfFirst { it.startsWith("iptables -w -N \$FWD") } < on.indexOfFirst { it.startsWith("iptables -w -I FORWARD") })
        val script = RootRouting.daemonScript(spec(hotspot = true))
        assertTrue("FWD=${RootRouting.FORWARD_CHAIN}" in script)
        assertTrue("while \$tool -w -D FORWARD -j \$FWD" in script)
        assertTrue("\$tool -w -X \$FWD" in script)
    }

    @Test
    fun returnTrafficRulesComeBeforeTheTunnelRules() {
        assertTrue(RootRouting.PREF_RETURN < RootRouting.PREF_TUNNEL)
        assertTrue(RootRouting.PREF_LOCAL_RETURN < RootRouting.PREF_TUNNEL)
        assertTrue(RootRouting.PREF_TUNNEL < 10000)
        val lines = RootRouting.setupCommands(spec())
        val ret = lines.indexOfFirst { "pref ${RootRouting.PREF_RETURN} " in it }
        val tunnel = lines.indexOfFirst { it.startsWith("ip rule add pref \$P fwmark") }
        assertTrue(ret in 0 until tunnel)
    }

    @Test
    fun rulesGoInOnlyAfterTunAndHevAreUp() {
        val lines = RootRouting.setupCommands(spec())
        val hev = lines.indexOf("start_hev || return 1")
        val firstRoute = lines.indexOfFirst { it.startsWith("ip route replace") }
        val firstRule = lines.indexOfFirst { it.startsWith("ip rule add") }
        val nat = lines.indexOfFirst { it.startsWith("iptables -w -t nat -I OUTPUT") }
        assertTrue(hev in 0 until firstRoute)
        assertTrue(firstRoute < firstRule)
        assertTrue(firstRule < nat)
    }

    @Test
    fun daemonScriptHasEveryPartTheLifecycleNeeds() {
        val script = RootRouting.daemonScript(spec())
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        assertFalse("__SETUP__" in script)
        assertTrue("setup() {" in script)
        assertTrue("teardown() {" in script)
        assertTrue("trap 'teardown;" in script)
        assertTrue("cat \"\$DIR/alive\"" in script)
        assertTrue(script.contains("umask 022"))
        assertTrue("ip tuntap del dev \$IF mode tun" in script)
        assertTrue("while ip rule del pref \$pr" in script)
        assertTrue("iptables -w -t nat -D OUTPUT -j \$CHAIN" in script)
        assertTrue("IF=smugly0" in script)
    }

    @Test
    fun cleanupIsDefinedBeforeItIsUsedAndRemovesEveryRulePref() {
        val script = RootRouting.daemonScript(spec())
        assertTrue(script.indexOf("clean() {") < script.indexOf("teardown() {"))
        assertTrue("for pr in \$P ${RootRouting.PREF_RETURN} ${RootRouting.PREF_LOCAL_RETURN}" in script)
    }

    @Test
    fun silentAppIsGivenUpAfterTheGracePeriod() {
        val script = RootRouting.daemonScript(spec())
        assertTrue("-ge ${RootRouting.STALE_TICKS} ]; then" in script)
        assertTrue("stopped: app heartbeat lost" in script)
        assertTrue(Regex("sleep ${RootRouting.TICK_SECONDS} &\\n +wait \\$!\\n").containsMatchIn(script))
        assertFalse("pidof" in script)
    }

    @Test
    fun aNewDaemonWaitsForTheOldOneToFinishItsTeardown() {
        val script = RootRouting.daemonScript(spec())
        val fn = script.indexOf("stop_daemon() {")
        assertTrue("while [ \$n -lt 80 ] && ours \"\$op\" rootd.sh" in script.substring(fn, script.indexOf("\n}", fn)))
        val start = script.indexOf("  start)")
        val call = script.indexOf("stop_daemon\n", start)
        val ownPid = script.indexOf("echo \$\$ > \"\$PIDF\"", start)
        assertTrue(call in start until ownPid)
    }

    @Test
    fun stopWaitsForTheDaemonBeforeTearingDown() {
        val script = RootRouting.daemonScript(spec())
        val stop = script.indexOf("  stop)")
        val call = script.indexOf("stop_daemon\n", stop)
        val teardown = script.indexOf("teardown\n", stop)
        assertTrue(call in stop until teardown)
    }

    @Test
    fun processCheckNeverPipesProcCmdlineThroughTr() {
        val script = RootRouting.daemonScript(spec())
        assertFalse("| tr " in script)
        assertTrue("grep -qa \"\$2\" \"/proc/\$1/cmdline\"" in script)
    }

    @Test
    fun hotspotOnlyRoutesClientsIntoTheSystemVpnWithoutTouchingAnythingElse() {
        val lines = RootRouting.setupCommands(spec().copy(vpnIface = "tun0"))
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.any { it.startsWith("VT=") && "' tun0\$'" in it })
        assertTrue(lines.filter { it.startsWith("ip ") || it.startsWith("ip -6 ") }.all { "iif " in it && "lookup \$VT" in it })
        assertTrue(lines.any { it == "ip rule add pref \$P iif ap0 lookup \$VT" })
        assertTrue(lines.any { it == "iptables -w -A \$FWD -o tun0 -j ACCEPT" })
        assertFalse(lines.any { "uidrange" in it || "-t nat" in it || "tuntap" in it || "start_hev" in it })
    }

    @Test
    fun hotspotOnlyDaemonSkipsHevSupervision() {
        val script = RootRouting.daemonScript(spec().copy(vpnIface = "tun0"))
        assertTrue("NOHEV=1" in script)
        assertTrue("if [ -z \"\$NOHEV\" ]; then" in script)
        assertTrue("NOHEV=\n" in RootRouting.daemonScript(spec()))
    }

    @Test
    fun loopbackGuardIsHookedForBothFamiliesAndSyncedOnce() {
        val lines = RootRouting.setupCommands(spec())
        assertTrue("iptables -w -N \$GUARD" in lines)
        assertTrue("iptables -w -I OUTPUT 1 -o lo -j \$GUARD" in lines)
        assertTrue("ip6tables -w -I OUTPUT 1 -o lo -j \$GUARD 2>/dev/null" in lines)
        assertEquals(1, lines.count { it == "guard_sync" })
        assertTrue(lines.indexOf("GUARDED=-") < lines.indexOf("guard_sync"))
    }

    @Test
    fun noGuardWithoutAnOwnerAndNoneInHotspotOnlyMode() {
        assertFalse(RootRouting.setupCommands(spec(guardUid = null)).any { "GUARD" in it || it == "guard_sync" })
        assertFalse(RootRouting.setupCommands(spec().copy(vpnIface = "tun0")).any { "GUARD" in it })
        assertTrue("OWN=\n" in RootRouting.daemonScript(spec(guardUid = null)))
    }

    @Test
    fun guardLetsRootAndOurselvesThroughAndResetsEveryoneElse() {
        val script = RootRouting.daemonScript(spec())
        assertTrue("OWN=10173\n" in script)
        val fn = script.substring(script.indexOf("guard_sync() {"), script.indexOf("clean() {"))
        val root = fn.indexOf("--uid-owner 0 -j RETURN")
        val own = fn.indexOf("--uid-owner \$OWN -j RETURN")
        val reject = fn.indexOf("-p tcp --dport \$p -j REJECT --reject-with tcp-reset")
        assertTrue(root in 0 until reject)
        assertTrue(own in 0 until reject)
        assertTrue("[ \"\$gp\" = \"\$GUARDED\" ] && return 0" in fn)
    }

    @Test
    fun guardPortsAreOurListeningSocketsOnly() {
        val script = RootRouting.daemonScript(spec())
        val fn = script.substring(script.indexOf("guard_ports() {"), script.indexOf("guard_sync() {"))
        assertTrue("grep -h ' 0A ' /proc/net/tcp /proc/net/tcp6" in fn)
        assertTrue("[ \"\$u\" = \"\$OWN\" ]" in fn)
        assertTrue("\$((0x\${la##*:}))" in fn)
        assertTrue("sort -un" in fn)
    }

    @Test
    fun guardIsResyncedEveryTickAndRemovedOnTeardown() {
        val script = RootRouting.daemonScript(spec())
        val loop = script.substring(script.indexOf("while :; do"))
        assertTrue("guard_sync\n" in loop)
        val clean = script.substring(script.indexOf("clean() {"), script.indexOf("teardown() {"))
        assertTrue("while \$tool -w -D OUTPUT -o lo -j \$GUARD 2>/dev/null; do :; done" in clean)
        assertTrue("\$tool -w -X \$GUARD 2>/dev/null" in clean)
    }

    @Test
    fun resolverConfigParcelSizeCoversEveryWrittenField() {
        val mapDns = RootRouting.resolverConfigCommand(64000, listOf("10.255.0.1"))
        assertTrue(mapDns.startsWith("service call dnsresolver 3 i32 1 i32 80 i32 64000 i32 1800 "))
        assertTrue(mapDns.endsWith("i32 1 s16 10.255.0.1 i32 0 s16 \"\" i32 0"))
        assertTrue(" i32 72 " in RootRouting.resolverConfigCommand(64000, listOf("1.1.1.1")))
        assertTrue(" i32 64 " in RootRouting.resolverConfigCommand(64000, emptyList()))
    }

    @Test
    fun netdRangesStopAtTheLargestSignedUid() {
        val capped = RootRouting.netdRanges(listOf(UidRange(0, 10172), UidRange(10174, RootRouting.MAX_UID)))
        assertEquals(listOf(UidRange(0, 10172), UidRange(10174, RootRouting.NETD_MAX_UID)), capped)
        assertTrue(RootRouting.netdRanges(listOf(UidRange(RootRouting.NETD_MAX_UID + 1, RootRouting.MAX_UID))).isEmpty())
    }

    @Test
    fun tunnelledUidsResolveThroughTheirOwnNetdNetwork() {
        val script = RootRouting.daemonScript(spec())
        val fn = script.substring(script.indexOf("split_dns() {"), script.indexOf("clean() {"))
        val create = fn.indexOf("ndc network create \$NETID vpn 0 | grep -q '^200 ' || return 1")
        val config = fn.indexOf("service call dnsresolver 3 i32 1 i32 80 i32 ${RootRouting.NET_ID} ")
        val iface = fn.indexOf("ndc network interface add \$NETID \$IF")
        val users = fn.indexOf("ndc network users add \$NETID 0-10172")
        assertTrue(create in 0 until config && config < iface && iface < users)
        assertTrue("ndc network route add \$NETID \$IF 10.255.0.1/32" in fn)
        assertTrue("ndc network users add \$NETID 10174-${RootRouting.NETD_MAX_UID} " in fn)
        assertTrue("service call dnsresolver 11 i32 \$n" in fn)
        assertTrue("NETID=${RootRouting.NET_ID}\n" in script)
    }

    @Test
    fun netdQueriesSkipTheDnatOnlyWhenTheSplitWorked() {
        val lines = RootRouting.setupCommands(spec())
        val split = lines.indexOf("split_dns && SPLIT=1")
        val chain = lines.indexOf("iptables -w -t nat -N \$CHAIN")
        val skip = lines.indexOf("[ -n \"\$SPLIT\" ] && iptables -w -t nat -A \$CHAIN -m owner --uid-owner 0 -j RETURN")
        val firstDnat = lines.indexOfFirst { "DNAT" in it }
        assertTrue(lines.indexOf("SPLIT=") in 0 until split)
        assertTrue(split < chain && chain < skip && skip < firstDnat)
        assertTrue(lines.indexOf("ip tuntap add dev \$IF mode tun || return 1") < split)
    }

    @Test
    fun noSplitWithoutTunnelledUidsOrInHotspotOnlyMode() {
        val empty = RootRouting.daemonScript(spec(ranges = emptyList()))
        assertTrue("split_dns() {\n  return 1\n}" in empty)
        val hotspot = RootRouting.daemonScript(spec().copy(vpnIface = "tun0"))
        assertTrue("split_dns() {\n  return 1\n}" in hotspot)
    }

    @Test
    fun teardownDestroysTheNetdNetworkAndItsCache() {
        val script = RootRouting.daemonScript(spec())
        val clean = script.substring(script.indexOf("clean() {"), script.indexOf("teardown() {"))
        assertTrue("ndc network destroy \$NETID > /dev/null 2>&1" in clean)
        assertTrue("service call dnsresolver 9 i32 \$NETID > /dev/null 2>&1" in clean)
    }

    @Test
    fun quotingSurvivesApostrophes() {
        assertEquals("'a'\\''b'", RootRouting.quote("a'b"))
        assertEquals("'/x/y'", RootRouting.quote("/x/y"))
    }

    @Test
    fun launchDetachesAndStopRunsInline() {
        val launch = RootRouting.launchCommand("/d/rootd.sh", "/d/rootd.log")
        assertTrue(launch.startsWith("umask 022; setsid sh '/d/rootd.sh' start"))
        assertTrue(launch.endsWith("&"))
        assertEquals("sh '/d/rootd.sh' stop", RootRouting.stopCommand("/d/rootd.sh"))
    }

    @Test
    fun rootModeParsing() {
        assertEquals(RootMode.OFF, RootMode.parse(null))
        assertEquals(RootMode.OFF, RootMode.parse(""))
        assertEquals(RootMode.TUN2SOCKS, RootMode.parse("tun2socks"))
        assertFalse(RootMode.OFF.isActive)
        assertTrue(RootMode.TUN2SOCKS.isActive)
    }

    @Test
    fun anAttachedEngineReplacesHevAndKeepsItsOwnSocketsOutside() {
        val attach = "'/data/app/x/lib/arm64/libturnfu.so' -attach-tun 'smugly0' -tun-uds 'smugly_turnfu_ab'"
        val script = RootRouting.daemonScript(
            spec(dnsTarget = "1.1.1.1", dnsTcp = true).copy(
                attachCommand = attach,
                tunAddressV4 = "10.66.128.30",
                tunnelMask = RootRouting.UNSELECTED_MASK
            )
        )
        assertTrue(script.contains("$attach || return 1"))
        assertFalse(script.contains("start_hev || return 1"))
        assertTrue(script.contains("NOHEV=1"))
        assertTrue(script.contains("ip addr add 10.66.128.30/32 dev \$IF"))
        assertTrue(script.contains("fwmark 0x0/0x30000 iif lo uidrange"))
        assertTrue(script.contains("to 1.1.1.1/32 fwmark 0x0/0x10000 uidrange"))
        assertTrue(script.contains("-m mark --mark 0x0/0x10000 -j DNAT --to-destination 1.1.1.1:53"))
    }

    @Test
    fun anAttachedEngineWithIpv6KeepsIpv4PacketsAtTheTunnelMtu() {
        val attach = "'/data/app/x/lib/arm64/libturnfu.so' -attach-tun 'smugly0' -tun-uds 'smugly_turnfu_ab'"
        val commands = RootRouting.setupCommands(
            spec(hotspot = true).copy(
                tun = TunSettings(ipv6 = true),
                attachCommand = attach,
                tunAddressV4 = "10.66.128.30",
                tunAddressV6 = "fd66:67::801e",
                tunnelMask = RootRouting.UNSELECTED_MASK,
                mtu = 1280,
                routeMtuV4 = 1200
            )
        )
        val link = commands.indexOfFirst { it.startsWith("ip link set \$IF mtu 1280 ") }
        val addr6 = commands.indexOf("ip -6 addr add fd66:67::801e/128 dev \$IF nodad 2>/dev/null")
        assertTrue(link in 0 until addr6, "the link must allow IPv6 before the address goes on")
        assertTrue(commands.contains("ip route replace 0.0.0.0/0 dev \$IF table \$T mtu 1200 || return 1"))
        assertTrue(commands.contains("ip -6 route replace ::/0 dev \$IF table \$T || return 1"))
        assertTrue(commands.contains("ip -6 rule add pref \$P iif wlan1 unreachable 2>/dev/null"), "the server only accepts our own IPv6 source")
        assertTrue(commands.contains("ip rule add pref \$P iif wlan1 lookup \$T"))
    }

    @Test
    fun hevKeepsTheOldRulesUntouched() {
        val script = RootRouting.daemonScript(spec())
        assertTrue(script.contains("start_hev || return 1"))
        assertTrue(script.contains("fwmark 0x0/0x20000 iif lo uidrange"))
        assertFalse(script.contains("0x0/0x10000"))
        assertTrue(script.contains("ip addr add ${RootRouting.TUN_ADDRESS_V4}/32"))
    }
}
