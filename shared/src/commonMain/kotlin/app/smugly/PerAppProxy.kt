package app.smugly

/**
 * Split tunnelling: which apps ride the tunnel and which go straight out.
 *
 * This is a property of the *tunnel*, not of a profile — it is applied to the TUN itself
 * (`VpnService.Builder`), so it holds for every protocol the app can carry (Slipstream DNS,
 * S3FU, Xray) without any of them knowing about it.
 */
enum class PerAppMode {
    /** Everything goes through the tunnel (what the app has always done). */
    OFF,

    /** Only the selected apps are routed into the tunnel; everything else goes direct. */
    ALLOWED,

    /** The selected apps go direct; everything else is routed into the tunnel. */
    DISALLOWED
}

/**
 * Turns the stored mode + package set into the two lists `VpnService.Builder` accepts.
 *
 * Pure on purpose: the Android side only has to loop over the result, and the rules that are
 * easy to get wrong — and impossible to see in a screenshot — are unit-tested.
 *
 * Rules, all of them load-bearing:
 * - **Never both lists.** Android throws `UnsupportedOperationException` the moment a builder
 *   is given an allowed *and* a disallowed package, so a plan carries exactly one of them.
 * - **Our own package never rides the tunnel.** Every protocol dials its server from this very
 *   process (Xray, s3fu and the DNS client all run in-app), so routing ourselves back
 *   into the TUN is a loop. In [PerAppMode.DISALLOWED] that means adding ourselves to the
 *   exclusions; in [PerAppMode.ALLOWED] it means we simply never appear in the allow list —
 *   which is also why the picker hides this app.
 * - **An empty allow list falls back to [PerAppMode.OFF].** Establishing a TUN that no app is
 *   routed into looks connected while every packet leaves in the clear. Given the choice
 *   between "protects nothing, silently" and "protects everything", a VPN takes the second;
 *   the screen warns about the empty selection so it is not a surprise.
 */
object PerAppFilter {
    /**
     * @param allowed packages to hand to `addAllowedApplication` (empty when [disallowed] is used)
     * @param disallowed packages to hand to `addDisallowedApplication`
     * @param fellBackToOff true when an empty [PerAppMode.ALLOWED] selection was widened to "all
     *        apps" — worth a log line, since the user asked for something else.
     */
    data class Plan(
        val allowed: List<String>,
        val disallowed: List<String>,
        val fellBackToOff: Boolean = false
    )

    fun plan(mode: PerAppMode, packages: Set<String>, ownPackage: String, includeSelf: Boolean = false): Plan {
        val picked = packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != ownPackage }
            .distinct()
            .sorted()
            .toList()
        val self = if (includeSelf) emptyList() else listOf(ownPackage)
        return when {
            mode == PerAppMode.ALLOWED && picked.isNotEmpty() ->
                Plan(allowed = if (includeSelf) (picked + ownPackage).sorted() else picked, disallowed = emptyList())

            mode == PerAppMode.ALLOWED ->
                Plan(allowed = emptyList(), disallowed = self, fellBackToOff = true)

            mode == PerAppMode.DISALLOWED ->
                Plan(allowed = emptyList(), disallowed = (picked + self).distinct().sorted())

            else -> Plan(allowed = emptyList(), disallowed = self)
        }
    }
}

/**
 * [GlobalSettings.perAppPackages] as one stored string, and back. Package names cannot contain
 * whitespace, so a newline is a safe separator — the same trick [CollapsedCategories] uses.
 */
object PerAppPackages {
    fun encode(packages: Set<String>): String = packages.filter { it.isNotBlank() }.sorted().joinToString("\n")

    fun decode(raw: String?): Set<String> =
        raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
