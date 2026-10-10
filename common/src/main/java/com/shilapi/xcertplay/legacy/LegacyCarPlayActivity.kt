package com.shilapi.xcertplay.legacy

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    @Volatile private var afterShutdown: (() -> Unit)? = null
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var sessionActive = false
    private var sessionTransport: CarPlayTransport? = null
    private var startPendingSurface = false
    private var awaitingVpnConsent = false
    private lateinit var statusView: TextView
    private lateinit var btStatusView: TextView
    private lateinit var logView: TextView
    private lateinit var debugControls: LinearLayout
    private lateinit var debugToggleButton: Button
    private var debugOverlayVisible = false
    private lateinit var surfaceView: SurfaceView
    private lateinit var videoFrame: FrameLayout
    private var latestSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var rotationReconnectScheduled = false
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 4)
        }
        btStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(255, 214, 120))
            setPadding(24, 0, 24, 0)
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

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                applyVideoLayout()
                scheduleRotationReconnect()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                latestSurface = null
            }
        })
        surfaceView.setOnTouchListener { view, event -> onHostTouch(view, event) }

        debugControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun controlButton(label: String, action: () -> Unit) = Button(this@LegacyCarPlayActivity).apply {
                text = label
                setOnClickListener { action() }
            }
            addView(controlButton("重新连接") { manualReconnect() })
            addView(controlButton("重启蓝牙") { bounceBluetooth() })
            debugToggleButton = controlButton("隐藏调试") { setDebugOverlayVisible(!debugOverlayVisible) }
            addView(debugToggleButton)
        }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            addView(statusView)
            addView(btStatusView)
            addView(debugControls)
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
        videoFrame = root
        setContentView(root)
        debugOverlayVisible = AirPlayPersistence.loadLegacyDebugOverlayVisible(this)
        applyDebugOverlayVisibility()
        mainHandler.post(object : Runnable {
            override fun run() {
                applyVideoLayout()
                scheduleRotationReconnect()
                updateBtStatus()
                mainHandler.postDelayed(this, 2000)
            }
        })
        runCatching {
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.append(
                this, com.shilapi.xcertplay.legacy.LegacyDiagnostics.LOG_FILE,
                "==== ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} 会话开始 ====\n" +
                    com.shilapi.xcertplay.legacy.LegacyDiagnostics.platformReport(this) + "\n",
            )
        }
        appendLog("compat-4.4 CarPlay 宿主已启动 SDK=${Build.VERSION.SDK_INT}")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MIC_REQUEST) {
            appendLog(if (resultCode == RESULT_OK) "麦克风权限授予" else "麦克风权限被拒（通话不可用）")
        }
        if (requestCode == VPN_REQUEST) {
            // A launch that failed has already reported itself and cleared the pending flag, so a
            // stray result must not start a second session on top of that attempt.
            if (!awaitingVpnConsent) return
            awaitingVpnConsent = false
            if (resultCode == RESULT_OK) {
                appendLog("VPN 授权成功")
                startSession()
            } else {
                setStatus("VPN 授权被拒绝：CarPlay 需要它建立到 iPhone 的网络通路")
                appendLog("VPN 授权被拒绝")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val attached = isUsbAttachmentIntent(intent)
        val requested = intent.explicitTransport()
        setIntent(intent)
        val running = sessionTransport
        if (controller != null && !shuttingDown.get() && running != null) {
            if (attached) {
                // A cable is unplugged on its own schedule, and a session that has to be rebuilt costs
                // the owner a full re-handshake. Switching routes is the home screen's job, where it is
                // a deliberate tap on one of the two buttons.
                appendLog("检测到 USB 设备插入，保持当前 $running 会话（要换路线请用主页的两个连接按钮）")
                return
            }
            if (requested == null) {
                // 打开 CarPlay 画面 / 查看连接进度 name no route: they ask for the picture, and the
                // recorded choice can predate the session on screen, so following it here used to drop a
                // working wireless session onto the wired path.
                appendLog("主页重进未指定路线，保持当前 $running 会话")
                setStatus(
                    if (sessionActive) "CarPlay 正在运行"
                    else "正在连接 iPhone…（要重试请在主页点「有线」或「无线」）",
                )
                return
            }
            // An explicit route is the owner speaking: the same route means reconnect, the other means
            // switch, and both are worth tearing the session down for.
            appendLog(if (requested == running) "按主页选择在本路线重连：$running" else "按主页选择切换路线：$running → $requested")
        }
        reconnectAttempts = 0
        appendLog("手动重连: 按主页请求重新启动会话")
        setStatus("手动重连中…")
        shutdown("manual-reconnect") { startSession() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // This activity declares orientation|screenSize in configChanges, so a rotation is
        // delivered here rather than recreating the host mid-session.
        videoFrame.post {
            applyVideoLayout()
            scheduleRotationReconnect()
        }
    }

    override fun onResume() {
        super.onResume()
        // The switch lives on the home screen now, so an owner can change it while this instance is
        // still alive behind it; coming back has to pick that up rather than keep the old overlay.
        debugOverlayVisible = AirPlayPersistence.loadLegacyDebugOverlayVisible(this)
        applyDebugOverlayVisibility()
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
    private fun pickAirPlayPort(host: java.net.InetAddress?, startFrom: Int): Int {
        val ports = (startFrom..7010).toList() + (7000 until maxOf(startFrom, 7000)).toList()
        for (port in ports.distinct()) {
            try {
                java.net.ServerSocket().use { socket ->
                    val bindAddress = host ?: java.net.InetAddress.getByName("0.0.0.0")
                    socket.bind(java.net.InetSocketAddress(bindAddress, port))
                    return port
                }
            } catch (error: java.io.IOException) {
                appendLog("端口 $port 在 ${host?.hostAddress ?: "0.0.0.0"} 上被占用，尝试下一个")
            }
        }
        return 7000
    }

    /** First Wi-Fi-ish interface IPv4 (the unit's hotspot or its station interface). */
    private fun passiveWifiAddress(): java.net.Inet4Address? = runCatching {
        val enumerated = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in enumerated) {
            if (!iface.isUp) continue
            val name = iface.name.lowercase()
            if (!(name.startsWith("ap") || name.startsWith("wlan") || name.startsWith("swlan") ||
                    name.startsWith("softap"))
            ) continue
            for (address in iface.inetAddresses) {
                if (address is java.net.Inet4Address && !address.isLoopbackAddress) return address
            }
        }
        null
    }.getOrDefault(null)

    @Volatile private var currentAirPlayPort = 7000

    private fun micAvailable(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Several car ROMs ship no VPN consent activity. Naming that failure on screen keeps it apart
     * from a session crash, which the top-level handler would otherwise report as 启动失败.
     */
    private fun onVpnConsentUnavailable(consent: Intent, error: RuntimeException) {
        awaitingVpnConsent = false
        Log.w(TAG, "VPN consent activity unavailable", error)
        appendLog(
            "VPN 授权界面无法打开 failureClass=${error.javaClass.simpleName} " +
                "component=${consent.component?.flattenToString() ?: "none"}",
        )
        setStatus("车机无法打开 VPN 授权界面：请在车机设置中允许 DiPlay 建立 VPN 连接，或改用无线连接")
    }

    /** The route this launch asks for, with the reason kept so the log can name the case. */
    private fun decisionFor(launchIntent: Intent): LegacyTransportChoice.Decision =
        LegacyTransportChoice.decide(
            usbAttachment = isUsbAttachmentIntent(launchIntent),
            explicitRequest = launchIntent.explicitTransport(),
            persistedChoice = runCatching { AirPlayPersistence.loadLegacyTransport(this) }.getOrNull(),
        )

    /** A launch that never mentions a route reads as no request; the default is wired, so it must not. */
    private fun Intent.explicitTransport(): CarPlayTransport? = when {
        !hasExtra(EXTRA_WIRELESS) -> null
        getBooleanExtra(EXTRA_WIRELESS, false) -> CarPlayTransport.WIRELESS
        else -> CarPlayTransport.WIRED
    }

    /**
     * Only the system's own attach intent counts as an insertion. The home screen's wired button
     * carries no device parcel, and treating it as an insertion would let a tap outrank a session.
     */
    private fun isUsbAttachmentIntent(launchIntent: Intent): Boolean =
        launchIntent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            launchIntent.getParcelableExtra<android.hardware.usb.UsbDevice?>(
                android.hardware.usb.UsbManager.EXTRA_DEVICE,
            ) != null

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
        val decision = decisionFor(intent)
        val wireless = decision.transport == CarPlayTransport.WIRELESS
        // Record the route as it is acted on, so a later launch that names nothing - the home card's
        // 打开 CarPlay 画面 - follows this session instead of falling back to the cable.
        AirPlayPersistence.saveLegacyTransport(this, decision.transport)
        sessionTransport = decision.transport
        appendLog("会话路线=${decision.transport} 来源=${decision.source}")
        if (!wireless) {
            val consent = CarPlayVpnService.prepare(this)
            CarPlayVpnService.prepareError?.let { error ->
                appendLog(
                    "VPN 授权检查在这台车机上不可用(${error.javaClass.simpleName})，" +
                        "继续尝试建立隧道，由 establish 判定权限",
                )
            }
            if (consent != null) {
                setStatus("请在弹窗中允许 VPN 连接（CarPlay 网络需要）")
                awaitingVpnConsent = true
                try {
                    startActivityForResult(consent, VPN_REQUEST)
                } catch (error: ActivityNotFoundException) {
                    onVpnConsentUnavailable(consent, error)
                    return
                } catch (error: SecurityException) {
                    onVpnConsentUnavailable(consent, error)
                    return
                }
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
        val hostAddress: java.net.Inet4Address? = if (wireless) passiveWifiAddress() else null
        // Negotiate against the window, not the surface: the surface shrinks to letterbox the canvas.
        val width = videoFrame.width.coerceAtLeast(64)
        val height = videoFrame.height.coerceAtLeast(64)
        val size = Pair(width / 2 * 2, height / 2 * 2)
        setStatus("启动 CarPlay 会话 ${size.first}x${size.second}…")

        val airPlayConfig = buildAirPlayConfig(size.first, size.second, identity, hostAddress)
        val config = buildRuntimeConfig(identity, decision.transport)
        val renderer = AndroidMediaSink(
            surface = latestSurface,
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            preferSoftwareHevcDecoder = false,
            advancedAudioChannelMapping = false,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            navigationDuckEnabled = AirPlayPersistence.loadNavigationDuckEnabled(this),
            callOnCabinSpeaker = AirPlayPersistence.loadCallOnCabinSpeaker(this),
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
        applyVideoLayout()
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
        val display = CarPlaySessionDisplay(
            size.first, size.second, displayRotation(), false, false, width, height,
        )
        sessionDisplay = display
        CarPlayBackgroundSession.store(
            next, renderer, size.first, size.second, this, display,
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

    private fun buildRuntimeConfig(
        identity: com.shilapi.xcertplay.airplay.AirPlayIdentity,
        transport: CarPlayTransport,
    ): CarPlayRuntimeConfig {
        val mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        val wireless = transport == CarPlayTransport.WIRELESS
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

    private fun buildAirPlayConfig(
        width: Int,
        height: Int,
        identity: com.shilapi.xcertplay.airplay.AirPlayIdentity,
        hostAddress: java.net.Inet4Address?,
    ): AirPlayConfig {
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = (width / resources.displayMetrics.xdpi * 25.4f).toInt().coerceAtLeast(1),
            heightPhysicalMm = (height / resources.displayMetrics.ydpi * 25.4f).toInt().coerceAtLeast(1),
            fps = AirPlayPersistence.loadFps(this),
            primaryInputDevice = 1,
        )
        // Probe ON the hotspot address: a wildcard probe misses services bound to the
        // specific interface address. Start after the last port that failed EADDRINUSE.
        val startFrom = getSharedPreferences("diplay", MODE_PRIVATE)
            .getInt("last_failed_airplay_port", 6999) + 1
        val chosenPort = pickAirPlayPort(hostAddress, startFrom)
        currentAirPlayPort = chosenPort
        appendLog("AirPlay 端口选择: $chosenPort（探测地址 ${hostAddress?.hostAddress ?: "0.0.0.0"}）")
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
            if (status is CarPlayStatus.Failed && status.message.contains("EADDRINUSE")) {
                // Remember the failed port so the retry probes past it.
                getSharedPreferences("diplay", MODE_PRIVATE)
                    .edit().putInt("last_failed_airplay_port", currentAirPlayPort).apply()
                setStatus("$text（端口 ${currentAirPlayPort} 被占用，已记录）")
            }
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

    /**
     * Keeps the decoder surface at the negotiated canvas aspect. A pre-Lollipop SurfaceView scales
     * its buffer to fill the view with no transform of its own, so letterboxing has to shrink the
     * view itself; otherwise a rotation leaves the old-shaped stream stretched across the new one.
     */
    private fun applyVideoLayout() {
        if (!::videoFrame.isInitialized || !::surfaceView.isInitialized) return
        val hostWidth = videoFrame.width
        val hostHeight = videoFrame.height
        if (hostWidth <= 0 || hostHeight <= 0) return
        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        val fullBleed = params.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            params.height == ViewGroup.LayoutParams.MATCH_PARENT &&
            params.leftMargin == 0 && params.topMargin == 0
        if (videoWidth <= 0 || videoHeight <= 0) {
            if (fullBleed) return
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.leftMargin = 0
            params.topMargin = 0
            surfaceView.layoutParams = params
            return
        }
        val content = com.shilapi.xcertplay.media.CarPlayVideoLayout.fit(
            videoWidth, videoHeight, hostWidth, hostHeight,
        )
        val scaledWidth = content.width.toInt().coerceAtLeast(1)
        val scaledHeight = content.height.toInt().coerceAtLeast(1)
        val left = content.left.toInt()
        val top = content.top.toInt()
        if (params.width == scaledWidth && params.height == scaledHeight &&
            params.leftMargin == left && params.topMargin == top) return
        params.width = scaledWidth
        params.height = scaledHeight
        params.leftMargin = left
        params.topMargin = top
        surfaceView.layoutParams = params
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        windowManager.defaultDisplay.rotation
    } catch (_: Exception) {
        0
    }

    /**
     * CarPlay only takes a canvas at handshake, so an orientation change cannot be followed without
     * reconnecting. Compares the window rather than the surface: the surface is deliberately smaller
     * whenever the letterbox is in effect.
     */
    private fun scheduleRotationReconnect() {
        if (rotationReconnectScheduled || controller == null || !sessionActive) return
        if (shuttingDown.get() || startPendingSurface || awaitingVpnConsent) return
        val display = sessionDisplay ?: return
        if (!displayNeedsReconnect(display, videoFrame.width, videoFrame.height, displayRotation())) return
        rotationReconnectScheduled = true
        mainHandler.postDelayed(rotationReconnect, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private val rotationReconnect = Runnable {
        rotationReconnectScheduled = false
        val display = sessionDisplay ?: return@Runnable
        if (shuttingDown.get() || controller == null) return@Runnable
        val width = videoFrame.width
        val height = videoFrame.height
        if (!displayNeedsReconnect(display, width, height, displayRotation())) return@Runnable
        appendLog("屏幕比例已变 ${display.windowWidth}x${display.windowHeight} -> ${width}x${height}，重连让 iPhone 按新形状出流")
        reconnectAttempts = 0
        reconnectScheduled = false
        setStatus("重连以适应新方向…")
        shutdown("display rotated") { startSession() }
    }

    /** Whether the window shape moved enough that only a fresh handshake can follow it. */
    private fun displayNeedsReconnect(
        display: CarPlaySessionDisplay,
        width: Int,
        height: Int,
        rotation: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || display.windowWidth <= 0 || display.windowHeight <= 0) return false
        if (rotation == display.rotation) {
            val aspectDiff = kotlin.math.abs(
                (width.toDouble() / height) / (display.windowWidth.toDouble() / display.windowHeight) - 1.0,
            )
            if (aspectDiff <= 0.08) return false
        }
        return true
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

    private fun manualReconnect() {
        reconnectAttempts = 0
        reconnectScheduled = false
        appendLog("手动重连")
        setStatus("手动重连中…")
        shutdown("manual-reconnect") { startSession() }
    }

    private fun bounceBluetooth() {
        val adapter = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null) {
            setStatus("本机没有蓝牙适配器")
            return
        }
        if (!adapter.isEnabled) {
            appendLog("蓝牙已关闭, 正在重新开启")
            setStatus("蓝牙已关闭, 正在重新开启…")
            runCatching { adapter.enable() }
            awaitBluetoothEnabled(adapter) { manualReconnect() }
            return
        }
        // The msm8916/T3 BT stacks wedge sometimes; bounce the adapter to recover it.
        appendLog("手动重启蓝牙: 先关闭")
        setStatus("正在重启蓝牙…")
        runCatching { adapter.disable() }
        mainHandler.postDelayed({
            appendLog("手动重启蓝牙: 重新开启")
            runCatching { adapter.enable() }
            awaitBluetoothEnabled(adapter) { manualReconnect() }
        }, 2500)
    }

    /** The T3 re-enables Bluetooth in tens of seconds - poll instead of a fixed wait. */
    private fun awaitBluetoothEnabled(
        adapter: android.bluetooth.BluetoothAdapter,
        attempt: Int = 0,
        done: () -> Unit,
    ) {
        if (adapter.isEnabled) {
            setStatus("蓝牙已就绪, 正在重连…")
            done()
            return
        }
        if (attempt >= 30) {
            setStatus("蓝牙迟迟未开启, 请到系统设置检查")
            appendLog("蓝牙重新开启超时")
            return
        }
        mainHandler.postDelayed({ awaitBluetoothEnabled(adapter, attempt + 1, done) }, 1000)
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled || shuttingDown.get()) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            setStatus("自动重连已暂停。点【重新连接】重试，蓝牙异常时先点【重启蓝牙】。")
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
            // A teardown is already in flight: replace what it will do next. Never drop the
            // request on the floor - that is how the activity used to become a zombie that
            // only a process kill could revive.
            afterShutdown = completion
            return
        }
        afterShutdown = completion
        restartGeneration += 1
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        sessionDisplay = null
        sessionActive = false
        stopInterfaceWatcher()
        Log.i(TAG, "shutdown reason=$reason")
        Thread {
            runCatching { oldController?.close() }
            runCatching { oldSink?.close() }
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            runOnUiThread {
                videoWidth = 0
                videoHeight = 0
                applyVideoLayout()
                val next = afterShutdown
                afterShutdown = null
                shuttingDown.set(false)
                next?.invoke()
            }
        }.start()
    }

    private fun updateBtStatus() {
        // Reading the bond list is not free on these ROMs and it is polled every two seconds, so it
        // only happens while that line is actually on screen.
        if (!debugOverlayVisible) return
        runOnUiThread {
            if (!::btStatusView.isInitialized) return@runOnUiThread
            val adapter = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
            val state = when {
                adapter == null -> "不可用"
                adapter.isEnabled -> "开"
                else -> "关 ⚠"
            }
            val bonded = runCatching { adapter?.bondedDevices?.size ?: 0 }.getOrDefault(0)
            btStatusView.text = "蓝牙: $state | 已配对设备: $bonded"
        }
    }

    /** Hides the on-screen debug block only; appendLog keeps writing the file log either way. */
    private fun setDebugOverlayVisible(visible: Boolean) {
        debugOverlayVisible = visible
        AirPlayPersistence.saveLegacyDebugOverlayVisible(this, visible)
        applyDebugOverlayVisibility()
    }

    /**
     * Debug mode owns the whole block: with it off the picture is all there is, because a wall of log
     * lines and buttons is what the owner sees instead of CarPlay. The status line stays until a
     * session is actually up — an otherwise black screen with no explanation is worse than one line
     * of text — and it is set by [setStatus], which every state change goes through.
     */
    private fun applyDebugOverlayVisibility() {
        if (!::logView.isInitialized) return
        val visibility = if (debugOverlayVisible) View.VISIBLE else View.GONE
        logView.visibility = visibility
        btStatusView.visibility = visibility
        debugControls.visibility = visibility
        statusView.visibility =
            if (debugOverlayVisible || !sessionActive) View.VISIBLE else View.GONE
        debugToggleButton.text = if (debugOverlayVisible) "隐藏调试" else "显示调试"
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            if (!::statusView.isInitialized) return@runOnUiThread
            statusView.text = text
            applyDebugOverlayVisibility()
        }
    }

    private fun appendLog(message: String) {
        runCatching {
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.append(
                this, com.shilapi.xcertplay.legacy.LegacyDiagnostics.LOG_FILE,
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " " + message + "\n",
            )
        }
        runOnUiThread {
            if (!::logView.isInitialized) return@runOnUiThread
            logLines.addLast(message.take(200))
            while (logLines.size > 5) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun appendCrash(what: String, error: Throwable) {
        runCatching {
            com.shilapi.xcertplay.legacy.LegacyDiagnostics.append(
                this, com.shilapi.xcertplay.legacy.LegacyDiagnostics.CRASH_FILE,
                com.shilapi.xcertplay.legacy.LegacyDiagnostics.crashEntry(what, error),
            )
        }
    }

    companion object {
        private const val TAG = "DiPlay-Legacy"
        const val VPN_REQUEST = 4001
        const val MIC_REQUEST = 4002
        internal const val EXTRA_WIRELESS = "wireless"
        private const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        private const val MAX_RECONNECT_ATTEMPTS = 50
        // CarPlayHostActivity's screen ids (110 main / 111 alt) - same wire values.
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
    }
}
