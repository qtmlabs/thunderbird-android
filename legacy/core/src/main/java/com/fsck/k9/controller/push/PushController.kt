package com.fsck.k9.controller.push

import app.k9mail.legacy.account.Account
import app.k9mail.legacy.account.AccountManager
import app.k9mail.legacy.account.AccountsChangeListener
import app.k9mail.legacy.mailstore.FolderRepository
import app.k9mail.legacy.preferences.BackgroundSync
import app.k9mail.legacy.preferences.GeneralSettingsManager
import com.fsck.k9.backend.BackendManager
import com.fsck.k9.helper.mapToSet
import com.fsck.k9.network.ConnectivityChangeListener
import com.fsck.k9.network.ConnectivityManager
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Starts and stops [AccountPushController]s as necessary. Manages the Push foreground service.
 */
@Suppress("LongParameterList")
class PushController internal constructor(
    private val accountManager: AccountManager,
    private val generalSettingsManager: GeneralSettingsManager,
    private val backendManager: BackendManager,
    private val pushServiceManager: PushServiceManager,
    private val bootCompleteManager: BootCompleteManager,
    private val autoSyncManager: AutoSyncManager,
    private val connectivityManager: ConnectivityManager,
    private val accountPushControllerFactory: AccountPushControllerFactory,
    private val folderRepository: FolderRepository,
    private val backgroundPermissionManager: BackgroundPermissionManager,
    private val coroutineScope: CoroutineScope = GlobalScope,
    private val coroutineDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
) {
    private val lock = Any()
    private var initializationStarted = false
    private val pushers = mutableMapOf<String, AccountPushController>()

    private val pushEnabledCollectorJobs = mutableMapOf<String, Job>()

    private val autoSyncListener = AutoSyncListener(::onAutoSyncChanged)
    private val accountsChangeListener = AccountsChangeListener(::onAccountsChanged)
    private val connectivityChangeListener = object : ConnectivityChangeListener {
        override fun onConnectivityChanged() = this@PushController.onConnectivityChanged()
        override fun onConnectivityLost() = this@PushController.onConnectivityLost()
    }

    /**
     * Initialize [PushController].
     *
     * Only call this method in situations where starting a foreground service is allowed.
     * See https://developer.android.com/about/versions/12/foreground-services
     */
    fun init() {
        synchronized(lock) {
            if (initializationStarted) {
                return
            }
            initializationStarted = true
        }

        coroutineScope.launch(coroutineDispatcher) {
            initInBackground()
        }
    }

    private fun disablePush() {
        Timber.v("PushController.disablePush()")

        try {
            accountManager.removeOnAccountsChangeListener(accountsChangeListener)
            for (account in accountManager.getAccounts()) {
                folderRepository.setPushDisabled(account)
            }
        } finally {
            accountManager.addOnAccountsChangeListener(accountsChangeListener)
        }
    }

    private fun checkBackgroundPermissionRevoked() {
        if (!backgroundPermissionManager.canRunBackgroundServices()) {
            // User revoked permission, disable push on all accounts
            disablePush()
        }
    }

    private fun initInBackground() {
        Timber.v("PushController.initInBackground()")

        accountManager.addOnAccountsChangeListener(accountsChangeListener)
        listenForBackgroundSyncChanges()
        backendManager.addListener(::onBackendChanged)

        updatePushers()
    }

    private fun listenForBackgroundSyncChanges() {
        generalSettingsManager.getSettingsFlow()
            .map { it.backgroundSync }
            .distinctUntilChanged()
            .onEach {
                launchUpdatePushers()
            }
            .launchIn(coroutineScope)
    }

    private fun onAccountsChanged() {
        launchUpdatePushers()
    }

    private fun onAutoSyncChanged() {
        launchUpdatePushers()
    }

    private fun onConnectivityChanged() {
        coroutineScope.launch(coroutineDispatcher) {
            synchronized(lock) {
                for (accountPushController in pushers.values) {
                    accountPushController.reconnect()
                }
            }

            updatePushers()
        }
    }

    private fun onConnectivityLost() {
        launchUpdatePushers()
    }

    private fun onBackendChanged(account: Account) {
        coroutineScope.launch(coroutineDispatcher) {
            val accountPushController = synchronized(lock) {
                pushers.remove(account.uuid)
            }

            accountPushController?.stop()
            updatePushers()
        }
    }

    private fun launchUpdatePushers() {
        coroutineScope.launch(coroutineDispatcher) {
            updatePushers()
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun updatePushers() {
        Timber.v("PushController.updatePushers()")

        checkBackgroundPermissionRevoked()

        val generalSettings = generalSettingsManager.getSettings()

        val backgroundSyncDisabledViaSystem = autoSyncManager.isAutoSyncDisabled
        val backgroundSyncDisabledInApp = generalSettings.backgroundSync == BackgroundSync.NEVER
        val networkNotAvailable = !connectivityManager.isNetworkAvailable()
        val realPushAccounts = getPushAccounts()

        val shouldDisablePushAccounts = backgroundSyncDisabledViaSystem ||
            backgroundSyncDisabledInApp ||
            networkNotAvailable

        val pushAccounts = if (shouldDisablePushAccounts) {
            emptyList()
        } else {
            realPushAccounts
        }
        val pushAccountUuids = pushAccounts.map { it.uuid }

        val arePushersActive = synchronized(lock) {
            val currentPushAccountUuids = pushers.keys
            val startPushAccountUuids = pushAccountUuids - currentPushAccountUuids
            val stopPushAccountUuids = currentPushAccountUuids - pushAccountUuids

            if (stopPushAccountUuids.isNotEmpty()) {
                Timber.v("..Stopping PushController for accounts: %s", stopPushAccountUuids)
                for (accountUuid in stopPushAccountUuids) {
                    val accountPushController = pushers.remove(accountUuid)
                    accountPushController?.stop()
                }
            }

            if (startPushAccountUuids.isNotEmpty()) {
                Timber.v("..Starting PushController for accounts: %s", startPushAccountUuids)
                for (accountUuid in startPushAccountUuids) {
                    val account = accountManager.getAccount(accountUuid) ?: error("Account not found: $accountUuid")
                    pushers[accountUuid] = accountPushControllerFactory.create(account).also { accountPushController ->
                        accountPushController.start()
                    }
                }
            }

            Timber.v("..Running PushControllers: %s", pushers.keys)

            pushers.isNotEmpty()
        }

        updatePushEnabledListeners(getPushCapableAccounts())

        when {
            realPushAccounts.isEmpty() -> {
                stopServices()
            }

            backgroundSyncDisabledViaSystem -> {
                startServices()
            }

            networkNotAvailable -> {
                startServices()
            }

            arePushersActive -> {
                startServices()
            }

            else -> {
                stopServices()
            }
        }
    }

    private fun getPushCapableAccounts(): Set<Account> {
        return accountManager.getAccounts()
            .asSequence()
            .filter { account -> backendManager.getBackend(account).isPushCapable }
            .toSet()
    }

    private fun getPushAccounts(): Set<Account> {
        return getPushCapableAccounts()
            .asSequence()
            .filter { account -> folderRepository.hasPushEnabledFolder(account) }
            .toSet()
    }

    private fun startServices() {
        pushServiceManager.start()
        bootCompleteManager.enableReceiver()
        registerAutoSyncListener()
        registerConnectivityChangeListener()
        connectivityManager.start()
    }

    private fun stopServices() {
        pushServiceManager.stop()
        bootCompleteManager.disableReceiver()
        autoSyncManager.unregisterListener()
        unregisterConnectivityChangeListener()
        connectivityManager.stop()
    }

    private fun registerAutoSyncListener() {
        if (autoSyncManager.respectSystemAutoSync) {
            autoSyncManager.registerListener(autoSyncListener)
        } else {
            autoSyncManager.unregisterListener()
        }
    }

    private fun registerConnectivityChangeListener() {
        connectivityManager.addListener(connectivityChangeListener)
    }

    private fun unregisterConnectivityChangeListener() {
        connectivityManager.removeListener(connectivityChangeListener)
    }

    private fun updatePushEnabledListeners(accounts: Set<Account>) {
        synchronized(lock) {
            // Stop listening to push enabled changes in accounts we no longer monitor
            val accountUuids = accounts.mapToSet { it.uuid }
            val iterator = pushEnabledCollectorJobs.iterator()
            while (iterator.hasNext()) {
                val (accountUuid, collectorJob) = iterator.next()
                if (accountUuid !in accountUuids) {
                    Timber.v("..Stopping to listen for push enabled changes in account: %s", accountUuid)
                    iterator.remove()
                    collectorJob.cancel()
                }
            }

            // Start "push enabled" state collector jobs for new accounts to monitor
            val newAccounts = accounts.filterNot { account -> pushEnabledCollectorJobs.containsKey(account.uuid) }
            for (account in newAccounts) {
                pushEnabledCollectorJobs[account.uuid] = coroutineScope.launch(coroutineDispatcher) {
                    Timber.v("..Starting to listen for push enabled changes in account: %s", account.uuid)
                    folderRepository.hasPushEnabledFolderFlow(account)
                        .collect {
                            updatePushers()
                        }
                }
            }
        }
    }
}
