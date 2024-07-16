package com.fsck.k9.controller.push

internal class BackgroundPermissionManagerStub : BackgroundPermissionManager {
    override fun canRunBackgroundServices(): Boolean {
        return true
    }
}
