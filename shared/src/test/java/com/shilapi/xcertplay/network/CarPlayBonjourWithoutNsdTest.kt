package com.shilapi.xcertplay.network

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Some head units ship without the Android NSD service, and interface-bound mDNS does not need it,
 * so its absence must not stop discovery from being set up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayBonjourWithoutNsdTest {
    private class NoNsdContext(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.NSD_SERVICE) null else super.getSystemService(name)

        // The class under test resolves the application context, so the override has to be reachable
        // through it as well.
        override fun getApplicationContext(): Context = this
    }

    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        model = "LIVI",
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    private fun withoutNsd(interfaceMdns: Boolean) = CarPlayBonjour(
        NoNsdContext(RuntimeEnvironment.getApplication()),
        config,
        identity,
        advertisedHost = "192.168.43.10",
        useInterfaceMdns = interfaceMdns,
    )

    @Test
    fun settingUpInterfaceBoundMdnsNeverAsksForTheNsdService() {
        val bonjour = withoutNsd(interfaceMdns = true)
        try {
            assertEquals(
                "mdnsFamilies=none",
                bonjour.diagnosticSnapshot().substringAfterLast(' '),
            )
        } finally {
            bonjour.close()
        }
    }

    @Test
    fun theNsdPathReportsTheMissingServiceAsAnIOException() {
        val bonjour = withoutNsd(interfaceMdns = false)
        val failure = try {
            bonjour.start()
            null
        } catch (error: Throwable) {
            error
        } finally {
            runCatching { bonjour.close() }
        }
        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals("Android NSD service is unavailable", failure?.message)
    }
}
