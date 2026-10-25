package app.smugly.net

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.concurrent.ConcurrentHashMap

object LocalSocksAuth {
    private val credentials = ConcurrentHashMap<Int, PasswordAuthentication>()
    private var installed = false

    fun register(port: Int, username: String?, password: String?) {
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            credentials.remove(port)
            return
        }
        credentials[port] = PasswordAuthentication(username, password.toCharArray())
        install()
    }

    internal fun lookup(protocol: String?, loopback: Boolean, port: Int): PasswordAuthentication? {
        if (!protocol.orEmpty().startsWith("SOCKS", ignoreCase = true) || !loopback) return null
        return credentials[port]
    }

    @Synchronized
    private fun install() {
        if (installed) return
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                lookup(requestingProtocol, requestingSite?.isLoopbackAddress == true, requestingPort)
        })
        installed = true
    }
}
