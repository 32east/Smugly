package app.smugly

/**
 * App identity shown in the drawer footer (and elsewhere).
 * Keep in sync with `appVersionName` in app/build.gradle.kts.
 *
 * Display only: the update checker deliberately compares GitHub's tag against the version the
 * package manager reports, so these two drifting apart can misprint a number but can never
 * hide a release or offer the running build back.
 */
object AppVersion {
    const val name = "0.1.2"
    /** ISO date this build was compiled — generated, never hand-edited (see BuildStamp.kt). */
    val buildDate: String get() = BUILD_STAMP_DATE

    val display: String get() = "$name · $buildDate"
}
