package com.atvpm.tvcontrol.domain

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.atvpm.tvcontrol.core.PrivilegeManager
import com.atvpm.tvcontrol.service.AccessibilityGate
import com.atvpm.tvcontrol.service.ForceStopAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MemorySnapshot(
    val totalKb: Long = 0L,
    val availableKb: Long = 0L,
    val freeKb: Long = 0L,
    val cachedKb: Long = 0L,
    val swapTotalKb: Long = 0L,
    val swapFreeKb: Long = 0L,
    val storageFreeMb: Long = 0L,
    val storageTotalMb: Long = 0L,
) {
    val usedKb: Long get() = (totalKb - availableKb).coerceAtLeast(0L)
    val totalMb: Long get() = totalKb / 1024L
    val availableMb: Long get() = availableKb / 1024L
    val usedMb: Long get() = usedKb / 1024L
    val cachedMb: Long get() = cachedKb / 1024L
    val swapUsedMb: Long get() = ((swapTotalKb - swapFreeKb).coerceAtLeast(0L)) / 1024L
    val swapTotalMb: Long get() = swapTotalKb / 1024L
    val usageFraction: Float
        get() = if (totalKb > 0L) (usedKb.toFloat() / totalKb.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Individual OS process (PID) belonging to a [RunningAppEntry].
 */
data class SubProcessEntry(
    val pid: Int,
    val processName: String,
    val shortName: String,
    val rssKb: Long,
    val stateCode: String,
) {
    val rssMb: Float get() = rssKb / 1024f
    val stateDescription: String
        get() = when {
            stateCode.startsWith("R") -> "Running"
            stateCode.startsWith("D") -> "Disk Wait"
            stateCode.startsWith("Z") -> "Zombie"
            else -> "Sleeping"
        }
}

/**
 * Represents a running or disabled App and all of its child [subProcesses].
 */
data class RunningAppEntry(
    val packageName: String,
    val label: String,
    val roleHint: String? = null,
    val icon: Drawable? = null,
    val isForeground: Boolean = false,
    val isSystemApp: Boolean = false,
    val isKillableExtra: Boolean = true,
    val isPinned: Boolean = false,
    val isDisabled: Boolean = false,
    val canDisable: Boolean = false,
    val canLaunch: Boolean = false,
    val subProcesses: List<SubProcessEntry> = emptyList(),
    val totalRssKb: Long = 0L,
) {
    val pids: List<Int> get() = subProcesses.map { it.pid }
    val rssMb: Float get() = totalRssKb / 1024f
}

data class RunningAppsSnapshot(
    val apps: List<RunningAppEntry>,
    val disabledApps: List<RunningAppEntry> = emptyList(),
    val memory: MemorySnapshot?,
)

data class ActionResult(
    val success: Boolean,
    val message: String,
)

class ProcessController(
    context: Context,
    private val privilegeManager: PrivilegeManager,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val prefs = appContext.getSharedPreferences("atv_task_prefs", Context.MODE_PRIVATE)

    private data class CachedPkgMeta(
        val label: String,
        val icon: Drawable?,
        val hasLauncher: Boolean,
        val roleHint: String,
    )

    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, CachedPkgMeta>()
    private val nativeStoppedPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var cachedProtectedPackages: Set<String>? = null

    fun clearCache() {
        metadataCache.clear()
        nativeStoppedPackages.clear()
    }

    fun getPinnedPackages(): Set<String> =
        prefs.getStringSet("pinned_packages", emptySet())?.toSet().orEmpty()

    fun togglePinPackage(packageName: String): Boolean {
        val current = getPinnedPackages().toMutableSet()
        val nowPinned = if (packageName in current) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        prefs.edit().putStringSet("pinned_packages", current).apply()
        return nowPinned
    }

    suspend fun fetchRunningApps(): RunningAppsSnapshot = withContext(Dispatchers.IO) {
        val localMem = readLocalProcMemInfo()
        val pm = appContext.packageManager
        val selfPkg = appContext.packageName
        val protectedPackages = cachedProtectedPackages
            ?: resolveProtectedPackages(pm, selfPkg).also { cachedProtectedPackages = it }
        val pinnedSet = getPinnedPackages()

        val subProcsByPkg = HashMap<String, MutableList<SubProcessEntry>>()
        val rssByPkg = HashMap<String, Long>()
        val foregroundPackages = HashSet<String>()
        var parsedMem: MemorySnapshot? = null

        val runner = privilegeManager.active
        if (runner.isReady) {
            val cmd = buildString {
                append("dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|ResumedActivity|mFocusedApp'; ")
                append("echo '===PS_SPLIT==='; ")
                append("ps -A -o PID,USER,RSS,STAT,NAME,ARGS 2>/dev/null || ps -o PID,USER,RSS,STAT,NAME,ARGS 2>/dev/null || ps; ")
                append("echo '===MEM_SPLIT==='; ")
                append("cat /proc/meminfo 2>/dev/null")
            }
            val res = runner.run(cmd)
            if (res.executed) {
                val part1 = res.stdout.substringBefore("===PS_SPLIT===", "")
                val afterPs = res.stdout.substringAfter("===PS_SPLIT===", res.stdout)
                val psPart = afterPs.substringBefore("===MEM_SPLIT===", afterPs)
                val memPart = afterPs.substringAfter("===MEM_SPLIT===", "")

                parseForegroundPackages(part1, foregroundPackages)
                parsePsIntoSubProcesses(psPart, subProcsByPkg, rssByPkg)
                parsedMem = parseMemInfo(memPart)
            }
        }

        val installed: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val entries = ArrayList<RunningAppEntry>()
        val disabledEntries = ArrayList<RunningAppEntry>()

        for (info in installed) {
            val pkg = info.packageName
            if (pkg == selfPkg) continue

            val isPureSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isProt = pkg in protectedPackages || CRITICAL_PREFIXES.any { pkg.startsWith(it) }

            if (!info.enabled) {
                val meta = metadataCache.getOrPut(pkg) {
                    val hasLaunch = hasLauncherIntent(pm, pkg)
                    val lbl = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(pkg)
                        .ifBlank { pkg }
                    val icn = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                    val hint = resolveUniversalRoleHint(pkg, info, isPureSystem, isProt, hasLaunch)
                    CachedPkgMeta(lbl, icn, hasLaunch, hint)
                }
                disabledEntries.add(
                    RunningAppEntry(
                        packageName = pkg,
                        label = meta.label,
                        roleHint = meta.roleHint,
                        icon = meta.icon,
                        isForeground = false,
                        isSystemApp = isPureSystem,
                        isKillableExtra = false,
                        isPinned = false,
                        isDisabled = true,
                        canDisable = false,
                    ),
                )
                continue
            }

            val subProcs = subProcsByPkg[pkg]?.sortedByDescending { it.rssKb }.orEmpty()
            val rssKb = rssByPkg[pkg] ?: 0L
            val isStopped = (info.flags and ApplicationInfo.FLAG_STOPPED) != 0
            val nativeStoppedAt = nativeStoppedPackages[pkg]
            val recentlyNativeStopped = !runner.isReady &&
                nativeStoppedAt != null &&
                (System.currentTimeMillis() - nativeStoppedAt) < 300_000L

            val isRunningOrActive = (subProcs.isNotEmpty() || !isStopped) && !recentlyNativeStopped
            if (!isRunningOrActive) continue

            val cachedMeta = metadataCache[pkg]
            val hasLaunch = cachedMeta?.hasLauncher ?: hasLauncherIntent(pm, pkg)
            if (subProcs.isEmpty() && isPureSystem && pkg !in KNOWN_BLOAT_PACKAGES && !hasLaunch) {
                continue
            }

            val meta = cachedMeta ?: metadataCache.getOrPut(pkg) {
                val lbl = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(pkg)
                    .ifBlank { pkg }
                val icn = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                val hint = resolveUniversalRoleHint(pkg, info, isPureSystem, isProt, hasLaunch)
                CachedPkgMeta(
                    label = lbl,
                    icon = icn,
                    hasLauncher = hasLaunch,
                    roleHint = hint,
                )
            }

            val isFg = pkg in foregroundPackages
            val isPinned = pkg in pinnedSet
            val baseKillable = !isProt && (!isPureSystem || pkg in KNOWN_BLOAT_PACKAGES || meta.hasLauncher)

            entries.add(
                RunningAppEntry(
                    packageName = pkg,
                    label = meta.label,
                    roleHint = meta.roleHint,
                    icon = meta.icon,
                    isForeground = isFg,
                    isSystemApp = isPureSystem,
                    isKillableExtra = baseKillable && !isPinned,
                    isPinned = isPinned,
                    isDisabled = false,
                    canDisable = baseKillable,
                    canLaunch = meta.hasLauncher,
                    subProcesses = subProcs,
                    totalRssKb = rssKb,
                ),
            )
        }

        val sorted = entries.sortedWith(
            compareByDescending<RunningAppEntry> { it.isForeground }
                .thenByDescending { it.isKillableExtra }
                .thenByDescending { it.totalRssKb }
                .thenBy { it.label.lowercase() },
        )

        val sortedDisabled = disabledEntries.sortedBy { it.label.lowercase() }

        RunningAppsSnapshot(
            apps = sorted,
            disabledApps = sortedDisabled,
            memory = parsedMem ?: localMem,
        )
    }

    /**
     * Kills a single specific child PID (`kill -9 <pid>`) belonging to an app.
     */
    suspend fun killSinglePid(app: RunningAppEntry, sub: SubProcessEntry): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching { activityManager.killBackgroundProcesses(app.packageName) }
            val runner = privilegeManager.active
            if (!runner.isReady) {
                return@withContext ActionResult(true, "Killed background processes for ${app.label}")
            }
            val res = runner.run("kill -9 ${sub.pid}")
            if (res.executed) {
                ActionResult(
                    success = true,
                    message = "Killed PID ${sub.pid} (${sub.shortName}) in ${app.label}",
                )
            } else {
                ActionResult(
                    success = false,
                    message = "Failed to kill PID ${sub.pid}: ${res.reason()}",
                )
            }
        }

    /**
     * Kills an entire app using:
     * 1. Native Android SDK `ActivityManager.killBackgroundProcesses` (0 setup, works from APK/Play Store install)
     * 2. Local ADB (`am force-stop <pkg>` + `kill -9 <all pids>`) if connected
     * 3. Accessibility Service Force-Stop automation if enabled
     */
    suspend fun killSingleApp(app: RunningAppEntry): ActionResult = withContext(Dispatchers.IO) {
        val beforeAvailMb = readLocalProcMemInfo()?.availableMb
        runCatching { activityManager.killBackgroundProcesses(app.packageName) }

        val runner = privilegeManager.active
        if (runner.isReady) {
            val pidKill = if (app.pids.isNotEmpty()) {
                "; kill -9 ${app.pids.joinToString(" ")} 2>/dev/null || true"
            } else {
                ""
            }
            val cmd = "am force-stop ${app.packageName}$pidKill"
            val res = runner.run(cmd)
            if (res.executed) {
                return@withContext ActionResult(
                    success = true,
                    message = "Stopped ${app.label} (${String.format("%.0f MB freed", app.rssMb)})",
                )
            }
        }

        val a11yService = ForceStopAccessibilityService.instance
        if (AccessibilityGate.isEnabled(appContext) && a11yService != null) {
            val outcome = a11yService.automateForceStop(appContext, app.packageName, app.label)
            return@withContext ActionResult(
                success = outcome.success,
                message = outcome.detail,
            )
        }

        nativeStoppedPackages[app.packageName] = System.currentTimeMillis()
        val afterAvailMb = readLocalProcMemInfo()?.availableMb
        val freedMb = if (beforeAvailMb != null && afterAvailMb != null) {
            (afterAvailMb - beforeAvailMb).coerceAtLeast(0L)
        } else {
            0L
        }
        val freedText = if (freedMb > 0L) " (+$freedMb MB RAM freed)" else ""
        ActionResult(
            success = true,
            message = "Stopped background processes for ${app.label}$freedText",
        )
    }

    /**
     * Disables a bloatware package (`pm disable-user --user 0 <pkg>`) so it never
     * starts in the background again.
     */
    suspend fun disablePackage(app: RunningAppEntry): ActionResult = withContext(Dispatchers.IO) {
        val runner = privilegeManager.active
        if (!runner.isReady) {
            openAppInfoScreen(app.packageName)
            return@withContext ActionResult(true, "Opened System App Info to disable ${app.label}")
        }
        val cmd = "am force-stop ${app.packageName}; pm disable-user --user 0 ${app.packageName}"
        val res = runner.run(cmd)
        if (res.ok || res.stdout.contains("disabled", ignoreCase = true)) {
            ActionResult(
                success = true,
                message = "Disabled ${app.label} — moved to Disabled tab",
            )
        } else {
            ActionResult(
                success = false,
                message = "Could not disable ${app.label}: ${res.reason()}",
            )
        }
    }

    /**
     * Re-enables a previously disabled package (`pm enable <pkg>`).
     */
    suspend fun enablePackage(app: RunningAppEntry): ActionResult = withContext(Dispatchers.IO) {
        val runner = privilegeManager.active
        if (!runner.isReady) {
            openAppInfoScreen(app.packageName)
            return@withContext ActionResult(true, "Opened System App Info to enable ${app.label}")
        }
        val cmd = "pm enable --user 0 ${app.packageName} 2>/dev/null || pm enable ${app.packageName}"
        val res = runner.run(cmd)
        if (res.ok || res.stdout.contains("enabled", ignoreCase = true)) {
            ActionResult(
                success = true,
                message = "Re-enabled ${app.label} (${app.packageName})",
            )
        } else {
            ActionResult(
                success = false,
                message = "Could not re-enable ${app.label}: ${res.reason()}",
            )
        }
    }

    /**
     * 1-Click Storage Cache Cleaner (`pm trim-caches 999G` + native background sweep).
     */
    suspend fun clearAllAppCaches(): ActionResult = withContext(Dispatchers.IO) {
        val beforeFreeMb = readStorageStats().first
        val runner = privilegeManager.active
        if (runner.isReady) {
            val res = runner.run("pm trim-caches 999G 2>/dev/null; sync")
            val afterFreeMb = readStorageStats().first
            val freedMb = (afterFreeMb - beforeFreeMb).coerceAtLeast(0L)
            if (res.executed) {
                val detail = if (freedMb > 0L) " (+$freedMb MB storage freed)" else ""
                return@withContext ActionResult(true, "Cleared all app caches$detail")
            }
        }
        // Native fallback when running without ADB
        appContext.cacheDir?.deleteRecursively()
        appContext.codeCacheDir?.deleteRecursively()
        val afterFreeMb = readStorageStats().first
        val freedMb = (afterFreeMb - beforeFreeMb).coerceAtLeast(0L)
        val detail = if (freedMb > 0L) " (+$freedMb MB freed)" else ""
        ActionResult(true, "Cleaned app cache & temporary memory$detail")
    }

    /**
     * Kills MULTIPLE or ALL target apps at once.
     * Always executes native Android SDK `killBackgroundProcesses` first (works on any APK/Play Store install
     * without debugging), plus Local ADB or Accessibility Force-Stop when available.
     */
    suspend fun killBatchApps(targets: List<RunningAppEntry>): ActionResult =
        withContext(Dispatchers.IO) {
            if (targets.isEmpty()) {
                return@withContext ActionResult(true, "All background apps are already stopped.")
            }

            val beforeAvailMb = readLocalProcMemInfo()?.availableMb
            val now = System.currentTimeMillis()
            for (app in targets) {
                runCatching { activityManager.killBackgroundProcesses(app.packageName) }
                nativeStoppedPackages[app.packageName] = now
            }

            val runner = privilegeManager.active
            if (runner.isReady) {
                val batchCmd = buildString {
                    for (app in targets) {
                        append("am force-stop ").append(app.packageName).append(" 2>/dev/null; ")
                    }
                    val allPids = targets.flatMap { it.pids }.distinct()
                    if (allPids.isNotEmpty()) {
                        append("kill -9 ").append(allPids.joinToString(" ")).append(" 2>/dev/null || true; ")
                    }
                    append("pm trim-caches 999G 2>/dev/null || true")
                }

                val res = runner.run(batchCmd)
                val afterAvailMb = readLocalProcMemInfo()?.availableMb
                val freedMb = if (beforeAvailMb != null && afterAvailMb != null) {
                    (afterAvailMb - beforeAvailMb).coerceAtLeast(0L)
                } else {
                    0L
                }

                if (res.executed) {
                    val freedText = if (freedMb > 0L) " • Freed +$freedMb MB RAM" else ""
                    return@withContext ActionResult(
                        success = true,
                        message = "Stopped ${targets.size} app(s)$freedText",
                    )
                }
            }

            val a11yService = ForceStopAccessibilityService.instance
            if (AccessibilityGate.isEnabled(appContext) && a11yService != null) {
                var okCount = 0
                for (app in targets) {
                    val outcome = a11yService.automateForceStop(appContext, app.packageName, app.label)
                    if (outcome.success) okCount++
                }
                bringSelfToFront()
                return@withContext ActionResult(
                    success = okCount > 0,
                    message = "Stopped $okCount/${targets.size} app(s)",
                )
            }

            val afterAvailMb = readLocalProcMemInfo()?.availableMb
            val freedMb = if (beforeAvailMb != null && afterAvailMb != null) {
                (afterAvailMb - beforeAvailMb).coerceAtLeast(0L)
            } else {
                0L
            }
            val freedText = if (freedMb > 0L) " • Freed +$freedMb MB RAM" else ""
            ActionResult(
                success = true,
                message = "Stopped background processes for ${targets.size} app(s)$freedText",
            )
        }

    fun openApp(packageName: String, label: String): ActionResult {
        val pm = appContext.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            val started = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                appContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) {
                return ActionResult(true, "Opened $label")
            }
        }
        val infoOpened = openAppInfoScreen(packageName)
        return ActionResult(
            success = infoOpened,
            message = if (infoOpened) "Opened App Info for $label" else "Cannot launch $label",
        )
    }

    fun openAppInfoScreen(packageName: String): Boolean = runCatching {
        AccessibilityGate.markLaunchingExternalActivity()
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun bringSelfToFront() {
        runCatching {
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: return
            appContext.startActivity(launch)
        }
    }

    private fun readStorageStats(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
        val totalMb = (stat.blockCountLong * stat.blockSizeLong) / (1024L * 1024L)
        freeMb to totalMb
    }.getOrDefault(0L to 0L)

    fun readLocalProcMemInfo(): MemorySnapshot? = runCatching {
        val text = java.io.File("/proc/meminfo").takeIf { it.canRead() }?.readText().orEmpty()
        parseMemInfo(text)
    }.getOrNull()

    private fun parseMemInfo(text: String): MemorySnapshot? {
        if (text.isBlank()) return null
        val values = mutableMapOf<String, Long>()
        text.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val rest = line.substring(colon + 1).trim().removeSuffix("kB").trim()
            val num = rest.toLongOrNull() ?: return@forEach
            values[key] = num
        }
        val total = values["MemTotal"] ?: return null
        val free = values["MemFree"] ?: 0L
        val cached = values["Cached"] ?: 0L
        val buffers = values["Buffers"] ?: 0L
        val available = values["MemAvailable"] ?: (free + cached + buffers)
        val swapTotal = values["SwapTotal"] ?: 0L
        val swapFree = values["SwapFree"] ?: 0L
        val (storageFreeMb, storageTotalMb) = readStorageStats()
        return MemorySnapshot(
            totalKb = total,
            availableKb = available,
            freeKb = free,
            cachedKb = cached,
            swapTotalKb = swapTotal,
            swapFreeKb = swapFree,
            storageFreeMb = storageFreeMb,
            storageTotalMb = storageTotalMb,
        )
    }

    private fun parseForegroundPackages(text: String, out: MutableSet<String>) {
        val regex = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/")
        for (match in regex.findAll(text)) {
            val pkg = match.groupValues.getOrNull(1) ?: continue
            if (pkg != appContext.packageName && pkg != "com.android.tv.settings") {
                out.add(pkg)
            }
        }
    }

    private fun parsePsIntoSubProcesses(
        text: String,
        subProcsByPkg: MutableMap<String, MutableList<SubProcessEntry>>,
        rssByPkg: MutableMap<String, Long>,
    ) {
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("PID ")) continue
            val parts = line.split(WHITESPACE_REGEX, limit = 6)
            if (parts.size < 4) continue

            val pid = parts[0].toIntOrNull() ?: continue
            val rssKb = parts[2].toLongOrNull() ?: 0L

            val stateCode: String
            val name: String
            val args: String
            if (parts.size >= 5) {
                stateCode = parts[3]
                name = parts[4]
                args = parts.getOrNull(5).orEmpty()
            } else {
                stateCode = "S"
                name = parts[3]
                args = name
            }

            val fullProcName = when {
                args.contains('.') && !args.startsWith('/') && !args.startsWith('[') ->
                    args.substringBefore(' ').trim()
                else -> name.trim()
            }

            val pkg = extractPackageName(name, args) ?: continue
            val shortName = if (fullProcName.startsWith("$pkg:")) {
                ":" + fullProcName.substringAfter("$pkg:")
            } else {
                "main ($pkg)"
            }

            subProcsByPkg.getOrPut(pkg) { mutableListOf() }.add(
                SubProcessEntry(
                    pid = pid,
                    processName = fullProcName,
                    shortName = shortName,
                    rssKb = rssKb,
                    stateCode = stateCode,
                ),
            )
            rssByPkg[pkg] = (rssByPkg[pkg] ?: 0L) + rssKb
        }
    }

    private fun resolveUniversalRoleHint(
        pkg: String,
        info: ApplicationInfo,
        isPureSystem: Boolean,
        isProtected: Boolean,
        hasLauncher: Boolean,
    ): String {
        PACKAGE_ROLE_HINTS[pkg]?.let { return it }

        for ((prefix, role) in VENDOR_PREFIX_ROLES) {
            if (pkg.startsWith(prefix)) return role
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (info.category) {
                ApplicationInfo.CATEGORY_VIDEO -> return "Video & Streaming App"
                ApplicationInfo.CATEGORY_AUDIO -> return "Music & Audio App"
                ApplicationInfo.CATEGORY_GAME -> return "TV Game"
                ApplicationInfo.CATEGORY_IMAGE -> return "Photos & Media App"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "Productivity App"
                ApplicationInfo.CATEGORY_MAPS -> return "Maps & Navigation"
                ApplicationInfo.CATEGORY_NEWS -> return "News & Media App"
                ApplicationInfo.CATEGORY_SOCIAL -> return "Social & Communication"
            }
        }

        return when {
            isProtected -> "Core Android TV System Service (Essential)"
            !isPureSystem && hasLauncher -> "Installed App — Safe to stop"
            !isPureSystem -> "User Background Service — Safe to stop"
            hasLauncher -> "System TV App"
            else -> "Background TV Service"
        }
    }

    private fun extractPackageName(name: String, args: String): String? {
        val base = name.substringBefore(':').trim()
        if (base.contains('.') && !base.startsWith('/') && !base.startsWith('[')) {
            return base
        }
        val argBase = args.substringBefore(' ').substringBefore(':').trim()
        if (argBase.contains('.') && !argBase.startsWith('/') && !argBase.startsWith('[')) {
            return argBase
        }
        return null
    }

    private fun hasLauncherIntent(pm: PackageManager, pkg: String): Boolean =
        pm.getLaunchIntentForPackage(pkg) != null ||
            pm.getLeanbackLaunchIntentForPackage(pkg) != null

    private fun resolveProtectedPackages(pm: PackageManager, selfPkg: String): Set<String> {
        val set = HashSet<String>()
        set.add(selfPkg)
        set.addAll(CRITICAL_PACKAGES)

        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { set.add(it) }
        }
        runCatching {
            val tvIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            pm.resolveActivity(tvIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { set.add(it) }
        }
        return set
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        private val CRITICAL_PACKAGES = setOf(
            "android",
            "system",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.se",
            "com.android.shell",
            "com.google.android.tv.remote.service",
            "com.google.android.inputmethod.latin",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
        )

        private val CRITICAL_PREFIXES = listOf(
            "com.android.providers.",
            "com.android.server.",
            "android.ext.",
            "com.realtek.",
        )

        private val KNOWN_BLOAT_PACKAGES = setOf(
            "com.google.android.tungsten.setupwraith",
            "com.google.android.backdrop",
            "com.google.android.apps.tv.dreamx",
            "com.google.android.katniss",
            "com.google.android.apps.mediashell",
            "com.google.android.feedback",
            "com.google.android.partnersetup",
            "com.google.android.videos",
            "com.google.android.play.games",
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
        )

        internal val KNOWN_BLOAT_ROLES: Map<String, String>
            get() = PACKAGE_ROLE_HINTS

        private val PACKAGE_ROLE_HINTS = mapOf(
            "com.google.android.tungsten.setupwraith" to "Setup Wizard daemon — safe to kill or disable",
            "com.google.android.katniss" to "Google Assistant voice & search background services",
            "com.google.android.apps.mediashell" to "Chromecast built-in background receiver",
            "com.google.android.apps.tv.dreamx" to "Ambient Mode screensaver & smart home daemon",
            "com.google.android.backdrop" to "Idle photo screensaver background service",
            "com.google.android.apps.tv.launcherx" to "Google TV Home Launcher & recommendations",
            "com.google.android.tvlauncher" to "Android TV Home Launcher (Essential)",
            "com.google.android.gms" to "Google Play Services core background processes",
            "com.realsil.ota" to "System OTA firmware update checker",
            "com.google.android.tv.dfuservice" to "Remote control firmware update service",
            "com.google.android.play.games" to "Google Play Games background service",
            "com.google.android.videos" to "Google TV Movies & Shows background service",
            "com.google.android.youtube.tv" to "YouTube TV App & background playback",
            "com.netflix.ninja" to "Netflix background service & streaming player",
            "com.amazon.amazonvideo.livingroom" to "Prime Video streaming & background service",
            "com.disney.disneyplus" to "Disney+ streaming & background service",
            "org.xbmc.kodi" to "Kodi Home Theater & media player",
            "com.plexapp.android" to "Plex Client & media player",
            "org.videolan.vlc" to "VLC Media Player",
            "com.teamsmart.videomanager.tv" to "SmartTube Next video player",
        )

        private val VENDOR_PREFIX_ROLES = listOf(
            "com.tcl." to "TCL Smart TV Vendor Daemon",
            "com.tct." to "TCL System Hardware Service",
            "com.hisense." to "Hisense Smart TV Vendor Service",
            "com.jamdeo." to "Hisense VIDAA / TV Platform Service",
            "com.xiaomi." to "Xiaomi / PatchWall TV Service",
            "com.mitv." to "Xiaomi Mi TV Hardware Daemon",
            "com.duokan." to "Xiaomi Media Player Service",
            "com.sony.dtv." to "Sony Bravia TV Hardware Daemon",
            "com.sony." to "Sony TV Vendor Service",
            "org.droidtv." to "Philips Smart TV Hardware Daemon",
            "com.tpv." to "TPV / Philips TV Platform Service",
            "com.amazon.tv." to "Fire TV Platform Daemon",
            "com.amazon.bueller." to "Fire TV Core Hardware Service",
            "com.mediatek." to "MediaTek TV Chipset Daemon",
            "com.realtek." to "Realtek TV Audio/Video Daemon",
            "com.realsil." to "Realtek Bluetooth/OTA Service",
            "com.droidlogic." to "Amlogic Chipset Hardware Daemon",
            "com.amlogic." to "Amlogic Video Engine Daemon",
            "com.mstar." to "MStar Display Controller Daemon",
            "com.nvidia." to "Nvidia Shield TV System Service",
            "com.google.android.youtube." to "YouTube Video Player",
            "com.netflix." to "Netflix Streaming Service",
            "com.amazon.amazonvideo." to "Prime Video Streaming Service",
            "com.disney." to "Disney+ Streaming Service",
            "com.spotify." to "Spotify Music Player",
            "com.apple.atve." to "Apple TV+ Streaming Service",
            "com.hulu." to "Hulu Video Streaming",
            "com.peacocktv." to "Peacock TV Streaming",
            "com.hbo." to "Max / HBO Video Streaming",
            "tv.twitch." to "Twitch Live Streaming",
        )
    }
}
