# Adds the PASSIVE_HOTSPOT wireless mode: for Android 4.3/4.4 units whose OWN system hotspot
# is the CarPlay network - no hotspot management APIs are touched, the unit's Wi-Fi interface
# IPv4 is found by scanning NetworkInterface, and NSD attribute calls are gated below API 21.
# Run from the repo root: python scripts/add-passive-hotspot.py
import io

def patch(path, old, new, label):
    s = io.open(path, encoding='utf-8').read()
    if new in s:
        print('SKIP (applied)', label)
        return
    if old not in s:
        print('MISS', label, 'in', path)
        raise SystemExit(1)
    s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8', newline='').write(s)
    print('patched', label)

# ---------- 1. enum values ----------
patch('shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayRuntimeConfig.kt',
      """enum class WirelessHotspotMode {
    WIFI_P2P,
    LOCAL_ONLY_HOTSPOT,
    MANUAL,
    EXISTING_WIFI,
}""",
      """enum class WirelessHotspotMode {
    WIFI_P2P,
    LOCAL_ONLY_HOTSPOT,
    MANUAL,
    EXISTING_WIFI,
    /** The unit's own system hotspot is the CarPlay network; no hotspot management is needed. */
    PASSIVE_HOTSPOT,
}""",
      'WirelessHotspotMode.PASSIVE_HOTSPOT')

patch('shared/src/main/java/com/shilapi/xcertplay/network/WirelessHotspotManager.kt',
      """enum class WirelessHotspotBackend(val label: String) {
    WIFI_P2P("Wi-Fi P2P"),
    LOCAL_ONLY_HOTSPOT("LocalOnlyHotspot"),
    MANUAL_HOTSPOT("Manual hotspot"),
    EXISTING_WIFI("Existing Wi-Fi / Same LAN"),
}""",
      """enum class WirelessHotspotBackend(val label: String) {
    WIFI_P2P("Wi-Fi P2P"),
    LOCAL_ONLY_HOTSPOT("LocalOnlyHotspot"),
    MANUAL_HOTSPOT("Manual hotspot"),
    EXISTING_WIFI("Existing Wi-Fi / Same LAN"),
    /** Pre-21 units: the system hotspot is the network; the interface is discovered by scan. */
    SYSTEM_HOTSPOT_PASSIVE("System hotspot (passive)"),
}""",
      'WirelessHotspotBackend.SYSTEM_HOTSPOT_PASSIVE')

# ---------- 2. startWirelessHotspot: passive branch before the <29 rejection ----------
patch('shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt',
      """    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        // Every wireless manager below Q relies on APIs that do not exist there (startLocalOnlyHotspot
        // is API 26, Channel.close 27, MacAddress 28). Android 6-9 units are wired-only by design, so
        // fail the wireless run with a clear message instead of crashing on a missing method.
        if (Build.VERSION.SDK_INT < 29) {""",
      """    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        // Pre-21 units whose own system hotspot carries the CarPlay network: the iPhone attaches
        // to the unit's hotspot, so no hotspot management APIs are needed - scan the interfaces
        // for the unit's Wi-Fi IPv4 and run discovery on it.
        if (Build.VERSION.SDK_INT < 29 &&
            config.wirelessHotspotMode == WirelessHotspotMode.PASSIVE_HOTSPOT
        ) {
            return passiveHotspotInfo(generation)
        }
        // Every wireless manager below Q relies on APIs that do not exist there (startLocalOnlyHotspot
        // is API 26, Channel.close 27, MacAddress 28). Android 6-9 units are wired-only by design, so
        // fail the wireless run with a clear message instead of crashing on a missing method.
        if (Build.VERSION.SDK_INT < 29) {""",
      'startWirelessHotspot passive branch')

# ---------- 3. passiveHotspotInfo implementation ----------
patch('shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt',
      """    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {""",
      """    /**
     * Pre-29 passive hotspot: the system hotspot is already up and the iPhone attaches to it.
     * Finds the unit's Wi-Fi interface IPv4 by scanning [NetworkInterface] (AP interfaces are
     * named ap0/wlan0/swlan0 etc. depending on the SoC) and reports it as the AirPlay host.
     */
    private fun passiveHotspotInfo(generation: Int): WirelessHotspotInfo {
        onStatus(CarPlayStatus.StartingHotspot, generation)
        val candidates = mutableListOf<Pair<java.net.NetworkInterface, java.net.Inet4Address>>()
        try {
            val enumerated = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in enumerated) {
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                val looksLikeWifiAp = name.startsWith("ap") || name.startsWith("wlan") ||
                    name.startsWith("swlan") || name.startsWith("softap")
                if (!looksLikeWifiAp) continue
                for (address in iface.inetAddresses) {
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        candidates += iface to address
                    }
                }
            }
        } catch (error: Exception) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Could not enumerate network interfaces: ${error.message}",
            )
        }
        if (candidates.isEmpty()) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY,
                "The system hotspot interface is not up yet. Turn the car hotspot on in the car settings and try again.",
            )
        }
        val (iface, address) = candidates.first()
        debugLog(
            "passive hotspot interface=${iface.name} host=$address " +
                "candidates=${candidates.joinToString { (i, a) -> i.name + "=" + a.hostAddress }}",
        )
        val ssid = config.existingWifiSsid.ifBlank { "hotspot" }
        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = config.existingWifiPassphrase,
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = 0,
            frequencyMHz = null,
            bssid = null,
            interfaceName = iface.name,
            hostAddress = address,
            bandLabel = "2.4 GHz (passive)",
            backend = com.shilapi.xcertplay.network.WirelessHotspotBackend.SYSTEM_HOTSPOT_PASSIVE,
            hostAddresses = candidates.map { (_, a) -> a },
        )
    }

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {""",
      'passiveHotspotInfo implementation')

# ---------- 4. manager dispatch exhaustiveness ----------
patch('shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt',
      """            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,""",
      """            WirelessHotspotMode.PASSIVE_HOTSPOT ->
                throw IllegalStateException("PASSIVE_HOTSPOT is handled without a wireless manager")
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,""",
      'manager dispatch exhaustiveness')

# ---------- 5. CarPlayBonjour NSD attribute gates (<21) ----------
patch('shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt',
      """            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity).forEach { (key, value) ->
                setAttribute(key, value)
            }""",
      """            // NsdServiceInfo.setAttribute is API 21; pre-21 units publish without TXT extras.
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                CarPlayBonjourProtocol.airPlayTxtRecords(config, identity).forEach { (key, value) ->
                    setAttribute(key, value)
                }
            }""",
      'registerAirPlay setAttribute gate')

patch('shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt',
      """        val bluetoothId = resolved.attributes
            ?.get("id")
            ?.let(::decodeTxtValue)
            ?.takeIf { it.isNotBlank() }""",
      """        // NsdServiceInfo.getAttributes is API 21; pre-21 discovery proceeds without the id.
        val bluetoothId = if (android.os.Build.VERSION.SDK_INT >= 21) {
            resolved.attributes
                ?.get("id")
                ?.let(::decodeTxtValue)
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }""",
      'handleService getAttributes gate')

print('ALL PASSIVE-HOTSPOT PATCHES DONE')
