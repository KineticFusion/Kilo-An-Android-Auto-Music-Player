package com.example.kilo.androidauto

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Observes Android Auto controller connections without changing session or queue behavior. */
object ConnectionReliabilityManager {
    private const val TAG = "KiloAutoConnection"
    private val connectedControllers = ConcurrentHashMap.newKeySet<String>()
    private val disconnectedControllers = ConcurrentHashMap.newKeySet<String>()
    private val disconnectCount = AtomicLong(0L)
    private val receiverRegistered = AtomicBoolean(false)
    @Volatile private var lastDisconnectAtMs: Long = 0L
    @Volatile private var receiverContext: Context? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> Log.d(TAG, "bluetooth_link_connected")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> Log.d(TAG, "bluetooth_link_disconnected")
            }
        }
    }

    fun startMonitoring(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val app = context.applicationContext
        receiverContext = app
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") app.registerReceiver(bluetoothReceiver, filter)
        }.onFailure {
            receiverRegistered.set(false)
            Log.w(TAG, "bluetooth_monitor_unavailable", it)
        }
    }

    fun stopMonitoring() {
        val app = receiverContext
        if (receiverRegistered.compareAndSet(true, false) && app != null) {
            runCatching { app.unregisterReceiver(bluetoothReceiver) }
        }
        receiverContext = null
    }

    fun onConnected(packageName: String, uid: Int) {
        val key = "$packageName:$uid"
        val wasDisconnected = disconnectedControllers.remove(key)
        connectedControllers.add(key)
        if (wasDisconnected) lastDisconnectAtMs = 0L
        Log.i(TAG, "controller_connected package=$packageName active=${connectedControllers.size} reconnect=$wasDisconnected")
    }

    fun onDisconnected(packageName: String, uid: Int) {
        val key = "$packageName:$uid"
        connectedControllers.remove(key)
        disconnectedControllers.add(key)
        lastDisconnectAtMs = System.currentTimeMillis()
        val count = disconnectCount.incrementAndGet()
        Log.i(TAG, "controller_disconnected package=$packageName active=${connectedControllers.size} disconnects=$count")
    }

    /** Queue contents and position are still restored by the existing Media3 resumption callback. */
    fun onQueueRestoreRequested(packageName: String) {
        Log.i(TAG, "queue_restore_requested package=$packageName active=${connectedControllers.size}")
    }

    fun onQueueRestoreReady(packageName: String, itemCount: Int) {
        Log.i(TAG, "queue_restore_ready package=$packageName items=$itemCount")
    }
}
