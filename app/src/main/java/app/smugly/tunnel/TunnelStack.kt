package app.smugly.tunnel

import android.content.Context
import app.smugly.ConfigStore

object TunnelStack {
    fun isRunning(): Boolean = HevSocks5Tunnel.isRunning() || (RootTunnel.ownsTunnel() && RootTunnel.isRunning())

    fun stats(): HevSocks5Tunnel.TrafficStats =
        if (RootTunnel.ownsTunnel()) RootTunnel.stats() else HevSocks5Tunnel.stats()

    fun stopRoot(context: Context) {
        runCatching {
            RootTunnel.stop(context, force = ConfigStore.loadGlobalSettings(context).let { it.rootMode.isActive || it.rootHotspot })
        }
    }

    fun stop(context: Context) {
        stopRoot(context)
        HevSocks5Tunnel.stop()
    }
}
