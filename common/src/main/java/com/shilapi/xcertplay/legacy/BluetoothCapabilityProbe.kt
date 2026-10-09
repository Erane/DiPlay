package com.shilapi.xcertplay.legacy

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.checkSelfPermissionCompat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Answers "can this head unit carry wireless CarPlay at all?" on ROMs whose Bluetooth is
 * described to the owner as music-only.
 *
 * Wireless CarPlay here bootstraps iAP2 over a plain RFCOMM socket, so the profiles the settings
 * page advertises (A2DP/HFP, and the missing PBAP/MAP phone-book ones) are not what matters:
 * contacts, calls and Siri travel inside the CarPlay tunnel, not over Bluetooth profiles. What
 * decides the outcome is whether the stack will still hand an app a generic RFCOMM channel, can
 * read SDP records, and can hold that channel while A2DP is attached. This probe measures each
 * separately, because a crippled 4.x stack usually fails exactly one of them and the remedy
 * differs per failure.
 *
 * Only symbols up to API 19 are used unguarded, so the probe runs on the units it diagnoses.
 */
object BluetoothCapabilityProbe {

    enum class Status { PASS, WARN, FAIL, INFO, SKIP }

    /** One measured capability. [Check.id] is stable so [verdict] can key off it. */
    data class Check(val id: Id, val label: String, val status: Status, val detail: String) {
        enum class Id {
            SESSION_IDLE,
            ADAPTER,
            ENABLED,
            CONNECT_PERMISSION,
            BONDED_LIST,
            TARGET,
            BOND_STATE,
            SDP,
            SOCKET_SECURE,
            SOCKET_INSECURE,
            SOCKET_RAW,
            LINK,
            A2DP_CONCURRENCY,
            BLE,
        }
    }

    /** Runtime permission on Android 12+ only; 4.x grants at install, and the literal keeps the
     *  dex API gate from seeing an API-31 field reference. */
    private const val BLUETOOTH_CONNECT_PERMISSION = "android.permission.BLUETOOTH_CONNECT"

    /** Apple's iAP2-over-Bluetooth service record, the channel the wireless bootstrap needs. */
    private const val IAP2_SERVICE_UUID = "00000000-deca-fade-deca-deafdecacafe"

    /** Raw RFCOMM channel used when the ROM's SDP lookup is a stub; a heuristic, not discovered. */
    private const val RFCOMM_RAW_CHANNEL = 1

    private const val SDP_FETCH_WAIT_MILLIS = 2_500L
    private const val CONNECT_ATTEMPT_TIMEOUT_MILLIS = 9_000L

    /** A connect() that returns this fast carried no paging: the stack is faking success. */
    private const val SUSPICIOUS_CONNECT_MILLIS = 50L

    /** How long a radio that is still coming up is given to settle before it is reported as such. */
    private const val RADIO_STATE_WAIT_MILLIS = 8_000L
    private const val RADIO_STATE_POLL_MILLIS = 250L

    /**
     * Runs the whole probe on the calling thread (it blocks for tens of seconds) and reports each
     * check as it finishes so a screen can show progress while socket attempts are still running.
     */
    fun run(context: Context, onCheck: (Check) -> Unit): List<Check> {
        val checks = ArrayList<Check>()
        fun report(check: Check) {
            checks += check
            onCheck(check)
        }

        val sessionBusy = runCatching { CarPlayBackgroundSession.hasSession() }.getOrDefault(false)
        report(
            Check(
                Check.Id.SESSION_IDLE,
                "是否已有 CarPlay 会话在跑",
                if (sessionBusy) Status.WARN else Status.PASS,
                if (sessionBusy) "有会话在运行：本检测会短暂争用蓝牙通道，建议先断开再测。"
                else "空闲，可以安全测试。",
            ),
        )

        // On these 4.x car ROMs the Bluetooth service process only starts once the radio has been
        // turned on, so a null adapter says "the stack is not up", not "this unit has no Bluetooth".
        // Only a unit that never declared the hardware feature qualifies for the cable verdict.
        val hasBluetoothHardware = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        }.getOrDefault(false)
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        report(
            Check(
                Check.Id.ADAPTER,
                "蓝牙适配器",
                when {
                    adapter != null -> Status.PASS
                    hasBluetoothHardware -> Status.WARN
                    else -> Status.FAIL
                },
                when {
                    adapter != null -> {
                        "存在。本机地址=${runCatching { adapter.address }.getOrNull() ?: "不可读"} " +
                            "名称=${runCatching { adapter.name }.getOrNull() ?: "?"}"
                    }
                    hasBluetoothHardware -> {
                        "系统当下没有给出蓝牙适配器对象。这类车机在蓝牙关闭时不会启动蓝牙服务，" +
                            "所以这一项只是「未知」，不能据此说这台机器不支持无线：" +
                            "请先在车机设置里打开蓝牙（必要时熄火重启车机）再复测。"
                    }
                    else -> "这份 ROM 没有声明蓝牙硬件能力，无线方案无法进行，请用有线 USB CarPlay。"
                },
            ),
        )
        if (adapter == null) return checks

        val radioState = settledRadioState(adapter)
        val enabled = radioState == BluetoothAdapter.STATE_ON
        report(
            Check(
                Check.Id.ENABLED,
                "蓝牙是否已开启",
                when {
                    enabled -> Status.PASS
                    radioState == BluetoothAdapter.STATE_OFF -> Status.FAIL
                    else -> Status.WARN
                },
                when {
                    enabled -> "已开启。"
                    radioState == BluetoothAdapter.STATE_OFF -> "蓝牙是关的：在车机设置里打开蓝牙后重新检测。"
                    radioState == null -> "读不到蓝牙开关状态，这一项不算失败：打开蓝牙后复测再看。"
                    else -> "蓝牙正在切换中（状态=$radioState），等它稳定后重新检测。"
                },
            ),
        )
        if (!enabled) return checks

        // BLUETOOTH_CONNECT is a runtime permission only from Android 12; asking the compat helper
        // on 4.x reports "denied" for a permission the platform never had, which would bury a
        // perfectly good link result under a bogus failure.
        val asksForGrant = Build.VERSION.SDK_INT >= 31
        val granted = !asksForGrant || bluetoothConnectGranted(context)
        report(
            Check(
                Check.Id.CONNECT_PERMISSION,
                "蓝牙连接权限",
                if (granted) Status.PASS else Status.FAIL,
                when {
                    !granted -> "系统拒绝读取蓝牙设备信息：请在设置「应用 → DiPlay → 权限」里允许。"
                    asksForGrant -> "已授予。"
                    else -> "安卓 12 以前蓝牙在安装时授权，无需运行时权限。"
                },
            ),
        )

        val bonded = bondedDevices(adapter)
        report(
            Check(
                Check.Id.BONDED_LIST,
                "已配对的设备",
                if (bonded.isEmpty()) Status.FAIL else Status.PASS,
                if (bonded.isEmpty()) "列表为空或读不到：请先在蓝牙设置里与 iPhone 完成配对。"
                else bonded.joinToString("；") { "${it.first ?: "未知设备"} ${it.second}" },
            ),
        )

        val preferred = DiPlayPreferences.phoneAddress(context)
        val target = selectTarget(bonded, preferred)
        report(
            Check(
                Check.Id.TARGET,
                "检测目标手机",
                if (target == null) Status.FAIL else Status.PASS,
                when {
                    target != null -> {
                        val named = target.first ?: "iPhone"
                        val note = when {
                            target.second.equals(preferred, true) -> "（DiPlay 选定的手机）"
                            !named.contains("iPhone", ignoreCase = true) ->
                                "（名称不像 iPhone：请确认这就是要投影的手机，必要时删掉其它配对）"
                            else -> ""
                        }
                        "$named ${target.second}$note"
                    }
                    bonded.isEmpty() -> "没有可测设备：先配对。"
                    else -> "配对列表里有 ${bonded.size} 台设备，认不出哪台是 iPhone。" +
                        "请在 DiPlay 里选过手机，或删掉其它配对后再测。"
                },
            ),
        )

        val device = target?.let { entry ->
            runCatching { adapter.getRemoteDevice(entry.second) }.getOrNull()
        }
        if (device == null) {
            val skip = "没有选定目标手机，这一项测不了。"
            listOf(
                Check.Id.SDP to "SDP 服务记录读取",
                Check.Id.LINK to "与 iPhone 建立数据通道",
                Check.Id.A2DP_CONCURRENCY to "音频通道与数据通道能否并存",
            ).forEach { (id, label) ->
                report(Check(id, label, Status.SKIP, skip))
            }
            report(bleCheck(context))
            return checks
        }

        val bondState = runCatching { device.bondState }.getOrDefault(-1)
        val reallyBonded = bondState == BluetoothDevice.BOND_BONDED
        report(
            Check(
                Check.Id.BOND_STATE,
                "配对状态是否属实",
                if (reallyBonded) Status.PASS else Status.WARN,
                if (reallyBonded) "BOND_BONDED，链路密钥可用。" else
                    "设备在配对列表里，但系统报告 bondState=$bondState（不是 BOND_BONDED）。" +
                        "这种不一致正是假连接的前兆：请在蓝牙设置里取消配对、重新配对后再测。",
            ),
        )
        report(sdpCheck(device))

        val availability = transports().map { transport ->
            val socket = transport.create(device)
            runCatching { socket?.close() }
            Check(
                transport.id,
                transport.label,
                if (socket == null) Status.FAIL else Status.PASS,
                if (socket == null) "系统拒绝创建这种数据通道（接口缺失或返回空）。"
                else "接口可用，能开出数据串口。",
            )
        }
        availability.forEach { report(it) }

        val audioBefore = audioProfileStates(adapter)
        report(linkCheck(device, availability))
        report(concurrencyCheck(adapter, audioBefore))
        report(bleCheck(context))
        return checks
    }

    /**
     * The radio passes through TURNING_ON / TURNING_OFF while the stack comes up, and reading that
     * transitional value once tells you nothing: poll until it settles so "off" and "still starting"
     * stay different answers. A null result means unknown, and an unknown must never be reported as
     * a missing capability.
     */
    private fun settledRadioState(adapter: BluetoothAdapter): Int? {
        val deadline = System.nanoTime() + RADIO_STATE_WAIT_MILLIS * 1_000_000L
        var last: Int? = null
        while (System.nanoTime() < deadline) {
            val state = runCatching { adapter.state }.getOrNull()
            if (state == null) return null
            last = state
            if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) return state
            val interrupted = runCatching { Thread.sleep(RADIO_STATE_POLL_MILLIS) }.isFailure
            if (interrupted) {
                Thread.currentThread().interrupt()
                return last
            }
        }
        return last
    }

    private fun bondedDevices(adapter: BluetoothAdapter): List<Pair<String?, String>> =
        runCatching {
            adapter.bondedDevices.orEmpty().map { device ->
                runCatching { device.name }.getOrNull() to device.address
            }
        }.getOrElse { emptyList() }

    private fun bluetoothConnectGranted(context: Context): Boolean = runCatching {
        checkSelfPermissionCompat(context, BLUETOOTH_CONNECT_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(true)

    /** SDP is the only app-side way to learn whether the peer's service records are readable. */
    private fun sdpCheck(device: BluetoothDevice): Check {
        val started = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
        runCatching { Thread.sleep(SDP_FETCH_WAIT_MILLIS) }
        val uuids = runCatching { device.uuids }.getOrNull()
        val iap2 = uuids?.any {
            runCatching { it.uuid == UUID.fromString(IAP2_SERVICE_UUID) }.getOrDefault(false)
        }
        val body = when {
            uuids == null -> "读不到服务记录（fetchUuidsWithSdp=$started）。这栈的 SDP 查询多半是桩，" +
                "连接时会退回固定通道号尝试。"
            uuids.isEmpty() -> "问得出但返回空记录（fetchUuidsWithSdp=$started），" +
                "iPhone 需处于可被读取的状态才有记录。"
            else -> "读到 ${uuids.size} 条服务记录（fetchUuidsWithSdp=$started）。"
        }
        return Check(
            Check.Id.SDP,
            "SDP 服务记录读取",
            when {
                uuids != null && uuids.isNotEmpty() -> Status.PASS
                else -> Status.WARN
            },
            body + " 是否含 iAP2 服务：" + when (iap2) {
                null -> "未知"
                true -> "是"
                false -> "否（手机不把 iAP2 服务公开给车机也常见，不一定致命）"
            },
        )
    }

    private class Transport(val id: Check.Id, val label: String, val create: (BluetoothDevice) -> BluetoothSocket?)

    /**
     * The same three transports, in the same order, the wireless bootstrap uses, so a transport the
     * probe cannot even build is one the real session will not get either.
     */
    private fun transports(): List<Transport> = listOf(
        Transport(Check.Id.SOCKET_SECURE, "RFCOMM 安全通道(走 SDP)") { device ->
            runCatching {
                device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_SERVICE_UUID))
            }.getOrNull()
        },
        Transport(Check.Id.SOCKET_INSECURE, "RFCOMM 非安全通道(走 SDP)") { device ->
            runCatching {
                device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(IAP2_SERVICE_UUID))
            }.getOrNull()
        },
        Transport(Check.Id.SOCKET_RAW, "RFCOMM 固定通道$RFCOMM_RAW_CHANNEL(绕过 SDP)") { device ->
            runCatching {
                val create = BluetoothDevice::class.java
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                create.invoke(device, RFCOMM_RAW_CHANNEL) as? BluetoothSocket
            }.getOrNull()
        },
    )

    /**
     * The decisive check: can a real RFCOMM link to the iPhone be established? Building a socket only
     * proves the API is not stubbed. A success faster than [SUSPICIOUS_CONNECT_MILLIS] is reported as
     * a fake connect rather than a pass, because the T3 stack returns success without paging the
     * phone at all.
     */
    private fun linkCheck(
        device: BluetoothDevice,
        availability: List<Check>,
    ): Check {
        val usable = transports().filter { transport ->
            availability.firstOrNull { it.id == transport.id }?.status == Status.PASS
        }
        if (usable.isEmpty()) {
            return Check(
                Check.Id.LINK,
                "与 iPhone 建立数据通道",
                Status.FAIL,
                "三种数据通道都开不出来，不必再试连接：这台蓝牙只剩音频。",
            )
        }
        val outcomes = ArrayList<String>()
        for (transport in usable) {
            val socket = transport.create(device) ?: continue
            val started = System.nanoTime()
            val failure = connectBounded(socket)
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            runCatching { socket.close() }
            if (failure == null) {
                val suspicious = elapsed < SUSPICIOUS_CONNECT_MILLIS
                return Check(
                    Check.Id.LINK,
                    "与 iPhone 建立数据通道",
                    if (suspicious) Status.WARN else Status.PASS,
                    "${transport.label} 连接成功，用时 ${elapsed}ms。" +
                        if (suspicious) {
                            "⚠ 用时短得不正常：这通常是栈谎报连接成功、实际没有链路。请删除配对后重新配对再测。"
                        } else {
                            "这就是无线 CarPlay 控制通道能否走通的关键证据。"
                        },
                )
            }
            outcomes += "${transport.label}失败(${failure.javaClass.simpleName}, ${elapsed}ms)"
        }
        return Check(
            Check.Id.LINK,
            "与 iPhone 建立数据通道",
            Status.FAIL,
            "通道接口都在，但连不上这台手机：${outcomes.joinToString("；")}。" +
                "常见原因是 iPhone 锁屏/未选中该配对，或它正被另一路 CarPlay 占用。",
        )
    }

    /** BluetoothSocket.connect() has no timeout of its own, so the attempt is bounded by a worker. */
    private fun connectBounded(socket: BluetoothSocket): Throwable? {
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            },
            "bt-capability-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            done.await(CONNECT_ATTEMPT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            runCatching { socket.close() }
            return failure.get() ?: java.io.IOException("连接超过 ${CONNECT_ATTEMPT_TIMEOUT_MILLIS}ms 无响应")
        }
        return failure.get()
    }

    /**
     * A single-chip 4.x stack can refuse a data channel only while A2DP is attached, which the owner
     * experiences as "it connects, then the music kills it". Comparing the audio profile state
     * across the attempts names that case instead of leaving it as an unexplained dropout.
     */
    private fun concurrencyCheck(adapter: BluetoothAdapter, before: Map<String, String>): Check {
        val after = audioProfileStates(adapter)
        val dropped = before.filter { (profile, state) -> state == "已连接" && after[profile] != "已连接" }
        val summary = "${formatStates(before)} → ${formatStates(after)}"
        return Check(
            Check.Id.A2DP_CONCURRENCY,
            "音频通道与数据通道能否并存",
            if (dropped.isEmpty()) Status.PASS else Status.WARN,
            if (dropped.isEmpty()) {
                "测试前后音频通道状态一致：$summary。"
            } else {
                "测试过程中掉掉了 ${dropped.keys.joinToString()}：这颗芯片可能不支持「正在放歌」时再开一路数据串口。" +
                    "可以先在手机上断开该车机的音频连接，再试无线。（$summary）"
            },
        )
    }

    private fun formatStates(states: Map<String, String>): String =
        states.entries.joinToString(" / ") { entry -> entry.key + entry.value }

    private fun audioProfileStates(adapter: BluetoothAdapter): Map<String, String> = listOf(
        "音乐(A2DP)" to BluetoothProfile.A2DP,
        "通话(HFP)" to BluetoothProfile.HEADSET,
    ).associate { (label, profile) ->
        label to runCatching { profileStateName(adapter.getProfileConnectionState(profile)) }
            .getOrDefault("读不到")
    }

    private fun profileStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "已连接"
        BluetoothProfile.STATE_CONNECTING -> "连接中"
        BluetoothProfile.STATE_DISCONNECTING -> "断开中"
        BluetoothProfile.STATE_DISCONNECTED -> "未连接"
        else -> "状态$state"
    }

    private fun bleCheck(context: Context): Check {
        val hasLe = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        }.getOrDefault(false)
        return Check(
            Check.Id.BLE,
            "蓝牙低功耗(BLE)",
            Status.INFO,
            if (hasLe) {
                "支持。本方案的无线控制通道走经典蓝牙 RFCOMM，不依赖 BLE，所以这一项不改变结论。"
            } else {
                "不支持。苹果官方无线 CarPlay 的发现阶段需要 BLE，但本项目走经典蓝牙 RFCOMM，" +
                    "所以缺 BLE 不构成障碍。"
            },
        )
    }

    private fun selectTarget(
        bonded: List<Pair<String?, String>>,
        preferredAddress: String?,
    ): Pair<String?, String>? {
        bonded.firstOrNull { it.second.equals(preferredAddress, ignoreCase = true) }?.let { return it }
        val iPhones = bonded.filter { it.first?.contains("iPhone", ignoreCase = true) == true }
        return when {
            iPhones.size == 1 -> iPhones.single()
            iPhones.size > 1 -> null
            bonded.size == 1 -> bonded.single()
            else -> null
        }
    }

    /**
     * Plain-language bottom line, derived only from check outcomes so it is testable without a
     * Bluetooth stack. Two rules it must never break: never claim wireless works from socket
     * availability alone, and never blame the missing phone-book profiles, which CarPlay tunnels
     * rather than carrying over Bluetooth.
     */
    fun verdict(checks: List<Check>): String {
        fun statusOf(id: Check.Id): Status? = checks.firstOrNull { it.id == id }?.status
        val adapter = statusOf(Check.Id.ADAPTER)
        val enabled = statusOf(Check.Id.ENABLED)
        val permission = statusOf(Check.Id.CONNECT_PERMISSION)
        val target = statusOf(Check.Id.TARGET)
        val link = statusOf(Check.Id.LINK)
        val sockets = listOf(Check.Id.SOCKET_SECURE, Check.Id.SOCKET_INSECURE, Check.Id.SOCKET_RAW)
            .mapNotNull { statusOf(it) }
        val anySocket = sockets.any { it == Status.PASS }

        val line = when {
            adapter == Status.FAIL ->
                "结论：这台车机的系统没有蓝牙能力，无线方案走不通，请用有线 USB CarPlay（有线不需要蓝牙）。"
            adapter == Status.WARN ->
                "结论：现在还不能下判断。车机的蓝牙服务没有起来（这类车机在蓝牙关闭时不会启动蓝牙服务），" +
                    "请先打开车机蓝牙、必要时重启车机之后再复测；在这之前不能认定这台机器不支持无线。"
            enabled == Status.FAIL ->
                "结论：蓝牙现在是关的，还判断不了。请先打开蓝牙再重新检测。"
            enabled == Status.WARN ->
                "结论：蓝牙状态读不到或还在切换，暂时判断不了。等蓝牙稳定开启后重新检测。"
            permission == Status.FAIL ->
                "结论：系统没给蓝牙权限，检测结果不完整，请先授予权限再测。"
            target == Status.FAIL ->
                "结论：还没找到可测的 iPhone。请先在蓝牙设置里与手机配对，再回来检测。"
            link == Status.PASS ->
                "结论：可以走无线。车机能与 iPhone 建立蓝牙数据通道，这正是无线 CarPlay 的控制通道；" +
                    "剩下的成败在 Wi-Fi 与鉴权阶段，蓝牙这一关已经过了。"
            link == Status.WARN ->
                "结论：高度可疑。通道接口在，但连接像是假成功。先在蓝牙设置里删除配对再重新配对后复测；" +
                    "复测仍如此的话，这台机型基本只能走有线。"
            link == Status.FAIL && anySocket ->
                "结论：仍有希望。数据通道接口是好的，这一轮只是没连上手机。让 iPhone 解锁停在蓝牙设置页、" +
                    "确认没被另一路 CarPlay 占用后复测；正式连接时才会验证协议层。"
            !anySocket ->
                "结论：这台蓝牙阉割得只剩音频，三种数据通道都开不出来，无线方案走不通，请用有线 USB CarPlay。"
            else ->
                "结论：证据不足。请完成配对后重新检测，或先用有线 USB CarPlay。"
        }

        return buildString {
            appendLine(line)
            if (statusOf(Check.Id.BOND_STATE) == Status.WARN) {
                appendLine("· 注意：配对列表与系统报告的配对状态不一致，这是这类机型假连接的典型前兆，先重新配对。")
            }
            if (statusOf(Check.Id.A2DP_CONCURRENCY) == Status.WARN) {
                appendLine("· 注意：测试期间掉掉了蓝牙音频，说明并发能力有限，无线使用时可能听不到歌。")
            }
            if (statusOf(Check.Id.SDP) == Status.WARN) {
                appendLine("· SDP 读不到服务记录不等于不能连：DiPlay 还会用固定 RFCOMM 通道号兜底再试。")
            }
            appendLine(
                "· 说明：无线 CarPlay 不使用蓝牙电话本/通话记录（PBAP、MAP），所以「只能听歌、不能传信息」" +
                    "这类设置页描述并不代表能力缺失——通讯录、通话、Siri 都打包在蓝牙数据通道之上。",
            )
            append("· 真正决定成败的只有三件事：能不能开蓝牙数据串口、配对是否真实有效、音频与数据能否并存。")
        }.trimEnd()
    }
}
