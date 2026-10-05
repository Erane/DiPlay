# One-shot patcher: pre-21 USB support for the compat-4.4 branch.
# Run from the repo root: python scripts/patch-pre21-usb.py
import io

def patch(path, old, new, label):
    s = io.open(path, encoding='utf-8').read()
    if old not in s:
        print('MISS', label, 'in', path)
        return False
    if new in s:
        print('SKIP (already applied)', label)
        return True
    s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8', newline='').write(s)
    print('patched', label)
    return True

# ---------- 1. IphoneUsbHost.openIap2UsbSession: pre-21 branch ----------
patch(
    'shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt',
    """        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            if (!connection.setConfiguration(configuration)) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "setConfiguration ${configuration.id} reported failure; claiming anyway",
                )
            }
            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")""",
    """        var claimedInterface: UsbInterface? = null
        try {
            // Pre-21 devices have no UsbConfiguration API: the re-enumerated iPhone's ACTIVE
            // configuration is the CarPlay one, exposed through the device-level interfaces.
            val usbMux: UsbInterface
            if (Build.VERSION.SDK_INT >= 21) {
                val configuration = IphoneCarPlayConfiguration.find(device)
                    ?: throw IphoneUsbException.Protocol(
                        "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                    )
                if (!connection.setConfiguration(configuration)) {
                    Log.w(
                        IphoneCarPlayConfiguration.TAG,
                        "setConfiguration ${configuration.id} reported failure; claiming anyway",
                    )
                }
                usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                    ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            } else {
                usbMux = IphoneCarPlayConfiguration.usbMuxInterfaceOnDevice(device)
                    ?: throw IphoneUsbException.Protocol(
                        "Re-enumerated iPhone exposes no USBMUX CarPlay interfaces (pre-21)",
                    )
            }""",
    'IphoneUsbHost pre-21 open branch',
)

# fix the log line that referenced configuration.id
patch(
    'shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt',
    '''                "usbmux config=${configuration.id} iface=${usbMux.id} alt=${usbMux.alternateSetting} " +''',
    '''                "usbmux iface=${usbMux.id} alt=${usbMux.alternateSetting} " +''',
    'IphoneUsbHost log line',
)

# Build import in IphoneUsbHost
s = io.open('shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt', encoding='utf-8').read()
if 'import android.os.Build' not in s:
    s = s.replace('import android.hardware.usb.UsbManager\n', 'import android.hardware.usb.UsbManager\nimport android.os.Build\n', 1)
    io.open('shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt', 'w', encoding='utf-8', newline='').write(s)
    print('patched IphoneUsbHost Build import')

# ---------- 2. CarPlayController: pre-21 branches ----------
patch(
    'shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt',
    """                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val configuration = IphoneCarPlayConfiguration.find(result.device)
                        connectionDiagnostic(
                            "USB configuration ready=${configuration != null} " +
                                "configurationId=${configuration?.id ?: "none"} " +
                                "reenumerationAttempts=$reenumerationAttempts " +
                                "action=${when {
                                    configuration != null -> "reuse-descriptors"
                                    reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS -> "request-transition"
                                    else -> "reject-missing-configuration"
                                }}",
                        )
                        if (configuration != null) {
                            openDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {""",
    """                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val pre21 = Build.VERSION.SDK_INT < 21
                        val configuration = if (pre21) null else IphoneCarPlayConfiguration.find(result.device)
                        val carPlayActive = configuration != null ||
                            (pre21 && IphoneCarPlayConfiguration.isCarPlayConfigActive(result.device))
                        connectionDiagnostic(
                            "USB configuration ready=$carPlayActive " +
                                "configurationId=${configuration?.id ?: if (pre21) "pre-21" else "none"} " +
                                "reenumerationAttempts=$reenumerationAttempts " +
                                "action=${when {
                                    carPlayActive -> "reuse-descriptors"
                                    reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS -> "request-transition"
                                    else -> "reject-missing-configuration"
                                }}",
                        )
                        if (carPlayActive) {
                            openDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {""",
    'CarPlayController reenumeration branch',
)

patch(
    'shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt',
    """    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +""",
    """    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val pre21 = Build.VERSION.SDK_INT < 21
        val configuration = if (pre21) null else IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = if (pre21) {
            NcmFunctionDiscovery.findOnDevice(device)
        } else {
            NcmFunctionDiscovery.find(configuration)
        } ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration?.id ?: "pre-21"} control=${function.control.id}/${function.control.alternateSetting}" +""",
    'CarPlayController openNcm branch',
)

s = io.open('shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt', encoding='utf-8').read()
if 'import android.os.Build' not in s:
    s = s.replace('import android.content.Context\n', 'import android.content.Context\nimport android.os.Build\n', 1)
    io.open('shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt', 'w', encoding='utf-8', newline='').write(s)
    print('patched CarPlayController Build import')

# ---------- 3. NcmFunctionDiscovery.findOnDevice ----------
patch(
    'shared/src/main/java/com/shilapi/xcertplay/transport/NcmFunctionDiscovery.kt',
    """    fun find(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcm(configuration)
    }""",
    """    fun find(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcm(configuration)
    }

    /** Pre-21 variant: the device-level interfaces of the ACTIVE (CarPlay) configuration. */
    fun findOnDevice(device: UsbDevice): NcmFunction? {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val control = interfaces.firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val data = interfaces
            .filter { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
            .minByOrNull { if (it.alternateSetting == DATA_ALTERNATE_SETTING) 0 else 1 }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }""",
    'NcmFunctionDiscovery findOnDevice',
)

# ---------- 4. NcmUsbBridge setInterface pre-21 fallback ----------
patch(
    'shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt',
    """                val altSelected = connection.setInterface(function.data)""",
    """                // UsbDeviceConnection.setInterface is API 21; pre-21 issues SET_INTERFACE directly.
                val altSelected = if (Build.VERSION.SDK_INT >= 21) {
                    connection.setInterface(function.data)
                } else {
                    connection.controlTransfer(
                        0x01, 0x01, function.data.alternateSetting, function.data.id, null, 0, 0,
                    ) >= 0
                }""",
    'NcmUsbBridge setInterface fallback',
)

s = io.open('shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt', encoding='utf-8').read()
if 'import android.os.Build' not in s:
    s = s.replace('import android.hardware.usb.UsbDeviceConnection\n', 'import android.hardware.usb.UsbDeviceConnection\nimport android.os.Build\n', 1)
    io.open('shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt', 'w', encoding='utf-8', newline='').write(s)
    print('patched NcmUsbBridge Build import')

print('ALL PATCHES DONE')
