package com.shilapi.xcertplay

import com.shilapi.xcertplay.legacy.BluetoothCapabilityProbe.Check
import com.shilapi.xcertplay.legacy.BluetoothCapabilityProbe.Status
import com.shilapi.xcertplay.legacy.BluetoothCapabilityProbe.verdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict is what a non-technical owner acts on, so it is tested as pure logic: an audio-only
 * stack must be told to use a cable, and a stack that only failed to reach the phone this round
 * must not be declared incapable.
 */
class BluetoothCapabilityVerdictTest {
    private fun check(id: Check.Id, status: Status) = Check(id, id.name, status, "")

    private fun healthyLink() = listOf(
        check(Check.Id.ADAPTER, Status.PASS),
        check(Check.Id.ENABLED, Status.PASS),
        check(Check.Id.CONNECT_PERMISSION, Status.PASS),
        check(Check.Id.BONDED_LIST, Status.PASS),
        check(Check.Id.TARGET, Status.PASS),
        check(Check.Id.BOND_STATE, Status.PASS),
        check(Check.Id.SDP, Status.PASS),
        check(Check.Id.SOCKET_SECURE, Status.PASS),
        check(Check.Id.LINK, Status.PASS),
        check(Check.Id.A2DP_CONCURRENCY, Status.PASS),
    )

    @Test fun reportsWirelessViableOnlyWhenTheRealLinkSucceeded() {
        assertTrue(verdict(healthyLink()).startsWith("结论：可以走无线"))
    }

    @Test fun socketAvailabilityAloneIsNeverDeclaredAsWorking() {
        val noLink = healthyLink().map {
            when (it.id) {
                Check.Id.LINK -> check(Check.Id.LINK, Status.FAIL)
                else -> it
            }
        }
        val line = verdict(noLink)
        assertTrue(line.startsWith("结论：仍有希望"))
        assertFalse(line.contains("可以走无线"))
    }

    @Test fun aStackWithNoDataChannelAtAllIsToldToUseACable() {
        val crippled = healthyLink().map {
            when (it.id) {
                Check.Id.SOCKET_SECURE -> check(Check.Id.SOCKET_SECURE, Status.FAIL)
                Check.Id.SDP -> check(Check.Id.SDP, Status.WARN)
                Check.Id.LINK -> check(Check.Id.LINK, Status.FAIL)
                else -> it
            }
        }
        assertTrue(verdict(crippled).startsWith("结论：这台蓝牙阉割得只剩音频"))
    }

    @Test fun aSuspiciouslyFastConnectIsCalledFakeNotSuccessful() {
        val faked = healthyLink().map {
            when (it.id) {
                Check.Id.LINK -> check(Check.Id.LINK, Status.WARN)
                Check.Id.BOND_STATE -> check(Check.Id.BOND_STATE, Status.WARN)
                else -> it
            }
        }
        val line = verdict(faked)
        assertTrue(line.startsWith("结论：高度可疑"))
        assertTrue(line.contains("重新配对"))
    }

    @Test fun everyVerdictExoneratesTheMissingPhoneBookProfiles() {
        // Owners read "只能听歌，不能传信息" as a disqualification; the tunnel says otherwise.
        listOf(healthyLink(), emptyList()).forEach { checks ->
            assertTrue(verdict(checks).contains("PBAP"))
        }
    }

    @Test fun anAdapterThatHasNotStartedIsUnknownNotADisqualification() {
        // On these 4.x units the Bluetooth service only comes up with the radio on, so a null
        // adapter is a missing measurement, and a missing measurement must not sentence the
        // owner to a cable.
        val stackNotUp = listOf(
            check(Check.Id.ADAPTER, Status.WARN),
            check(Check.Id.ENABLED, Status.WARN),
        )
        val line = verdict(stackNotUp)
        assertTrue(line.startsWith("结论：现在还不能下判断"))
        assertFalse(line.contains("请用有线"))
        assertFalse(line.contains("结论：可以走无线"))
    }

    @Test fun onlyAnAbsentBluetoothFeatureSentencesTheUnitToACable() {
        val line = verdict(listOf(check(Check.Id.ADAPTER, Status.FAIL)))
        assertTrue(line.startsWith("结论：这台车机的系统没有蓝牙能力"))
        assertTrue(line.contains("有线"))
    }

    @Test fun aDisabledAdapterOrMissingTargetBlocksAnyWirelessClaim() {
        listOf(
            healthyLink().map {
                if (it.id == Check.Id.ENABLED) check(Check.Id.ENABLED, Status.FAIL) else it
            },
            healthyLink().map {
                if (it.id == Check.Id.TARGET) check(Check.Id.TARGET, Status.FAIL) else it
            },
        ).forEach { assertTrue(!verdict(it).contains("结论：可以走无线")) }
    }
}
