package com.atvpm.tvcontrol.domain

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.atvpm.tvcontrol.core.PrivilegeManager
import com.atvpm.tvcontrol.service.AccessibilityGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ManagedAppEntry(
    val packageName: String,
    val label: String,
    val roleHint: String?,
    val icon: Drawable?,
    val versionName: String,
    val apkSizeMb: Float,
    val isSystemApp: Boolean,
    val isUpdatedSystemApp: Boolean,
    val isEnabled: Boolean,
    val isInstalledForUser: Boolean,
    val isCriticalProtected: Boolean,
    val isBloatware: Boolean = false,
    val safetyLevel: DebloatSafetyLevel = DebloatSafetyLevel.SAFE,
    val oemBrand: String? = null,
) {
    val isRemovedOrDisabled: Boolean
        get() = !isEnabled || !isInstalledForUser
}

data class LocalApkFileEntry(
    val filePath: String,
    val fileName: String,
    val appLabel: String,
    val packageName: String?,
    val versionName: String?,
    val sizeMb: Float,
    val icon: Drawable?,
)

data class AppManagerSnapshot(
    val userApps: List<ManagedAppEntry> = emptyList(),
    val bloatwareApps: List<ManagedAppEntry> = emptyList(),
    val coreSystemApps: List<ManagedAppEntry> = emptyList(),
    val disabledOrRemovedApps: List<ManagedAppEntry> = emptyList(),
    val apkFiles: List<LocalApkFileEntry> = emptyList(),
) {
    val systemApps: List<ManagedAppEntry>
        get() = bloatwareApps + coreSystemApps
}

class AppManagerController(
    context: Context,
    private val privilegeManager: PrivilegeManager,
    private val processController: ProcessController,
) {
    private val appContext = context.applicationContext

    private data class CachedAppInfo(
        val label: String,
        val icon: Drawable?,
        val versionName: String,
        val sizeMb: Float,
        val roleHint: String?,
    )

    private val cache = ConcurrentHashMap<String, CachedAppInfo>()

    suspend fun refreshSnapshot(): AppManagerSnapshot = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val selfPkg = appContext.packageName

        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val allApps: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags)
        }

        val userList = ArrayList<ManagedAppEntry>()
        val bloatList = ArrayList<ManagedAppEntry>()
        val coreList = ArrayList<ManagedAppEntry>()
        val disabledOrRemovedList = ArrayList<ManagedAppEntry>()

        for (info in allApps) {
            val pkg = info.packageName
            if (pkg == selfPkg) continue

            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isInstalledForUser = (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            val isEnabled = info.enabled
            val isCritical = isCoreProtected(pkg)
            val hasLauncher = runCatching {
                pm.getLaunchIntentForPackage(pkg) != null || pm.getLeanbackLaunchIntentForPackage(pkg) != null
            }.getOrDefault(false)
            val debloatDef = DebloatDatabase.find(pkg)
            val safetyLevel = DebloatDatabase.getSafetyLevel(pkg, isSystem, isCritical)
            val isBloat = if (isCritical || !isSystem) false else (debloatDef != null || isSafeBloatware(pkg, hasLauncher))

            // Skip obscure internal overlays that have no APK size and aren't user-facing
            if (pkg.endsWith(".overlay") || pkg.startsWith("com.android.internal.")) continue

            val meta = cache.getOrPut(pkg) {
                val lbl = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(pkg)
                    .ifBlank { debloatDef?.humanName ?: pkg }
                val icn = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                val ver = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0).versionName ?: ""
                }.getOrDefault("")
                val sizeBytes = runCatching {
                    info.sourceDir?.let { File(it).length() } ?: 0L
                }.getOrDefault(0L)
                val sizeMb = sizeBytes / (1024f * 1024f)
                val hint = debloatDef?.let { "${it.oemBrand} • ${it.description}" }
                    ?: ProcessController.KNOWN_BLOAT_ROLES[pkg] ?: when {
                    isCritical -> "Core Android TV OS component (Protected)"
                    isBloat -> "OEM Bloatware & Telemetry (Safe to Debloat)"
                    !isSystem -> "Installed User Application"
                    else -> "Internal System Service (Protected)"
                }
                CachedAppInfo(
                    label = lbl,
                    icon = icn,
                    versionName = ver,
                    sizeMb = sizeMb,
                    roleHint = hint,
                )
            }

            val entry = ManagedAppEntry(
                packageName = pkg,
                label = meta.label,
                roleHint = meta.roleHint,
                icon = meta.icon,
                versionName = meta.versionName,
                apkSizeMb = meta.sizeMb,
                isSystemApp = isSystem,
                isUpdatedSystemApp = isUpdatedSystem,
                isEnabled = isEnabled,
                isInstalledForUser = isInstalledForUser,
                isCriticalProtected = isCritical || (!isBloat && isSystem),
                isBloatware = isBloat,
                safetyLevel = safetyLevel,
                oemBrand = debloatDef?.oemBrand,
            )

            when {
                !isEnabled || !isInstalledForUser -> disabledOrRemovedList.add(entry)
                !isSystem -> userList.add(entry)
                isBloat -> bloatList.add(entry)
                else -> coreList.add(entry)
            }
        }

        val sortedUser = userList.sortedWith(
            compareByDescending<ManagedAppEntry> { it.apkSizeMb }
                .thenBy { it.label.lowercase(Locale.US) },
        )
        val sortedBloat = bloatList.sortedWith(
            compareByDescending<ManagedAppEntry> { it.packageName in KNOWN_BLOATWARE_PACKAGES }
                .thenByDescending { it.apkSizeMb }
                .thenBy { it.label.lowercase(Locale.US) },
        )
        val sortedCore = coreList.sortedWith(
            compareByDescending<ManagedAppEntry> { it.apkSizeMb }
                .thenBy { it.label.lowercase(Locale.US) },
        )
        val sortedDisabled = disabledOrRemovedList.sortedBy { it.label.lowercase(Locale.US) }
        val apkFiles = scanLocalApkFiles(pm)

        AppManagerSnapshot(
            userApps = sortedUser,
            bloatwareApps = sortedBloat,
            coreSystemApps = sortedCore,
            disabledOrRemovedApps = sortedDisabled,
            apkFiles = apkFiles,
        )
    }

    private suspend fun scanLocalApkFiles(pm: PackageManager): List<LocalApkFileEntry> {
        val discoveredPaths = LinkedHashSet<String>()

        // 1. Native File scan of common TV storage folders
        val candidateDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
            File("/sdcard/Download"),
            File("/sdcard/Downloads"),
            File("/sdcard"),
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0"),
        )
        for (dir in candidateDirs) {
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".apk", ignoreCase = true)) {
                        discoveredPaths.add(f.canonicalPath)
                    } else if (f.isDirectory && f.name.lowercase(Locale.US) in setOf("download", "downloads", "apk", "apks")) {
                        f.listFiles()?.forEach { sub ->
                            if (sub.isFile && sub.name.endsWith(".apk", ignoreCase = true)) {
                                discoveredPaths.add(sub.canonicalPath)
                            }
                        }
                    }
                }
            }
        }

        // 2. Also scan via Local ADB if connected (bypasses Android 11+ Scoped Storage restrictions on /sdcard)
        val runner = privilegeManager.active
        if (runner.isReady) {
            val res = runner.run("find /sdcard/Download /sdcard /storage/emulated/0/Download -maxdepth 2 -iname '*.apk' 2>/dev/null")
            if (res.executed) {
                res.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.endsWith(".apk", ignoreCase = true) }
                    .forEach { path ->
                        val normalized = path
                            .replaceFirst("/storage/emulated/0/", "/sdcard/")
                        if (discoveredPaths.none { it.endsWith(File(normalized).name) }) {
                            discoveredPaths.add(path)
                        }
                    }
            }
        }

        val entries = ArrayList<LocalApkFileEntry>()
        for (path in discoveredPaths) {
            val file = File(path)
            val fileName = file.name
            val sizeBytes = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)
            val sizeMb = sizeBytes / (1024f * 1024f)

            @Suppress("DEPRECATION")
            val pkgInfo = runCatching { pm.getPackageArchiveInfo(path, 0) }.getOrNull()
            val appInfo = pkgInfo?.applicationInfo?.apply {
                sourceDir = path
                publicSourceDir = path
            }
            val label = runCatching {
                if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else fileName.removeSuffix(".apk")
            }.getOrDefault(fileName.removeSuffix(".apk"))
            val icon = runCatching {
                if (appInfo != null) pm.getApplicationIcon(appInfo) else null
            }.getOrNull()

            entries.add(
                LocalApkFileEntry(
                    filePath = path,
                    fileName = fileName,
                    appLabel = label.ifBlank { fileName },
                    packageName = pkgInfo?.packageName,
                    versionName = pkgInfo?.versionName,
                    sizeMb = sizeMb,
                    icon = icon,
                ),
            )
        }
        return entries.sortedBy { it.appLabel.lowercase(Locale.US) }
    }

    /**
     * Uninstalls a single User or System app.
     * - User app: `pm uninstall <pkg>` via ADB, or native `ACTION_DELETE` dialog without ADB.
     * - System app: `pm uninstall -k --user 0 <pkg>` via ADB (removes system bloatware for user 0 without Root!),
     *   or opens System App Info without ADB.
     */
    suspend fun uninstallApp(app: ManagedAppEntry): ActionResult = withContext(Dispatchers.IO) {
        if (app.isCriticalProtected) {
            return@withContext ActionResult(false, "${app.label} is a core OS package and is protected.")
        }

        val runner = privilegeManager.active
        if (runner.isReady) {
            val cmd = if (app.isSystemApp) {
                "am force-stop ${app.packageName} 2>/dev/null; pm uninstall -k --user 0 ${app.packageName} 2>/dev/null || pm disable-user --user 0 ${app.packageName}"
            } else {
                "am force-stop ${app.packageName} 2>/dev/null; pm uninstall ${app.packageName}"
            }
            val res = runner.run(cmd)
            if (res.ok || res.stdout.contains("Success", ignoreCase = true) || res.stdout.contains("disabled", ignoreCase = true)) {
                return@withContext ActionResult(
                    success = true,
                    message = if (app.isSystemApp) {
                        "Removed system app ${app.label} for user 0 (can be restored anytime in Removed tab)"
                    } else {
                        "Uninstalled ${app.label}"
                    },
                )
            }
        }

        // Native No-ADB Fallback
        if (!app.isSystemApp || app.isUpdatedSystemApp) {
            val launched = launchNativeUninstall(app.packageName)
            if (launched) {
                return@withContext ActionResult(true, "Opened uninstaller for ${app.label}")
            }
        }
        processController.openAppInfoScreen(app.packageName)
        ActionResult(true, "Opened App Info for ${app.label}")
    }

    /**
     * Batch uninstalls MULTIPLE User and/or System apps at once in a single ADB shell session!
     */
    suspend fun batchUninstallApps(targets: List<ManagedAppEntry>): ActionResult = withContext(Dispatchers.IO) {
        val valid = targets.filter { !it.isCriticalProtected }
        if (valid.isEmpty()) {
            return@withContext ActionResult(false, "No uninstallable packages selected.")
        }

        val runner = privilegeManager.active
        if (runner.isReady) {
            val batchCmd = buildString {
                for (app in valid) {
                    if (app.isSystemApp) {
                        append("am force-stop ${app.packageName} 2>/dev/null; ")
                        append("(pm uninstall -k --user 0 ${app.packageName} 2>/dev/null || pm disable-user --user 0 ${app.packageName} 2>/dev/null) && echo 'OK_PKG'; ")
                    } else {
                        append("am force-stop ${app.packageName} 2>/dev/null; ")
                        append("pm uninstall ${app.packageName} 2>/dev/null && echo 'OK_PKG'; ")
                    }
                }
            }
            val res = runner.run(batchCmd)
            val okCount = res.stdout.lines().count {
                it.contains("OK_PKG") || it.contains("Success", ignoreCase = true) || it.contains("disabled", ignoreCase = true)
            }.coerceIn(if (res.executed) 1 else 0, valid.size)
            return@withContext ActionResult(
                success = okCount > 0,
                message = "Uninstalled / removed $okCount of ${valid.size} selected app(s)",
            )
        }

        // Native No-ADB fallback: trigger native uninstall prompt
        for (app in valid) {
            if (!app.isSystemApp || app.isUpdatedSystemApp) {
                launchNativeUninstall(app.packageName)
            } else {
                processController.openAppInfoScreen(app.packageName)
            }
        }
        ActionResult(true, "Launched uninstaller for ${valid.size} app(s)")
    }

    /**
     * Disables one or multiple apps (`pm disable-user --user 0 <pkg>`) in a single batched ADB session.
     */
    suspend fun batchDisableApps(targets: List<ManagedAppEntry>): ActionResult = withContext(Dispatchers.IO) {
        val valid = targets.filter { !it.isCriticalProtected }
        if (valid.isEmpty()) {
            return@withContext ActionResult(false, "Selected packages are protected core OS components.")
        }

        val runner = privilegeManager.active
        if (runner.isReady) {
            val batchCmd = buildString {
                for (app in valid) {
                    append("am force-stop ${app.packageName} 2>/dev/null; pm disable-user --user 0 ${app.packageName} 2>/dev/null && echo 'OK_DIS'; ")
                }
            }
            val res = runner.run(batchCmd)
            val okCount = res.stdout.lines().count {
                it.contains("OK_DIS") || it.contains("disabled", ignoreCase = true)
            }.coerceIn(if (res.executed) 1 else 0, valid.size)
            return@withContext ActionResult(
                success = okCount > 0,
                message = "Disabled $okCount of ${valid.size} app(s) — won't run in background",
            )
        }

        processController.openAppInfoScreen(valid.first().packageName)
        ActionResult(true, "Opened App Info to disable ${valid.first().label}")
    }

    /**
     * Re-enables or restores one or multiple disabled / user-0 uninstalled system apps in a single batched ADB session!
     */
    suspend fun batchRestoreOrEnableApps(targets: List<ManagedAppEntry>): ActionResult = withContext(Dispatchers.IO) {
        if (targets.isEmpty()) {
            return@withContext ActionResult(false, "No packages selected.")
        }

        val runner = privilegeManager.active
        if (runner.isReady) {
            val batchCmd = buildString {
                for (app in targets) {
                    append("cmd package install-existing ${app.packageName} 2>/dev/null || pm install-existing ${app.packageName} 2>/dev/null || true; ")
                    append("(pm enable --user 0 ${app.packageName} 2>/dev/null || pm enable ${app.packageName} 2>/dev/null) && echo 'OK_EN'; ")
                }
            }
            val res = runner.run(batchCmd)
            val okCount = res.stdout.lines().count {
                it.contains("OK_EN") || it.contains("enabled", ignoreCase = true) || it.contains("installed", ignoreCase = true)
            }.coerceIn(if (res.executed) 1 else 0, targets.size)
            return@withContext ActionResult(
                success = okCount > 0,
                message = "Restored & enabled $okCount of ${targets.size} app(s)",
            )
        }

        processController.openAppInfoScreen(targets.first().packageName)
        ActionResult(true, "Opened App Info to enable ${targets.first().label}")
    }

    /**
     * Freezes/suspends an app (`pm suspend <pkg>`), preventing all background wakeups and hiding it from the launcher.
     */
    suspend fun suspendApp(packageName: String): ActionResult = withContext(Dispatchers.IO) {
        val runner = privilegeManager.active
        if (runner.isReady) {
            val res = runner.run("am force-stop $packageName 2>/dev/null; pm suspend $packageName")
            if (res.ok || res.stdout.contains("Success", ignoreCase = true) || res.stdout.contains("suspended", ignoreCase = true)) {
                return@withContext ActionResult(true, "Suspended $packageName (Frozen)")
            }
        }
        ActionResult(false, "Requires ADB to suspend packages.")
    }

    /**
     * Un-suspends a frozen app (`pm unsuspend <pkg>`).
     */
    suspend fun unsuspendApp(packageName: String): ActionResult = withContext(Dispatchers.IO) {
        val runner = privilegeManager.active
        if (runner.isReady) {
            val res = runner.run("pm unsuspend $packageName")
            if (res.ok || res.stdout.contains("Success", ignoreCase = true) || res.stdout.contains("unsuspended", ignoreCase = true)) {
                return@withContext ActionResult(true, "Un-suspended $packageName")
            }
        }
        ActionResult(false, "Requires ADB to unsuspend packages.")
    }

    /**
     * Restricts background execution via AppOps (`cmd appops set <pkg> RUN_IN_BACKGROUND ignore`).
     */
    suspend fun restrictBackgroundAppOps(packageName: String): ActionResult = withContext(Dispatchers.IO) {
        val runner = privilegeManager.active
        if (runner.isReady) {
            val res = runner.run("cmd appops set $packageName RUN_IN_BACKGROUND ignore && cmd appops set $packageName RUN_ANY_IN_BACKGROUND ignore")
            if (res.ok) {
                return@withContext ActionResult(true, "Restricted background execution for $packageName")
            }
        }
        ActionResult(false, "Requires ADB to restrict AppOps.")
    }

    /**
     * Installs a local `.apk` file (`pm install -r "<path>"` via ADB, or native PackageInstaller via FileProvider).
     */
    suspend fun installLocalApk(apk: LocalApkFileEntry): ActionResult = withContext(Dispatchers.IO) {
        val runner = privilegeManager.active
        if (runner.isReady) {
            val escaped = apk.filePath.replace("\"", "\\\"")
            val res = runner.run("pm install -r -d \"$escaped\" 2>&1")
            if (res.ok || res.stdout.contains("Success", ignoreCase = true)) {
                return@withContext ActionResult(
                    success = true,
                    message = "Installed ${apk.appLabel} (${apk.fileName}) ✓",
                )
            } else {
                return@withContext ActionResult(
                    success = false,
                    message = "Install failed: ${res.reason()}",
                )
            }
        }

        val opened = runCatching {
            AccessibilityGate.markLaunchingExternalActivity()
            val file = File(apk.filePath)
            val apkUri = runCatching {
                FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            }.getOrElse { Uri.fromFile(file) }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)

        ActionResult(
            success = opened,
            message = if (opened) "Opened installer for ${apk.fileName}" else "Enable Local ADB to silently install ${apk.fileName}",
        )
    }

    /**
     * Deletes a local `.apk` file from storage to reclaim disk space.
     */
    suspend fun deleteLocalApk(apk: LocalApkFileEntry): ActionResult = withContext(Dispatchers.IO) {
        val deletedNative = runCatching { File(apk.filePath).delete() }.getOrDefault(false)
        if (!deletedNative && privilegeManager.active.isReady) {
            val escaped = apk.filePath.replace("\"", "\\\"")
            privilegeManager.active.run("rm -f \"$escaped\"")
        }
        ActionResult(true, "Deleted ${apk.fileName} (${String.format(Locale.US, "%.1f MB", apk.sizeMb)} freed)")
    }

    private fun launchNativeUninstall(packageName: String): Boolean = runCatching {
        AccessibilityGate.markLaunchingExternalActivity()
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", packageName, null)).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        true
    }.getOrElse {
        runCatching {
            AccessibilityGate.markLaunchingExternalActivity()
            val fallback = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(fallback)
            true
        }.getOrDefault(false)
    }

    companion object {
        private val CORE_PROTECTED_PACKAGES = setOf(
            "android",
            "system",
            "com.android.systemui",
            "com.android.tv.settings",
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.shell",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
            "com.google.android.tv.remote.service",
            "com.android.providers.media.module",
            "com.android.providers.contacts",
            "com.android.providers.telephony",
            "com.android.providers.downloads",
            "com.android.wifi.resources",
            "com.android.connectivity.resources",
            "com.android.networkstack.resources",
            "com.android.companiondevicemanager",
            "com.android.cts.priv.ctsshim",
        )

        private val CORE_PROTECTED_PREFIXES = listOf(
            "com.android.server.",
            "android.ext.",
            "com.android.internal.",
            "com.google.android.overlay.modules.",
        )

        private val OEM_BLOAT_PREFIXES = listOf(
            "org.droidtv.",
            "com.tpv.",
            "com.tcl.",
            "com.tct.",
            "com.hisense.",
            "com.jamdeo.",
            "com.xiaomi.",
            "com.mitv.",
            "com.duokan.",
            "com.sony.dtv.",
            "com.sony.",
            "com.amazon.tv.",
            "com.amazon.bueller.",
        )

        private val STREAMING_BLOAT_PREFIXES = listOf(
            "com.netflix.",
            "com.amazon.amazonvideo.",
            "com.disney.",
            "com.apple.atve.",
            "com.hulu.",
            "com.peacocktv.",
            "com.hbo.",
            "tv.twitch.",
            "com.spotify.",
        )

        private val KNOWN_BLOATWARE_PACKAGES = setOf(
            "com.apps.factory.ui",
            "com.apps.esticker3",
            "com.realtek.tvfactory",
            "com.realtek.setupwizard",
            "com.realtek.leanbacklauncher.partnercustomizer",
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
            "com.realsil.ota",
            "com.google.android.tv.dfuservice",
            "com.android.dreams.basic",
        )

        fun isCoreProtected(pkg: String): Boolean {
            if (pkg in CORE_PROTECTED_PACKAGES) return true
            for (prefix in CORE_PROTECTED_PREFIXES) {
                if (pkg.startsWith(prefix)) return true
            }
            return false
        }

        fun isSafeBloatware(pkg: String, hasLauncher: Boolean): Boolean {
            if (isCoreProtected(pkg)) return false
            if (pkg in KNOWN_BLOATWARE_PACKAGES) return true
            for (prefix in OEM_BLOAT_PREFIXES) {
                if (pkg.startsWith(prefix)) return true
            }
            for (prefix in STREAMING_BLOAT_PREFIXES) {
                if (pkg.startsWith(prefix)) return true
            }
            val lower = pkg.lowercase(Locale.US)
            if (lower.contains("esticker") ||
                lower.contains("retail") ||
                lower.contains("shopmode") ||
                lower.contains("demomode") ||
                lower.contains("factory") ||
                lower.contains("telemetry") ||
                lower.contains("partnercustomizer") ||
                lower.contains("setupwraith")
            ) {
                return true
            }
            if (hasLauncher) return true
            return false
        }
    }
}
