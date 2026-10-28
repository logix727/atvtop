package com.atvpm.tvcontrol.domain

enum class DebloatSafetyLevel(val label: String, val badgeColorHex: Long) {
    SAFE("Safe to Remove", 0xFF10B981),        // Green: Pure telemetry, shop demo, partner stubs
    RECOMMENDED("Recommended", 0xFF3B82F6),   // Blue: Unused OEM launchers, screensaver ads, redundant assistants
    ADVANCED("Advanced", 0xFFF59E0B),         // Orange: Stock voice input, OEM keyboards, setup wizards
    CORE_LOCKED("Core OS (Locked)", 0xFFEF4444) // Red: Framework, Bluetooth, Settings, SystemUI
}

enum class DebloatCategory(val label: String) {
    OEM_TELEMETRY("OEM Telemetry & Ads"),
    GOOGLE_TV("Google TV & Launcher Ads"),
    SPONSORED_PARTNER("Pre-installed Partner Stubs"),
    DEMO_SHOP_MODE("Shop & Retail Demo Mode"),
    SYSTEM_FRAMEWORK("Core Framework")
}

data class DebloatDefinition(
    val packageName: String,
    val humanName: String,
    val oemBrand: String,
    val category: DebloatCategory,
    val safetyLevel: DebloatSafetyLevel,
    val description: String,
)

object DebloatDatabase {

    private val ENTRIES = listOf(
        // Google TV / Android TV Home & Launcher Ads
        DebloatDefinition(
            "com.google.android.apps.tv.launcherx",
            "Google TV Home Launcher (Sponsored Banners)",
            "Google TV",
            DebloatCategory.GOOGLE_TV,
            DebloatSafetyLevel.ADVANCED,
            "Injects sponsored home screen video ads and promoted channels. Can be replaced with custom launchers (e.g. ProjectIvy, FLauncher)."
        ),
        DebloatDefinition(
            "com.google.android.tvrecommendations",
            "Google TV Recommendations Service",
            "Google TV",
            DebloatCategory.GOOGLE_TV,
            DebloatSafetyLevel.RECOMMENDED,
            "Pushes promotional media cards and trending banners to the home screen background."
        ),
        DebloatDefinition(
            "com.google.android.tungsten.setupwraith",
            "Android TV Setup Wraith",
            "Google TV",
            DebloatCategory.GOOGLE_TV,
            DebloatSafetyLevel.SAFE,
            "Initial setup wizard daemon. Lingers in background consuming memory after initial setup."
        ),
        DebloatDefinition(
            "com.google.android.videos",
            "Google TV Movies & TV Stub",
            "Google TV",
            DebloatCategory.GOOGLE_TV,
            DebloatSafetyLevel.RECOMMENDED,
            "Legacy Google Play Movies & TV storefront."
        ),

        // Sony Bravia
        DebloatDefinition(
            "com.sony.dtv.braviasync",
            "Sony BRAVIA Sync Telemetry",
            "Sony",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.RECOMMENDED,
            "Background analytics logging CEC device usage and power cycles."
        ),
        DebloatDefinition(
            "com.sony.dtv.demo",
            "Sony Bravia Retail Demo",
            "Sony",
            DebloatCategory.DEMO_SHOP_MODE,
            DebloatSafetyLevel.SAFE,
            "Store showroom looping 4K HDR demo videos and retail sticker overlays."
        ),
        DebloatDefinition(
            "com.sony.dtv.smarthelp",
            "Sony Bravia Help Guide",
            "Sony",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.RECOMMENDED,
            "Offline help manual app, regularly polls Sony servers for catalog updates."
        ),
        DebloatDefinition(
            "com.sony.dtv.networktelemetry",
            "Sony Network Telemetry Service",
            "Sony",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.SAFE,
            "Aggregates Sony TV viewing stats and internet connection metrics."
        ),

        // TCL
        DebloatDefinition(
            "com.tcl.partnercustomizer",
            "TCL Partner Customizer & Ad Injector",
            "TCL",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.SAFE,
            "Injects sponsored third-party apps, featured promotions, and OEM ads on TCL TVs."
        ),
        DebloatDefinition(
            "com.tcl.commercial.tv",
            "TCL Shop & Commercial Mode",
            "TCL",
            DebloatCategory.DEMO_SHOP_MODE,
            DebloatSafetyLevel.SAFE,
            "Store display mode. Not needed in personal home setups."
        ),
        DebloatDefinition(
            "com.tcl.useragreement",
            "TCL Data Collection Daemon",
            "TCL",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.SAFE,
            "Continually sends anonymized viewing habits to TCL telemetry cloud."
        ),
        DebloatDefinition(
            "com.tcl.tv.launcher",
            "TCL Channel Launcher",
            "TCL",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.RECOMMENDED,
            "Custom TCL streaming channel row overlay."
        ),

        // Philips
        DebloatDefinition(
            "com.tpv.shopmode",
            "Philips Ambilight Shop Mode",
            "Philips",
            DebloatCategory.DEMO_SHOP_MODE,
            DebloatSafetyLevel.SAFE,
            "Philips retail display mode with looping store videos."
        ),
        DebloatDefinition(
            "com.tpv.telemetry",
            "Philips Smart TV Analytics",
            "Philips",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.SAFE,
            "Collects Ambilight usage profiles and app launch metrics."
        ),

        // Xiaomi / Mi TV
        DebloatDefinition(
            "com.xiaomi.mitv.advertise",
            "Xiaomi Mi TV Ad Service",
            "Xiaomi",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.SAFE,
            "Startup boot ads and banner promotions on Xiaomi Mi TV and Mi Box devices."
        ),
        DebloatDefinition(
            "com.xiaomi.mitv.shopmode",
            "Xiaomi Mi TV Shop Mode",
            "Xiaomi",
            DebloatCategory.DEMO_SHOP_MODE,
            DebloatSafetyLevel.SAFE,
            "Xiaomi showroom demo app."
        ),
        DebloatDefinition(
            "com.mitv.tvhome",
            "PatchWall Launcher (Xiaomi)",
            "Xiaomi",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.RECOMMENDED,
            "Heavily ad-supported PatchWall alternative launcher for Mi TVs."
        ),

        // Walmart Onn. & Hisense
        DebloatDefinition(
            "com.hisense.tv.service",
            "Hisense TV Telemetry Hub",
            "Hisense",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.SAFE,
            "Manufacturer diagnostic and analytics reporter on Hisense Google TVs."
        ),
        DebloatDefinition(
            "com.walmart.onn.tvservice",
            "Walmart Onn Promotional Stub",
            "Walmart Onn",
            DebloatCategory.SPONSORED_PARTNER,
            DebloatSafetyLevel.SAFE,
            "Pre-installed promotional partner stub on Onn 4K streaming devices."
        ),

        // Amazon Fire TV
        DebloatDefinition(
            "com.amazon.device.sale.service",
            "Amazon Retail Promotion Daemon",
            "Amazon",
            DebloatCategory.SPONSORED_PARTNER,
            DebloatSafetyLevel.SAFE,
            "Pushes Amazon promotional banners and Prime video upsells."
        ),
        DebloatDefinition(
            "com.amazon.tv.launcher",
            "Amazon Fire TV Home (Ad Heavy)",
            "Amazon",
            DebloatCategory.OEM_TELEMETRY,
            DebloatSafetyLevel.ADVANCED,
            "Stock Fire TV launcher. Disabling requires a replacement custom launcher."
        ),

        // Sponsored Partner Stubs
        DebloatDefinition(
            "com.sling",
            "Sling TV Pre-installed Stub",
            "Partner",
            DebloatCategory.SPONSORED_PARTNER,
            DebloatSafetyLevel.SAFE,
            "Pre-installed sponsored live TV stub."
        ),
        DebloatDefinition(
            "com.disney.disneyplus",
            "Disney+ Sponsored Stub",
            "Partner",
            DebloatCategory.SPONSORED_PARTNER,
            DebloatSafetyLevel.SAFE,
            "OEM factory pre-installed partner app stub."
        ),
        DebloatDefinition(
            "com.netflix.ninja",
            "Netflix Factory Stub",
            "Partner",
            DebloatCategory.SPONSORED_PARTNER,
            DebloatSafetyLevel.SAFE,
            "OEM factory pre-installed partner app stub."
        ),
    )

    private val LOOKUP: Map<String, DebloatDefinition> = ENTRIES.associateBy { it.packageName }

    fun find(packageName: String): DebloatDefinition? = LOOKUP[packageName]

    fun getSafetyLevel(packageName: String, isSystem: Boolean, isCoreCritical: Boolean): DebloatSafetyLevel {
        if (isCoreCritical) return DebloatSafetyLevel.CORE_LOCKED
        val def = LOOKUP[packageName]
        if (def != null) return def.safetyLevel
        if (!isSystem) return DebloatSafetyLevel.SAFE
        return DebloatSafetyLevel.CORE_LOCKED
    }

    val allKnownBloatPackages: Set<String> = LOOKUP.keys
}
