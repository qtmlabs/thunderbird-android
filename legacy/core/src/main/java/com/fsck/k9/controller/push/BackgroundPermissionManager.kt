package com.fsck.k9.controller.push

import android.content.Context
import android.os.Build
import android.provider.Settings

interface BackgroundPermissionManager {
    /**
     * Checks whether the app can run background services.
     * If this method returns `false`, the app has to request the permission to run background services. See
     * [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
     */
    fun canRunBackgroundServices(): Boolean
}

/**
 * Factory method to create an Android API-specific instance of [BackgroundPermissionManager].
 */
internal fun BackgroundPermissionManager(context: Context): BackgroundPermissionManager {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        BackgroundPermissionManagerApi26(context)
    } else {
        BackgroundPermissionManagerStub()
    }
}
