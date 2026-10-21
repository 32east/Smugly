package app.smugly

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference

class OAuthReturnActivity : Activity() {

    companion object {
        private val pendingCode = AtomicReference<String?>(null)

        fun takeCode(): String? = pendingCode.getAndSet(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.getQueryParameter("code")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }?.let {
            pendingCode.set(it)
        }
        startActivity(
            Intent(this, ComposeMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
