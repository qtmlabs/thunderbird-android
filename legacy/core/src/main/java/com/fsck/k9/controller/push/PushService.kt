package com.fsck.k9.controller.push

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Foreground service that is used to keep the app alive while listening for new emails (Push).
 */
class PushService : Service() {
    private val pushServiceManager: PushServiceManager by inject()
    private val pushController: PushController by inject()

    override fun onCreate() {
        Timber.v("PushService.onCreate()")
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.v("PushService.onStartCommand(%s)", intent)
        super.onStartCommand(intent, flags, startId)

        val isAutomaticRestart = intent == null
        if (isAutomaticRestart) {
            initializePushController()
        }

        notifyServiceStarted()

        return START_STICKY
    }

    override fun onDestroy() {
        Timber.v("PushService.onDestroy()")
        notifyServiceStopped()
        super.onDestroy()
    }

    private fun notifyServiceStarted() {
        // If our process was low-memory killed and now this service is being restarted by the system,
        // PushServiceManager doesn't necessarily know about this service's state. So we're updating it now.
        pushServiceManager.setServiceStarted()
    }

    private fun notifyServiceStopped() {
        // Usually this service is only stopped via PushServiceManager. But we still notify PushServiceManager here in
        // case the system decided to stop the service (without killing the process).
        pushServiceManager.setServiceStopped()
    }

    private fun initializePushController() {
        // When the app is killed by the system and later recreated to start this service nobody else is initializing
        // PushController. So we'll have to do it here.
        pushController.init()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
