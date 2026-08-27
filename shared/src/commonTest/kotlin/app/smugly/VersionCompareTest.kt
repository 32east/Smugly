package app.smugly

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionCompareTest {

    @Test
    fun higherPatchIsNewer() {
        assertTrue(VersionCompare.isNewer("0.1.1", "0.1.0"))
        assertFalse(VersionCompare.isNewer("0.1.0", "0.1.1"))
    }

    /** The classic: string comparison would put 1.9.0 above 1.10.0 and never offer the update. */
    @Test
    fun componentsCompareAsNumbersNotText() {
        assertTrue(VersionCompare.isNewer("1.10.0", "1.9.0"))
        assertFalse(VersionCompare.isNewer("0.2.0", "0.10.0"))
    }

    @Test
    fun theVeePrefixIsIgnoredOnEitherSide() {
        assertTrue(VersionCompare.isNewer("v0.2.0", "0.1.0"))
        assertTrue(VersionCompare.isNewer("0.2.0", "v0.1.0"))
        assertFalse(VersionCompare.isNewer("v0.1.0", "v0.1.0"))
    }

    /** A tag with fewer components than the running build must not read as a downgrade. */
    @Test
    fun missingComponentsCountAsZero() {
        assertFalse(VersionCompare.isNewer("0.1", "0.1.0"))
        assertTrue(VersionCompare.isNewer("0.2", "0.1.9"))
        assertTrue(VersionCompare.isNewer("1", "0.9.9"))
    }

    @Test
    fun theSameVersionIsNotAnUpdate() {
        assertFalse(VersionCompare.isNewer("0.1.0", "0.1.0"))
    }

    @Test
    fun aReleaseOutranksItsOwnPreRelease() {
        assertTrue(VersionCompare.isNewer("0.2.0", "0.2.0-rc1"))
        assertFalse(VersionCompare.isNewer("0.2.0-rc1", "0.2.0"))
        assertTrue(VersionCompare.isNewer("0.2.0-rc2", "0.2.0-rc1"))
    }

    /** A pre-release of a higher version still beats the lower final release. */
    @Test
    fun preReleaseOfAHigherVersionIsStillNewer() {
        assertTrue(VersionCompare.isNewer("0.3.0-beta", "0.2.0"))
    }

    @Test
    fun junkNeverReadsAsAnUpdate() {
        assertFalse(VersionCompare.isNewer("", "0.1.0"))
        assertFalse(VersionCompare.isNewer("nightly", "0.1.0"))
    }

    /** Whitespace around a hand-typed tag must not change the answer. */
    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertTrue(VersionCompare.isNewer("  v0.2.0 ", "0.1.0"))
        assertFalse(VersionCompare.isNewer(" 0.1.0 ", " 0.1.0"))
    }
}
