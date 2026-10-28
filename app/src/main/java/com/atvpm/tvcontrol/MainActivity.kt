package com.atvpm.tvcontrol

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.atvpm.tvcontrol.service.AccessibilityGate
import com.atvpm.tvcontrol.service.ForceStopAccessibilityService
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atvpm.tvcontrol.core.AdbConnectionState
import com.atvpm.tvcontrol.domain.DebloatSafetyLevel
import com.atvpm.tvcontrol.domain.LocalApkFileEntry
import com.atvpm.tvcontrol.domain.ManagedAppEntry
import com.atvpm.tvcontrol.domain.RebootCategory
import com.atvpm.tvcontrol.domain.RebootMode
import com.atvpm.tvcontrol.domain.RunningAppEntry
import com.atvpm.tvcontrol.domain.SubProcessEntry
import com.atvpm.tvcontrol.ui.AppListFilter
import com.atvpm.tvcontrol.ui.AppManagerTab
import com.atvpm.tvcontrol.ui.AppManagerUiState
import com.atvpm.tvcontrol.ui.MainScreenTab
import com.atvpm.tvcontrol.ui.MainViewModel
import com.atvpm.tvcontrol.ui.PhoneCompanionScreen
import com.atvpm.tvcontrol.ui.RunningAppsUiState
import com.atvpm.tvcontrol.ui.components.AppIconBox
import com.atvpm.tvcontrol.ui.components.StatusPill
import com.atvpm.tvcontrol.ui.components.TvButton
import com.atvpm.tvcontrol.ui.components.TvFilterChip
import com.atvpm.tvcontrol.ui.components.TvFocusableSurface
import com.atvpm.tvcontrol.ui.theme.TvColors
import com.atvpm.tvcontrol.ui.theme.TvControlTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val baseDensity = LocalDensity.current
            // Calibrate density for large 55"-75"+ Android TV screens so widescreen space is used
            // efficiently and no labels or buttons get truncated with '...'
            val tvWidescreenDensity = remember(baseDensity) {
                Density(
                    density = baseDensity.density * 0.84f,
                    fontScale = 0.94f,
                )
            }
            CompositionLocalProvider(LocalDensity provides tvWidescreenDensity) {
                TvControlTheme {
                    MainTaskManagerScreen(
                        viewModel = viewModel,
                        onCloseApp = ::closeAndFreeApp,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Ignore when launching a child Settings / Uninstaller / PackageInstaller / Accessibility intent
        if (AccessibilityGate.isExternalActivityLaunchRecent()) {
            viewModel.onHostStopped()
            return
        }
        // User pressed HOME — close and free memory without revoking Accessibility service
        closeAndFreeApp()
    }

    override fun onStop() {
        viewModel.onHostStopped()
        super.onStop()
        if (isFinishing) {
            closeAndFreeApp()
        }
    }

    override fun onDestroy() {
        viewModel.onHostStopped()
        super.onDestroy()
        if (!AccessibilityGate.isRunning()) {
            Process.killProcess(Process.myPid())
        }
    }

    /**
     * Closes the activity, stops background polling, removes it from Recents,
     * and frees memory while preserving the user's Accessibility Service authorization.
     */
    private fun closeAndFreeApp() {
        viewModel.onHostStopped()
        finishAndRemoveTask()
        if (!AccessibilityGate.isRunning()) {
            window.decorView.postDelayed({
                Process.killProcess(Process.myPid())
            }, 80L)
        }
    }
}

@Composable
private fun MainTaskManagerScreen(
    viewModel: MainViewModel,
    onCloseApp: () -> Unit,
) {
    val adbState by viewModel.adbState.collectAsState()
    val activeScreenTab by viewModel.activeScreenTab.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val appMgrState by viewModel.appManagerState.collectAsState()
    val showSetupTutorial by viewModel.showSetupTutorial.collectAsState()
    var openRebootDrawer by remember { mutableStateOf<RebootCategory?>(null) }
    var expandedPackages by remember { mutableStateOf(setOf<String>()) }

    val companionUrl by viewModel.companionUrl.collectAsState()
    val qrBitmap by viewModel.qrBitmap.collectAsState()

    BackHandler {
        if (openRebootDrawer != null) {
            openRebootDrawer = null
        } else if (showSetupTutorial) {
            viewModel.dismissSetupTutorial()
        } else if (activeScreenTab != MainScreenTab.TASK_MANAGER) {
            viewModel.setScreenTab(MainScreenTab.TASK_MANAGER)
        } else {
            onCloseApp()
        }
    }

    if (showSetupTutorial) {
        AdbSetupTutorialScreen(
            adbState = adbState,
            onOpenAboutSettings = { viewModel.openAboutSettings() },
            onOpenDeveloperOptions = { viewModel.openDeveloperOptions() },
            onConnectAdb = { viewModel.connectAdbFromTutorial() },
            onOpenAccessibility = { viewModel.openAccessibilitySettings() },
            onContinueToMain = { viewModel.dismissSetupTutorial() },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. Top Navigation & Unified Power Bar
        TopHeaderBar(
            adbState = adbState,
            activeScreenTab = activeScreenTab,
            uiState = uiState,
            isPowerMenuOpen = openRebootDrawer != null,
            onSelectScreenTab = { viewModel.setScreenTab(it) },
            onTogglePowerMenu = {
                openRebootDrawer = if (openRebootDrawer != null) null else RebootCategory.REGULAR_REBOOT
            },
            onOpenSetupGuide = { viewModel.openSetupTutorial() },
            onExit = onCloseApp,
        )

        // 1b. Expandable Unified Power & Reboot Options Drawer
        if (openRebootDrawer != null) {
            UnifiedPowerAndRebootPanel(
                adbConnected = adbState.connected,
                executingReboot = uiState.executingReboot,
                onSelectRebootMode = { viewModel.executeReboot(it) },
                onClose = { openRebootDrawer = null },
            )
        }

        when (activeScreenTab) {
            MainScreenTab.PHONE_COMPANION -> {
                PhoneCompanionScreen(
                    companionUrl = companionUrl,
                    qrBitmap = qrBitmap,
                    onRefreshBridge = { viewModel.ensureCompanionRunning() },
                    onKillAll = { viewModel.killAllRunningApps() },
                    onReloadLauncher = { viewModel.executeReboot(RebootMode.RELOAD_HOME_UI) },
                    modifier = Modifier.weight(1f),
                )
            }
            MainScreenTab.SMART_DEBLOATER, MainScreenTab.APK_FILES, MainScreenTab.APP_MANAGER -> {
                // Screen 2: Install / Remove Apps (User + System App Uninstaller, Batch Disable/Restore & Local APK Installer)
                AppUninstallerAndInstallerScreen(
                    adbState = adbState,
                    state = appMgrState,
                    onSelectTab = { viewModel.setAppManagerTab(it) },
                    onToggleSortOrder = { viewModel.toggleAppManagerSortOrder() },
                    onToggleSelect = { viewModel.toggleAppManagerSelect(it) },
                    onSelectAll = { viewModel.selectAllAppManagerVisible() },
                    onClearSelection = { viewModel.clearAppManagerSelection() },
                    onBatchUninstall = { viewModel.batchUninstallManagedApps() },
                    onBatchDisable = { viewModel.batchDisableManagedApps() },
                    onBatchRestore = { viewModel.batchRestoreManagedApps() },
                    onOpenApp = { app -> viewModel.openApp(app.packageName, app.label) },
                    onUninstallSingle = { viewModel.uninstallSingleManagedApp(it) },
                    onDisableSingle = { viewModel.disableSingleManagedApp(it) },
                    onRestoreSingle = { viewModel.restoreOrEnableSingleManagedApp(it) },
                    onInstallApk = { viewModel.installApkFile(it) },
                    onDeleteApk = { viewModel.deleteApkFile(it) },
                    onRefresh = { viewModel.refreshAppManager() },
                    modifier = Modifier.weight(1f),
                )
            }
            MainScreenTab.POWER_MENU -> {
                UnifiedPowerAndRebootPanel(
                    adbConnected = adbState.connected,
                    executingReboot = uiState.executingReboot,
                    onSelectRebootMode = { viewModel.executeReboot(it) },
                    onClose = { viewModel.setScreenTab(MainScreenTab.TASK_MANAGER) },
                )
            }
            MainScreenTab.TASK_MANAGER -> {
                // Screen 1: Task Manager (RAM Telemetry + Live Rolling Graph + 1-Click Kill All + Running Apps & Expandable PIDs)
                RamTelemetryAndActionsCard(
                    uiState = uiState,
                    onKillAll = { viewModel.killAllRunningApps() },
                    onKillAllAndExit = { viewModel.killAllAndExit(onCloseApp) },
                    onClearCache = { viewModel.clearAllCaches() },
                    onKillSelected = { viewModel.killSelectedApps() },
                    onSelectAll = { viewModel.selectAllVisible() },
                    onClearSelection = { viewModel.clearSelection() },
                    onRefresh = { viewModel.refreshRunningApps() },
                )

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = TvColors.Surface,
                border = BorderStroke(1.dp, TvColors.Divider),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Utilitarian Column Header + Integrated Filter Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TvColors.SurfaceElevated.copy(alpha = 0.7f))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (uiState.filter != AppListFilter.DISABLED_APPS) {
                                TvButton(
                                    text = if (uiState.selectedPackages.isNotEmpty()) "Deselect All" else "Select All",
                                    onClick = {
                                        if (uiState.selectedPackages.isNotEmpty()) {
                                            viewModel.clearSelection()
                                        } else {
                                            viewModel.selectAllVisible()
                                        }
                                    },
                                    accentColor = TvColors.FocusRing,
                                )
                            }
                            Text(
                                text = if (uiState.filter == AppListFilter.DISABLED_APPS) {
                                    "DISABLED BLOATWARE PACKAGES (WON'T AUTO-START)"
                                } else {
                                    "APPLICATION  •  CLICK OK TO EXPAND PIDs, THEN DOWN → RIGHT TO KILL PID"
                                },
                                color = TvColors.TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (f in AppListFilter.entries) {
                                val count = when (f) {
                                    AppListFilter.RUNNING_APPS -> uiState.killableApps.size
                                    AppListFilter.ALL_ACTIVE -> uiState.allRunning.size
                                    AppListFilter.DISABLED_APPS -> uiState.disabledApps.size
                                }
                                TvFilterChip(
                                    label = "${f.label} ($count)",
                                    selected = uiState.filter == f,
                                    onClick = { viewModel.setFilter(f) },
                                )
                            }
                        }

                        Text(
                            text = "TYPE   •   STATUS   •   MEMORY / PIDs   •   ACTIONS",
                            color = TvColors.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    if (uiState.visibleApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = when {
                                    uiState.loading -> "Reading active processes and memory..."
                                    uiState.filter == AppListFilter.DISABLED_APPS ->
                                        "No disabled packages yet — click 'Disable' on any bloatware app to prevent it from running."
                                    else -> "No extra background apps running — TV memory is clean."
                                },
                                color = TvColors.TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(
                                items = uiState.visibleApps,
                                key = { it.packageName },
                            ) { app ->
                                val isSelected = app.packageName in uiState.selectedPackages
                                val isExpanded = app.packageName in expandedPackages
                                val isBusy = uiState.busyPackage == app.packageName
                                RunningAppWithSubPidsCard(
                                    app = app,
                                    isSelected = isSelected,
                                    isExpanded = isExpanded,
                                    isBusy = isBusy,
                                    busyPid = uiState.busyPid,
                                    onToggleExpand = {
                                        expandedPackages = if (isExpanded) {
                                            expandedPackages - app.packageName
                                        } else {
                                            expandedPackages + app.packageName
                                        }
                                    },
                                    onToggleSelect = { viewModel.toggleSelect(app.packageName) },
                                    onOpenApp = { viewModel.openApp(app.packageName, app.label) },
                                    onTogglePin = { viewModel.togglePin(app) },
                                    onKillApp = { viewModel.killEachApp(app) },
                                    onKillPid = { sub -> viewModel.killSinglePid(app, sub) },
                                    onDisableApp = { viewModel.disableBloatApp(app) },
                                    onEnableApp = { viewModel.enableBloatApp(app) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun TopHeaderBar(
    adbState: AdbConnectionState,
    activeScreenTab: MainScreenTab,
    uiState: RunningAppsUiState,
    isPowerMenuOpen: Boolean,
    onSelectScreenTab: (MainScreenTab) -> Unit,
    onTogglePowerMenu: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = TvColors.Surface,
        border = BorderStroke(1.dp, TvColors.Divider),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: App Logo + Segmented Tab Switcher + Engine Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // App Logo / Identity
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "⚡",
                        color = TvColors.FocusRing,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "ATV TOP",
                        color = TvColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // Segmented Glass Switcher
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TvColors.SurfaceElevated.copy(alpha = 0.6f))
                        .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val visibleTabs = listOf(
                        MainScreenTab.TASK_MANAGER,
                        MainScreenTab.SMART_DEBLOATER,
                        MainScreenTab.APK_FILES,
                        MainScreenTab.PHONE_COMPANION,
                        MainScreenTab.POWER_MENU,
                    )
                    for (tab in visibleTabs) {
                        val selected = activeScreenTab == tab || (tab == MainScreenTab.SMART_DEBLOATER && activeScreenTab == MainScreenTab.APP_MANAGER)
                        TvFocusableSurface(
                            onClick = { onSelectScreenTab(tab) },
                            selected = selected,
                            shape = RoundedCornerShape(6.dp),
                            containerColor = if (selected) Color(0xFF0E3A5A) else Color.Transparent,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                        ) { isFocused ->
                            Text(
                                text = "${tab.icon} ${tab.label}",
                                color = when {
                                    isFocused -> TvColors.FocusRing
                                    selected -> TvColors.TextPrimary
                                    else -> TvColors.TextSecondary
                                },
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                softWrap = false,
                            )
                        }
                    }
                }

                if (!uiState.statusMessage.isNullOrBlank()) {
                    StatusPill(
                        text = uiState.statusMessage,
                        color = if (uiState.statusSuccess) TvColors.Info else TvColors.Danger,
                        backgroundColor = if (uiState.statusSuccess) TvColors.InfoBg else TvColors.DangerBg,
                    )
                }
            }

            // Right: Optional ADB Setup + Reboot ▼ + Exit
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvButton(
                    text = if (adbState.connected) "ADB Setup" else "Optional ADB Setup",
                    onClick = onOpenSetupGuide,
                    accentColor = if (adbState.connected) TvColors.Success else TvColors.FocusRing,
                )

                // Unified Reboot Dropdown (Contains all sensible reboot & power modes)
                TvButton(
                    text = if (isPowerMenuOpen) "Reboot ▲" else "Reboot ▼",
                    onClick = onTogglePowerMenu,
                    accentColor = TvColors.FocusRing,
                    containerColor = if (isPowerMenuOpen) TvColors.InfoBg else TvColors.SurfaceElevated,
                )

                TvButton(
                    text = "Exit",
                    onClick = onExit,
                    accentColor = TvColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun RamTelemetryAndActionsCard(
    uiState: RunningAppsUiState,
    onKillAll: () -> Unit,
    onKillAllAndExit: () -> Unit,
    onClearCache: () -> Unit,
    onKillSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRefresh: () -> Unit,
) {
    val killAllFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { killAllFocusRequester.requestFocus() }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = TvColors.Surface,
        border = BorderStroke(1.dp, TvColors.Divider),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Left: Full Clean Memory + Storage Readout + Segmented Allocation Bar
            val mem = uiState.memory
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (mem != null && mem.totalMb > 0L) {
                    val pct = (mem.usageFraction * 100).toInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(0.95f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "RAM ${mem.usedMb} / ${mem.totalMb} MB ($pct% Used)",
                            color = TvColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                        )
                        Text(
                            text = "${mem.availableMb} MB Free",
                            color = TvColors.Success,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                        )
                    }

                    val totalMbFloat = mem.totalMb.toFloat().coerceAtLeast(1f)
                    val appFrac = (uiState.killableRamMb / totalMbFloat).coerceIn(0f, mem.usageFraction)
                    val sysFrac = (mem.usageFraction - appFrac).coerceAtLeast(0f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(TvColors.SurfaceElevated),
                    ) {
                        if (sysFrac > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(sysFrac)
                                    .background(TvColors.FocusGlow),
                            )
                        }
                        if (appFrac > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((appFrac / (1f - sysFrac).coerceAtLeast(0.01f)).coerceIn(0f, 1f))
                                    .background(TvColors.AccentGold),
                            )
                        }
                    }

                    val diskText = if (mem.storageTotalMb > 0L) {
                        val freeGb = mem.storageFreeMb / 1024f
                        val totalGb = mem.storageTotalMb / 1024f
                        String.format(Locale.US, " • Disk %.1f / %.1f GB Free", freeGb, totalGb)
                    } else {
                        ""
                    }
                    Text(
                        text = "Apps: ${uiState.killableRamMb} MB • Cache: ${mem.cachedMb} MB$diskText",
                        color = TvColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                    )
                } else {
                    Text(
                        text = "Memory Telemetry",
                        color = TvColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // 1b. Center: Live Rolling RAM Sparkline Graph
            if (uiState.ramHistory.size >= 2) {
                LiveRamSparkline(
                    history = uiState.ramHistory,
                    modifier = Modifier
                        .width(120.dp)
                        .height(42.dp),
                )
            }

            // 2. Right: Focused Action Cluster (Hero Kill All + Kill All & Exit + Clear Cache + Refresh)
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.selectedPackages.isNotEmpty()) {
                        TvButton(
                            text = "Kill Selected (${uiState.selectedPackages.size})",
                            onClick = onKillSelected,
                            enabled = !uiState.batchKilling,
                            accentColor = TvColors.AccentGold,
                            containerColor = TvColors.WarningBg,
                        )
                        TvButton(
                            text = "Clear Selection",
                            onClick = onClearSelection,
                        )
                    } else {
                        val killAllLabel = when {
                            uiState.batchKilling -> "Stopping..."
                            uiState.killableApps.isEmpty() -> "All Stopped (0)"
                            uiState.killableRamMb > 0 -> "Kill All (${uiState.killableApps.size} • ${uiState.killableRamMb} MB)"
                            else -> "Kill All (${uiState.killableApps.size})"
                        }

                        TvButton(
                            text = killAllLabel,
                            onClick = onKillAll,
                            enabled = !uiState.batchKilling && uiState.killableApps.isNotEmpty(),
                            accentColor = TvColors.Danger,
                            containerColor = TvColors.DangerBg,
                            modifier = Modifier.focusRequester(killAllFocusRequester),
                        )

                        TvButton(
                            text = "Kill All & Exit",
                            onClick = onKillAllAndExit,
                            enabled = !uiState.batchKilling,
                            accentColor = TvColors.AccentGold,
                            containerColor = TvColors.WarningBg,
                        )
                    }

                    TvButton(
                        text = if (uiState.clearingCache) "Clearing..." else "Clear Cache",
                        onClick = onClearCache,
                        enabled = !uiState.clearingCache,
                        accentColor = TvColors.FocusRing,
                    )

                    TvButton(
                        text = if (uiState.loading) "Refreshing..." else "Refresh",
                        onClick = onRefresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveRamSparkline(
    history: List<Float>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.Background.copy(alpha = 0.8f))
            .border(1.dp, TvColors.Divider, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pts = history.takeLast(32)
            if (pts.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val stepX = w / (pts.size - 1).coerceAtLeast(1)

            // Midline guide
            drawLine(
                color = TvColors.Divider.copy(alpha = 0.6f),
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1f,
            )

            val strokePath = Path()
            val fillPath = Path()

            pts.forEachIndexed { idx, frac ->
                val x = idx * stepX
                val clamped = frac.coerceIn(0.05f, 0.98f)
                val y = h * (1f - clamped)
                if (idx == 0) {
                    strokePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    strokePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                if (idx == pts.lastIndex) {
                    fillPath.lineTo(x, h)
                    fillPath.close()
                }
            }

            val latest = pts.last()
            val lineColor = when {
                latest >= 0.85f -> TvColors.Danger
                latest >= 0.70f -> TvColors.AccentGold
                else -> TvColors.FocusRing
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.35f), Color.Transparent),
                ),
            )
            drawPath(
                path = strokePath,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun RunningAppWithSubPidsCard(
    app: RunningAppEntry,
    isSelected: Boolean,
    isExpanded: Boolean,
    isBusy: Boolean,
    busyPid: Int?,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpenApp: () -> Unit,
    onTogglePin: () -> Unit,
    onKillApp: () -> Unit,
    onKillPid: (SubProcessEntry) -> Unit,
    onDisableApp: () -> Unit,
    onEnableApp: () -> Unit,
) {
    val hasPids = !app.isDisabled && app.subProcesses.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.SurfaceElevated.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = when {
                    app.isPinned -> TvColors.Success.copy(alpha = 0.7f)
                    isSelected -> TvColors.FocusRing
                    isExpanded -> TvColors.FocusRing.copy(alpha = 0.65f)
                    else -> TvColors.Divider.copy(alpha = 0.7f)
                },
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Parent App Row (Click app row to expand/collapse PIDs!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!app.isDisabled) {
                TvFocusableSurface(
                    onClick = onToggleSelect,
                    selected = isSelected,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) TvColors.FocusRing else Color.Transparent)
                            .border(
                                width = 1.5.dp,
                                color = if (isSelected) TvColors.FocusRing else TvColors.TextMuted,
                                shape = RoundedCornerShape(4.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Text(
                                text = "✓",
                                color = Color(0xFF062E6F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }
            }

            TvFocusableSurface(
                onClick = when {
                    app.isDisabled -> onEnableApp
                    hasPids -> onToggleExpand
                    else -> onToggleSelect
                },
                selected = isExpanded,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        AppIconBox(drawable = app.icon, label = app.label, size = 26.dp)
                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = app.label,
                                    color = TvColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                if (app.roleHint != null) {
                                    Text(
                                        text = "— ${app.roleHint}",
                                        color = TvColors.AccentGold,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Text(
                                text = app.packageName,
                                color = TvColors.TextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Full State Labels (SYSTEM / USER APP, BACKGROUND / FOREGROUND / PINNED / DISABLED)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 1. System App vs User App Origin Badge
                        if (app.isSystemApp) {
                            StatusPill(
                                text = "SYSTEM",
                                color = TvColors.TextSecondary,
                                backgroundColor = TvColors.SurfaceElevated,
                            )
                        } else {
                            StatusPill(
                                text = "USER APP",
                                color = TvColors.FocusRing,
                                backgroundColor = TvColors.InfoBg,
                            )
                        }

                        // 2. Lifecycle State
                        when {
                            app.isDisabled -> StatusPill(
                                text = "DISABLED",
                                color = TvColors.Danger,
                                backgroundColor = TvColors.DangerBg,
                            )
                            app.isPinned -> StatusPill(
                                text = "PINNED (SAFE)",
                                color = TvColors.Success,
                                backgroundColor = TvColors.SuccessBg,
                            )
                            app.isForeground -> StatusPill(
                                text = "FOREGROUND",
                                color = TvColors.Success,
                                backgroundColor = TvColors.SuccessBg,
                            )
                            else -> StatusPill(
                                text = "BACKGROUND",
                                color = TvColors.AccentGold,
                                backgroundColor = TvColors.WarningBg,
                            )
                        }

                        if (!app.isDisabled && (app.totalRssKb > 0L || hasPids)) {
                            val arrow = if (hasPids) {
                                if (isExpanded) " ▲" else " ▼"
                            } else {
                                ""
                            }
                            val pidLabel = if (app.subProcesses.size == 1) {
                                "1 PID$arrow"
                            } else {
                                "${app.subProcesses.size} PIDs$arrow"
                            }
                            StatusPill(
                                text = String.format(Locale.US, "%.0f MB • %s", app.rssMb, pidLabel),
                                color = if (app.rssMb >= 200f) TvColors.Danger else TvColors.Info,
                                backgroundColor = if (app.rssMb >= 200f) TvColors.DangerBg else TvColors.InfoBg,
                            )
                        }
                    }
                }
            }

            if (app.isDisabled) {
                TvButton(
                    text = if (isBusy) "Enabling..." else "Enable App",
                    onClick = onEnableApp,
                    enabled = !isBusy,
                    accentColor = TvColors.Success,
                    containerColor = TvColors.SuccessBg,
                )
            } else {
                // 1. Open Button (Launch background app directly!)
                if (app.canLaunch) {
                    TvButton(
                        text = "Open",
                        onClick = onOpenApp,
                        accentColor = TvColors.FocusRing,
                    )
                }

                // 2. Pin / Protect toggle (excludes app from Kill All)
                TvButton(
                    text = if (app.isPinned) "Unpin" else "Pin",
                    onClick = onTogglePin,
                    accentColor = if (app.isPinned) TvColors.Success else TvColors.TextSecondary,
                    containerColor = if (app.isPinned) TvColors.SuccessBg else TvColors.SurfaceElevated,
                )

                // 3. Disable button for any non-protected app
                if (app.canDisable) {
                    TvButton(
                        text = "Disable",
                        onClick = onDisableApp,
                        enabled = !isBusy,
                        accentColor = TvColors.AccentGold,
                    )
                }

                // 4. Force Stop Button
                TvButton(
                    text = if (isBusy) "Stopping..." else "Force Stop",
                    onClick = onKillApp,
                    enabled = !isBusy,
                    accentColor = TvColors.Danger,
                    containerColor = TvColors.DangerBg,
                )
            }
        }

        // Expandable Sub-Processes:
        // Left/Center is a focusable surface directly below the parent app row so pressing DOWN
        // lands directly on the PID row, and pressing RIGHT moves to 'Kill PID <id>'!
        if (isExpanded && hasPids) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (sub in app.subProcesses) {
                    val isSubBusy = busyPid == sub.pid
                    val killButtonFocus = remember { FocusRequester() }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Focusable PID Info Row (aligned under parent app so D-Pad DOWN lands right here!)
                        TvFocusableSurface(
                            onClick = {
                                runCatching { killButtonFocus.requestFocus() }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            containerColor = TvColors.Background.copy(alpha = 0.85f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) { isPidFocused ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = "└─ PID ${sub.pid}",
                                        color = if (isPidFocused) TvColors.Success else TvColors.FocusRing,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        softWrap = false,
                                    )
                                    Text(
                                        text = sub.shortName,
                                        color = TvColors.TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        softWrap = false,
                                    )
                                    Text(
                                        text = "(${sub.processName})",
                                        color = TvColors.TextMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (isPidFocused) {
                                        Text(
                                            text = "Press RIGHT or OK →",
                                            color = TvColors.FocusRing,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    StatusPill(
                                        text = "${sub.stateDescription} • ${String.format(Locale.US, "%.0f MB", sub.rssMb)}",
                                        color = TvColors.TextSecondary,
                                        backgroundColor = TvColors.Surface,
                                    )
                                }
                            }
                        }

                        // Right: Kill Individual PID Button (Pressing RIGHT from PID row lands here!)
                        TvButton(
                            text = if (isSubBusy) "Killing..." else "Kill PID ${sub.pid}",
                            onClick = { onKillPid(sub) },
                            enabled = !isSubBusy,
                            accentColor = TvColors.Danger,
                            containerColor = TvColors.DangerBg,
                            modifier = Modifier.focusRequester(killButtonFocus),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppUninstallerAndInstallerScreen(
    adbState: AdbConnectionState,
    state: AppManagerUiState,
    onSelectTab: (AppManagerTab) -> Unit,
    onToggleSortOrder: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBatchUninstall: () -> Unit,
    onBatchDisable: () -> Unit,
    onBatchRestore: () -> Unit,
    onOpenApp: (ManagedAppEntry) -> Unit,
    onUninstallSingle: (ManagedAppEntry) -> Unit,
    onDisableSingle: (ManagedAppEntry) -> Unit,
    onRestoreSingle: (ManagedAppEntry) -> Unit,
    onInstallApk: (LocalApkFileEntry) -> Unit,
    onDeleteApk: (LocalApkFileEntry) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Top Action & Filter Bar for App Uninstaller / System Remover / APK Installer
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = TvColors.Surface,
            border = BorderStroke(1.dp, TvColors.Divider),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 4 Category Tabs: User Apps | System & Bloatware | Disabled / Removed | Install APK Files
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (tab in AppManagerTab.entries) {
                        val count = when (tab) {
                            AppManagerTab.USER_APPS -> state.snapshot.userApps.size
                            AppManagerTab.BLOATWARE_APPS -> state.snapshot.bloatwareApps.size
                            AppManagerTab.CORE_SYSTEM_APPS -> state.snapshot.coreSystemApps.size
                            AppManagerTab.DISABLED_OR_REMOVED -> state.snapshot.disabledOrRemovedApps.size
                            AppManagerTab.LOCAL_APK_FILES -> state.snapshot.apkFiles.size
                        }
                        TvFilterChip(
                            label = "${tab.label} ($count)",
                            selected = state.tab == tab,
                            onClick = { onSelectTab(tab) },
                        )
                    }
                }

                // Batch Action Buttons (Multiple Uninstall / Disable / Restore)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.tab != AppManagerTab.LOCAL_APK_FILES) {
                        if (state.tab == AppManagerTab.CORE_SYSTEM_APPS) {
                            StatusPill(
                                text = "LOCKED FOR TV SYSTEM STABILITY",
                                color = TvColors.TextSecondary,
                                backgroundColor = TvColors.SurfaceElevated,
                            )
                        } else if (state.selectedPackages.isNotEmpty()) {
                            if (state.tab == AppManagerTab.DISABLED_OR_REMOVED) {
                                TvButton(
                                    text = if (state.batchWorking) "Restoring..." else "Restore & Enable Selected (${state.selectedPackages.size})",
                                    onClick = onBatchRestore,
                                    enabled = !state.batchWorking,
                                    accentColor = TvColors.Success,
                                    containerColor = TvColors.SuccessBg,
                                )
                            } else {
                                TvButton(
                                    text = if (state.batchWorking) "Removing..." else "Uninstall Selected (${state.selectedPackages.size})",
                                    onClick = onBatchUninstall,
                                    enabled = !state.batchWorking,
                                    accentColor = TvColors.Danger,
                                    containerColor = TvColors.DangerBg,
                                )
                                TvButton(
                                    text = "Disable Selected (${state.selectedPackages.size})",
                                    onClick = onBatchDisable,
                                    enabled = !state.batchWorking,
                                    accentColor = TvColors.AccentGold,
                                    containerColor = TvColors.WarningBg,
                                )
                            }
                            TvButton(
                                text = "Clear Selection",
                                onClick = onClearSelection,
                            )
                        } else {
                            if (state.tab == AppManagerTab.DISABLED_OR_REMOVED && state.visibleApps.isNotEmpty()) {
                                TvButton(
                                    text = "Restore All (${state.visibleApps.size})",
                                    onClick = onBatchRestore,
                                    enabled = !state.batchWorking,
                                    accentColor = TvColors.Success,
                                    containerColor = TvColors.SuccessBg,
                                )
                            }
                            if (state.tab == AppManagerTab.BLOATWARE_APPS && state.visibleApps.isNotEmpty()) {
                                TvButton(
                                    text = "Select All Bloatware",
                                    onClick = onSelectAll,
                                    accentColor = TvColors.AccentGold,
                                )
                            } else {
                                TvButton(
                                    text = "Select All",
                                    onClick = onSelectAll,
                                    enabled = state.visibleApps.isNotEmpty(),
                                )
                            }
                        }

                        TvButton(
                            text = "Sort: ${state.sortOrder.label}",
                            onClick = onToggleSortOrder,
                        )
                    }

                    TvButton(
                        text = if (state.loading) "Scanning..." else "Rescan",
                        onClick = onRefresh,
                    )
                }
            }
        }

        // Main List Area
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = TvColors.Surface,
            border = BorderStroke(1.dp, TvColors.Divider),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TvColors.SurfaceElevated.copy(alpha = 0.7f))
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when (state.tab) {
                            AppManagerTab.USER_APPS -> "INSTALLED USER APPS  •  SELECT TO BATCH UNINSTALL OR DISABLE"
                            AppManagerTab.BLOATWARE_APPS -> "OEM BLOATWARE & TELEMETRY  •  SAFE TO UNINSTALL FOR USER 0 OR DISABLE (NO ROOT)"
                            AppManagerTab.CORE_SYSTEM_APPS -> "CORE ANDROID TV OS COMPONENTS  •  PROTECTED (LOCKED TO PREVENT BRICKS & BOOTLOOPS)"
                            AppManagerTab.DISABLED_OR_REMOVED -> "DISABLED & REMOVED PACKAGES  •  1-CLICK RESTORE & RE-ENABLE"
                            AppManagerTab.LOCAL_APK_FILES -> "LOCAL APK INSTALLER  •  SCANNED FROM /sdcard/Download & INTERNAL STORAGE"
                        },
                        color = TvColors.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )

                    Text(
                        text = if (state.tab == AppManagerTab.LOCAL_APK_FILES) {
                            "FILE SIZE   •   INSTALL / DELETE"
                        } else {
                            "TYPE   •   DISK SIZE   •   ACTIONS"
                        },
                        color = TvColors.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                if (state.tab == AppManagerTab.LOCAL_APK_FILES) {
                    if (state.snapshot.apkFiles.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (state.loading) {
                                    "Scanning storage for .apk files..."
                                } else {
                                    "No .apk files found in /sdcard/Download or /sdcard."
                                },
                                color = TvColors.TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(
                                items = state.snapshot.apkFiles,
                                key = { it.filePath },
                            ) { apk ->
                                val isBusy = state.busyTarget == apk.filePath
                                LocalApkRowCard(
                                    apk = apk,
                                    isBusy = isBusy,
                                    onInstall = { onInstallApk(apk) },
                                    onDelete = { onDeleteApk(apk) },
                                )
                            }
                        }
                    }
                } else {
                    if (state.visibleApps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (state.loading) {
                                    "Scanning installed and system packages..."
                                } else {
                                    "No packages in this category."
                                },
                                color = TvColors.TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(
                                items = state.visibleApps,
                                key = { it.packageName },
                            ) { app ->
                                val isSelected = app.packageName in state.selectedPackages
                                val isBusy = state.busyTarget == app.packageName
                                ManagedAppRowCard(
                                    app = app,
                                    isSelected = isSelected,
                                    isBusy = isBusy,
                                    onToggleSelect = { onToggleSelect(app.packageName) },
                                    onOpen = { onOpenApp(app) },
                                    onDisable = { onDisableSingle(app) },
                                    onUninstall = { onUninstallSingle(app) },
                                    onRestore = { onRestoreSingle(app) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagedAppRowCard(
    app: ManagedAppEntry,
    isSelected: Boolean,
    isBusy: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onDisable: () -> Unit,
    onUninstall: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.SurfaceElevated.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = if (isSelected) TvColors.FocusRing else TvColors.Divider.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!app.isCriticalProtected) {
            TvFocusableSurface(
                onClick = onToggleSelect,
                selected = isSelected,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) TvColors.FocusRing else Color.Transparent)
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) TvColors.FocusRing else TvColors.TextMuted,
                            shape = RoundedCornerShape(4.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Text(
                            text = "✓",
                            color = Color(0xFF062E6F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }

        TvFocusableSurface(
            onClick = if (app.isCriticalProtected) onOpen else onToggleSelect,
            selected = isSelected,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    AppIconBox(drawable = app.icon, label = app.label, size = 26.dp)
                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = app.label,
                                color = TvColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                            if (app.versionName.isNotBlank()) {
                                Text(
                                    text = "v${app.versionName}",
                                    color = TvColors.TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            if (app.roleHint != null) {
                                Text(
                                    text = "— ${app.roleHint}",
                                    color = TvColors.AccentGold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Text(
                            text = app.packageName,
                            color = TvColors.TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (app.oemBrand != null) {
                        StatusPill(
                            text = app.oemBrand.uppercase(),
                            color = TvColors.FocusRing,
                            backgroundColor = Color(0x222563EB),
                        )
                    }
                    when {
                        !app.isInstalledForUser -> StatusPill(
                            text = "REMOVED (USER 0)",
                            color = TvColors.Danger,
                            backgroundColor = TvColors.DangerBg,
                        )
                        !app.isEnabled -> StatusPill(
                            text = "DISABLED",
                            color = TvColors.Danger,
                            backgroundColor = TvColors.DangerBg,
                        )
                        app.safetyLevel == DebloatSafetyLevel.SAFE && app.isBloatware -> StatusPill(
                            text = "SAFE TO DEBLOAT",
                            color = Color(0xFF10B981),
                            backgroundColor = Color(0x2210B981),
                        )
                        app.safetyLevel == DebloatSafetyLevel.RECOMMENDED && app.isBloatware -> StatusPill(
                            text = "RECOMMENDED",
                            color = Color(0xFF3B82F6),
                            backgroundColor = Color(0x223B82F6),
                        )
                        app.safetyLevel == DebloatSafetyLevel.ADVANCED && app.isBloatware -> StatusPill(
                            text = "ADVANCED",
                            color = Color(0xFFF59E0B),
                            backgroundColor = Color(0x22F59E0B),
                        )
                        app.isCriticalProtected -> StatusPill(
                            text = "CORE OS (LOCKED)",
                            color = TvColors.TextSecondary,
                            backgroundColor = TvColors.SurfaceElevated,
                        )
                        app.isSystemApp -> StatusPill(
                            text = "SYSTEM APP",
                            color = TvColors.AccentGold,
                            backgroundColor = TvColors.WarningBg,
                        )
                        else -> StatusPill(
                            text = "USER APP",
                            color = TvColors.Info,
                            backgroundColor = TvColors.InfoBg,
                        )
                    }

                    if (app.apkSizeMb > 0.1f) {
                        StatusPill(
                            text = String.format(Locale.US, "%.1f MB", app.apkSizeMb),
                            color = TvColors.TextSecondary,
                            backgroundColor = TvColors.Surface,
                        )
                    }
                }
            }
        }

        if (app.isRemovedOrDisabled) {
            TvButton(
                text = if (isBusy) "Restoring..." else "Restore & Enable",
                onClick = onRestore,
                enabled = !isBusy,
                accentColor = TvColors.Success,
                containerColor = TvColors.SuccessBg,
            )
        } else {
            TvButton(
                text = "Open",
                onClick = onOpen,
                accentColor = TvColors.FocusRing,
            )

            if (!app.isCriticalProtected) {
                TvButton(
                    text = if (isBusy) "..." else "Disable",
                    onClick = onDisable,
                    enabled = !isBusy,
                    accentColor = TvColors.AccentGold,
                )

                TvButton(
                    text = when {
                        isBusy -> "Removing..."
                        app.isBloatware -> "Uninstall Bloatware"
                        app.isSystemApp -> "Uninstall System"
                        else -> "Uninstall"
                    },
                    onClick = onUninstall,
                    enabled = !isBusy,
                    accentColor = TvColors.Danger,
                    containerColor = TvColors.DangerBg,
                )
            } else {
                StatusPill(
                    text = "LOCKED",
                    color = TvColors.TextMuted,
                    backgroundColor = TvColors.Surface,
                )
            }
        }
    }
}

@Composable
private fun LocalApkRowCard(
    apk: LocalApkFileEntry,
    isBusy: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvColors.SurfaceElevated.copy(alpha = 0.45f))
            .border(1.dp, TvColors.Divider.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvFocusableSurface(
            onClick = onInstall,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    AppIconBox(drawable = apk.icon, label = apk.appLabel, size = 28.dp)
                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = apk.appLabel,
                                color = TvColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                            if (!apk.versionName.isNullOrBlank()) {
                                Text(
                                    text = "v${apk.versionName}",
                                    color = TvColors.AccentGold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            if (!apk.packageName.isNullOrBlank()) {
                                Text(
                                    text = "(${apk.packageName})",
                                    color = TvColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Text(
                            text = apk.filePath,
                            color = TvColors.TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                StatusPill(
                    text = String.format(Locale.US, "APK • %.1f MB", apk.sizeMb),
                    color = TvColors.Info,
                    backgroundColor = TvColors.InfoBg,
                )
            }
        }

        TvButton(
            text = if (isBusy) "Installing..." else "Install APK",
            onClick = onInstall,
            enabled = !isBusy,
            accentColor = TvColors.Success,
            containerColor = TvColors.SuccessBg,
        )

        TvButton(
            text = "Delete File",
            onClick = onDelete,
            enabled = !isBusy,
            accentColor = TvColors.Danger,
            containerColor = TvColors.DangerBg,
        )
    }
}

@Composable
private fun UnifiedPowerAndRebootPanel(
    adbConnected: Boolean,
    executingReboot: RebootMode?,
    onSelectRebootMode: (RebootMode) -> Unit,
    onClose: () -> Unit,
) {
    val firstOptionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstOptionFocus.requestFocus() }
    }

    val primaryRow1 = remember {
        listOf(
            RebootMode.STANDARD_RESTART,
            RebootMode.SOFT_RESTART,
            RebootMode.RELOAD_HOME_UI,
        )
    }
    val primaryRow2 = remember {
        listOf(
            RebootMode.SLEEP_STANDBY,
            RebootMode.POWER_OFF,
            RebootMode.RECOVERY,
        )
    }
    var showAdvancedBootModes by remember { mutableStateOf(false) }
    val advancedRow1 = remember {
        listOf(
            RebootMode.TV_POWER_MENU,
            RebootMode.BOOTLOADER,
            RebootMode.FASTBOOT,
        )
    }
    val advancedRow2 = remember {
        listOf(
            RebootMode.USERSPACE,
            RebootMode.SIDELOAD,
            RebootMode.UPDATE,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = TvColors.SurfaceElevated,
        border = BorderStroke(1.5.dp, TvColors.FocusRing.copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "TV POWER & REBOOT CONTROLS",
                        color = TvColors.FocusRing,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    StatusPill(
                        text = if (adbConnected) "LOCAL ADB ACTIVE" else "NATIVE OS MODE",
                        color = if (adbConnected) TvColors.Success else TvColors.Info,
                        backgroundColor = if (adbConnected) TvColors.SuccessBg else TvColors.InfoBg,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvButton(
                        text = if (showAdvancedBootModes) "Hide Bootloader / Fastboot ▲" else "Advanced Boot Modes ▼",
                        onClick = { showAdvancedBootModes = !showAdvancedBootModes },
                        accentColor = TvColors.AccentGold,
                    )
                    TvButton(
                        text = "Close ▲",
                        onClick = onClose,
                    )
                }
            }

            // Row 1: Restart TV | Quick UI Restart | Reload Home Screen
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((idx, mode) in primaryRow1.withIndex()) {
                    val isRunning = executingReboot == mode
                    val isFirst = idx == 0
                    val canRun = mode.worksWithoutAdb || adbConnected

                    TvFocusableSurface(
                        onClick = { onSelectRebootMode(mode) },
                        enabled = executingReboot == null && canRun,
                        focusBorderColor = TvColors.FocusRing,
                        containerColor = TvColors.Surface,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isFirst) Modifier.focusRequester(firstOptionFocus) else Modifier),
                    ) { isFocused ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (isRunning) "Executing..." else mode.title,
                                    color = if (isFocused) TvColors.FocusRing else TvColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                StatusPill(
                                    text = if (mode.worksWithoutAdb) "NATIVE" else "ADB",
                                    color = if (mode.worksWithoutAdb) TvColors.Success else TvColors.AccentGold,
                                    backgroundColor = if (mode.worksWithoutAdb) TvColors.SuccessBg else TvColors.WarningBg,
                                )
                            }
                            Text(
                                text = mode.subtitle,
                                color = TvColors.TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Row 2: Sleep / Standby | Power Off | Recovery Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (mode in primaryRow2) {
                    val isRunning = executingReboot == mode
                    val canRun = mode.worksWithoutAdb || adbConnected

                    TvFocusableSurface(
                        onClick = { onSelectRebootMode(mode) },
                        enabled = executingReboot == null && canRun,
                        focusBorderColor = TvColors.Danger,
                        containerColor = TvColors.Surface,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        modifier = Modifier.weight(1f),
                    ) { isFocused ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (isRunning) "Executing..." else mode.title,
                                    color = if (isFocused) TvColors.Danger else TvColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                StatusPill(
                                    text = if (mode.worksWithoutAdb) "NATIVE" else "ADB",
                                    color = if (mode.worksWithoutAdb) TvColors.Success else TvColors.AccentGold,
                                    backgroundColor = if (mode.worksWithoutAdb) TvColors.SuccessBg else TvColors.WarningBg,
                                )
                            }
                            Text(
                                text = mode.subtitle,
                                color = TvColors.TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (showAdvancedBootModes) {
                for (advRow in listOf(advancedRow1, advancedRow2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (mode in advRow) {
                            val isRunning = executingReboot == mode
                            val canRun = mode.worksWithoutAdb || adbConnected

                            TvFocusableSurface(
                                onClick = { onSelectRebootMode(mode) },
                                enabled = executingReboot == null && canRun,
                                focusBorderColor = TvColors.AccentGold,
                                containerColor = TvColors.Surface,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                                modifier = Modifier.weight(1f),
                            ) { isFocused ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = if (isRunning) "Executing..." else mode.title,
                                            color = if (isFocused) TvColors.AccentGold else TvColors.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        StatusPill(
                                            text = if (mode.worksWithoutAdb) "NATIVE" else "ADB",
                                            color = if (mode.worksWithoutAdb) TvColors.Success else TvColors.AccentGold,
                                            backgroundColor = if (mode.worksWithoutAdb) TvColors.SuccessBg else TvColors.WarningBg,
                                        )
                                    }
                                    Text(
                                        text = mode.subtitle,
                                        color = TvColors.TextSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AdbSetupTutorialScreen(
    adbState: AdbConnectionState,
    onOpenAboutSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onConnectAdb: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onContinueToMain: () -> Unit,
) {
    val step1Focus = remember { FocusRequester() }
    val step2Focus = remember { FocusRequester() }
    val step3Focus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }

    val a11yDone = adbState.accessibilityReady
    val adbDone = adbState.connected

    LaunchedEffect(a11yDone, adbDone) {
        runCatching {
            when {
                !adbDone -> step3Focus.requestFocus()
                !a11yDone -> step1Focus.requestFocus()
                else -> continueFocus.requestFocus()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = TvColors.Surface,
            border = BorderStroke(1.dp, TvColors.Divider),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Optional ADB & Accessibility Setup",
                            color = TvColors.TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        StatusPill(
                            text = if (adbDone) "LOCAL ADB CONNECTED ✓" else "NATIVE MODE ACTIVE ✓",
                            color = TvColors.Success,
                            backgroundColor = TvColors.SuccessBg,
                        )
                    }
                    Text(
                        text = "The app works immediately upon install. Enable Accessibility or Local ADB below if you want silent batch Force-Stop, System App Uninstall, or Bootloader/Recovery Reboot.",
                        color = TvColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }

                TvButton(
                    text = "Back to Task Manager →",
                    onClick = onContinueToMain,
                    accentColor = TvColors.Success,
                    containerColor = TvColors.SuccessBg,
                    modifier = Modifier.focusRequester(continueFocus),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TutorialStepCard(
                stepNumber = 1,
                isDone = true,
                isCurrent = false,
                title = "Standard Native Mode (Included automatically with APK / Play Store install)",
                instructions = "Uses Android's built-in KILL_BACKGROUND_PROCESSES permission to terminate background processes and free RAM immediately — zero setup required.",
                statusBadgeText = "✓ ACTIVE BY DEFAULT",
                buttonText = "Active ✓",
                onButtonClick = onContinueToMain,
                buttonFocusRequester = step2Focus,
                modifier = Modifier.weight(1f),
            )

            TutorialStepCard(
                stepNumber = 2,
                isDone = a11yDone,
                isCurrent = !a11yDone,
                title = "Accessibility Force-Stop & Power Menu Helper (No Debugging Needed)",
                instructions = "Prominent Disclosure: Uses AccessibilityService only when you click Force Stop or Power Off/Reboot to automate the system dialog. Zero personal or screen data is ever collected.",
                statusBadgeText = if (a11yDone) "✓ ENABLED" else "OPTIONAL HELPER",
                buttonText = if (a11yDone) "Accessibility (On ✓)" else "Open Accessibility Settings",
                onButtonClick = onOpenAccessibility,
                buttonFocusRequester = step1Focus,
                modifier = Modifier.weight(1f),
            )

            TutorialStepCard(
                stepNumber = 3,
                isDone = adbDone,
                isCurrent = !adbDone,
                title = "Optional Local ADB Mode (For System App Uninstall, Silent APK Install & 8-Mode ADB Reboot)",
                instructions = "In TV Settings → System → About, tap 'Android TV OS build' 7 times, enable 'USB debugging' in Developer Options, then click 'Connect Local ADB'.",
                statusBadgeText = when {
                    adbDone -> "✓ ADB CONNECTED"
                    adbState.portOpen -> "READY TO CONNECT"
                    else -> "OPTIONAL PRO MODE"
                },
                buttonText = when {
                    adbDone -> "✓ Local ADB Active"
                    adbState.portOpen -> "Connect Local ADB"
                    !adbState.devOptionsEnabled -> "1. Open About Settings"
                    else -> "2. Open Developer Options"
                },
                onButtonClick = {
                    when {
                        adbDone || adbState.portOpen -> onConnectAdb()
                        !adbState.devOptionsEnabled -> onOpenAboutSettings()
                        else -> onOpenDeveloperOptions()
                    }
                },
                buttonFocusRequester = step3Focus,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TutorialStepCard(
    stepNumber: Int,
    isDone: Boolean,
    isCurrent: Boolean,
    title: String,
    instructions: String,
    statusBadgeText: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    buttonFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        isDone -> TvColors.Success.copy(alpha = 0.65f)
        isCurrent -> TvColors.FocusRing
        else -> TvColors.Divider
    }
    val containerColor = when {
        isCurrent -> TvColors.SurfaceElevated
        else -> TvColors.Surface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(if (isCurrent) 2.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isDone -> TvColors.SuccessBg
                                isCurrent -> TvColors.InfoBg
                                else -> TvColors.Background
                            },
                        )
                        .border(
                            width = 1.5.dp,
                            color = when {
                                isDone -> TvColors.Success
                                isCurrent -> TvColors.FocusRing
                                else -> TvColors.Divider
                            },
                            shape = RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isDone) "✓" else "$stepNumber",
                        color = when {
                            isDone -> TvColors.Success
                            isCurrent -> TvColors.FocusRing
                            else -> TvColors.TextSecondary
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = title,
                            color = TvColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        StatusPill(
                            text = statusBadgeText,
                            color = when {
                                isDone -> TvColors.Success
                                isCurrent -> TvColors.FocusRing
                                else -> TvColors.TextSecondary
                            },
                            backgroundColor = when {
                                isDone -> TvColors.SuccessBg
                                isCurrent -> TvColors.InfoBg
                                else -> TvColors.Background
                            },
                        )
                    }
                    Text(
                        text = instructions,
                        color = TvColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            TvButton(
                text = buttonText,
                onClick = onButtonClick,
                accentColor = when {
                    isDone -> TvColors.Success
                    isCurrent -> TvColors.FocusRing
                    else -> TvColors.TextSecondary
                },
                containerColor = when {
                    isDone -> TvColors.SuccessBg
                    isCurrent -> TvColors.InfoBg
                    else -> TvColors.SurfaceElevated
                },
                modifier = Modifier.focusRequester(buttonFocusRequester),
            )
        }
    }
}
