# ⚡ atvtop — ATV Control v2.0

> **`htop` / `btop`-inspired Task Manager, PID Tree Inspector, OEM Bloatware Remover & Unified Power Center for Android TV & Google TV**

![Android TV](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-blue)
![API](https://img.shields.io/badge/API-24%2B%20(Android%207.0%20--%2014)-green)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20TV-purple)
![License](https://img.shields.io/badge/License-Apache%202.0-orange)

**atvtop** (**ATV Control**) is a high-performance utility engineered from the ground up for modern large-screen TVs (from 43" to 85"+). Inspired by Linux's `top`, `htop`, and `btop`, it solves the #1 complaint of Android TV and Google TV users: **background sluggishness, low available RAM, frozen launchers, and OEM pre-installed bloatware**.

---

## 🌟 Key Features

### 1. ⚡ Precision Task Manager, Live RAM Sparkline & PID Inspector
- **1-Click "Kill All" & "Kill All & Exit"**: Safely frees up hundreds of megabytes of RAM without breaking TV audio or video inputs, with an option to clean memory and return to the Home screen in a single click.
- **Live Rolling RAM Sparkline Graph**: Real-time memory telemetry bar and 32-point rolling sparkline graph with color-coded load thresholds.
- **Deep PID Tree (`htop`-style)**: Click any running app to expand its active child process tree (`main`, `:sandboxed`, `:media`, etc.) with individual RSS memory usage and direct 1-click PID termination (`kill -9`).
- **Zero Background Resource Footprint**: Frees memory on exit while preserving Accessibility Service authorization across sessions.
- **Accurate System vs. User App Badging**: Distinguishes pure framework services from user-installed streaming apps.

### 2. 📦 Intelligent OEM Bloatware Remover (Safe Debloater)
- **Safe Bloatware vs. Core OS Separation**: Categorizes pre-installed packages into:
  - **Bloatware (Safe to Remove)**: Pre-installed manufacturer telemetry, demo stickers, shop mode apps, partner promo daemons (Philips, TCL, Sony, Hisense, Xiaomi), and sponsored streaming stubs.
  - **Core OS (Locked)**: Critical Android TV system components (SystemUI, Settings, PackageInstaller, Bluetooth, GMS Core) are protected and locked to prevent accidental bootloops or bricks.
- **1-Click "Select All Bloatware"**: Instantly mark all detected OEM bloatware and batch-disable or uninstall them for User 0 (`pm uninstall -k --user 0`) without needing Root!
- **1-Click App Restorer**: Easily restore or re-enable any disabled or uninstalled package from the *Disabled / Removed* tab.
- **Local APK Installer**: Sideload and install `.apk` files directly from TV internal storage or `/sdcard/Download`.

### 3. ⟳ Unified Power & Reboot Controls
Combines all essential power modes into a clean, intuitive 6-card control center:
1. **Restart TV**: Standard safe system reboot (works natively or via ADB).
2. **Quick UI Restart**: Fast Android TV UI reload (`stop && start`) without long hardware boot cycles.
3. **Reload Home Screen**: Instantly restarts Google TV / Android TV launcher to fix frozen or sluggish home screens.
4. **Sleep / Standby**: Puts the TV screen and backlight directly into sleep mode.
5. **Power Off**: Full TV shutdown or native TV power menu dialog.
6. **Recovery Mode**: Reboots directly into Android TV Recovery for maintenance or factory resets.

---

## 📺 Supported TV Devices & OS Compatibility

Tested and optimized for Android 8.0 through Android 14:
- **Philips Android TV / Google TV** (Ambilight series, OLED, Mini-LED)
- **Sony Bravia** (Google TV & Android TV models)
- **TCL Smart TVs** (Google TV & Android TV)
- **Hisense Smart TVs** (Android TV / Google TV series)
- **Google Chromecast** (Chromecast with Google TV HD & 4K)
- **Nvidia Shield TV & Shield TV Pro**
- **Walmart Onn. Google TV 4K & Onn. Pro 4K**
- **Xiaomi Mi Box & Mi TV Stick**
- **Amazon Fire TV** (Omni TV, Fire TV Stick 4K Max — sideload / Amazon Appstore)

---

## 🛠️ Build & Installation

### Build Release APK:
```bash
./gradlew assembleRelease --no-daemon
```
The optimized, Proguard-shrunk release APK is generated at:
`app/build/outputs/apk/release/app-release.apk`

### Install via ADB:
```bash
adb connect <your-tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 📜 Permissions & Security
- **No Root Required**: All user-level operations work out of the box.
- **Optional Local ADB**: Unlocks silent batch force-stopping, User 0 bloatware removal, and advanced boot modes.
- **Accessibility Service**: Used solely for native power menu actions and automated app force-stop dialogs.
