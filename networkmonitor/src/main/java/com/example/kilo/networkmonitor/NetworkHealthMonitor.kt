package com.example.kilo.networkmonitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

enum class NetworkGrade { EXCELLENT, GOOD, MODERATE, POOR, CRITICAL }
enum class NetworkTransport { WIFI, CELLULAR, ETHERNET, OTHER, OFFLINE }

data class NetworkHealthSnapshot(
    val transport: NetworkTransport,
    val grade: NetworkGrade,
    val downstreamKbps: Int,
    val validated: Boolean,
    val observedAtMs: Long
)

/** Observes Android's current network capabilities without issuing probe traffic. */
object NetworkHealthMonitor {
    private const val TAG = "KiloNetworkHealth"
    @Volatile private var current = NetworkHealthSnapshot(
        NetworkTransport.OFFLINE, NetworkGrade.CRITICAL, 0, false, 0L
    )
    @Volatile private var connectivity: ConnectivityManager? = null
    @Volatile private var registered = false
    private val listeners = CopyOnWriteArrayList<(NetworkHealthSnapshot) -> Unit>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            publish(from(networkCapabilities))
        }
        override fun onLost(network: Network) = refresh(null)
    }

    @Synchronized
    fun start(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = manager
        refresh(null)
        if (registered) return
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "Network callback registration failed", it) }
    }

    fun snapshot(): NetworkHealthSnapshot = current

    fun addListener(listener: (NetworkHealthSnapshot) -> Unit) {
        listeners.addIfAbsent(listener)
        listener(current)
    }

    fun removeListener(listener: (NetworkHealthSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    private fun refresh(network: Network?) {
        val manager = connectivity ?: return
        val active = network ?: runCatching { manager.activeNetwork }.getOrNull()
        val capabilities = active?.let { runCatching { manager.getNetworkCapabilities(it) }.getOrNull() }
        publish(capabilities?.let(::from) ?: NetworkHealthSnapshot(
            NetworkTransport.OFFLINE, NetworkGrade.CRITICAL, 0, false, System.currentTimeMillis()
        ))
    }

    private fun from(capabilities: NetworkCapabilities): NetworkHealthSnapshot {
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        }
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val bandwidth = capabilities.linkDownstreamBandwidthKbps.coerceAtLeast(0)
        val grade = when {
            !validated -> NetworkGrade.CRITICAL
            transport == NetworkTransport.WIFI || transport == NetworkTransport.ETHERNET -> when {
                bandwidth >= 15_000 -> NetworkGrade.EXCELLENT
                bandwidth >= 5_000 -> NetworkGrade.GOOD
                bandwidth >= 1_500 -> NetworkGrade.MODERATE
                else -> NetworkGrade.POOR
            }
            else -> when {
                bandwidth >= 8_000 -> NetworkGrade.GOOD
                bandwidth >= 3_000 -> NetworkGrade.MODERATE
                bandwidth >= 800 -> NetworkGrade.POOR
                else -> NetworkGrade.CRITICAL
            }
        }
        return NetworkHealthSnapshot(transport, grade, bandwidth, validated, System.currentTimeMillis())
    }

    private fun publish(snapshot: NetworkHealthSnapshot) {
        val previous = current
        current = snapshot
        if (previous.transport != snapshot.transport || previous.grade != snapshot.grade ||
            previous.validated != snapshot.validated
        ) {
            Log.i(TAG, "networkChanged transport=${snapshot.transport} grade=${snapshot.grade} " +
                "downstreamKbps=${snapshot.downstreamKbps} validated=${snapshot.validated}")
            listeners.forEach { listener -> runCatching { listener(snapshot) } }
        }
    }
}
