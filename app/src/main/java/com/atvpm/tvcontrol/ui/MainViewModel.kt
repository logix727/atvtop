package com.atvpm.tvcontrol.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atvpm.tvcontrol.companion.CompanionWebServer
import com.atvpm.tvcontrol.companion.QrCodeGenerator
import com.atvpm.tvcontrol.core.AdbConnectionState
import com.atvpm.tvcontrol.core.PrivilegeManager
import com.atvpm.tvcontrol.domain.AppManagerController
import com.atvpm.tvcontrol.domain.AppManagerSnapshot
import com.atvpm.tvcontrol.domain.LocalApkFileEntry
import com.atvpm.tvcontrol.domain.ManagedAppEntry
import com.atvpm.tvcontrol.domain.MemorySnapshot
import com.atvpm.tvcontrol.domain.ProcessController
import com.atvpm.tvcontrol.domain.RebootController
import com.atvpm.tvcontrol.domain.RebootMode
import com.atvpm.tvcontrol.domain.RunningAppEntry
import com.atvpm.tvcontrol.domain.SubProcessEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MainScreenTab(val label: String, val icon: String) {
    TASK_MANAGER("Tasks & RAM", "⚡"),
    SMART_DEBLOATER("Smart Debloater", "📦"),
    APK_FILES("Sideload APKs", "📥"),
    PHONE_COMPANION("Phone Bridge", "📱"),
    POWER_MENU("Power Center", "🔌"),
    APP_MANAGER("App Manager", "📦");
}

enum class AppListFilter(val label: String) {
    RUNNING_APPS("Running Apps"),
    ALL_ACTIVE("All Processes"),
    DISABLED_APPS("Disabled"),
}

enum class AppManagerTab(val label: String) {
    USER_APPS("User Apps"),
    BLOATWARE_APPS("Bloatware (Safe)"),
    CORE_SYSTEM_APPS("Core OS (Locked)"),
    DISABLED_OR_REMOVED("Disabled / Removed"),
    LOCAL_APK_FILES("Install APK Files"),
}

enum class AppSortOrder(val label: String) {
    SIZE_DESC("Size ▼"),
    NAME_ASC("Name A–Z"),
}

data class RunningAppsUiState(
    val loading: Boolean = false,
    val allRunning: List<RunningAppEntry> = emptyList(),
    val disabledApps: List<RunningAppEntry> = emptyList(),
    val visibleApps: List<RunningAppEntry> = emptyList(),
    val killableApps: List<RunningAppEntry> = emptyList(),
    val killableRamMb: Int = 0,
    val selectedPackages: Set<String> = emptySet(),
    val memory: MemorySnapshot? = null,
    val ramHistory: List<Float> = emptyList(),
    val filter: AppListFilter = AppListFilter.RUNNING_APPS,
    val busyPackage: String? = null,
    val busyPid: Int? = null,
    val batchKilling: Boolean = false,
    val clearingCache: Boolean = false,
    val executingReboot: RebootMode? = null,
    val statusMessage: String? = null,
    val statusSuccess: Boolean = true,
)

data class AppManagerUiState(
    val loading: Boolean = false,
    val tab: AppManagerTab = AppManagerTab.USER_APPS,
    val sortOrder: AppSortOrder = AppSortOrder.SIZE_DESC,
    val snapshot: AppManagerSnapshot = AppManagerSnapshot(),
    val visibleApps: List<ManagedAppEntry> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val busyTarget: String? = null,
    val batchWorking: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    val privilegeManager = PrivilegeManager(appContext)
    private val processController = ProcessController(appContext, privilegeManager)
    private val appManagerController = AppManagerController(appContext, privilegeManager, processController)
    private val rebootController = RebootController(appContext, privilegeManager)

    val adbState: StateFlow<AdbConnectionState> = privilegeManager.state

    private val _activeScreenTab = MutableStateFlow(MainScreenTab.TASK_MANAGER)
    val activeScreenTab: StateFlow<MainScreenTab> = _activeScreenTab

    private val _loading = MutableStateFlow(false)
    private val _allRunning = MutableStateFlow<List<RunningAppEntry>>(emptyList())
    private val _disabledApps = MutableStateFlow<List<RunningAppEntry>>(emptyList())
    private val _memory = MutableStateFlow<MemorySnapshot?>(null)
    private val _ramHistory = MutableStateFlow<List<Float>>(emptyList())
    private val _filter = MutableStateFlow(AppListFilter.RUNNING_APPS)
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val _busyPackage = MutableStateFlow<String?>(null)
    private val _busyPid = MutableStateFlow<Int?>(null)
    private val _batchKilling = MutableStateFlow(false)
    private val _clearingCache = MutableStateFlow(false)
    private val _executingReboot = MutableStateFlow<RebootMode?>(null)
    private val _statusBanner = MutableStateFlow<Pair<String, Boolean>?>(null)
    private val _manualTutorialOverride = MutableStateFlow<Boolean?>(null)

    // App Manager / Uninstaller / APK Installer state
    private val _appMgrLoading = MutableStateFlow(false)
    private val _appMgrTab = MutableStateFlow(AppManagerTab.USER_APPS)
    private val _appMgrSort = MutableStateFlow(AppSortOrder.SIZE_DESC)
    private val _appMgrSnapshot = MutableStateFlow(AppManagerSnapshot())
    private val _appMgrSelected = MutableStateFlow<Set<String>>(emptySet())
    private val _appMgrBusyTarget = MutableStateFlow<String?>(null)
    private val _appMgrBatchWorking = MutableStateFlow(false)

    // Companion Web Bridge & QR Code state
    private val _companionUrl = MutableStateFlow<String?>(null)
    val companionUrl: StateFlow<String?> = _companionUrl

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap

    val companionServer = CompanionWebServer(
        context = appContext,
        onApkUploaded = { file ->
            viewModelScope.launch {
                refreshAppManager()
                _statusBanner.value = "Received APK from Phone: ${file.name}" to true
                installApkFile(
                    LocalApkFileEntry(
                        filePath = file.absolutePath,
                        fileName = file.name,
                        appLabel = file.nameWithoutExtension,
                        packageName = null,
                        versionName = null,
                        sizeMb = file.length() / (1024f * 1024f),
                        icon = null,
                    ),
                )
            }
        },
        onActionTriggered = { action ->
            when (action) {
                "kill-all" -> {
                    killAllRunningApps()
                    "Killed background apps"
                }
                "reload-launcher" -> {
                    executeReboot(RebootMode.RELOAD_HOME_UI)
                    "Reloaded launcher"
                }
                "restart" -> {
                    executeReboot(RebootMode.STANDARD_RESTART)
                    "Rebooting TV..."
                }
                "sleep" -> {
                    executeReboot(RebootMode.SLEEP_STANDBY)
                    "Standby triggered"
                }
                else -> "Action executed"
            }
        },
        getSystemStatusJson = {
            val mem = _memory.value
            val free = (mem?.availableKb ?: 0L) / 1024L
            val total = (mem?.totalKb ?: 0L) / 1024L
            """{"freeRamMb":$free,"totalRamMb":$total,"device":"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}","androidVersion":"${android.os.Build.VERSION.RELEASE}"}"""
        },
    )

    /**
     * Always opens directly to the Main Task Manager screen on launch.
     * The optional ADB / Permissions guide is only shown if the user clicks "ADB Setup" in the top bar.
     */
    val showSetupTutorial: StateFlow<Boolean> = combine(
        adbState,
        _manualTutorialOverride,
    ) { _, manualOverride ->
        manualOverride == true
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    val uiState: StateFlow<RunningAppsUiState> = combine(
        _loading,
        _allRunning,
        _disabledApps,
        _memory,
        _ramHistory,
        _filter,
        _selectedPackages,
        _busyPackage,
        _busyPid,
        _batchKilling,
        _clearingCache,
        _executingReboot,
        _statusBanner,
    ) { values ->
        val loading = values[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val all = values[1] as List<RunningAppEntry>
        @Suppress("UNCHECKED_CAST")
        val disabled = values[2] as List<RunningAppEntry>
        val mem = values[3] as MemorySnapshot?
        @Suppress("UNCHECKED_CAST")
        val history = values[4] as List<Float>
        val filter = values[5] as AppListFilter
        @Suppress("UNCHECKED_CAST")
        val selected = values[6] as Set<String>
        val busyPkg = values[7] as String?
        val busyPid = values[8] as Int?
        val batch = values[9] as Boolean
        val clearing = values[10] as Boolean
        val rebooting = values[11] as RebootMode?
        @Suppress("UNCHECKED_CAST")
        val banner = values[12] as Pair<String, Boolean>?

        val killable = all.filter { it.isKillableExtra }
        val killableRamMb = (killable.sumOf { it.totalRssKb } / 1024L).toInt()

        val visible = when (filter) {
            AppListFilter.RUNNING_APPS -> killable
            AppListFilter.ALL_ACTIVE -> all
            AppListFilter.DISABLED_APPS -> disabled
        }

        val validSelected = selected.intersect(all.map { it.packageName }.toSet())

        RunningAppsUiState(
            loading = loading,
            allRunning = all,
            disabledApps = disabled,
            visibleApps = visible,
            killableApps = killable,
            killableRamMb = killableRamMb,
            selectedPackages = validSelected,
            memory = mem,
            ramHistory = history,
            filter = filter,
            busyPackage = busyPkg,
            busyPid = busyPid,
            batchKilling = batch,
            clearingCache = clearing,
            executingReboot = rebooting,
            statusMessage = banner?.first,
            statusSuccess = banner?.second ?: true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunningAppsUiState(loading = true))

    val appManagerState: StateFlow<AppManagerUiState> = combine(
        _appMgrLoading,
        _appMgrTab,
        _appMgrSort,
        _appMgrSnapshot,
        _appMgrSelected,
        _appMgrBusyTarget,
        _appMgrBatchWorking,
    ) { values ->
        val loading = values[0] as Boolean
        val tab = values[1] as AppManagerTab
        val sort = values[2] as AppSortOrder
        val snap = values[3] as AppManagerSnapshot
        @Suppress("UNCHECKED_CAST")
        val selected = values[4] as Set<String>
        val busy = values[5] as String?
        val batch = values[6] as Boolean

        val rawVisible = when (tab) {
            AppManagerTab.USER_APPS -> snap.userApps
            AppManagerTab.BLOATWARE_APPS -> snap.bloatwareApps
            AppManagerTab.CORE_SYSTEM_APPS -> snap.coreSystemApps
            AppManagerTab.DISABLED_OR_REMOVED -> snap.disabledOrRemovedApps
            AppManagerTab.LOCAL_APK_FILES -> emptyList()
        }
        val visible = when (sort) {
            AppSortOrder.SIZE_DESC -> rawVisible.sortedWith(
                compareByDescending<ManagedAppEntry> { it.apkSizeMb }
                    .thenBy { it.label.lowercase() },
            )
            AppSortOrder.NAME_ASC -> rawVisible.sortedBy { it.label.lowercase() }
        }
        val validSelected = selected.intersect(visible.map { it.packageName }.toSet())

        AppManagerUiState(
            loading = loading,
            tab = tab,
            sortOrder = sort,
            snapshot = snap,
            visibleApps = visible,
            selectedPackages = validSelected,
            busyTarget = busy,
            batchWorking = batch,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppManagerUiState(loading = true))

    private var liveLoopJob: Job? = null

    init {
        processController.readLocalProcMemInfo()?.let { updateMemoryAndHistory(it) }
        probeAndRefresh()
        startLiveRefreshLoop()
    }

    fun setScreenTab(tab: MainScreenTab) {
        _activeScreenTab.value = tab
        when (tab) {
            MainScreenTab.SMART_DEBLOATER -> {
                _appMgrTab.value = AppManagerTab.BLOATWARE_APPS
                refreshAppManager()
            }
            MainScreenTab.APK_FILES -> {
                _appMgrTab.value = AppManagerTab.LOCAL_APK_FILES
                refreshAppManager()
            }
            MainScreenTab.APP_MANAGER -> {
                refreshAppManager()
            }
            MainScreenTab.PHONE_COMPANION -> {
                ensureCompanionRunning()
            }
            else -> {
                refreshRunningApps()
            }
        }
    }

    fun ensureCompanionRunning() {
        companionServer.start(viewModelScope)
        val url = companionServer.serverUrl
        _companionUrl.value = url
        if (url != null) {
            _qrBitmap.value = QrCodeGenerator.createQrBitmap(url, 512)
        }
    }

    private fun updateMemoryAndHistory(snap: MemorySnapshot?) {
        _memory.value = snap
        if (snap != null && snap.totalKb > 0L) {
            val current = _ramHistory.value
            val next = (current + snap.usageFraction).takeLast(32)
            _ramHistory.value = if (next.size == 1) {
                List(4) { snap.usageFraction }
            } else {
                next
            }
        }
    }

    fun onHostResumed() {
        probeAndRefresh()
        startLiveRefreshLoop()
        ensureCompanionRunning()
    }

    fun onHostStopped() {
        liveLoopJob?.cancel()
        liveLoopJob = null
        companionServer.stop()
    }

    fun killAllAndExit(onExit: () -> Unit) {
        val targets = uiState.value.killableApps
        viewModelScope.launch {
            _batchKilling.value = true
            if (targets.isNotEmpty()) {
                processController.killBatchApps(targets)
            }
            processController.clearCache()
            _batchKilling.value = false
            onExit()
        }
    }

    fun probeAndRefresh() {
        viewModelScope.launch {
            privilegeManager.probe()
            refreshRunningApps()
            if (_activeScreenTab.value == MainScreenTab.APP_MANAGER) {
                refreshAppManager()
            }
        }
    }

    fun connectAdbFromTutorial() {
        _manualTutorialOverride.value = null
        probeAndRefresh()
    }

    fun openSetupTutorial() {
        _manualTutorialOverride.value = true
    }

    fun dismissSetupTutorial() {
        _manualTutorialOverride.value = false
    }

    fun refreshRunningApps() {
        viewModelScope.launch {
            _loading.value = true
            val snap = processController.fetchRunningApps()
            _allRunning.value = snap.apps
            _disabledApps.value = snap.disabledApps
            updateMemoryAndHistory(snap.memory)
            _loading.value = false
        }
    }

    fun refreshAppManager() {
        viewModelScope.launch {
            _appMgrLoading.value = true
            _appMgrSnapshot.value = appManagerController.refreshSnapshot()
            _appMgrLoading.value = false
        }
    }

    private fun startLiveRefreshLoop() {
        liveLoopJob?.cancel()
        liveLoopJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                if (!adbState.value.connected && !adbState.value.probing) {
                    privilegeManager.probe()
                }
                if (_activeScreenTab.value == MainScreenTab.TASK_MANAGER &&
                    !_loading.value && !_batchKilling.value && _busyPackage.value == null && _busyPid.value == null
                ) {
                    val snap = processController.fetchRunningApps()
                    _allRunning.value = snap.apps
                    _disabledApps.value = snap.disabledApps
                    updateMemoryAndHistory(snap.memory)
                }
            }
        }
    }

    fun openApp(packageName: String, label: String) {
        val res = processController.openApp(packageName, label)
        _statusBanner.value = res.message to res.success
    }

    fun setFilter(filter: AppListFilter) {
        _filter.value = filter
    }

    fun toggleSelect(packageName: String) {
        val current = _selectedPackages.value
        _selectedPackages.value = if (packageName in current) {
            current - packageName
        } else {
            current + packageName
        }
    }

    fun selectAllVisible() {
        val pkgs = uiState.value.visibleApps.filter { !it.isDisabled && !it.isPinned }.map { it.packageName }.toSet()
        _selectedPackages.value = pkgs
    }

    fun clearSelection() {
        _selectedPackages.value = emptySet()
    }

    fun togglePin(app: RunningAppEntry) {
        val nowPinned = processController.togglePinPackage(app.packageName)
        _statusBanner.value = if (nowPinned) {
            "Pinned ${app.label} — protected from Kill All" to true
        } else {
            "Unpinned ${app.label}" to true
        }
        refreshRunningApps()
    }

    fun clearAllCaches() {
        viewModelScope.launch {
            _clearingCache.value = true
            val res = processController.clearAllAppCaches()
            _statusBanner.value = res.message to res.success
            _clearingCache.value = false
            refreshRunningApps()
        }
    }

    fun killEachApp(app: RunningAppEntry) {
        viewModelScope.launch {
            _busyPackage.value = app.packageName
            val res = processController.killSingleApp(app)
            _statusBanner.value = res.message to res.success
            _busyPackage.value = null
            refreshRunningApps()
        }
    }

    fun killSinglePid(app: RunningAppEntry, sub: SubProcessEntry) {
        viewModelScope.launch {
            _busyPid.value = sub.pid
            val res = processController.killSinglePid(app, sub)
            _statusBanner.value = res.message to res.success
            _busyPid.value = null
            refreshRunningApps()
        }
    }

    fun disableBloatApp(app: RunningAppEntry) {
        viewModelScope.launch {
            _busyPackage.value = app.packageName
            val res = processController.disablePackage(app)
            _statusBanner.value = res.message to res.success
            _busyPackage.value = null
            refreshRunningApps()
        }
    }

    fun enableBloatApp(app: RunningAppEntry) {
        viewModelScope.launch {
            _busyPackage.value = app.packageName
            val res = processController.enablePackage(app)
            _statusBanner.value = res.message to res.success
            _busyPackage.value = null
            refreshRunningApps()
        }
    }

    fun killSelectedApps() {
        val selected = uiState.value.selectedPackages
        if (selected.isEmpty()) return
        val targets = _allRunning.value.filter { it.packageName in selected }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _batchKilling.value = true
            val res = processController.killBatchApps(targets)
            _statusBanner.value = res.message to res.success
            _selectedPackages.value = emptySet()
            _batchKilling.value = false
            refreshRunningApps()
        }
    }

    fun killAllRunningApps() {
        val targets = uiState.value.killableApps
        if (targets.isEmpty()) {
            _statusBanner.value = "All background apps are already stopped!" to true
            return
        }

        viewModelScope.launch {
            _batchKilling.value = true
            val res = processController.killBatchApps(targets)
            _statusBanner.value = res.message to res.success
            _selectedPackages.value = emptySet()
            _batchKilling.value = false
            refreshRunningApps()
        }
    }

    // --- App Manager (Uninstall / Disable / Restore / Local APK Installer) Actions ---

    fun setAppManagerTab(tab: AppManagerTab) {
        _appMgrTab.value = tab
        _appMgrSelected.value = emptySet()
    }

    fun toggleAppManagerSortOrder() {
        _appMgrSort.value = when (_appMgrSort.value) {
            AppSortOrder.SIZE_DESC -> AppSortOrder.NAME_ASC
            AppSortOrder.NAME_ASC -> AppSortOrder.SIZE_DESC
        }
    }

    fun toggleAppManagerSelect(packageName: String) {
        val current = _appMgrSelected.value
        _appMgrSelected.value = if (packageName in current) {
            current - packageName
        } else {
            current + packageName
        }
    }

    fun selectAllAppManagerVisible() {
        val pkgs = appManagerState.value.visibleApps
            .filter { !it.isCriticalProtected }
            .map { it.packageName }
            .toSet()
        _appMgrSelected.value = pkgs
    }

    fun clearAppManagerSelection() {
        _appMgrSelected.value = emptySet()
    }

    fun uninstallSingleManagedApp(app: ManagedAppEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = app.packageName
            val res = appManagerController.uninstallApp(app)
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun disableSingleManagedApp(app: ManagedAppEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = app.packageName
            val res = appManagerController.batchDisableApps(listOf(app))
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun restoreOrEnableSingleManagedApp(app: ManagedAppEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = app.packageName
            val res = appManagerController.batchRestoreOrEnableApps(listOf(app))
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun suspendApp(app: ManagedAppEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = app.packageName
            val res = appManagerController.suspendApp(app.packageName)
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun unsuspendApp(app: ManagedAppEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = app.packageName
            val res = appManagerController.unsuspendApp(app.packageName)
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun restrictBackground(app: ManagedAppEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = app.packageName
            val res = appManagerController.restrictBackgroundAppOps(app.packageName)
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun batchUninstallManagedApps() {
        val selected = appManagerState.value.selectedPackages
        if (selected.isEmpty()) return
        val targets = appManagerState.value.visibleApps.filter { it.packageName in selected }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _appMgrBatchWorking.value = true
            val res = appManagerController.batchUninstallApps(targets)
            _statusBanner.value = res.message to res.success
            _appMgrSelected.value = emptySet()
            _appMgrBatchWorking.value = false
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun batchDisableManagedApps() {
        val selected = appManagerState.value.selectedPackages
        if (selected.isEmpty()) return
        val targets = appManagerState.value.visibleApps.filter { it.packageName in selected }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _appMgrBatchWorking.value = true
            val res = appManagerController.batchDisableApps(targets)
            _statusBanner.value = res.message to res.success
            _appMgrSelected.value = emptySet()
            _appMgrBatchWorking.value = false
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun batchRestoreManagedApps() {
        val selected = appManagerState.value.selectedPackages
        val targets = if (selected.isNotEmpty()) {
            appManagerState.value.visibleApps.filter { it.packageName in selected }
        } else {
            appManagerState.value.visibleApps
        }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _appMgrBatchWorking.value = true
            val res = appManagerController.batchRestoreOrEnableApps(targets)
            _statusBanner.value = res.message to res.success
            _appMgrSelected.value = emptySet()
            _appMgrBatchWorking.value = false
            refreshAppManager()
            refreshRunningApps()
        }
    }

    fun installApkFile(apk: LocalApkFileEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = apk.filePath
            val res = appManagerController.installLocalApk(apk)
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
        }
    }

    fun deleteApkFile(apk: LocalApkFileEntry) {
        viewModelScope.launch {
            _appMgrBusyTarget.value = apk.filePath
            val res = appManagerController.deleteLocalApk(apk)
            _statusBanner.value = res.message to res.success
            _appMgrBusyTarget.value = null
            refreshAppManager()
        }
    }

    fun executeReboot(mode: RebootMode) {
        viewModelScope.launch {
            _executingReboot.value = mode
            val res = rebootController.executeReboot(mode)
            _statusBanner.value = res.message to res.success
            _executingReboot.value = null
        }
    }

    fun openAboutSettings() {
        privilegeManager.openAboutSettings()
    }

    fun openDeveloperOptions() {
        privilegeManager.openDeveloperSettings()
    }

    fun openAccessibilitySettings() {
        privilegeManager.openAccessibilitySettings()
    }

    override fun onCleared() {
        liveLoopJob?.cancel()
        super.onCleared()
    }
}
