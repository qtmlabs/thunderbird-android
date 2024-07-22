package com.fsck.k9.network

import android.net.ConnectivityManager as SystemConnectivityManager

interface ConnectivityManager {
    fun start()
    fun stop()
    fun isNetworkAvailable(): Boolean
    fun addListener(listener: ConnectivityChangeListener)
    fun removeListener(listener: ConnectivityChangeListener)
}

interface ConnectivityChangeListener {
    fun onConnectivityChanged()
    fun onConnectivityLost()
}

internal fun ConnectivityManager(systemConnectivityManager: SystemConnectivityManager): ConnectivityManager {
    return ConnectivityManagerApi21(systemConnectivityManager)
}
