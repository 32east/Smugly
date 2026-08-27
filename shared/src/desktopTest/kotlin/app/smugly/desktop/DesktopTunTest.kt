package app.smugly.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What can be asserted about TUN mode without an administrator token, a driver or a network.
 * The adapter itself is exercised by hand (see docs/tun-mode.md); these cover the decisions around it.
 */
class DesktopTunTest {

    @Test
    fun tunModeIsOfferedOnlyWhereItIsImplemented() {
        assertEquals(WindowsSystemProxy.isWindows, DesktopTun.isSupported())
    }

    /**
     * Our own executable in the never-tunnel list is the difference between a working tunnel and
     * a routing loop, so the list must never come back empty even if the JVM declines to name the
     * running command.
     */
    @Test
    fun ourOwnProcessNamesAreAlwaysKnown() {
        val names = DesktopTun.ownProcessNames()
        assertTrue(names.isNotEmpty())
        assertTrue(names.all { it.isNotBlank() })
        assertEquals(names.distinct(), names)
    }

    @Test
    fun preflightNamesTheOneThingToFix() {
        val problem = DesktopTun.preflight()
        if (!DesktopTun.isSupported()) {
            assertTrue(problem != null && problem.contains("Windows"))
            return
        }
        // On Windows the outcome depends on what is installed and whether the test runner is
        // elevated; either way the answer must be actionable rather than a bare failure.
        if (problem != null) {
            assertTrue(
                listOf("sing-box", "wintun", "administrator").any { problem.contains(it, ignoreCase = true) },
                "unhelpful preflight message: $problem"
            )
        }
    }

    /** Diagnostics must survive a machine with nothing installed. */
    @Test
    fun reportNeverThrows() {
        val report = DesktopTun.report()
        assertTrue(report.contains("sing-box"))
        assertTrue(report.contains("wintun.dll"))
    }

    /** Nothing is running, so nothing may claim to be. */
    @Test
    fun anUnstartedTunIsNotRunning() {
        assertFalse(DesktopTun.isRunning)
        assertEquals("", DesktopTun.logTail())
    }
}
