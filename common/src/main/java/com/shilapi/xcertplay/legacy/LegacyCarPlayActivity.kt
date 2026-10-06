package com.shilapi.xcertplay.legacy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayBootstrap
import com.shilapi.xcertplay.DiPlayBluetooth
import com.shilapi.xcertplay.CarPlayMediaKeys
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.DiPlayProbeActivity
import com.shilapi.xcertplay.DiPlaySessionService
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId

/**
 * View-based CarPlay projection host for pre-23 units (compat-4.4). The session machinery is
 * ported from [com.shilapi.xcertplay.CarPlayHostActivity]; the UI is a plain SurfaceView plus a
 * status overlay so it runs on Android 4.3/4.4 where Compose cannot. Everything below API 26 is
 * also gated so the same code keeps working on 6.0+.
 */
class LegacyCarPlayActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var restartGeneration = 0
    private val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var sessionActive = false
    private var startPendingSurface = false
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var surfaceView: SurfaceView
    private var latestSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 4)
        }
        logView = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.rgb(166, 200, 255))
            setPadding(24, 0, 24, 12)
        }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                latestSurface = holder.surface
                sink?.setSurface(SCREEN_TYPE_MAIN, holder.surface)
                sink?.setSurface(SCREEN_TYPE_ALT, holder.surface)
                if (controller == null && !startPendingSurface) {
                    startPendingSurface = true
                    mainHandler.post {
                        startPendingSurface = false
                        startSession()
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                latestSurface = null
            }
        })
        surfaceView.setOnTouchListener { view, event -> onHostTouch(view, event) }

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            addView(statusView)
            addView(logView)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM))
        }
        setContentView(root)
        runCatching {
            getExternalFilesDir(null)?.let { dir ->
                dir.mkdirs()
                val profile = "显示版本 ${Build.VERSION.RELEASE} / 真实 SDK ${Build.VERSION.SDK_INT} / " +
                    "硬件 ${Build.HARDWARE} / 内核 ${System.getProperty("os.version")}"
                File(dir, "legacy-log.txt").appendText("==== ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} 会话开始 ====\n$profile\n")
            }
        }
        appendLog("compat-4.4 CarPlay 宿主已启动 SDK=${Build.VERSION.SDK_INT}")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MIC_REQUEST) {
            appendLog(if (resultCode == RESULT_OK) "麦克风权限授予" else "麦克风权限被拒（通话不可用）")
        }
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                appendLog("VPN 授权成功")
                startSession()
            } else {
                setStatus("VPN 授权被拒绝：CarPlay 需要它建立到 iPhone 的网络通路")
                appendLog("VPN 授权被拒绝")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (controller == null && latestSurface != null && !startPendingSurface) {
            startPendingSurface = true
            mainHandler.post {
                startPendingSurface = false
                startSession()
            }
        }
    }

    /** All non-loopback IPv4s of Wi-Fi-ish interfaces, for spotting AP bounce in the log. */
    private fun interfaceSnapshot(): String = runCatching {
        val lines = mutableListOf<String>()
        val enumerated = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in enumerated) {
            val addrs = iface.inetAddresses.toList()
                .filterIsInstance<java.net.Inet4Address>()
                .joinToString(",") { it.hostAddress ?: "" }
            lines += "${iface.name}(up=${iface.isUp} ipv4=[$addrs])"
        }
        lines.joinToString(" ")
    }.getOrDefault("(枚举失败)")

    private var interfaceWatcher: Thread? = null
    @Volatile private var watchingInterfaces = false

    private fun startInterfaceWatcher() {
        if (watchingInterfaces) return
        watchingInterfaces = true
        interfaceWatcher = Thread({
            var last = ""
            while (watchingInterfaces) {
                val snapshot = interfaceSnapshot()
                if (snapshot != last) {
                    appendLog("接口状态: $snapshot")
                    last = snapshot
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "diplay-iface-watcher").apply { isDaemon = true; start() }
    }

    private fun stopInterfaceWatcher() {
        watchingInterfaces = false
        interfaceWatcher?.interrupt()
        interfaceWatcher = null
    }

    /**
     * AirPlay's default port 7000 is frequently held by the unit's bundled adapter app or the
     * ROM cast service (observed EADDRINUSE on the K2X). Pick the first free port from the
     * AirPlay range; the chosen port is advertised to the iPhone over iAP2 (0.2.10 feature).
     */
    private fun pickAirPlayPort(): Int {
        for (port in 7000..7010) {
            try {
                java.net.ServerSocket().use { socket ->
                    socket.bind(java.net.InetSocketAddress(port))
                    return port
                }
            } catch (error: java.io.IOException) {
                appendLog("端口 $port 被占用，尝试下一个")
            }
        }
        return 7000
    }

    private fun micAvailable(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startSession() {
        try {
            startSessionInternal()
        } catch (error: Throwable) {
            // Head-unit ROMs often swallow crash dialogs: keep the failure visible on screen
            // and in the diagnostic files instead of dying back to the launcher.
            appendCrash("startSession", error)
            setStatus("启动错误: ${error.javaClass.simpleName}: ${error.message?.take(150)}")
            appendLog("启动错误: $error")
            shutdown("启动失败", {})
        }
    }

    private fun startSessionInternal() {
        if (shuttingDown.get()) return
        // The wired session routes AirPlay through CarPlayVpnService; consent must be granted
        // before the controller can establish the tunnel. Wireless runs over the Wi-Fi network
        // and does not need it (the Compose host gates the same way).
        val wireless = intent.getBooleanExtra(EXTRA_WIRELESS, false)
        if (!wireless) {
            val consent = CarPlayVpnService.prepare(this)
            if (consent != null) {
                setStatus("请在弹窗中允许 VPN 连接（CarPlay 网络需要）")
                startActivityForResult(consent, VPN_REQUEST)
                return
            }
        }
        restartGeneration += 1
        val generation = restartGeneration
        setStatus("准备 CarPlay 身份…")
        runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }
            .onFailure { error ->
                appendLog("身份加载失败: ${error.message}")
                setStatus("CarPlay 身份不可用：${error.message}")
                return
            }
        val identity = AirPlayPersistence.loadIdentity(this)
        val width = surfaceView.width.coerceAtLeast(64)
        val height = surfaceView.height.coerceAtLeast(64)
        val size = Pair(width / 2 * 2, height / 2 * 2)
        setStatus("启动 CarPlay 会话 ${size.first}x${size.second}…")

        val airPlayConfig = buildAirPlayConfig(size.first, size.second, identity)
        val config = buildRuntimeConfig(identity)
        val renderer = AndroidMediaSink(
            surface = latestSurface,
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            preferSoftwareHevcDecoder = false,
            advancedAudioChannelMapping = false,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            context = this,
            navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this),
            onScreenStreamActiveChanged = { _, _ -> },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message -> appendLog(message) },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
        )
        sink = renderer
        videoWidth = airPlayConfig.main.widthPixels
        videoHeight = airPlayConfig.main.heightPixels
        latestSurface?.let {
            renderer.setSurface(SCREEN_TYPE_MAIN, it)
            renderer.setSurface(SCREEN_TYPE_ALT, it)
        }
        val media = CarPlayMediaEngine(
            sink = renderer,
            microphoneEnabled = micAvailable(),
            audioCaptureDirectory = null,
        )
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = identity,
            pairings = pairings,
            listener = createSessionListener(generation),
            media = media,
            reportStatus = createStatusReporter(generation),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { AirPlayPersistence.saveLockdownRecord(this, it) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider(),
            vehicleStatusProvider = null,
        )
        controller = next
        CarPlayMediaKeys.attach(this, next)
        val rotation = try {
            windowManager.defaultDisplay.rotation
        } catch (_: Exception) {
            0
        }
        CarPlayBackgroundSession.store(
            next, renderer, size.first, size.second, this,
            CarPlaySessionDisplay(size.first, size.second, rotation, false, false, width, height),
        ) { completion ->
            runOnUiThread {
                shutdown("断开连接", completion)
                finish()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(Intent(this, DiPlaySessionService::class.java))
            } else {
                startService(Intent(this, DiPlaySessionService::class.java))
            }
            appendLog("会话启动…")
            startInterfaceWatcher()
            next.start()
        } catch (error: RuntimeException) {
            appendLog("会话启动失败: ${error.javaClass.simpleName}")
            shutdown("无法启动", {})
        }
    }

    private fun buildRuntimeConfig(identity: com.shilapi.xcertplay.airplay.AirPlayIdentity): CarPlayRuntimeConfig {
        val mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        val wireless = intent.getBooleanExtra(EXTRA_WIRELESS, false)
        if (Build.VERSION.SDK_INT >= 23 && !wireless &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
        val deviceId = DiPlayBootstrap.deviceId(identity)
        return CarPlayRuntimeConfig(
            mfiTarget = mfiTarget,
            ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1a86, 0x5512)) else emptyList(),
            ch341MfiResetGpio = null,
            linuxI2cPath = null,
            remoteMfiServer = null,
            remoteMfiToken = null,
            identification = Iap2IdentificationConfig(
                name = "DiPlay",
                modelIdentifier = Build.MODEL ?: "legacy",
                manufacturer = "DiPlay",
                serialNumber = "DIPLAY-" + deviceId.replace(":", ""),
                firmwareVersion = "0.1.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = false,
                vehicleStatusEnabled = false,
                chargingConnectors = EvChargingConnectors.CCS1_J1772,
                vehicleSpeedEnabled = false,
            ),
            label = "DiPlay",
            hostName = "diplay-" + deviceId.replace(":", "").lowercase(),
            hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
            transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            wirelessHotspotMode = if (wireless) WirelessHotspotMode.PASSIVE_HOTSPOT else WirelessHotspotMode.WIFI_P2P,
            existingWifiSsid = if (wireless) AirPlayPersistence.loadExistingWifiSsid(this) else "",
            existingWifiPassphrase = if (wireless) AirPlayPersistence.loadExistingWifiPassphrase(this) else "",
            locationReportingEnabled = false,
        )
    }

    private fun buildAirPlayConfig(width: Int, height: Int, identity: com.shilapi.xcertplay.airplay.AirPlayIdentity): AirPlayConfig {
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = (width / resources.displayMetrics.xdpi * 25.4f).toInt().coerceAtLeast(1),
            heightPhysicalMm = (height / resources.displayMetrics.ydpi * 25.4f).toInt().coerceAtLeast(1),
            fps = AirPlayDisplaySettings.DEFAULT_FPS,
            primaryInputDevice = 1,
        )
        val chosenPort = pickAirPlayPort()
        appendLog("AirPlay 端口选择: $chosenPort")
        return AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = DiPlayBootstrap.deviceId(identity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(identity),
            sourceVersion = "950.7.1",
            port = chosenPort,
            main = baseDisplay,
            cluster = null,
            rightHandDrive = false,
            // Old SoCs in these units have H264 but rarely HEVC hardware decode.
            hevc = false,
            microphone = micAvailable(),
            manufacturer = "DiPlay",
            model = Build.MODEL ?: "legacy",
            oemLabel = "",
            icons = listOf(loadAirPlayIcon()),
            videoInCar = false,
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val encoded = resources.openRawResource(com.shilapi.xcertplay.host.R.raw.ic_car_home).use { it.readBytes() }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outWidth != bounds.outHeight) {
            throw IllegalStateException("Packaged AirPlay icon is invalid")
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun locationProvider(): AndroidCarPlayLocationProvider? {
        val enabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        if (!enabled) return null
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return AndroidCarPlayLocationProvider(this)
    }

    private fun createStatusReporter(generation: Int): (CarPlayStatus) -> Unit = { status ->
        if (generation == restartGeneration) {
            val text = describeStatus(status)
            setStatus(text)
            if (status is CarPlayStatus.Failed) {
                appendLog("失败: ${status.message}")
                scheduleReconnect()
            }
        }
    }

    private fun createSessionListener(generation: Int): AirPlaySessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            runOnUiThread {
                if (generation != restartGeneration) return@runOnUiThread
                sessionActive = true
                reconnectAttempts = 0
                CarPlayBackgroundSession.active = true
                setStatus("CarPlay 已连接")
                appendLog("AirPlay 会话已激活")
            }
        }

        override fun onVideoFrameRendered(session: AirPlaySession) {
            runOnUiThread {
                if (generation == restartGeneration && sessionActive) setStatus("视频渲染中")
            }
        }

        override fun onSessionEnded(session: AirPlaySession) {
            runOnUiThread {
                if (generation != restartGeneration) return@runOnUiThread
                sessionActive = false
                CarPlayBackgroundSession.active = false
                setStatus("会话结束，准备重连…")
                appendLog("AirPlay 会话结束")
                scheduleReconnect()
            }
        }

        override fun onTransportError(message: String) {
            runOnUiThread {
                if (generation != restartGeneration) return@runOnUiThread
                sessionActive = false
                setStatus("传输错误: $message")
                appendLog("传输错误: $message")
                scheduleReconnect()
            }
        }

        override fun onDebugLog(message: String) {
            runOnUiThread { appendLog(message) }
        }
    }

    private fun describeStatus(status: CarPlayStatus): String = when (status) {
        is CarPlayStatus.Failed -> "失败: ${status.message}"
        CarPlayStatus.DiscoveringMfi -> "准备 MFi 认证…"
        CarPlayStatus.MfiReady -> "MFi 认证就绪"
        CarPlayStatus.DiscoveringIphone -> "正在查找 iPhone"
        CarPlayStatus.WaitingForIphone -> "等待 iPhone 接入 USB"
        CarPlayStatus.RequestingIphonePermission -> "正在请求 iPhone USB 权限"
        CarPlayStatus.WaitingForReenumeration -> "等待 iPhone 重新识别"
        CarPlayStatus.SelectingConfiguration -> "正在选择 CarPlay 配置"
        CarPlayStatus.OpeningDataPaths -> "正在打开 USB 数据通路"
        CarPlayStatus.Pairing -> "正在与 iPhone 配对"
        CarPlayStatus.ConnectingControl -> "正在建立 iAP2 控制"
        CarPlayStatus.AttachingNetwork -> "正在挂载网络"
        CarPlayStatus.RunningControl -> "CarPlay 控制通道运行中"
        CarPlayStatus.WaitingForPairedIphone -> "等待已配对 iPhone"
        else -> status.javaClass.simpleName
    }

    private fun onHostTouch(view: android.view.View, event: MotionEvent): Boolean {
        val activeController = controller ?: return true
        if (!sessionActive) return true
        val layout = com.shilapi.xcertplay.media.CarPlayVideoLayout.fit(
            if (videoWidth > 0) videoWidth else view.width,
            if (videoHeight > 0) videoHeight else view.height,
            view.width, view.height,
        )
        val contacts = CarPlayTouchMapper.contacts(event, layout)
        if (contacts.isNotEmpty()) activeController.sendTouch(contacts)
        return true
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled || shuttingDown.get()) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            setStatus("多次重连失败。请检查连接后从主页重试。")
            appendLog("重连次数用尽")
            return
        }
        reconnectScheduled = true
        reconnectAttempts += 1
        val delay = (3000L * reconnectAttempts).coerceAtMost(15000L)
        appendLog("${delay / 1000} 秒后重连（第 $reconnectAttempts 次，等待网络接口稳定）")
        mainHandler.postDelayed({
            reconnectScheduled = false
            if (shuttingDown.get()) return@postDelayed
            // If the AP interface is bouncing (driver reset per Bonjour registration), do not
            // amplify the cycle: wait until the interface is present and stable before retrying.
            var stableCount = 0
            var lastSnapshot = interfaceSnapshot()
            val stabilityCheck = object : Runnable {
                override fun run() {
                    if (shuttingDown.get()) { reconnectScheduled = false; return }
                    val snapshot = interfaceSnapshot()
                    stableCount = if (snapshot == lastSnapshot) stableCount + 1 else 0
                    lastSnapshot = snapshot
                    if (snapshot.contains("ipv4=[]") || snapshot.isBlank()) {
                        stableCount = 0
                    }
                    if (stableCount < 3) {
                        mainHandler.postDelayed(this, 1000)
                        return
                    }
                    startSession()
                }
            }
            stabilityCheck.run()
        }, delay)
    }

    private fun shutdown(reason: String, completion: () -> Unit) {
        if (!shuttingDown.compareAndSet(false, true)) {
            completion()
            return
        }
        restartGeneration += 1
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        sessionActive = false
        stopInterfaceWatcher()
        Log.i(TAG, "shutdown reason=$reason")
        Thread {
            runCatching { oldController?.close() }
            runCatching { oldSink?.close() }
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            runOnUiThread { completion() }
        }.start()
    }

    private fun setStatus(text: String) {
        runOnUiThread { if (::statusView.isInitialized) statusView.text = text }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            if (!::logView.isInitialized) return@runOnUiThread
            logLines.addLast(message.take(200))
            while (logLines.size > 5) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun appendCrash(what: String, error: Throwable) {
        val trace = java.io.StringWriter()
            .also { java.io.PrintWriter(it).use { w -> error.printStackTrace(w) } }
            .toString()
        val entry = "==== ${System.currentTimeMillis()} $what (SDK ${Build.VERSION.SDK_INT}) ====\n$trace\n"
        for (dir in listOfNotNull(getExternalFilesDir(null), filesDir)) {
            runCatching {
                dir.mkdirs()
                File(dir, "diplay-crash.txt").appendText(entry)
            }
        }
    }

    companion object {
        private const val TAG = "DiPlay-Legacy"
        const val VPN_REQUEST = 4001
        const val MIC_REQUEST = 4002
        internal const val EXTRA_WIRELESS = "wireless"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        // CarPlayHostActivity's screen ids (110 main / 111 alt) - same wire values.
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
    }
}
