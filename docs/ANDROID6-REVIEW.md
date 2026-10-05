# 安卓 6 兼容方案审查（2026-10-05）

审查对象：提交 `f80872d`（minSdk 28 → 23）+ 工作区未提交的第二轮修复。
方法：把 APK 的 dex 调用点与 SDK 自带的 `data/api-versions.xml`（每个框架方法的引入版本）逐条对照，
而不是靠 grep 猜。工具见 `scripts/check-dex-api-levels.py`，完整清单见 `docs/android6-api-calls.txt`。

## 一句话结论

**方向是对的（单套代码 + 运行时门控 + JDK 库脱糖），但"能在安卓 6 上完美跑 CarPlay"这件事现在无法成立，
而且现有工程手段永远证明不了它**：真正该被测的两个模块 `:common`/`:shared`（450 个文件）从未被 Lint 检查过，
所有测试的最低 SDK 是 28，仓库里那个 android6 APK 早于关键修复、拿它上车机测一定会失败。

## P0：会让"安卓 6 跑通"这件事直接不成立

1. **根目录的 android6 APK 是陈旧产物，USB 读路径还没修。**
   `DiPlay-0.2.12-android6-debug.apk` 构建于 10-05 14:05，而 `IphoneUsbHost.kt` 的兼容读改在 15:29。
   在 dex 里可以验证：`legacyFocusStream`、脱糖后的 `j$.util.Base64` 都在，
   但 `readBulkCompat` / `bulkReadBuffer` / `underrunCountCompat` / `PendingAnswer` / `closeP2pChannel` **全部不存在**。
   也就是说这个包里 `Iap2UsbSession.read()` 仍直接调用 `UsbRequest.queue(ByteBuffer)` 和
   `UsbDeviceConnection.requestWait(long)` —— 按 SDK 元数据这两个方法都是 **API 26**，
   在安卓 6 上是 `NoSuchMethodError`（属于 `Error`，代码里的 `catch (RuntimeException)` 拦不住）。
   后果：任何"我在车机上试过这个包"的结论都作废，兼容修复从未在设备上被执行过一次。

2. **兼容分支目前零验证。** `.github/workflows/android.yml:26` 对 `:shared`/`:common` 只跑单元测试，
   lint 仅 `:mobile`、`:home`、`:maphost` —— 而 `:mobile/src/main` 一个 Kotlin 文件都没有（只有 3 个 debug 目录文件），
   所有框架调用都在 `:common`/`:shared`，**`NewApi` 门禁事实上不存在**。
   单元测试方面，85 个 Robolectric 测试类全都带 `@Config(sdk = […])`，取值最低 28（分布 28/29/30/32/33），
   也就是说 Android 6 走的 legacy 分支一次都没有被执行过。

3. **`docs/ANDROID6-PORT.md:9` 与事实不符。** `shared/src/main/jni/Application.mk:1` 仍是 `APP_PLATFORM := android-28`，
   只有 `shared/build.gradle:18` 的命令行参数把它覆盖成 23。Gradle 构建没问题，
   但任何直接 `ndk-build`、IDE 同步、或以后加第二个 native 入口都会按 28 编译，文档却宣称已经按 23。

## P1：能跑，但体验和可维护性有实质问题

4. **安卓 6 的 USB 读回退用 `bulkTransfer`，会重新引入你们自己记录过的性能故障。**
   `NcmUsbBridge.kt:45-48` 的注释写得很清楚：`bulkTransfer()` 在等待期间把 `byte[]` pin 在 JNI critical 区，
   阻塞 ART 的 GC thread flip，进而拖住同一连接上的其它传输，表现为约 0.8 秒的音视频停顿；
   正是因为这个，读方向才被改成常驻异步 request。而 `IphoneUsbHost.kt:413` 的 `readBulkCompat`
   和 `NcmUsbBridge.kt:223` 的 <26 分支，把这条退化路径原样放回了安卓 6 —— 恰好是视频/音频都在跑的主路径。
   好消息是没必要这样：**真正 API 26 才有的只有 `queue(ByteBuffer)` 和 `requestWait(long)` 两个签名；
   `initialize(conn, endpoint)`、`queue(ByteBuffer, int)`、无参 `requestWait()`、`cancel()` 全是 API 12 就有**（已核对 api-versions.xml）。
   所以安卓 6-7 可以保留"常驻异步请求 + 无超时等待"的架构，把超时上移一层（专职 reader 线程阻塞在 `requestWait()`，
   调用方 `LinkedBlockingQueue.poll(timeout)`，超时后 `cancel()`）。这样异步语义、可取消性、GC 不阻塞三点都保住，
   还顺带解决现在"超时和 I/O 错误无法区分、`close()` 打断不了阻塞读"的问题。
   另外 `timeoutMillis.coerceAtLeast(1).toInt()` 存在 Long→Int 截断（超时 > 24.8 天才会，实际无害，但写法不对）。

5. **`CenterMapOverlay` 在安卓 6 上静默失效。** `CenterMapOverlay.kt:99` 无条件用
   `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`（API 26，值是编译期内联的 2038），
   `:246` 又用 `catch (RuntimeException)` 把 `addView` 的失败吞掉只返回 false。
   它不是 BYD 专属功能：入口在 `CarPlayHostActivity.kt:1069-1091`，由用户开关"中控地图卡片"控制，
   所以安卓 6 用户开了开关什么也得不到，也没有任何提示。修法很简单：低于 26 用 `TYPE_SYSTEM_ALERT`/`TYPE_PHONE`。

6. **MFi/Lockdown TLS 的算法选择在安卓 6 上是未知量。** `LockdownTlsEngineFactory.kt:44` 只试
   `KeyManagerFactory.getInstance("PKIX")`，没有备选。Android 7 换用 OpenJDK 化 libcore 之后 JSSE 注册表才稳定，
   安卓 6 的 provider 是否给 KeyManagerFactory 注册 PKIX，静态分析证明不了，而这是配对必经路径，
   现场表现会是 `NoSuchAlgorithmException` → 连不上且看不出原因。加一条 `PKIX → SunX509 → X509 → 默认算法`
   的回退链成本极低。同一函数里 `:54` 的 `sslParameters` 门控写法是对的（null 本就是引擎默认值）。

7. **安卓 6 的 Doze / App Standby 完全没处理。** 全仓（common/shared/mobile）没有 `WAKE_LOCK`、没有 `PowerManager`、
   没有 `isIgnoringBatteryOptimizations`/`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的任何使用
   —— Android 6 恰恰是引入 Doze 的那个版本。息屏 + 静止时，会话里的 USB 轮询与网络会被整体推迟。
   目前只有 `CarPlayHostActivity.kt:491` 的 `FLAG_KEEP_SCREEN_ON`（前台有效，Activity 一退到后台就没了）。
   建议：会话期间持 PARTIAL_WAKE_LOCK，并在设置页/诊断导出里给出 Doze 状态与省电豁免入口（都是 API 23 自带）。

8. **`coreLibraryDesugaringEnabled` 放在 library 模块里只有 lint 效果，注释写成了运行时效果。**
   `common/build.gradle.kts` 与 `shared/build.gradle` 的注释说"covers any desugared API this module adds later"，
   但库模块不产 dex，脱糖发生在 app 构建阶段；`:shared` 的 AAR 里 `java.util.Base64` 调用照原样存在。
   现在没问题（只有 `mobile` 消费它，且 minSdk 23），但这句话以后会误导人 —— 谁把 `:shared` 给别的 app 用就翻车。
   顺带：`Iap2LocationClient` 把 `java.time.Instant` 换成 `Calendar` 其实不必要（java.time 是脱糖覆盖的），
   不算错，只是说明当时是在盲改。

9. **依赖栈不是障碍，这一条可以放心。** 实测 AAR 元数据：`androidx.media3:* 1.11.1` 与 `androidx.core 1.19.0` 的 minSdk 都是 23，
   `androidx.car.app:app/app-projected 1.4.0` 是 21。所以清单里"manifest 合并可能失败"的担心不成立。
   另外 `abiFilters` 缺 32 位 `x86`（有 `x86_64`），只影响 I2C/热点那两个 `.so` 的模拟器与非关键路径。

## 我认为更好的整体方案

现在的门控写法有 6 种并存：`SDK_INT >= 26`、`>= Build.VERSION_CODES.TIRAMISU`、`@Suppress("NewApi")`、
`@SuppressLint("NewApi")`、扩展属性 `underrunCountCompat`、私有方法 `readBulkCompat`。
门控散落在业务代码里是这类长期移植最容易失控的地方（本轮就漏了 USB 读、overlay、CompletableFuture、startForegroundService 四处，
全靠人工 grep 才发现）。建议四层收口：

1. **产物级门禁（最高价值，我已落地）**：`scripts/check-dex-api-levels.py` 直接读 APK，把"我们自己的类调用的高于 floor 的框架 API"
   连同调用方类名打印出来，退出码非 0。它比 Lint 强的地方是**不受 `@SuppressLint("NewApi")` 影响**，
   而本次审查恰恰有几处是用这个注解消掉的（`LocalOnlyHotspotManager`、`ManualHotspotInterfaces`、`CarPlayMediaKeys`、`IphoneUsbHost.drainCancelledRead`）。
   接进步骤：CI 里对每次 android6 构建产物跑一遍，输出物当检查清单，人工在 PR 上确认每条都有门控或不可达。
2. **模块级 Lint**：CI 补 `:common:lintDebug :shared:lintDebug`（默认 `abortOnError=true`，无需额外配置），
   并把"带 `NewApi` 抑制必须写明不可达证据"作为 review 规则。
3. **门控集中化**：把兼容性判断收敛成少数门面（`UsbIoCompat`、`AudioFocusCompat`、`NotificationCompat` 用法、`WindowTypeCompat`、
   `LocaleCompat`），每个配一个 `@Config(sdk = 23)` 的 Robolectric 测试。能用官方 backport 就别说"更好"，而是"应该换掉"：
   `androidx.core` 的 `NotificationCompat.Builder`、`ContextCompat.registerReceiver(… RECEIVER_NOT_EXPORTED)` 已经内置了版本分派，
   `androidx.media` 的 `AudioFocusRequestCompat` 就是专为 <26 设计的（含 ref-count），
   这三处现在都是手写 `SDK_INT` 分派。
4. **能力探测 + 启动自检**：`CarPlayController.startWirelessHotspot` 在 <29 抛 `WirelessStartupException` 是对的，
   但同类判断应该一次算清（无线 / 中控卡片 / HUD / 热点反射 / 低延迟音频 / 硬解能力），存进一个 `DeviceCapabilities`，
   UI 据此隐藏入口、诊断导出据此上报，而不是运行到某处才炸。
   另外在 <26 设备上跑一次主动探针，`try/catch (Throwable)`（必须含 `LinkageError`）逐个调用兼容面，
   结果写入现有诊断导出——因为代码里大量 `catch (RuntimeException)` 会让方法缺失表现为"功能悄悄消失"，这是现场最难查的一类问题。

## 需要真机才能定案的项（静态无法证明）

- Lockdown TLS 在安卓 6 上能否建起来（PKIX、以及系统 OpenSSL 无 TLS 1.3；若 iPhone 侧要求 1.3 则软件无解）。
- `bulkTransfer` 在安卓 6 usbfs 上对 64 KB（`USBMUX_READ_CHUNK_BYTES`）/ 32 KB（`READ_CHUNK_BYTES`）单次读是否有 16 KB 级限制
  （这是老安卓 USB 开发里的常见坑，业界普遍做法是把单次传输压到 16 KB 以内；我无法离线证实，但它是我改动顺序里的第一位）。
- 车机 H.264 硬解在 6 年前廉价 SoC 上能否跟住 CarPlay 的帧率（仓库已有 decoder probe，跑一次就有结论）。
- Siri 上行录音（RECORD_AUDIO，Android 6 起是运行时危险权限）在无线之外的有线路径上是否已完整申请。

## 验证矩阵（要"确保完美运行"必须跑完这张表）

| 项 | 通过标准 |
| --- | --- |
| 重新构建（含 15:29 之后的修复）+ 跑 dex 门禁 | 63 条高于 API 23 的调用逐条有门控/不可达说明 |
| 真机冷启动到 CarPlay 首帧 | 无 crash、无 `LinkageError` 日志 |
| 连续播放 30 分钟 | 音视频无 >200 ms 停顿，audio underrun 计数可解释（安卓 6 上恒为 0） |
| 息屏 / 后台 10 分钟后回前台 | 会话不断；Doze 行为已知 |
| 拔线/锁屏/接电话 | 断开检测正确（当前依赖 keepalive 写路径） |
| 中控地图卡片、HUD、无线 | 明确"不可用"提示，而不是静默失败 |
