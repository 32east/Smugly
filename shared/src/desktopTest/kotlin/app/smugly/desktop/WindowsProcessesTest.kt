package app.smugly.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsProcessesTest {

    @Test
    fun theExecutableNameIsTheIdentityAndTheLabelDropsTheSuffix() {
        val apps = WindowsProcesses.fromCommands(listOf("""C:\Program Files\Discord\Discord.exe"""))
        assertEquals(1, apps.size)
        assertEquals("discord.exe", apps[0].packageName)
        assertEquals("Discord", apps[0].label)
    }

    /** Chrome runs a dozen processes; routing sees one app, so the picker must too. */
    @Test
    fun oneRowPerExecutableNoMatterHowManyProcesses() {
        val apps = WindowsProcesses.fromCommands(
            listOf(
                """C:\Program Files\Google\Chrome\Application\chrome.exe""",
                """C:\Program Files\Google\Chrome\Application\chrome.exe""",
                """C:\Program Files\Google\Chrome\Application\chrome.exe"""
            )
        )
        assertEquals(1, apps.size)
    }

    /** The rule matches case-insensitively, so two spellings are not two apps. */
    @Test
    fun theSameBinaryInDifferentCaseIsOneApp() {
        val apps = WindowsProcesses.fromCommands(
            listOf("""C:\a\Chrome.exe""", """C:\b\chrome.EXE""")
        )
        assertEquals(1, apps.size)
        assertEquals("chrome.exe", apps[0].packageName)
    }

    @Test
    fun rowsAreSortedByNameForAScannableList() {
        val apps = WindowsProcesses.fromCommands(
            listOf("""C:\x\zoom.exe""", """C:\x\Alpha.exe""", """C:\x\midori.exe""")
        )
        assertEquals(listOf("Alpha", "midori", "zoom"), apps.map { it.label })
    }

    /** Without this the list is svchost all the way down and the browser is unfindable. */
    @Test
    fun windowsOwnBinariesAreMarkedAsSystem() {
        val root = System.getenv("SystemRoot") ?: """C:\Windows"""
        val apps = WindowsProcesses.fromCommands(
            listOf("""$root\System32\svchost.exe""", """C:\Program Files\Discord\Discord.exe""")
        )
        assertTrue(apps.first { it.packageName == "svchost.exe" }.isSystem)
        assertFalse(apps.first { it.packageName == "discord.exe" }.isSystem)
    }

    /** A path under a directory that merely *starts* with the Windows path is not system. */
    @Test
    fun aLookalikePathIsNotTreatedAsSystem() {
        assertFalse(WindowsProcesses.isSystemPath("""C:\WindowsApps-Portable\thing.exe"""))
        assertTrue(WindowsProcesses.isSystemPath("""C:\Windows\System32\svchost.exe"""))
    }

    @Test
    fun forwardSlashesAndQuotesSurviveTheParse() {
        val apps = WindowsProcesses.fromCommands(listOf(""""C:/Games/steam.exe""""))
        assertEquals("steam.exe", apps.single().packageName)
    }

    /** Non-executables and blanks are not apps and must not become rules. */
    @Test
    fun nonExecutablesAreDropped() {
        val apps = WindowsProcesses.fromCommands(listOf("", "   ", "/usr/bin/bash", """C:\x\a.dll"""))
        assertTrue(apps.isEmpty())
    }

    /**
     * The picker's identity has to be exactly what the config rules match, or a ticked app
     * silently does nothing.
     */
    @Test
    fun pickerIdentitiesSurviveConfigNormalizationUnchanged() {
        val apps = WindowsProcesses.fromCommands(
            listOf("""C:\Program Files\Google\Chrome\Application\chrome.exe""", """C:\x\Discord.exe""")
        )
        val ids = apps.map { it.packageName }.toSet()
        assertEquals(ids.sorted(), TunConfig.normalizeProcesses(ids))
    }
}
