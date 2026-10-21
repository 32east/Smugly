package app.smugly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalSocksUsernameTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs() = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs().edit().clear().commit()
    }

    @Test
    fun a_fresh_install_uses_smugly() {
        assertEquals("smugly", ConfigStore.loadGlobalSettings(context).localSocksUsername)
    }

    @Test
    fun the_old_default_is_replaced_and_the_password_kept() {
        prefs().edit()
            .putString("globalLocalSocksUsername", "slipstream")
            .putString("globalLocalSocksPassword", "pw")
            .commit()

        val settings = ConfigStore.loadGlobalSettings(context)

        assertEquals("smugly", settings.localSocksUsername)
        assertEquals("pw", settings.localSocksPassword)
        assertEquals("smugly", prefs().getString("globalLocalSocksUsername", null))
    }

    @Test
    fun a_name_the_user_chose_is_left_alone() {
        prefs().edit()
            .putString("globalLocalSocksUsername", "alice")
            .putString("globalLocalSocksPassword", "pw")
            .commit()

        assertEquals("alice", ConfigStore.loadGlobalSettings(context).localSocksUsername)
    }

    @Test
    fun picking_slipstream_again_after_the_migration_sticks() {
        prefs().edit()
            .putString("globalLocalSocksUsername", "slipstream")
            .putString("globalLocalSocksPassword", "pw")
            .commit()
        val migrated = ConfigStore.loadGlobalSettings(context)
        ConfigStore.saveGlobalSettings(context, migrated.copy(localSocksUsername = "slipstream"))

        assertEquals("slipstream", ConfigStore.loadGlobalSettings(context).localSocksUsername)
    }
}
