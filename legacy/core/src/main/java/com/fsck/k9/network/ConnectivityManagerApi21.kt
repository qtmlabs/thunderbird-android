package com.fsck.k9.network

import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import timber.log.Timber
import android.net.ConnectivityManager as SystemConnectivityManager

internal class ConnectivityManagerApi21(
    private val systemConnectivityManager: SystemConnectivityManager,
) : ConnectivityManagerBase() {
    private var isRunning = false
    private var isConnectivityAvailable = false
    private var linkProperties: LinkProperties? = null

    private val networkCallback = object : NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Timber.v("Network capabilities changed: $network: $networkCapabilities")
            synchronized(this@ConnectivityManagerApi21) {
                val lastConnectivityAvailable = isConnectivityAvailable

                isConnectivityAvailable = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                if (isConnectivityAvailable == lastConnectivityAvailable) return

                if (isConnectivityAvailable) {
                    notifyOnConnectivityChanged()
                } else {
                    notifyOnConnectivityLost()
                }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, newLinkProperties: LinkProperties) {
            Timber.v("Network link properties changed: $network: $newLinkProperties")
            synchronized(this@ConnectivityManagerApi21) {
                val lastLinkProperties = linkProperties

                linkProperties = newLinkProperties

                if (lastLinkProperties !== null &&
                    lastLinkProperties.linkAddresses != newLinkProperties.linkAddresses &&
                    isConnectivityAvailable
                ) {
                    notifyOnConnectivityChanged()
                }
            }
        }

        override fun onLost(network: Network) {
            Timber.v("Network lost: $network")
            synchronized(this@ConnectivityManagerApi21) {
                isConnectivityAvailable = false
                linkProperties = null

                notifyOnConnectivityLost()
            }
        }
    }

    @Synchronized
    override fun start() {
        if (!isRunning) {
            isRunning = true
            isConnectivityAvailable = false
            linkProperties = null

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            systemConnectivityManager.requestNetwork(networkRequest, networkCallback)
        }
    }

    @Synchronized
    override fun stop() {
        if (isRunning) {
            isRunning = false

            systemConnectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    @Synchronized
    override fun isNetworkAvailable(): Boolean {
        return isConnectivityAvailable
    }
}
