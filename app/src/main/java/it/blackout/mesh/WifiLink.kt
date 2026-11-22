package it.blackout.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * DarkProof phones on the same Wi-Fi network, found with network service discovery (Bonjour/mDNS) and linked with
 * [WifiLinks]. Only the phone with the smaller node id connects, and it tries again every 10 s while unlinked.
 */
class WifiLink(c: Context, private val nodeId: String, router: () -> MeshRouter?, changed: () -> Unit) {
    companion object { const val SERVICE = "_darkproof._tcp" }
    // Debug builds also take the phone's own loopback: `adb forward` in the emulator test (README) comes in that way.
    private val debug = (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val links = WifiLinks(nodeId, router, changed) { WifiLinks.onLan(it) || (debug && it.isLoopbackAddress) }
    private val nsd = c.getSystemService(NsdManager::class.java)
    private val h = Handler(Looper.getMainLooper())
    private val found = HashMap<String, Pair<String, Int>>() // node id -> host, port (main thread)
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    var running = false; private set
    /** Stopped for good: the Wi-Fi switch makes a new WifiLink. A start still on its way when this one was stopped does nothing. */
    @Volatile private var stopped = false

    fun start() {
        if (running || stopped) return
        running = true
        val port = links.listen()
        if (port == 0) return
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }.also { nsd.registerService(NsdServiceInfo().apply { serviceName = nodeId; serviceType = SERVICE; this.port = port }, NsdManager.PROTOCOL_DNS_SD, it) }
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            // A device can announce any number of names: only so many wait to be resolved, or are remembered.
            override fun onServiceFound(info: NsdServiceInfo) = h.post { if (wanted(info.serviceName) && toResolve.size < LinkLimits.FOUND) { toResolve += info; resolveNext() } }.let { }
            override fun onServiceLost(info: NsdServiceInfo) = h.post { found.remove(info.serviceName) }.let { }
        }.also { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, it) }
        h.postDelayed(::retry, 10_000)
    }

    fun stop() {
        stopped = true
        if (!running) return
        running = false
        h.removeCallbacksAndMessages(null)
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        found.clear(); toResolve.clear(); resolving = false
        links.stop()
    }

    /** Only the smaller node id connects (spec), and not to itself. */
    private fun wanted(node: String) = node != nodeId && nodeId < node

    @Suppress("DEPRECATION") // resolveService: its replacement needs Android 14
    private fun resolveNext() {
        if (resolving || !running) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) = h.post { resolving = false; resolveNext() }.let { }
            override fun onServiceResolved(i: NsdServiceInfo) = h.post {
                resolving = false
                val host = i.host?.hostAddress
                if (host != null && running && (found.size < LinkLimits.FOUND || i.serviceName in found)) { found[i.serviceName] = host to i.port; links.dial(host, i.port) }
                resolveNext()
            }.let { }
        })
    }

    /**
     * Every 10 s, dials the devices it found that have no link (a link can drop while the service stays visible). Only
     * a link to the address it would dial counts: one from elsewhere may only claim that phone's node id.
     */
    private fun retry() {
        if (!running) return
        // in a new order each time: only 8 are dialed at once, and it must not always be the same ones
        found.filter { (node, at) -> wanted(node) && !links.linkedAt(node, at.first) }.values.shuffled().forEach { (host, port) -> links.dial(host, port) }
        h.postDelayed(::retry, 10_000)
    }
}
