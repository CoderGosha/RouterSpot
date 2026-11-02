package app.routerspot.adb

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/**
 * Запускает служебный процесс через встроенный ADB. Процесс отвязывается от ADB-сессии
 * (setsid, nohup) и живёт дальше сам; после запуска он сам передаёт binder приложению.
 */
object ServerLauncher {
    const val WORK_DIR = "/data/local/tmp/routerspot"
    private const val DISCOVERY_TIMEOUT_MS = 5_000L
    private const val PREFS = "adb"
    private const val KEY_PORT = "connect_port"

    /** Подключается к adbd этого телефона и запускает служебный процесс. Блокирующий вызов. */
    fun launch(context: Context): String {
        val adb = AdbManager.get(context)
        if (!adb.isConnected) connect(context, adb)
        val output = StringBuffer()
        val done = CountDownLatch(1)
        adb.openStream("shell:${command(context)}").use { stream ->
            // Когда команда завершается, adbd закрывает поток, и libadb сообщает это
            // исключением IOException("Stream closed") вместо обычного конца данных.
            // Бывает, что поток не закрывается вовсе (соединение после переключения Wi-Fi),
            // поэтому ждём ответа не дольше OUTPUT_TIMEOUT_S, а «launched» считаем успехом сразу.
            thread(name = "adb-launch-output", isDaemon = true) {
                runCatching {
                    stream.openInputStream().bufferedReader().forEachLine {
                        output.appendLine(it)
                        if (it.trim() == "launched") done.countDown()
                    }
                }
                done.countDown()
            }
            done.await(OUTPUT_TIMEOUT_S, TimeUnit.SECONDS)
        }
        val result = output.toString().trim()
        check(result == "launched") { result.ifEmpty { "no output from adb shell" } }
        return result
    }

    /**
     * Порт adbd меняется при каждом включении беспроводной отладки. Сначала пробуем последний
     * удачный (mDNS бывает медленным), затем ищем заново.
     */
    private fun connect(context: Context, adb: AdbManager) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getInt(KEY_PORT, 0)
        val candidates = sequence {
            if (cached > 0) yield(cached)
            AdbDiscovery.find(context, AdbDiscovery.CONNECT, DISCOVERY_TIMEOUT_MS)?.let { yield(it.port) }
            // mDNS-объявление adbd иногда пропадает; тогда ищем порт среди открытых локальных.
            yieldAll(openLocalPorts())
        }
        val port = candidates.distinct().firstOrNull { port ->
            runCatching { adb.connect(LOCALHOST, port) }.getOrDefault(false)
        } ?: error("wireless debugging is off or this app is not paired")
        prefs.edit().putInt(KEY_PORT, port).apply()
    }

    /** Открытые TCP-порты на localhost в эфемерном диапазоне Linux, где adbd выбирает порт. */
    private fun openLocalPorts(): List<Int> {
        val pool = Executors.newFixedThreadPool(32)
        try {
            return (32768..60999).map { port ->
                pool.submit<Int?> {
                    runCatching { Socket().use { it.connect(InetSocketAddress(LOCALHOST, port), 200) } }
                        .map { port }.getOrNull()
                }
            }.mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private const val LOCALHOST = "127.0.0.1"
    private const val OUTPUT_TIMEOUT_S = 15L

    private fun command(context: Context): String {
        val info = context.applicationInfo
        // Предыдущий экземпляр (после обновления приложения или повторного запуска) завершаем.
        // Сессию ADB не закрываем, пока служба не напишет «ready»: иначе adbd может завершить
        // группу процессов раньше, чем setsid создаст новую сессию.
        // Только ASCII: libadb считает размер буфера команды в символах, а не в байтах.
        return """
            mkdir -p $WORK_DIR
            [ -f $WORK_DIR/server.pid ] && kill $(cat $WORK_DIR/server.pid) 2>/dev/null
            CLASSPATH=${info.sourceDir} nohup setsid app_process \
                -Djava.library.path=${info.nativeLibraryDir} /system/bin \
                app.routerspot.server.ShellServer --daemon \
                > $WORK_DIR/server.log 2>&1 < /dev/null &
            for i in $(seq 1 50); do grep -q ready $WORK_DIR/server.log 2>/dev/null && break; sleep 0.1; done
            grep -q ready $WORK_DIR/server.log && echo launched || echo "not started: $(cat $WORK_DIR/server.log)"
        """.trimIndent()
    }
}
