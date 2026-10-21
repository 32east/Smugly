package app.smugly.account

data class AccountProfile(
    val name: String,
    val email: String = "",
    val telegramLinked: Boolean = false
)

data class AccountPlan(
    val endsAtMs: Long,
    val isTrial: Boolean,
    val deviceCount: Int,
    val dataLimitBytes: Long,
    val dataUsedBytes: Long,
    val configSyncPending: Boolean
)

data class AccountSnapshot(
    val profile: AccountProfile,
    val plan: AccountPlan?,
    val trialAvailable: Boolean = false,
    val trialDays: Int = 0,
    val trialNeedsTelegram: Boolean = false,
    val supportUrl: String = ""
)

data class AccountLink(
    val secret: String,
    val browserUrl: String,
    val base: String,
    val startedAtMs: Long
) {
    fun expired(nowMs: Long): Boolean = nowMs - startedAtMs > LINK_LIFETIME_MS

    companion object {
        const val LINK_LIFETIME_MS = 14L * 60_000
    }
}

sealed interface AccountLinkPoll {
    data object Pending : AccountLinkPoll
    data class Linked(val name: String) : AccountLinkPoll
    data class Failed(val message: String) : AccountLinkPoll
}

enum class SignOutReason { ACCOUNT_GONE, BANNED, SESSION_INVALID, USER }

sealed interface AccountSyncResult {
    data object NotSignedIn : AccountSyncResult
    data class Ready(
        val snapshot: AccountSnapshot,
        val folderChanged: Boolean,
        val accessReissued: Boolean = false
    ) : AccountSyncResult
    data class SignedOut(val reason: SignOutReason) : AccountSyncResult
    data class Offline(val message: String) : AccountSyncResult
}

interface AccountApi {
    fun isSignedIn(): Boolean
    fun snapshot(): AccountSnapshot?
    fun managedSubscriptionId(): String?
    fun trialDays(): Int?
    fun fetchTrialDays(): Int?
    fun beginLink(): Result<AccountLink>
    fun pollLink(link: AccountLink): AccountLinkPoll
    fun confirmOAuth(link: AccountLink, code: String): Boolean = false
    fun sync(): AccountSyncResult
    fun webCabinetUrl(): Result<String>
    fun signOut()
}
