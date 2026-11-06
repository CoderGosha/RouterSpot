package app.routerspot.server

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import app.routerspot.IRouterSpotClient
import app.routerspot.IRouterSpotServer
import app.routerspot.ServerProvider
import app.routerspot.core.TetherGuard
import app.routerspot.core.ProxyLink
import app.routerspot.core.VlessLink
import app.routerspot.core.XrayConfig
import hev.htproxy.TProxyService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.lang.reflect.Method
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.function.Supplier
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Служебный процесс с правами shell. Запуск (прототип): scripts/run-server.sh
 *
 * При старте передаёт приложению свой binder ([IRouterSpotServer]) через ServerProvider.
 * Для отладки те же действия доступны командами построчно из stdin:
 *   start <vless://...|https://...> | stop | selftest [url] | hotspot on|off | quit
 *   отладка: up | down | sniff | prefer on|off
 */
object ShellServer {
    private const val SHELL_PKG = "com.android.shell"
    private const val TUN_ADDR = "10.111.0.1/24"
    private const val UPLINK_SETTLE_MS = 2_000L
    private const val TRANSPORT_TEST = 7 // NetworkCapabilities.TRANSPORT_TEST, @SystemApi
    private const val TUNNEL_DNS = "1.1.1.1"
    private const val SELFTEST_URL = "https://ifconfig.me/ip"
    private const val WORK_DIR = "/data/local/tmp/routerspot"
    private const val APP_PKG = "app.routerspot"
    private const val APK_CHECK_MS = 5_000L
    private const val WIFI_SHARING = "wifi_ap_wifi_sharing"

    /** Подробные журналы Xray и tun2socks в server.log: `adb shell touch /data/local/tmp/routerspot/verbose`. */
    private val verbose get() = File(WORK_DIR, "verbose").exists()
    /** Отладка: MTU стека tun2socks из файла mtu (меньше MTU — меньше MSS для клиентов). */
    private val mtuOverride get() = runCatching { File(WORK_DIR, "mtu").readText().trim().toInt() }.getOrNull()
    private const val CONNECTIVITY_SCOPE_GLOBAL = 1
    private const val HEALTH_INTERVAL_MS = 15_000L
    private const val HEALTH_FAILS = 2
    private const val PROXY_CHECK_HOST = "www.gstatic.com"
    private const val PROXY_CHECK_TIMEOUT_MS = 6_000
    private const val MAX_XRAY_RESTARTS = 5
    private const val LOG_LINES = 20
    private val XRAY_TIMESTAMP = Regex("""^\d{4}/\d\d/\d\d \d\d:\d\d:\d\d\.\d+ """) // своё время ставит log()

    private lateinit var main: Handler
    private val token = Binder()
    private var tun: ParcelFileDescriptor? = null
    private var tunName: String? = null
    private var xray: Process? = null
    private var tunnelRunning = false
    @Volatile private var network: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var uplinkCallback: ConnectivityManager.NetworkCallback? = null
    private var uplink: Network? = null
    private var guard: TetherGuard? = null
    private var client: IRouterSpotClient? = null
    private var xrayConfig: String? = null
    private var xrayRestarts = 0
    /** Проходит ли сейчас трафик через VLESS (см. checkTraffic). */
    private var trafficOk = true
    private var trafficFails = 0
    private var hotspotByUs = false
    private var wifiSharingWas: String? = null

    // Для статуса в интерфейсе.
    private var startedAt = 0L
    private var failure: String? = null
    private var hotspotActive = false
    private var clients = 0
    private var pingMs = -1L
    private var endpoint: Pair<String, Int>? = null
    private val recentLog = ArrayDeque<String>()

    /** Раздача VPN включена: сторожа взведены, падение Xray перезапускается. */
    private var vpnActive = false
        set(value) {
            field = value
            runCatching { client?.onVpnStateChanged(value) }
        }

    @JvmStatic
    fun main(args: Array<String>) {
        HiddenApiBypass.addHiddenApiExemptions("L")
        File(WORK_DIR).mkdirs()
        Looper.prepareMainLooper()
        main = Handler(Looper.getMainLooper())
        log("ready uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT}")
        runCatching { grantAppOps() }.onFailure { log("appops: ${it.rootMessage()}") }
        runCatching { startGuard() }.onFailure { log("guard: ${it.rootMessage()}") }
        publishWithRetry()
        if ("--daemon" in args) {
            // Запуск из приложения: управление только через binder, stdin не читаем.
            File(WORK_DIR, "server.pid").writeText(android.os.Process.myPid().toString())
            main.postDelayed(apkWatch, APK_CHECK_MS)
        } else {
            thread(name = "stdin") {
                generateSequence(::readLine).forEach { line ->
                    main.post { runCatching { exec(line.trim()) }.onFailure { log("error: ${it.rootMessage()}") } }
                }
                main.post { shutdown() }
            }
        }
        Looper.loop()
    }

    private fun exec(cmd: String) {
        if (cmd.startsWith("start ")) return start(cmd.removePrefix("start ").trim())
        if (cmd.startsWith("selftest")) return selftest(cmd.removePrefix("selftest").trim().ifEmpty { SELFTEST_URL })
        when (cmd) {
            "" -> Unit
            "stop" -> stop()
            "sniff" -> sniff(tun ?: return log("tun is not up"))
            "up" -> up()
            "down" -> down()
            "prefer on" -> prefer(true)
            "prefer off" -> prefer(false)
            "hotspot on" -> hotspot(true)
            "hotspot off" -> hotspot(false)
            "quit" -> shutdown()
            else -> log("unknown command: $cmd")
        }
    }

    // --- Тестовая сеть ---

    private fun testNetworkManager(): Any {
        val cm = asInterface("android.net.IConnectivityManager", service("connectivity"))
        val binder = cm.javaClass.getMethod("startOrGetTestNetworkService").invoke(cm) as IBinder
        // С Android 14 тестовая сеть живёт в обновляемом модуле Connectivity, и её AIDL-интерфейс
        // может называться иначе (jarjar) и грузиться другим загрузчиком. Поэтому берём тип
        // интерфейса из конструктора TestNetworkManager, а не по имени.
        val ctor = Class.forName("android.net.TestNetworkManager").declaredConstructors
            .first { it.parameterTypes.size == 1 && IInterface::class.java.isAssignableFrom(it.parameterTypes[0]) }
            .apply { isAccessible = true }
        val iface = ctor.parameterTypes[0]
        val itn = Class.forName("${iface.name}\$Stub", true, iface.classLoader)
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        return ctor.newInstance(itn)
    }

    private fun up() {
        if (tun != null) return log("tun already up: $tunName")
        val tnm = testNetworkManager()
        val linkAddress = Class.forName("android.net.LinkAddress")
            .getDeclaredConstructor(String::class.java).newInstance(TUN_ADDR)
        val iface = tnm.javaClass.getMethod("createTunInterface", Collection::class.java)
            .invoke(tnm, listOf(linkAddress))!!
        val name = iface.javaClass.getMethod("getInterfaceName").invoke(iface) as String
        val fd = iface.javaClass.getMethod("getFileDescriptor").invoke(iface) as ParcelFileDescriptor
        tnm.javaClass.getMethod("setupTestNetwork", Class.forName("android.net.LinkProperties"),
            Boolean::class.javaPrimitiveType, IBinder::class.java)
            .invoke(tnm, linkProperties(name, linkAddress), false, token)
        tun = fd
        tunName = name
        log("tun up: $name $TUN_ADDR")
        holdTestNetwork()
    }

    private fun linkProperties(iface: String, address: Any): Any {
        val lpClass = Class.forName("android.net.LinkProperties")
        val lp = lpClass.getDeclaredConstructor().newInstance()
        lpClass.getMethod("setInterfaceName", String::class.java).invoke(lp, iface)
        lpClass.getMethod("addLinkAddress", address.javaClass).invoke(lp, address)
        val prefixClass = Class.forName("android.net.IpPrefix")
        val routeClass = Class.forName("android.net.RouteInfo")
        val defaultRoute = routeClass
            .getDeclaredConstructor(prefixClass, java.net.InetAddress::class.java, String::class.java)
            .newInstance(prefixClass.getDeclaredConstructor(String::class.java).newInstance("0.0.0.0/0"), null, iface)
        lpClass.getMethod("addRoute", routeClass).invoke(lp, defaultRoute)
        // DNS-прокси хотспота шлёт запросы клиентов на этот адрес через выход раздачи, т.е. в туннель.
        lp.javaClass.getMethod("addDnsServer", java.net.InetAddress::class.java)
            .invoke(lp, java.net.InetAddress.getByName(TUNNEL_DNS))
        // IPv6 намеренно не настраиваем: без v6-выхода клиенты хотспота не получают IPv6.
        return lp
    }

    /** Прототип: только логирует пакеты клиентов, пришедшие в TUN. Дальше здесь будет tun2socks → Xray. */
    private fun sniff(fd: ParcelFileDescriptor) = thread(name = "tun-reader", isDaemon = true) {
        // Дескриптор от TestNetworkManager неблокирующий: ждём данных через poll.
        val pollFd = StructPollfd().apply { this.fd = fd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val buf = ByteArray(65535)
        var count = 0L
        runCatching {
            while (true) {
                if (Os.poll(arrayOf(pollFd), -1) <= 0) continue
                val n = Os.read(fd.fileDescriptor, buf, 0, buf.size)
                if (n <= 0) break
                count++
                if (count <= 20 || count % 500 == 0L) log("pkt #$count ${describe(buf, n)}")
            }
        }.onFailure { log("tun reader: ${it.rootMessage()}") }
        log("tun reader stopped after $count packets")
    }

    private fun describe(p: ByteArray, n: Int): String {
        val version = (p[0].toInt() shr 4) and 0xF
        if (version != 4 || n < 20) return "v$version len=$n"
        val proto = when (val pr = p[9].toInt() and 0xFF) { 6 -> "TCP"; 17 -> "UDP"; 1 -> "ICMP"; else -> "proto$pr" }
        fun ip(o: Int) = (o until o + 4).joinToString(".") { (p[it].toInt() and 0xFF).toString() }
        val ihl = (p[0].toInt() and 0xF) * 4
        val port = if ((proto == "TCP" || proto == "UDP") && n >= ihl + 4)
            ":" + (((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)) else ""
        return "$proto ${ip(12)} -> ${ip(16)}$port len=$n"
    }

    private fun down() {
        val fd = tun ?: return log("tun is not up")
        testNetwork()?.let { network ->
            val tnm = testNetworkManager()
            tnm.javaClass.getMethod("teardownTestNetwork", Network::class.java).invoke(tnm, network)
        }
        releaseTestNetwork()
        fd.close()
        tun = null
        log("tun down: $tunName")
        tunName = null
    }

    // --- Цепочка TUN → hev-socks5-tunnel → Xray → VLESS ---

    private fun start(link: String) {
        val proxy = ProxyLink.parse(link)
        // Адрес сервера разрешаем здесь (обычная сеть устройства), чтобы Xray не зависел от DNS.
        val serverIp = java.net.InetAddress.getByName(proxy.host).hostAddress!!
        log("profile: ${proxy.name} ${proxy.host}($serverIp):${proxy.port} ${proxy.tags.joinToString("/")}")
        if (vpnActive && tunnelRunning) return switchProfile(proxy, serverIp)
        // Недоделанная прежняя цепочка держит порт Xray и tun2socks.
        if (xray != null) stop()

        failure = null
        runCatching { startChain(proxy, serverIp) }.onFailure {
            log("start failed, rolling back")
            stop()
            failure = it.rootMessage()
            throw it
        }
        endpoint = serverIp to proxy.port
        startedAt = System.currentTimeMillis()
        vpnActive = true
        watchUplink()
        schedulePing()
        main.postDelayed(healthTask, HEALTH_INTERVAL_MS)
    }

    /**
     * Смена профиля во время раздачи: меняется только Xray, хотспот, TUN и tun2socks остаются.
     * Клиенты не отключаются от Wi-Fi, рвутся лишь открытые соединения; пока Xray перезапускается,
     * трафик стоит в туннеле, прямого выхода нет. Если новый сервер не работает — возвращаем прежний.
     */
    private fun switchProfile(proxy: ProxyLink, serverIp: String) {
        val previous = xrayConfig!!
        runCatching { launchXray(proxy, serverIp) }.onFailure {
            log("switch failed, back to previous profile")
            runCatching { replaceXray(previous) }
            throw it
        }
        log("switched to ${proxy.name}")
        endpoint = serverIp to proxy.port
        pingMs = -1
        trafficOk = true
        trafficFails = 0
    }

    /**
     * Xray с проверкой трафика. Если сервер молчит, а отпечаток TLS не safari, пробуем safari:
     * у новых chrome/firefox/ios в ClientHello постквантовый ключ, пакет ~1,7 КБ, и DPI
     * провайдера такие рукопожатия режет, а короткое safari пропускает.
     */
    private fun launchXray(proxy: ProxyLink, serverIp: String) {
        replaceXray(XrayConfig.build(proxy, serverIp, verbose))
        // Сервер может принимать подключение, но не пропускать данные (блокировка у провайдера).
        if (proxyWorks() || proxyWorks()) return
        val vless = (proxy as? VlessLink)?.takeIf { it.fingerprint.let { fp -> fp != null && fp != VlessLink.SAFARI } }
            ?: error("proxy server passes no traffic")
        log("no traffic with fp=${vless.fingerprint}, trying ${VlessLink.SAFARI}")
        val fallback = vless.withFingerprint(VlessLink.SAFARI)
        replaceXray(XrayConfig.build(fallback, serverIp, verbose))
        check(proxyWorks() || proxyWorks()) { "proxy server passes no traffic" }
    }

    /** Перезапуск Xray с [config] при живых хотспоте, TUN и tun2socks. */
    private fun replaceXray(config: String) {
        val old = xray
        xray = null // смерть старого процесса onXrayExited не примет за падение
        old?.destroy()
        old?.waitFor()
        xrayRestarts = 0
        xrayConfig = config
        startXray(config)
        waitForPort(XrayConfig.SOCKS_PORT)
    }

    private val uplinkTask = Runnable {
        if (!vpnActive) return@Runnable
        log("reconnecting xray after uplink change")
        runCatching { replaceXray(xrayConfig!!) }.onFailure { log("xray restart failed: ${it.rootMessage()}") }
        trafficFails = 0
    }

    /**
     * Основная сеть телефона (Wi-Fi ↔ мобильная). При её смене соединения Xray с сервером
     * висят на пропавшей сети, а naive гонит всё через одно HTTP/2-соединение — без
     * перезапуска раздача стоит до таймаутов. Ждём, пока сеть устоится, и перезапускаем Xray.
     */
    private fun watchUplink() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                val caps = runCatching { connectivity().getNetworkCapabilities(n) }.getOrNull()
                if (caps?.hasTransport(TRANSPORT_TEST) == true) return
                val previous = uplink
                uplink = n
                if (previous == null || previous == n) return
                log("uplink changed: $previous -> $n")
                main.removeCallbacks(uplinkTask)
                main.postDelayed(uplinkTask, UPLINK_SETTLE_MS)
            }
        }
        connectivity().registerDefaultNetworkCallback(callback, main)
        uplinkCallback = callback
    }

    private fun unwatchUplink() {
        uplinkCallback?.let { runCatching { connectivity().unregisterNetworkCallback(it) } }
        uplinkCallback = null
        uplink = null
        main.removeCallbacks(uplinkTask)
    }

    private val healthTask = Runnable { checkTraffic() }

    /**
     * Честный статус: раз в 15 с реальный HTTP-запрос через туннель. Провайдер может пропускать
     * подключение к серверу и молча отбрасывать данные — тогда «раздача активна» была бы неправдой.
     */
    private fun checkTraffic() {
        thread(name = "health", isDaemon = true) {
            val ok = proxyWorks()
            main.post {
                if (!vpnActive) return@post
                if (ok) {
                    if (!trafficOk) log("traffic through proxy is back")
                    trafficFails = 0
                    trafficOk = true
                } else if (++trafficFails >= HEALTH_FAILS && trafficOk) {
                    log("no traffic through proxy for ${trafficFails} checks")
                    trafficOk = false
                }
                main.postDelayed(healthTask, HEALTH_INTERVAL_MS)
            }
        }
    }

    private val pingTask = Runnable { schedulePing() }

    /** Пинг до VLESS-сервера раз в 10 с: время TCP-подключения по обычной сети телефона. */
    private fun schedulePing() {
        val (host, port) = endpoint ?: return
        thread(name = "ping", isDaemon = true) {
            val started = System.nanoTime()
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 3000) } }.isSuccess
            val ms = if (ok) (System.nanoTime() - started) / 1_000_000 else -1
            main.post {
                if (!vpnActive) return@post
                pingMs = ms
                main.postDelayed(pingTask, 10_000)
            }
        }
    }

    private fun startChain(proxy: ProxyLink, serverIp: String) {
        up()
        val fd = tun ?: return
        File(WORK_DIR).mkdirs()
        launchXray(proxy, serverIp)

        val tunnelConfig = File(WORK_DIR, "tunnel.yml").apply { writeText(XrayConfig.tunnel(mtuOverride ?: 1500, verbose)) }
        // Библиотека грузится через java.library.path, который задаёт запуск служебного процесса.
        check(TProxyService.TProxyStartService(tunnelConfig.path, fd.fd)) { "tun2socks failed to start" }
        tunnelRunning = true
        log("tun2socks running on $tunName")
        prefer(true)
    }

    /** HTTP-запрос через SOCKS5 Xray: 204 от generate_204 значит, что VLESS-туннель живой. */
    private fun proxyWorks(): Boolean = runCatching {
        Socket().use { socket ->
            socket.soTimeout = PROXY_CHECK_TIMEOUT_MS
            socket.connect(InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT), PROXY_CHECK_TIMEOUT_MS)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            check(input.read() == 5 && input.read() == 0)
            val host = PROXY_CHECK_HOST.toByteArray()
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80))
            val reply = ByteArray(10)
            var read = 0
            while (read < reply.size) read += input.read(reply, read, reply.size - read).also { check(it > 0) }
            check(reply[1] == 0.toByte())
            out.write("GET /generate_204 HTTP/1.1\r\nHost: $PROXY_CHECK_HOST\r\nConnection: close\r\n\r\n".toByteArray())
            input.bufferedReader().readLine().orEmpty().contains(" 204")
        }
    }.getOrDefault(false)

    private fun startXray(config: String) {
        // Из каталога приложения shell исполнять не может, копируем в свой рабочий каталог.
        val binary = File(WORK_DIR, "xray")
        nativeLib("libxray.so").copyTo(binary, overwrite = true)
        binary.setExecutable(true)
        val configFile = File(WORK_DIR, "xray.json").apply { writeText(config) }
        val process = ProcessBuilder(binary.path, "run", "-c", configFile.path)
            .directory(File(WORK_DIR))
            .redirectErrorStream(true)
            .start()
        xray = process
        thread(name = "xray-log", isDaemon = true) {
            // При остановке поток прерывается посреди чтения — это штатный выход.
            runCatching { process.inputStream.bufferedReader().forEachLine { log("[xray] ${it.replace(XRAY_TIMESTAMP, "")}") } }
            log("xray exited: ${runCatching { process.waitFor() }.getOrDefault(-1)}")
            main.post { onXrayExited(process) }
        }
    }

    /**
     * Неожиданное падение Xray: туннель остаётся выходом раздачи, поэтому трафик клиентов
     * просто не уходит никуда, пока Xray перезапускается. Прямого выхода при этом нет.
     */
    private fun onXrayExited(process: Process) {
        if (xray !== process || !vpnActive) return
        if (++xrayRestarts > MAX_XRAY_RESTARTS) {
            log("xray keeps crashing, stopping hotspot")
            guard?.stopSharing()
            stop()
            failure = "Xray crashed $MAX_XRAY_RESTARTS times in a row"
            return
        }
        val delayMs = 1000L shl (xrayRestarts - 1)
        log("restarting xray in ${delayMs}ms (attempt $xrayRestarts)")
        main.postDelayed({
            if (vpnActive && xray === process) runCatching { startXray(xrayConfig!!) }
                .onFailure { log("xray restart failed: ${it.rootMessage()}") }
        }, delayMs)
    }

    private fun waitForPort(port: Int) {
        repeat(50) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return log("xray socks on :$port")
            check(xray?.isAlive == true) { "xray exited" }
            Thread.sleep(100)
        }
        error("xray socks port $port did not open")
    }

    /** Нативные библиотеки распакованы рядом с APK: <apk dir>/lib/<abi>/. */
    private fun nativeLib(name: String): File =
        File(currentApk().parentFile, "lib/$libAbi/$name").also { check(it.exists()) { "missing $it" } }

    /** Каталог библиотек внутри установленного APK (useLegacyPackaging: они распакованы). */
    private val libAbi = when (android.os.Build.SUPPORTED_ABIS.first()) {
        "arm64-v8a" -> "arm64"
        else -> android.os.Build.SUPPORTED_ABIS.first()
    }

    /** APK, из которого запущена служба, а если приложение с тех пор обновили — новый. */
    private fun currentApk(): File {
        val own = File(System.getenv("CLASSPATH") ?: error("CLASSPATH not set"))
        if (own.exists()) return own
        val output = ProcessBuilder("pm", "path", APP_PKG).start().inputStream.bufferedReader().readText()
        return output.lineSequence().map { it.trim().removePrefix("package:") }
            .firstOrNull { it.endsWith("/base.apk") }?.let(::File) ?: error("$APP_PKG is not installed")
    }

    /**
     * После обновления приложения старый APK удаляется. Как только раздача не идёт, служба
     * перезапускается из нового — права shell на это хватает, отладка по Wi-Fi не нужна.
     */
    private val apkWatch = object : Runnable {
        override fun run() {
            val own = System.getenv("CLASSPATH")?.let(::File)
            if (own != null && !own.exists() && !vpnActive) {
                runCatching { relaunchFromNewApk() }.onSuccess { return }.onFailure { log("self-update: ${it.rootMessage()}") }
            }
            main.postDelayed(this, APK_CHECK_MS)
        }
    }

    private fun relaunchFromNewApk() {
        val apk = currentApk()
        log("app updated, restarting from $apk")
        // Новый экземпляр стартует через секунду, когда этот уже завершится: иначе приложение
        // успело бы подключиться к новому, а смерть старого приняло бы за его падение.
        val cmd = "(sleep 1; CLASSPATH=$apk exec app_process -Djava.library.path=${apk.parentFile}/lib/$libAbi /system/bin " +
            "app.routerspot.server.ShellServer --daemon) > $WORK_DIR/server.log 2>&1 < /dev/null &"
        ProcessBuilder("setsid", "sh", "-c", cmd).start().waitFor()
        shutdown()
    }

    private fun stop() {
        // Порядок важен: сначала разоружаем сторожей и снимаем выбор тестовой сети, затем туннель.
        vpnActive = false
        unwatchUplink()
        pingMs = -1
        main.removeCallbacks(pingTask)
        main.removeCallbacks(healthTask)
        trafficOk = true
        trafficFails = 0
        if (hotspotByUs) runCatching { hotspot(false) }
        runCatching { prefer(false) }
        if (tunnelRunning) TProxyService.TProxyStopService()
        tunnelRunning = false
        xray?.destroy()
        xray = null
        down()
    }

    /** Запрос через тестовую сеть — тот же путь, что у клиентов хотспота. */
    private fun selftest(url: String) {
        thread(name = "selftest", isDaemon = true) {
            runCatching { log("selftest $url -> ${fetchViaTunnel(url)}") }
                .onFailure { log("selftest failed: ${it.rootMessage()}") }
        }
    }

    /** HTTP-запрос через тестовую сеть; ждёт её появления до 5 с. Не на главном потоке. */
    private fun fetchViaTunnel(url: String): String {
        val deadline = System.currentTimeMillis() + 5000
        var network = testNetwork()
        while (network == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            network = testNetwork()
        }
        val conn = (network ?: error("test network not available")).openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
        return conn.inputStream.bufferedReader().readText().take(200).trim()
    }

    /** Скачивание через тестовую сеть с замером: «байты мс». */
    private fun downloadViaTunnel(url: String): String {
        val network = testNetwork() ?: error("test network not available")
        val started = System.nanoTime()
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val bytes = conn.inputStream.use { input ->
            val buf = ByteArray(65536)
            var total = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; total += n }
            total
        }
        return "$bytes ${(System.nanoTime() - started) / 1_000_000}"
    }

    /**
     * Диагностика для экрана приложения: доступность скрытых API, Xray, tun2socks и, если
     * передан профиль, сквозная проверка туннеля. Без раздачи туннель поднимается временно
     * (хотспот не трогаем) и снимается. Вызывается с binder-потока, может идти ~15 с.
     */
    private fun diagnose(link: String?): JSONObject {
        val result = JSONObject()
        fun probe(name: String, block: () -> String) {
            result.put(name, runCatching(block).fold({ JSONObject().put("ok", true).put("detail", it) },
                { JSONObject().put("ok", false).put("detail", it.rootMessage()) }))
        }
        probe("shell") {
            val uid = android.os.Process.myUid()
            check(uid == 2000) { "uid $uid" }
            "uid $uid · Android ${android.os.Build.VERSION.RELEASE}"
        }
        probe("testNetwork") {
            val tnm = testNetworkManager()
            tnm.javaClass.getMethod("createTunInterface", Collection::class.java)
            "TestNetworkManager"
        }
        probe("tethering") {
            val connector = tethering()
            listOf("setPreferTestNetworks", "startTethering", "stopTethering").forEach { name ->
                connector.javaClass.methods.firstOrNull { it.name == name } ?: error("$name missing")
            }
            "ITetheringConnector"
        }
        probe("softAp") { softApConfig().first ?: error("no SSID") }
        probe("xray") {
            val binary = File(WORK_DIR, "xray")
            nativeLib("libxray.so").copyTo(binary, overwrite = true)
            binary.setExecutable(true)
            val process = ProcessBuilder(binary.path, "version").redirectErrorStream(true).start()
            val line = process.inputStream.bufferedReader().readLine().orEmpty()
            check(process.waitFor() == 0) { line.ifEmpty { "exit ${process.exitValue()}" } }
            line.substringBefore(" (").ifEmpty { "ok" }
        }
        probe("tun2socks") {
            TProxyService.TProxyIsRunning() // загрузка библиотеки и регистрация JNI
            "hev-socks5-tunnel"
        }
        if (link != null) probe("tunnel") {
            val wasActive = onMain { vpnActive }
            if (!wasActive) onMain { start(link) }
            try {
                fetchViaTunnel(SELFTEST_URL)
            } finally {
                if (!wasActive) onMain { stop() }
            }
        }
        return result
    }

    /**
     * Без активного запроса система сносит тестовую сеть через несколько секунд (linger).
     * Держим её своим запросом; он же отдаёт объект Network для самопроверки.
     */
    private fun holdTestNetwork() {
        val request = NetworkRequest.Builder()
            .apply { javaClass.getMethod("clearCapabilities").invoke(this) }
            .addTransportType(TRANSPORT_TEST)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                log("test network available: $n")
            }

            override fun onLost(n: Network) {
                if (network == n) network = null
                log("test network lost: $n")
            }
        }
        connectivity().requestNetwork(request, callback, main)
        networkCallback = callback
    }

    private fun releaseTestNetwork() {
        networkCallback?.let { runCatching { connectivity().unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun testNetwork(): Network? = network

    /** ConnectivityManager поверх системного binder; контекст нужен ему только ради имени пакета. */
    private val connectivity: ConnectivityManager by lazy {
        val iface = Class.forName("android.net.IConnectivityManager")
        ConnectivityManager::class.java.getDeclaredConstructor(Context::class.java, iface)
            .newInstance(ShellContext, asInterface("android.net.IConnectivityManager", service("connectivity")))
    }

    private fun connectivity() = connectivity

    private object ShellContext : ContextWrapper(null) {
        override fun getPackageName() = SHELL_PKG
        override fun getOpPackageName() = SHELL_PKG
        override fun getAttributionTag(): String? = null
        override fun getApplicationContext() = this
    }

    // --- Раздача ---

    private fun tethering(): Any = asInterface("android.net.ITetheringConnector", service("tethering"))

    private fun prefer(on: Boolean) {
        val connector = tethering()
        call(connector, "setPreferTestNetworks", listOf(on), listener("prefer $on"))
    }

    private fun hotspot(on: Boolean) {
        hotspotByUs = on
        val connector = tethering()
        if (on) {
            keepWifiWithHotspot()
            // Класс запроса берём из сигнатуры метода: в модуле Tethering он может быть переименован.
            val requestClass = connector.javaClass.methods.first { it.name == "startTethering" }.parameterTypes
                .first { it.name.endsWith("TetheringRequestParcel") }
            val request = requestClass.getDeclaredConstructor().newInstance()
            requestClass.getField("tetheringType").setInt(request, 0) // TETHERING_WIFI
            // exemptFromEntitlementCheck=true требует TETHER_PRIVILEGED, которого у shell нет;
            // без него проверка идёт через WRITE_SETTINGS.
            requestClass.getField("exemptFromEntitlementCheck").setBoolean(request, false)
            requestClass.getField("showProvisioningUi").setBoolean(request, false)
            // Android 16 выбирает режим раздачи по connectivityScope; со значением по умолчанию (UNKNOWN)
            // Tethering поднимает точку доступа, но не обслуживает её: ни адресов, ни DHCP, ни NAT.
            runCatching { requestClass.getField("connectivityScope").setInt(request, CONNECTIVITY_SCOPE_GLOBAL) }
            call(connector, "startTethering", listOf(request), listener("hotspot on"))
        } else {
            call(connector, "stopTethering", listOf(0), listener("hotspot off"))
            restoreWifiSharing()
        }
    }

    /**
     * Samsung «Общий доступ к Wi-Fi»: без него хотспот отключает Wi-Fi, вместе с ним выключается
     * отладка по Wi-Fi, Samsung перезапускает adbd, а init убивает всю его cgroup — и эту службу.
     * Включаем перед раздачей, если параметр есть на телефоне, и возвращаем прежнее значение после.
     */
    private fun keepWifiWithHotspot() {
        val current = settings("get", WIFI_SHARING).takeIf { it == "0" || it == "1" } ?: return
        if (current == "1") return
        settings("put", WIFI_SHARING, "1")
        wifiSharingWas = current
        log("wifi sharing on (was $current)")
    }

    private fun restoreWifiSharing() {
        val was = wifiSharingWas ?: return
        settings("put", WIFI_SHARING, was)
        wifiSharingWas = null
        log("wifi sharing restored to $was")
    }

    private fun settings(vararg args: String): String =
        ProcessBuilder(listOf("settings", args[0], "secure") + args.drop(1)).start()
            .inputStream.bufferedReader().readText().trim()

    // --- Связь с приложением и сторож ---

    /** Даёт приложению право выключить хотспот самому, если служебный процесс погибнет. */
    private fun grantAppOps() {
        val code = ProcessBuilder("appops", "set", APP_PKG, "WRITE_SETTINGS", "allow").start().waitFor()
        log("appops WRITE_SETTINGS -> $code")
    }

    private fun startGuard() {
        val tm = Class.forName("android.net.TetheringManager")
            .getDeclaredConstructor(Context::class.java, Supplier::class.java)
            .newInstance(ShellContext, Supplier<IBinder> { service("tethering") })
        guard = TetherGuard(tm, connectivity, { main.post(it) }, ::log, armed = { vpnActive }) { active, count ->
            hotspotActive = active
            clients = count
        }.also { it.register() }
    }

    /** Передаёт binder в ServerProvider приложения: IActivityManager → IContentProvider.call. */
    private fun publishToApp() {
        val am = asInterface("android.app.IActivityManager", service("activity"))
        val providerToken = Binder()
        val holder = am.javaClass.methods.first { it.name == "getContentProviderExternal" }
            .invoke(am, ServerProvider.AUTHORITY, 0, providerToken, null)
            ?: error("provider ${ServerProvider.AUTHORITY} not found (app not installed?)")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)!!
            val extras = Bundle().apply { putBinder(ServerProvider.EXTRA_BINDER, binder) }
            val attribution = AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PKG).build()
            provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size == 5 }
                .invoke(provider, attribution, ServerProvider.AUTHORITY, ServerProvider.METHOD_ATTACH, null, extras)
            log("published to app")
        } finally {
            am.javaClass.methods.first { it.name == "removeContentProviderExternalAsUser" }
                .invoke(am, ServerProvider.AUTHORITY, providerToken, 0)
        }
    }

    private var publishAttempt = 0

    /** Передаёт binder приложению, повторяя с паузой, пока оно не примет (до ~минуты). */
    private fun publishWithRetry() {
        val ok = runCatching { publishToApp() }.onFailure { log("publish to app failed: ${it.rootMessage()}") }.isSuccess
        if (ok) {
            publishAttempt = 0
            return
        }
        if (++publishAttempt > 8) return
        main.postDelayed(::publishWithRetry, 1000L * publishAttempt)
    }

    /** Binder-вызовы приходят в пуле потоков; всё состояние живёт на главном потоке. */
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        main.post(task)
        return try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun errorOf(block: () -> Unit): String? =
        runCatching { onMain(block) }.exceptionOrNull()?.rootMessage()

    private val binder = object : IRouterSpotServer.Stub() {
        override fun start(vlessLink: String): String? = errorOf { this@ShellServer.start(vlessLink) }
        override fun stop() = onMain { this@ShellServer.stop() }
        override fun setHotspot(on: Boolean): String? = errorOf { hotspot(on) }
        override fun status(): String = onMain { statusJson().toString() }
        override fun diagnose(vlessLink: String?): String = this@ShellServer.diagnose(vlessLink).toString()
        override fun speedtest(url: String): String = runCatching { downloadViaTunnel(url) }.getOrElse { it.rootMessage() }

        override fun attachClient(c: IRouterSpotClient) {
            // Асинхронно: вызов приходит, пока главный поток ждёт ответа провайдера в publishToApp.
            main.post {
                client = c
                c.asBinder().linkToDeath({
                    main.post {
                        if (client !== c) return@post
                        client = null
                        log("app died")
                        // Приложение перезапустится (обновление, система выгрузила процесс) — снова
                        // отдаём ему binder, чтобы вернуть интерфейс и второго сторожа.
                        publishWithRetry()
                    }
                }, 0)
                runCatching { c.onVpnStateChanged(vpnActive) }
            }
        }
    }

    private fun statusJson(): JSONObject {
        val state = when {
            vpnActive && xray?.isAlive != true -> "reconnecting"
            vpnActive -> "running"
            failure != null -> "failed"
            else -> "idle"
        }
        // hev: tx — записано в TUN (к клиентам), rx — прочитано из TUN (от клиентов).
        val stats = if (tunnelRunning) runCatching { TProxyService.TProxyGetStats() }.getOrNull() else null
        val softAp = runCatching { softApConfig() }.getOrNull()
        return JSONObject()
            .put("state", state)
            .put("startedAt", startedAt)
            .put("downBytes", stats?.getOrNull(1) ?: 0)
            .put("upBytes", stats?.getOrNull(3) ?: 0)
            .put("pingMs", pingMs)
            .put("traffic", trafficOk)
            .put("hotspot", hotspotActive)
            .put("clients", clients)
            .put("ssid", softAp?.first)
            .put("passphrase", softAp?.second)
            .put("failure", failure)
            .put("log", JSONArray(synchronized(this) { recentLog.toList() }))
            .put("apk", System.getenv("CLASSPATH"))
    }

    private fun softApConfig(): Pair<String?, String?> {
        val wifi = asInterface("android.net.wifi.IWifiManager", service("wifi"))
        val config = wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi)!!
        val ssid = config.javaClass.getMethod("getSsid").invoke(config) as String?
        val pass = config.javaClass.getMethod("getPassphrase").invoke(config) as String?
        return ssid to pass
    }

    private fun shutdown() {
        runCatching { stop() }
        log("bye")
        main.postDelayed({ exitProcess(0) }, 300)
    }

    // --- Рефлексия и binder ---

    private fun service(name: String): IBinder =
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder ?: error("service $name not found")

    private fun asInterface(iface: String, binder: IBinder): Any =
        Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

    /**
     * Вызывает AIDL-метод по имени, подставляя аргументы по типу параметров:
     * значения из [values] по порядку, строки — пакет shell и null-атрибуция, слушатель — [listener].
     * Так переживаем различия сигнатур между версиями Android.
     */
    private fun call(target: Any, name: String, values: List<Any>, listener: Any) {
        val method: Method = target.javaClass.methods.firstOrNull { it.name == name }
            ?: error("$name not found in ${target.javaClass.name}")
        val queue = ArrayDeque(values)
        var strings = 0
        val args = method.parameterTypes.map { type ->
            when {
                type.name.endsWith("IIntResultListener") -> Class.forName("${type.name}\$Stub", true, type.classLoader)
                    .getMethod("asInterface", IBinder::class.java).invoke(null, listener)
                type == String::class.java -> if (strings++ == 0) SHELL_PKG else null
                else -> queue.removeFirstOrNull()
            }
        }
        log("call $name(${method.parameterTypes.joinToString { it.simpleName }})")
        method.invoke(target, *args.toTypedArray())
    }

    private fun listener(tag: String): IBinder = ResultBinder(tag)

    private class ResultBinder(private val tag: String) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("android.net.IIntResultListener")
            val result = data.readInt()
            log("$tag -> ${if (result == 0) "OK" else "error $result"}")
            return true
        }
    }

    private fun Throwable.rootMessage(): String {
        var t: Throwable = this
        while (t.cause != null) t = t.cause!!
        return "${t.javaClass.simpleName}: ${t.message}"
    }

    @Synchronized
    private fun log(msg: String) {
        println("[rs] $msg")
        System.out.flush()
        recentLog.addLast("${TIME.format(java.util.Date())}  ${msg.removePrefix("[xray] ")}")
        while (recentLog.size > LOG_LINES) recentLog.removeFirst()
    }

    private val TIME = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
}
