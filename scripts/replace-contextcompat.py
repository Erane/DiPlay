# Replaces androidx ContextCompat.getSystemService/checkSelfPermission call sites with
# local shims that work on Android 4.4 (androidx.core 1.19 dropped the pre-23 fallbacks).
# Run from the repo root: python scripts/replace-contextcompat.py
import io, glob, re

# Shared Compat.kt gains the shims (public top-level, package com.shilapi.xcertplay).
f = 'shared/src/main/java/com/shilapi/xcertplay/Compat.kt'
s = io.open(f, encoding='utf-8').read()
if 'systemServiceCompat' not in s:
    s = s.rstrip() + '''

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
    else -> null
}

/**
 * getSystemService(Class) is API 23, and androidx.core 1.19's ContextCompat no longer carries
 * the pre-23 fallback - so the pre-23 units use the string-based lookup (API 1) with the
 * framework's own service-name mapping.
 */
fun <T> Context.systemServiceCompat(serviceClass: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= 23) {
        getSystemService(serviceClass)
    } else {
        val name = systemServiceNameFor(serviceClass) ?: return null
        @Suppress("DEPRECATION")
        getSystemService(name) as T?
    }

/** checkSelfPermission(String) is API 23; pre-23 grants dangerous permissions at install. */
fun Context.checkSelfPermissionCompat(permission: String): Int =
    if (Build.VERSION.SDK_INT >= 23) {
        checkSelfPermission(permission)
    } else {
        @Suppress("DEPRECATION")
        checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
    }
'''
    io.open(f, 'w', encoding='utf-8', newline='').write(s)
    print('Compat.kt extended')

# ---------- global call-site replacement ----------
changed = []
for pattern in ('common/src/main/java/**/*.kt', 'shared/src/main/java/**/*.kt', 'mobile/src/main/java/**/*.kt'):
    for path in glob.glob(pattern, recursive=True):
        s = io.open(path, encoding='utf-8').read()
        orig = s
        # typed getSystemService: ContextCompat.getSystemService(recv, X::class.java) -> systemServiceCompat(recv, X::class.java)
        s = re.sub(r'ContextCompat\.getSystemService\(([^,()]+), (\w+)::class\.java\)',
                   r'systemServiceCompat(\1, \2::class.java)', s)
        # string-based (the sink's AUDIO_SERVICE path): keep as-is (API 1) - no change needed
        # checkSelfPermission: ContextCompat.checkSelfPermission(ctx, perm) -> ctx.checkSelfPermissionCompat(perm)
        s = re.sub(r'ContextCompat\.checkSelfPermission\(([^,()]+), ([^)]+)\)',
                   r'\1.checkSelfPermissionCompat(\2)', s)
        if s != orig:
            # ensure import
            if 'com.shilapi.xcertplay.systemServiceCompat' not in s and 'systemServiceCompat(' in s:
                needs_import = re.search(r'systemServiceCompat\(', s) is not None and 'package com.shilapi.xcertplay\n' not in s.split('\n\n')[0]
                if 'package com.shilapi.xcertplay\n' not in s.split('\n\n')[0]:
                    if 'import com.shilapi.xcertplay.systemServiceCompat' not in s:
                        idx = s.find('\nimport ')
                        if idx >= 0:
                            s = s[:idx] + '\nimport com.shilapi.xcertplay.systemServiceCompat\nimport com.shilapi.xcertplay.checkSelfPermissionCompat' + s[idx:]
                        else:
                            s = 'import com.shilapi.xcertplay.systemServiceCompat\nimport com.shilapi.xcertplay.checkSelfPermissionCompat\n' + s
            io.open(path, 'w', encoding='utf-8', newline='').write(s)
            changed.append(path)
print('changed files:', len(changed))
for c in changed: print(' ', c)

# leftover scan: any remaining ContextCompat.getSystemService/checkSelfPermission with tricky receivers
left = 0
for pattern in ('common/src/main/java/**/*.kt', 'shared/src/main/java/**/*.kt', 'mobile/src/main/java/**/*.kt'):
    for path in glob.glob(pattern, recursive=True):
        for i, line in enumerate(io.open(path, encoding='utf-8')):
            if 'ContextCompat.getSystemService' in line or 'ContextCompat.checkSelfPermission' in line:
                print('LEFT:', path, i + 1, line.strip()[:100])
                left += 1
print('leftovers:', left)
