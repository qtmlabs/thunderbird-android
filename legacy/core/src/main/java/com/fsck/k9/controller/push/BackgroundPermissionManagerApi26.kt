package com.fsck.k9.controller.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.system.exitProcess

@RequiresApi(Build.VERSION_CODES.M)
internal class BackgroundPermissionManagerApi26(private val context: Context) : BackgroundPermissionManager {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val intentFilter = IntentFilter("android.os.action.POWER_SAVE_WHITELIST_CHANGED")

    private var lastPermissionValue = canRunBackgroundServices()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val currentPermissionValue = canRunBackgroundServices()
            if (currentPermissionValue == lastPermissionValue) return
            lastPermissionValue = currentPermissionValue
            if (!currentPermissionValue) {
                // User revoked our permission, die immediately.
                exitProcess(0);
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun canRunBackgroundServices(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
