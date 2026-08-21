package dev.flexaccess.ezvpn

import android.app.Application

class EzvpnApplication : Application() {
    lateinit var manager: TunnelsManager
        private set

    override fun onCreate() {
        super.onCreate()
        EzvpnNative.init(this)
        manager = TunnelsManager(this)
    }
}
