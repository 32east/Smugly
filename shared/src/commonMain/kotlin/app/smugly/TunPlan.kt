package app.smugly

data class TunRoute(val address: String, val prefix: Int) {
    override fun toString(): String = "$address/$prefix"
}

object TunPlan {
    val DEFAULT_DNS: List<String> = listOf("1.1.1.1", "8.8.8.8")

    val TUN_SUBNET_V4 = TunRoute("10.255.0.0", 24)

    val LAN_V4: List<TunRoute> = listOf(
        TunRoute("0.0.0.0", 8),
        TunRoute("10.0.0.0", 8),
        TunRoute("127.0.0.0", 8),
        TunRoute("169.254.0.0", 16),
        TunRoute("172.16.0.0", 12),
        TunRoute("192.168.0.0", 16),
        TunRoute("224.0.0.0", 4),
        TunRoute("240.0.0.0", 4)
    )

    val PUBLIC_V6 = TunRoute("2000::", 3)

    fun mtu(settings: TunSettings): Int =
        settings.mtu.coerceIn(TunSettings.MIN_MTU, TunSettings.MAX_MTU)

    fun ipv4Routes(settings: TunSettings): List<TunRoute> {
        if (!settings.bypassLan) return listOf(TunRoute("0.0.0.0", 0))
        val excluded = LAN_V4.map { toRange(it) }
        val out = ArrayList<TunRoute>()
        subtract(0L, 0, excluded, out)
        out.add(TUN_SUBNET_V4)
        return out.sortedBy { parseIpv4(it.address) }
    }

    fun ipv6Routes(settings: TunSettings): List<TunRoute> = when {
        !settings.ipv6 -> emptyList()
        settings.bypassLan -> listOf(PUBLIC_V6)
        else -> listOf(TunRoute("::", 0))
    }

    fun dnsServers(raw: String): List<String> {
        val parsed = raw.split(',', ';', ' ', '\t', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && isIpLiteral(it) }
            .distinct()
        return parsed.ifEmpty { DEFAULT_DNS }
    }

    fun invalidDnsEntries(raw: String): List<String> =
        raw.split(',', ';', ' ', '\t', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isIpLiteral(it) }

    fun isRouted(routes: List<TunRoute>, ipv4: String): Boolean {
        val ip = parseIpv4(ipv4) ?: return false
        return routes.any { r ->
            val start = parseIpv4(r.address) ?: return@any false
            val size = 1L shl (32 - r.prefix)
            ip in start until start + size
        }
    }

    fun isIpLiteral(value: String): Boolean = parseIpv4(value) != null || isIpv6Literal(value)

    fun parseIpv4(value: String): Long? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        var acc = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null
            val n = p.toInt()
            if (n > 255) return null
            acc = (acc shl 8) or n.toLong()
        }
        return acc
    }

    fun formatIpv4(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 0xFF).toString() }

    private fun isIpv6Literal(value: String): Boolean {
        if (!value.contains(':') || value.length > 45) return false
        if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
        val doubleColons = value.windowed(2).count { it == "::" }
        if (doubleColons > 1 || value.contains(":::")) return false
        val groups = value.split(':')
        val tail = groups.last()
        val embeddedV4 = tail.contains('.')
        if (embeddedV4 && parseIpv4(tail) == null) return false
        val hexGroups = if (embeddedV4) groups.dropLast(1) else groups
        if (hexGroups.any { it.length > 4 || it.contains('.') }) return false
        val filled = hexGroups.count { it.isNotEmpty() } + if (embeddedV4) 2 else 0
        return if (doubleColons == 1) filled < 8 else filled == 8 && hexGroups.none { it.isEmpty() }
    }

    private fun toRange(route: TunRoute): LongRange {
        val start = parseIpv4(route.address) ?: error("bad route ${route.address}")
        return start until start + (1L shl (32 - route.prefix))
    }

    private fun subtract(start: Long, prefix: Int, excluded: List<LongRange>, out: MutableList<TunRoute>) {
        val end = start + (1L shl (32 - prefix)) - 1
        val overlapping = excluded.filter { it.first <= end && it.last >= start }
        if (overlapping.isEmpty()) {
            out.add(TunRoute(formatIpv4(start), prefix))
            return
        }
        if (overlapping.any { it.first <= start && it.last >= end }) return
        val half = 1L shl (31 - prefix)
        subtract(start, prefix + 1, overlapping, out)
        subtract(start + half, prefix + 1, overlapping, out)
    }
}
