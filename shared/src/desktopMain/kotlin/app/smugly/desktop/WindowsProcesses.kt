package app.smugly.desktop

import app.smugly.ui.InstalledApp

/**
 * The apps the split-tunnelling picker offers on desktop.
 *
 * Android can enumerate everything installed; Windows has no comparable list worth showing — the
 * registry's uninstall entries miss portable apps and name things like "Microsoft Edge WebView2
 * Runtime". What the rules actually match is an **executable name**, so the honest list is the
 * processes running right now: what you can pick is what is really on the machine and really
 * opening sockets.
 */
object WindowsProcesses {
    /**
     * One entry per distinct executable, newest-looking name first.
     *
     * Deduplicated by executable because that is the granularity sing-box rules work at: Chrome
     * runs a dozen processes and they are one app as far as routing is concerned.
     */
    fun list(): List<InstalledApp> = fromCommands(
        ProcessHandle.allProcesses()
            .map { it.info().command().orElse("") }
            .filter { it.isNotBlank() }
            .toList()
    )

    /**
     * Pure half of [list] — full command paths in, picker rows out.
     *
     * Split out so the naming, the deduplication and the system-app rule can be tested without
     * depending on whatever happens to be running on the machine the tests run on.
     */
    fun fromCommands(commands: Collection<String>): List<InstalledApp> =
        commands.asSequence()
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .mapNotNull { path ->
                val exe = path.substringAfterLast('\\').substringAfterLast('/')
                if (!exe.endsWith(".exe", ignoreCase = true)) return@mapNotNull null
                InstalledApp(
                    // The rule matches the executable name, so that is the identity — the path is
                    // only shown so two same-named binaries can be told apart.
                    packageName = exe.lowercase(),
                    label = exe.removeSuffix(".exe").removeSuffix(".EXE"),
                    isSystem = isSystemPath(path)
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()

    /**
     * Windows' own binaries, hidden behind the picker's "system apps" toggle.
     *
     * The list is otherwise dominated by svchost, dwm, csrss and friends — none of which the user
     * has any reason to route differently, and all of which would bury the browser they came for.
     */
    fun isSystemPath(path: String): Boolean {
        val normalized = path.replace('/', '\\').lowercase()
        val windows = (System.getenv("SystemRoot") ?: """C:\Windows""").replace('/', '\\').lowercase()
        return normalized.startsWith("$windows\\")
    }
}
