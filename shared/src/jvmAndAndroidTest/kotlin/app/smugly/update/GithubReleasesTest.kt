package app.smugly.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GithubReleasesTest {

    private fun release(
        tag: String,
        assets: String,
        draft: Boolean = false,
        body: String = ""
    ) = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "body": ${quote(body)},
          "html_url": "https://github.com/32east/Smugly/releases/tag/$tag",
          "assets": [$assets]
        }
    """.trimIndent()

    private fun apkAsset(name: String = "smugly.apk", size: Long = 12345) = """
        {
          "name": "$name",
          "size": $size,
          "browser_download_url": "https://github.com/32east/Smugly/releases/download/x/$name"
        }
    """.trimIndent()

    private fun quote(text: String) = "\"" + text.replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun readsTagNotesAndApkAsset() {
        val parsed = GithubReleases.parse("[${release("v0.2.0", apkAsset(size = 999), body = "Фикс DNS")}]")
        assertEquals("0.2.0", parsed?.versionName)
        assertEquals("v0.2.0", parsed?.tagName)
        assertEquals("Фикс DNS", parsed?.notes)
        assertEquals(999L, parsed?.apkSizeBytes)
        assertEquals(
            "https://github.com/32east/Smugly/releases/download/x/smugly.apk",
            parsed?.apkUrl
        )
    }

    /** GitHub lists newest-first, but a re-tagged or back-dated release must not win on order. */
    @Test
    fun picksTheHighestVersionNotTheFirstListed() {
        val body = "[" + listOf(
            release("v0.1.5", apkAsset()),
            release("v0.3.0", apkAsset()),
            release("v0.2.0", apkAsset())
        ).joinToString(",") + "]"
        assertEquals("0.3.0", GithubReleases.parse(body)?.versionName)
    }

    /** Nothing to install means nothing to offer, however good the release notes are. */
    @Test
    fun releasesWithoutAnApkAreIgnored() {
        val sourceOnly = """{"name":"Source code.zip","size":10,"browser_download_url":"https://x/y.zip"}"""
        assertNull(GithubReleases.parse("[${release("v0.9.0", sourceOnly)}]"))
    }

    @Test
    fun draftsAreIgnored() {
        val body = "[" + listOf(
            release("v0.9.0", apkAsset(), draft = true),
            release("v0.2.0", apkAsset())
        ).joinToString(",") + "]"
        assertEquals("0.2.0", GithubReleases.parse(body)?.versionName)
    }

    @Test
    fun emptyOrBrokenPayloadsGiveNothing() {
        assertNull(GithubReleases.parse("[]"))
        assertNull(GithubReleases.parse(""))
        assertNull(GithubReleases.parse("not json at all"))
        assertNull(GithubReleases.parse("""{"message":"API rate limit exceeded"}"""))
    }

    /** A release whose only asset is a mapping file must not be handed to the installer. */
    @Test
    fun onlyApkAssetsCount() {
        val mapping = """{"name":"mapping.txt","size":10,"browser_download_url":"https://x/mapping.txt"}"""
        val both = "$mapping,${apkAsset("app-release.apk", 42)}"
        val parsed = GithubReleases.parse("[${release("v0.4.0", both)}]")
        assertEquals("https://github.com/32east/Smugly/releases/download/x/app-release.apk", parsed?.apkUrl)
        assertEquals(42L, parsed?.apkSizeBytes)
    }
}
