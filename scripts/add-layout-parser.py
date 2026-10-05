# Adds the raw-descriptor layout parser + device-level helpers to IphoneCarPlayConfiguration.
# Run from the repo root AFTER patch-pre21-usb.py: python scripts/add-layout-parser.py
import io

f = 'shared/src/main/java/com/shilapi/xcertplay/transport/IphoneCarPlayConfiguration.kt'
s = io.open(f, encoding='utf-8').read()

if 'readLayout' in s:
    print('SKIP: readLayout already present')
    raise SystemExit(0)

if 'import android.hardware.usb.UsbDeviceConnection' not in s:
    s = s.replace('import android.util.Log\n', 'import android.util.Log\nimport android.hardware.usb.UsbDeviceConnection\n', 1)

# Insert the helpers before the object's final closing brace.
anchor = """    private fun hasAppleEthernet(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}"""
helpers = """    private fun hasAppleEthernet(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }

    /** The USBMUX interface as exposed by the device's ACTIVE configuration (API 3+). */
    fun usbMuxInterfaceOnDevice(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    /** Whether the device's active configuration already carries the CarPlay functions. */
    fun isCarPlayConfigActive(device: UsbDevice): Boolean {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val usbMux = interfaces.firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        } ?: return false
        val cdcNcm = interfaces.any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }
        val appleEthernet = interfaces.any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
        return cdcNcm && appleEthernet
    }

    /**
     * Raw control-transfer descriptor read for pre-21 platforms, where UsbConfiguration does
     * not exist. Returns every configuration the iPhone advertises, parsed from the standard
     * GET_DESCRIPTOR(CONFIGURATION) responses.
     */
    fun readLayout(device: UsbDevice, connection: UsbDeviceConnection): UsbDeviceLayout? {
        val deviceDescriptor = ByteArray(18)
        val gotDevice = connection.controlTransfer(
            0x80, 0x06, 0x0100, 0, deviceDescriptor, deviceDescriptor.size, CONTROL_TIMEOUT_MILLIS,
        )
        if (gotDevice < 18) return null
        val configurationCount = deviceDescriptor[17].toInt() and 0xff
        if (configurationCount <= 0) return null
        val configurations = mutableListOf<UsbDeviceLayout.ConfigLayout>()
        for (index in 0 until configurationCount) {
            val header = ByteArray(9)
            val gotHeader = connection.controlTransfer(
                0x80, 0x06, 0x0200 or index, 0, header, header.size, CONTROL_TIMEOUT_MILLIS,
            )
            if (gotHeader < 9) continue
            val totalLength = ((header[3].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
            if (totalLength < 9 || totalLength > 4096) continue
            val buffer = ByteArray(totalLength)
            val gotFull = connection.controlTransfer(
                0x80, 0x06, 0x0200 or index, 0, buffer, totalLength, CONTROL_TIMEOUT_MILLIS,
            )
            if (gotFull < totalLength) continue
            configurations += parseConfigLayout(buffer)
        }
        return if (configurations.isEmpty()) null else UsbDeviceLayout(configurations)
    }

    private fun parseConfigLayout(buffer: ByteArray): UsbDeviceLayout.ConfigLayout {
        val configurationValue = buffer[5].toInt() and 0xff
        val interfaces = mutableListOf<UsbDeviceLayout.InterfaceLayout>()
        var currentNumber = -1
        var currentAlternate = -1
        var currentClass = -1
        var currentSubclass = -1
        var currentProtocol = -1
        var endpoints = mutableListOf<UsbDeviceLayout.EndpointLayout>()
        var offset = 9 // skip the configuration descriptor itself
        while (offset < buffer.size) {
            val length = buffer[offset].toInt() and 0xff
            if (length < 2 || offset + length > buffer.size) break
            when (buffer[offset + 1].toInt() and 0xff) {
                0x04 -> { // INTERFACE
                    if (currentNumber >= 0) {
                        interfaces += UsbDeviceLayout.InterfaceLayout(
                            currentNumber, currentAlternate, currentClass, currentSubclass,
                            currentProtocol, endpoints.toList(),
                        )
                    }
                    currentNumber = buffer[offset + 2].toInt() and 0xff
                    currentAlternate = buffer[offset + 3].toInt() and 0xff
                    endpoints = mutableListOf()
                    if (length >= 7) {
                        currentClass = buffer[offset + 5].toInt() and 0xff
                        currentSubclass = buffer[offset + 6].toInt() and 0xff
                        currentProtocol = buffer[offset + 7].toInt() and 0xff
                    }
                }
                0x05 -> { // ENDPOINT
                    if (length >= 5 && currentNumber >= 0) {
                        endpoints += UsbDeviceLayout.EndpointLayout(
                            buffer[offset + 2].toInt() and 0xff,
                            buffer[offset + 3].toInt() and 0xff,
                            (buffer[offset + 4].toInt() and 0xff) or
                                ((buffer[offset + 5].toInt() and 0xff) shl 8),
                        )
                    }
                }
            }
            offset += length
        }
        if (currentNumber >= 0) {
            interfaces += UsbDeviceLayout.InterfaceLayout(
                currentNumber, currentAlternate, currentClass, currentSubclass, currentProtocol,
                endpoints.toList(),
            )
        }
        return UsbDeviceLayout.ConfigLayout(configurationValue, interfaces)
    }

    private const val CONTROL_TIMEOUT_MILLIS = 1000
}

/**
 * Parsed descriptor tree for pre-21 platforms: [IphoneCarPlayConfiguration.readLayout] fills it
 * via control transfers so the CarPlay configuration can be selected without the API-21
 * UsbConfiguration API.
 */
class UsbDeviceLayout(private val configurations: List<ConfigLayout>) {
    data class ConfigLayout(
        val value: Int,
        val interfaces: List<InterfaceLayout>,
    )

    data class InterfaceLayout(
        val number: Int,
        val alternateSetting: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpoints: List<EndpointLayout>,
    )

    data class EndpointLayout(val address: Int, val attributes: Int, val maxPacketSize: Int)

    fun findCarPlayConfig(): ConfigLayout? =
        configurations.firstOrNull { c -> usbMux(c) != null && hasCdcNcm(c) && hasAppleEthernet(c) }
            ?: configurations.firstOrNull { c -> usbMux(c) != null && hasCdcNcm(c) }

    private fun usbMux(config: ConfigLayout): InterfaceLayout? = config.interfaces.firstOrNull {
        it.interfaceClass == 0xff && it.interfaceSubclass == 0xfe && it.interfaceProtocol == 0x02
    }

    private fun hasCdcNcm(config: ConfigLayout): Boolean = config.interfaces.any {
        it.interfaceClass == 0x02 && it.interfaceSubclass == 0x0d
    }

    private fun hasAppleEthernet(config: ConfigLayout): Boolean = config.interfaces.any {
        it.interfaceClass == 0xff && it.interfaceSubclass == 0xfd && it.interfaceProtocol == 0x01
    }
}"""
if anchor not in s:
    print('ANCHOR NOT FOUND in IphoneCarPlayConfiguration')
    raise SystemExit(1)
s = s.replace(anchor, helpers, 1)
io.open(f, 'w', encoding='utf-8', newline='').write(s)
print('IphoneCarPlayConfiguration extended with layout parser')
