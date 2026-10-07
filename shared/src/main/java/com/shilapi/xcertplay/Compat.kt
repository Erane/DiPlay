package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbInterface
import android.os.Build
import java.io.File

/** getNoBackupFilesDir exists from API 21; older units fall back to the same on-disk path. */
fun Context.compatNoBackupFilesDir(): File =
    if (Build.VERSION.SDK_INT >= 21) noBackupFilesDir else File(applicationInfo.dataDir, "no_backup")

/**
 * UsbInterface.getAlternateSetting is API 21 and is absent from the 4.x framework, so reading it
 * there throws NoSuchMethodError rather than returning a default. Null means "this unit cannot
 * report an alternate setting", which callers must handle instead of assuming setting 0.
 */
fun UsbInterface.compatAlternateSetting(): Int? =
    if (Build.VERSION.SDK_INT >= 21) alternateSetting else null

/** Service-name lookup for the string-based getSystemService (API 1) used below API 23. */
private fun systemServiceNameFor(cls: Class<*>): String? = when (cls.name) {
    "android.media.AudioManager" -> "audio"
    "android.app.NotificationManager" -> "notification"
    "android.net.wifi.WifiManager" -> "wifi"
    "android.net.ConnectivityManager" -> "connectivity"
    "android.hardware.usb.UsbManager" -> "usb"
    "android.bluetooth.BluetoothManager" -> "bluetooth"
    "android.hardware.display.DisplayManager" -> "display"
    "android.location.LocationManager" -> "location"
    "android.net.nsd.NsdManager" -> "servicediscovery"
    "android.app.UiModeManager" -> "uimode"
    "android.app.ActivityManager" -> "activity"
    "android.app.usage.UsageStatsManager" -> "usagestats"
    "android.app.AppOpsManager" -> "appops"
    "android.view.inputmethod.InputMethodManager" -> "input_method"
    "android.os.PowerManager" -> "power"
    "android.net.TetheringManager" -> "tethering"
    "android.net.wifi.p2p.WifiP2pManager" -> "wifip2p"
    "android.content.ClipboardManager" -> "clipboard"
    else -> null
}

/**
 * getSystemService(Class) is API 23, and androidx.core 1.19's ContextCompat no longer carries
 * the pre-23 fallback - so the pre-23 units use the string-based lookup (API 1) with the
 * framework's own service-name mapping.
 */
fun <T> systemServiceCompat(context: Context, serviceClass: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= 23) {
        context.getSystemService(serviceClass)
    } else {
        val name = systemServiceNameFor(serviceClass) ?: return null
        @Suppress("DEPRECATION")
        context.getSystemService(name) as T?
    }

/** checkSelfPermission(String) is API 23; pre-23 grants dangerous permissions at install. */
fun checkSelfPermissionCompat(context: Context, permission: String): Int =
    if (Build.VERSION.SDK_INT >= 23) {
        context.checkSelfPermission(permission)
    } else {
        @Suppress("DEPRECATION")
        context.checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
    }
