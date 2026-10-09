package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * JmDNS 3.6 is Java 8 bytecode, so a Dalvik unit cannot run it: the publish fails on
 * `Map.getOrDefault` and the library's own threads then die on `java.util.function` classes.
 * The engine choice has to be made from the platform level alone, because the failure it prevents
 * takes the whole process with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayBonjourDalvikEngineTest {
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

    @Test
    fun `a unit below API 24 publishes through system NSD even when interface mDNS was asked for`() {
        for (sdk in listOf(18, 19, 21, 23)) {
            assertEquals(
                "systemNsd(jmDNSNeedsApi24)",
                mdnsEngineFor(useInterfaceMdns = true, sdkInt = sdk),
            )
        }
    }

    @Test
    fun `interface mDNS is used as soon as the library can link`() {
        assertEquals(JMDNS_ENGINE, mdnsEngineFor(useInterfaceMdns = true, sdkInt = MIN_JMDNS_SDK))
        assertEquals(JMDNS_ENGINE, mdnsEngineFor(useInterfaceMdns = true, sdkInt = 34))
    }

    @Test
    fun `a unit that never asked for interface binding keeps the plain NSD name`() {
        // The downgrade reason must not be reported as a problem the unit was never asked to avoid.
        assertEquals(SYSTEM_NSD_ENGINE, mdnsEngineFor(useInterfaceMdns = false, sdkInt = 19))
    }

    @Test
    fun `the chosen engine is visible in the exported report`() {
        val bonjour = CarPlayBonjour(
            RuntimeEnvironment.getApplication(),
            config,
            identity,
            advertisedHost = "192.168.43.10",
            useInterfaceMdns = true,
        )
        assertTrue(bonjour.interfaceMdnsActive)
        assertTrue(bonjour.diagnosticSnapshot(), "mdnsEngine=$JMDNS_ENGINE" in bonjour.diagnosticSnapshot())

        val nsdBonjour = CarPlayBonjour(
            RuntimeEnvironment.getApplication(),
            config,
            identity,
            advertisedHost = "192.168.43.10",
            useInterfaceMdns = false,
        )
        assertFalse(nsdBonjour.interfaceMdnsActive)
        assertTrue(nsdBonjour.diagnosticSnapshot(), "mdnsEngine=$SYSTEM_NSD_ENGINE" in nsdBonjour.diagnosticSnapshot())
    }

    @Test
    fun `only the threads the library names itself are contained`() {
        // Thread names copied from a device that died on them.
        assertTrue(isMdnsOwnedThread("SocketListener(carplay-3E586F7E0476)"))
        assertTrue(isMdnsOwnedThread("JmDNS(carplay-3E586F7E0476)"))
        // Every thread this application owns must still be allowed to fail loudly.
        for (name in listOf("main", "carplay-bonjour", "carplay-mic", "pool-2-thread-1")) {
            assertFalse(name, isMdnsOwnedThread(name))
        }
        assertFalse(isMdnsOwnedThread(null))
    }
}
