package com.fsck.k9.controller.push

import org.koin.dsl.module

internal val controllerPushModule = module {
    single { PushServiceManager(context = get()) }
    single { BootCompleteManager(context = get()) }
    single { AutoSyncManager(context = get()) }
    single { BackgroundPermissionManager(context = get()) }
    single {
        AccountPushControllerFactory(
            backendManager = get(),
            messagingController = get(),
            folderRepository = get(),
        )
    }
    single {
        PushController(
            accountManager = get(),
            generalSettingsManager = get(),
            backendManager = get(),
            pushServiceManager = get(),
            bootCompleteManager = get(),
            autoSyncManager = get(),
            connectivityManager = get(),
            accountPushControllerFactory = get(),
            folderRepository = get(),
            backgroundPermissionManager = get(),
        )
    }
}
