package dev.flexaccess.ezvpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.flexaccess.ezvpn.tunnelcore.DnsProxy
import dev.flexaccess.ezvpn.tunnelcore.DnsProxyRequest
import dev.flexaccess.ezvpn.tunnelcore.IpLiteral
import dev.flexaccess.ezvpn.tunnelcore.LocalNetworks
import dev.flexaccess.ezvpn.tunnelcore.NetworkConfig
import dev.flexaccess.ezvpn.tunnelcore.TunnelConfigJson
import dev.flexaccess.ezvpn.tunnelcore.TunnelPlan
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfile
import java.net.Inet6Address
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The VPN service: the Android counterpart of the packet-tunnel provider. It
 * bridges `VpnService` to the Rust core (libezvpn.so via [EzvpnNative]):
 * connect + handshake first to learn the assigned addresses and bypass set,
 * program the interface from the resulting [TunnelPlan], `establish()` it, and
 * hand the fd to the Rust data loop.
 *
 * Everything that touches the session runs on one worker thread, so stop,
 * the connect continuation, the exit callback, and path queries never race
 * into a double `stop` (which would double-free the handle). The blocking
 * `connect` itself runs on its own thread — parking the worker on it would
 * also park the disconnect the user taps while a connect to an offline server
 * is still timing out — and re-checks on the worker whether it was stopped
 * meanwhile.
 *
 * No foreground notification: the system binds the service while the
 * interface is established, which keeps the process alive (the WireGuard app
 * relies on the same).
 */
class EzvpnVpnService : VpnService() {
    private class Session(val profileId: UUID) {
        var handle = 0L
        var tun: ParcelFileDescriptor? = null
        var stopRequested = false
        var monitor: NetworkMonitor? = null
        /** Our copies of the protected fallback-DNS sockets; closed once the core has its dups. */
        var dnsSockets: List<ParcelFileDescriptor> = emptyList()

        fun closeDnsSockets() {
            dnsSockets.forEach { runCatching { it.close() } }
            dnsSockets = emptyList()
        }
    }

    private lateinit var manager: TunnelsManager
    private lateinit var worker: ExecutorService
    private var current: Session? = null

    override fun onCreate() {
        super.onCreate()
        manager = TunnelsManager.get(this)
        worker = Executors.newSingleThreadExecutor { Thread(it, "ezvpn-vpn") }
        EzvpnNative.exitListener = EzvpnNative.ExitListener { handle, error ->
            worker.execute {
                val s = current ?: return@execute
                if (s.handle != handle) return@execute
                teardown(s, error ?: "The tunnel was closed.")
            }
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_PROFILE_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: manager.profileStore.lastProfileId
        if (intent == null) Log.i(TAG, "started by the system (always-on): profile $id")
        worker.execute { startTunnel(id) }
        return START_NOT_STICKY
    }

    /** The user turned the VPN off in Settings, or another VPN app took over. */
    override fun onRevoke() {
        worker.execute { current?.let { teardown(it, "VPN permission was revoked.") } }
    }

    override fun onDestroy() {
        instance = null
        EzvpnNative.exitListener = null
        worker.execute { current?.let { teardown(it, null) } }
        worker.shutdown()
        super.onDestroy()
    }

    fun disconnect() {
        worker.execute { current?.let { teardown(it, null) } }
    }

    /** The `connPath` JSON of the running session, or null. Serialized with stop. */
    fun connPathBlocking(): String? = try {
        worker.submit<String?> {
            current?.takeIf { it.handle != 0L }?.let { EzvpnNative.connPath(it.handle) }
        }.get(3, TimeUnit.SECONDS)
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------
    // Worker-thread session lifecycle

    private fun startTunnel(id: UUID?) {
        if (current != null) {
            Log.i(TAG, "connect ignored: a session is already running")
            return
        }
        if (id == null) {
            manager.onDisconnected(null, "No profile to connect.")
            stopSelf()
            return
        }
        val session = Session(id)
        current = session
        manager.onConnecting(id)

        val profile = manager.profileStore.profile(id)
        if (profile == null) {
            teardown(session, "Profile not found.")
            return
        }
        val authKey = try {
            manager.profileStore.authKey(id)
        } catch (e: SecretStoreException) {
            teardown(session, "Couldn't read the auth key: ${e.message}")
            return
        }
        if (authKey.isNullOrEmpty()) {
            teardown(session, "The profile has no auth key; edit it and pick one.")
            return
        }
        val relayToken = runCatching { manager.profileStore.relayAuthToken(id) }.getOrNull()

        // Refuse to start when a configured split-tunnel prefix overlaps the
        // network the device is on: routing the local subnet into the tunnel
        // would cut off on-link hosts, including the gateway carrying the
        // tunnel's own underlay traffic.
        val cm = getSystemService(ConnectivityManager::class.java)
        LocalNetworks.splitTunnelConflict(profile.routes, profile.routes6, AndroidLocalNetworks.current(cm))?.let {
            Log.e(TAG, it)
            teardown(session, it)
            return
        }

        // Split DNS (match domains) needs the in-tunnel forwarder: give the core
        // the underlying network's resolvers and sockets that bypass the VPN to
        // reach them (protect() works before establish()). Read the resolvers
        // now, while the physical network is still this app's default one.
        val dnsProxy = if (DnsProxy.isEnabled(profile)) {
            session.dnsSockets = listOfNotNull(
                protectedUdpSocket(OsConstants.AF_INET),
                protectedUdpSocket(OsConstants.AF_INET6),
            )
            DnsProxyRequest(
                fallbackServers = underlyingDnsServers(cm),
                fallbackFds = session.dnsSockets.map { it.fd },
            ).also { Log.i(TAG, "split DNS: fallback resolvers ${it.fallbackServers}") }
        } else {
            null
        }

        val configJson = TunnelConfigJson.build(profile, authKey, relayToken, dnsProxy)
        thread(name = "ezvpn-connect") {
            val out = arrayOfNulls<String>(1)
            val handle = EzvpnNative.connect(configJson, out)
            worker.execute { afterConnect(session, profile, handle, out[0] ?: "") }
        }
    }

    private fun afterConnect(session: Session, profile: TunnelProfile, handle: Long, result: String) {
        // The core dup'ed the fallback sockets it needs during connect.
        session.closeDnsSockets()
        if (session.stopRequested || current !== session) {
            // Stopped while the handshake was in flight: nothing published this
            // handle, so it is ours to free.
            if (handle != 0L) EzvpnNative.stop(handle)
            return
        }
        if (handle == 0L) {
            Log.e(TAG, "connect failed: $result")
            teardown(session, "Connect failed: $result")
            return
        }
        session.handle = handle
        Log.i(TAG, "handshake result: $result")

        val net = NetworkConfig.parse(result)
        if (net == null) {
            teardown(session, "Bad network config from the server: $result")
            return
        }
        val plan = TunnelPlan.from(net, profile)
        plan.warnings.forEach { Log.w(TAG, it) }
        if (plan.remoteAddress == null) {
            teardown(session, "The server assigned no address.")
            return
        }

        val builder = Builder().setSession(profile.name).setMtu(plan.mtu)
        plan.address4?.let { builder.addAddress(it.address, 32) }
        plan.address6?.let { builder.addAddress(it.address, 128) }
        (plan.routes4 + plan.routes6).forEach { builder.addRoute(it.address, it.prefixLength) }
        plan.dnsServers.forEach { builder.addDnsServer(it) }
        // An address family with no address on the interface is blocked for
        // every app by default; we are a split tunnel, so let it bypass instead.
        if (plan.address4 == null) builder.allowFamily(OsConstants.AF_INET)
        if (plan.address6 == null) builder.allowFamily(OsConstants.AF_INET6)

        val tun = try {
            builder.establish()
        } catch (e: Exception) {
            teardown(session, "Couldn't establish the VPN interface: ${e.message}")
            return
        }
        if (tun == null) {
            teardown(session, "VPN permission is missing — connect from the app to grant it.")
            return
        }
        session.tun = tun

        val rc = EzvpnNative.run(handle, tun.fd)
        if (rc != 0) {
            teardown(session, "Couldn't start the tunnel data loop (rc=$rc).")
            return
        }
        Log.i(TAG, "tunnel running on fd ${tun.fd}")
        val cm = getSystemService(ConnectivityManager::class.java)
        session.monitor = NetworkMonitor(cm, worker) { reason ->
            val s = current ?: return@NetworkMonitor
            if (s !== session) return@NetworkMonitor
            Log.i(TAG, "$reason, disconnecting")
            teardown(session, "Network changed ($reason), disconnected.")
        }.also { it.start() }
        manager.onConnected(profile.id, plan.runtimeInfo())
    }

    /** Tear the session down (idempotent per session) and report. Worker thread. */
    private fun teardown(session: Session, error: String?) {
        if (current !== session) return
        session.stopRequested = true
        session.monitor?.stop()
        session.monitor = null
        if (session.handle != 0L) {
            manager.onDisconnecting()
            EzvpnNative.stop(session.handle)
            session.handle = 0L
        }
        // Close our fd only after the data loop is dead; the interface goes
        // away with it.
        runCatching { session.tun?.close() }
        session.tun = null
        session.closeDnsSockets()
        current = null
        Log.i(TAG, "tunnel stopped" + (error?.let { ": $it" } ?: ""))
        manager.onDisconnected(session.profileId, error)
        stopSelf()
    }

    /**
     * A UDP socket of `family` marked to bypass the VPN (`protect()`), as a
     * ParcelFileDescriptor we own; null when the OS refused.
     */
    private fun protectedUdpSocket(family: Int): ParcelFileDescriptor? {
        return try {
            val fd = Os.socket(family, OsConstants.SOCK_DGRAM, 0)
            try {
                if (family == OsConstants.AF_INET6) {
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1)
                }
                val pfd = ParcelFileDescriptor.dup(fd)
                if (!protect(pfd.fd)) {
                    pfd.close()
                    Log.w(TAG, "split DNS: protect() refused a fallback socket")
                    null
                } else {
                    pfd
                }
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            Log.w(TAG, "split DNS: cannot create a fallback socket: ${e.message}")
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "split DNS: cannot dup a fallback socket: ${e.message}")
            null
        }
    }

    /**
     * The resolvers of the network the device would use without us: the first
     * non-VPN network with Internet, Wi-Fi/Ethernet preferred over cellular.
     * IPv6 link-local resolvers carry their interface index as `%<index>` so the
     * core can address them.
     */
    private fun underlyingDnsServers(cm: ConnectivityManager): List<String> {
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            val link = cm.getLinkProperties(network) ?: return@mapNotNull null
            val rank = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 1 else 0
            rank to link
        }
        val link = candidates.minByOrNull { it.first }?.second ?: return emptyList()
        return link.dnsServers.map { addr ->
            val text = IpLiteral.format(addr.address)
            val scope = (addr as? Inet6Address)?.takeIf { it.isLinkLocalAddress }?.scopeId ?: 0
            if (scope > 0) "$text%$scope" else text
        }
    }

    /**
     * Watches the physical networks while the tunnel runs and asks for a
     * disconnect when the one the tunnel rides on changes — the same policy as
     * the Apple app (the session is not migrated; the user reconnects). The
     * callback's initial burst of `onAvailable` calls records the baseline;
     * after that, a new Wi-Fi/Ethernet network, or the loss of a baseline
     * network, is a change. Cellular appearing next to Wi-Fi, or a lingering
     * cellular link dropping while Wi-Fi stays, does not move the default
     * network and is ignored.
     */
    private class NetworkMonitor(
        private val cm: ConnectivityManager,
        private val worker: ExecutorService,
        private val onChange: (String) -> Unit,
    ) {
        private val baseline = HashMap<Network, String>()
        private var settled = false
        private var stopped = false

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                worker.execute {
                    if (stopped) return@execute
                    val kind = transport(network)
                    if (!settled) {
                        baseline[network] = kind
                        return@execute
                    }
                    if (baseline.containsKey(network)) return@execute
                    if (kind == "cellular" && baseline.values.any { it != "cellular" }) {
                        baseline[network] = kind
                        return@execute
                    }
                    onChange("new $kind network")
                }
            }

            override fun onLost(network: Network) {
                worker.execute {
                    if (stopped) return@execute
                    val kind = baseline.remove(network) ?: return@execute
                    if (!settled) return@execute
                    if (kind == "cellular" && baseline.values.any { it != "cellular" }) return@execute
                    onChange("$kind network lost")
                }
            }
        }

        fun start() {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            Handler(Looper.getMainLooper()).postDelayed({
                worker.execute {
                    settled = true
                    Log.i(TAG, "network baseline: ${baseline.values.sorted().joinToString(",")}")
                }
            }, SETTLE_MILLIS)
        }

        fun stop() {
            stopped = true
            runCatching { cm.unregisterNetworkCallback(callback) }
        }

        private fun transport(network: Network): String {
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "unknown"
            }
        }

        private companion object {
            const val SETTLE_MILLIS = 1500L
        }
    }

    companion object {
        private const val TAG = "ezvpn"
        const val ACTION_CONNECT = "dev.flexaccess.ezvpn.CONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"

        /** The live service, while one exists (it runs in the app's process). */
        @Volatile
        var instance: EzvpnVpnService? = null
            private set
    }
}
