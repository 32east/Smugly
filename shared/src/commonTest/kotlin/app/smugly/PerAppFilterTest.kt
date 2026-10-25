package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OWN = "app.smugly"

class PerAppFilterTest {

    @Test
    fun offExcludesOnlyOurself() {
        val plan = PerAppFilter.plan(PerAppMode.OFF, setOf("com.telegram", "com.chrome"), OWN)
        assertEquals(emptyList(), plan.allowed)
        assertEquals(listOf(OWN), plan.disallowed)
        assertFalse(plan.fellBackToOff)
    }

    @Test
    fun allowedListsOnlyThePickedApps() {
        val plan = PerAppFilter.plan(PerAppMode.ALLOWED, setOf("com.telegram", "com.chrome"), OWN)
        assertEquals(listOf("com.chrome", "com.telegram"), plan.allowed)
        assertEquals(emptyList(), plan.disallowed)
    }

    /** Both lists on one builder makes `establish()` throw, so a plan may only ever carry one. */
    @Test
    fun planNeverCarriesBothLists() {
        for (mode in PerAppMode.entries) {
            val plan = PerAppFilter.plan(mode, setOf("com.telegram"), OWN)
            assertTrue(
                plan.allowed.isEmpty() || plan.disallowed.isEmpty(),
                "mode=$mode produced both an allow and a disallow list"
            )
        }
    }

    @Test
    fun disallowedAlwaysAddsOurOwnPackage() {
        val plan = PerAppFilter.plan(PerAppMode.DISALLOWED, setOf("com.telegram"), OWN)
        assertEquals(listOf(OWN, "com.telegram"), plan.disallowed)
        assertEquals(emptyList(), plan.allowed)
    }

    /** Every protocol dials out from our own process; allowing it back in is a loop. */
    @Test
    fun ourOwnPackageIsNeverAllowed() {
        val plan = PerAppFilter.plan(PerAppMode.ALLOWED, setOf(OWN, "com.telegram"), OWN)
        assertEquals(listOf("com.telegram"), plan.allowed)
    }

    /** Picking ourselves in DISALLOWED must not list the package twice. */
    @Test
    fun ourOwnPackageIsNotDuplicatedInDisallowed() {
        val plan = PerAppFilter.plan(PerAppMode.DISALLOWED, setOf(OWN, "com.telegram"), OWN)
        assertEquals(listOf(OWN, "com.telegram"), plan.disallowed)
    }

    /**
     * An allow list of nothing would establish a TUN no app is routed into: "connected" with
     * every packet leaving in the clear. Widen to "all apps" instead, and say so.
     */
    @Test
    fun emptyAllowedFallsBackToRoutingEverything() {
        val plan = PerAppFilter.plan(PerAppMode.ALLOWED, emptySet(), OWN)
        assertEquals(emptyList(), plan.allowed)
        assertEquals(listOf(OWN), plan.disallowed)
        assertTrue(plan.fellBackToOff)
    }

    /** An empty exclusion list already means "everything is tunnelled" — no fallback needed. */
    @Test
    fun emptyDisallowedIsNotAFallback() {
        val plan = PerAppFilter.plan(PerAppMode.DISALLOWED, emptySet(), OWN)
        assertEquals(listOf(OWN), plan.disallowed)
        assertFalse(plan.fellBackToOff)
    }

    @Test
    fun blankAndDuplicateEntriesAreDropped() {
        val plan = PerAppFilter.plan(
            PerAppMode.ALLOWED,
            setOf("com.telegram", " com.telegram ", "", "   "),
            OWN
        )
        assertEquals(listOf("com.telegram"), plan.allowed)
    }

    @Test
    fun packagesRoundTripThroughStorage() {
        val packages = setOf("com.telegram", "com.chrome", "org.mozilla.firefox")
        assertEquals(packages, PerAppPackages.decode(PerAppPackages.encode(packages)))
    }

    @Test
    fun decodingMissingOrEmptyStorageGivesNoPackages() {
        assertEquals(emptySet(), PerAppPackages.decode(null))
        assertEquals(emptySet(), PerAppPackages.decode(""))
        assertEquals(emptySet(), PerAppPackages.decode("\n \n"))
    }

    @Test
    fun includeSelfKeepsTheAppInsideTheTunnel() {
        assertEquals(emptyList(), PerAppFilter.plan(PerAppMode.OFF, emptySet(), OWN, includeSelf = true).disallowed)
        assertEquals(
            listOf("com.telegram"),
            PerAppFilter.plan(PerAppMode.DISALLOWED, setOf("com.telegram"), OWN, includeSelf = true).disallowed
        )
        assertEquals(
            listOf(OWN, "com.telegram").sorted(),
            PerAppFilter.plan(PerAppMode.ALLOWED, setOf("com.telegram"), OWN, includeSelf = true).allowed
        )
        assertEquals(emptyList(), PerAppFilter.plan(PerAppMode.ALLOWED, emptySet(), OWN, includeSelf = true).disallowed)
    }
}
