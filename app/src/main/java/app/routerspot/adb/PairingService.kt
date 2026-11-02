package app.routerspot.adb

import app.routerspot.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import app.routerspot.AppController
import app.routerspot.MainActivity
import kotlin.concurrent.thread

/**
 * Сопряжение с беспроводной отладкой на этом же телефоне. Диалог с кодом в настройках
 * закрывается, если уйти из них, поэтому код вводится прямо в уведомлении (RemoteInput).
 *
 * Служба следит за mDNS: как только в настройках открыли «Подключить по коду», всплывает
 * уведомление с полем для кода. После сопряжения сразу запускается служебный процесс.
 */
class PairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var stopWatch: (() -> Unit)? = null
    @Volatile private var endpoint: AdbDiscovery.Endpoint? = null
    @Volatile private var pairing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (stopWatch == null) {
            stopWatch = AdbDiscovery.watch(this, AdbDiscovery.PAIRING, ::onDialogOpened, ::onDialogClosed)
            main.postDelayed(::stopSelf, LIFETIME_MS)
        }
        val code = intent?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_CODE) }
            ?: intent?.getStringExtra(KEY_CODE)
        if (code != null && !pairing) {
            pairing = true
            thread(name = "adb-pair") { pairAndLaunch(code.toString().filter(Char::isDigit)) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWatch?.invoke()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun onDialogOpened(found: AdbDiscovery.Endpoint) {
        if (found == endpoint) return
        Log.i(TAG, "pairing dialog open at ${found.host}:${found.port}")
        endpoint = found
        if (!pairing) notify(codeNotification(null))
    }

    private fun onDialogClosed() {
        endpoint = null
        if (!pairing) notify(statusNotification())
    }

    private fun pairAndLaunch(code: String) {
        val result = runCatching {
            notify(progressNotification(getString(R.string.pair_searching)))
            val target = endpoint ?: AdbDiscovery.find(this, AdbDiscovery.PAIRING, FIND_TIMEOUT_MS)
                ?: error(getString(R.string.pair_no_dialog))
            notify(progressNotification(getString(R.string.busy_pairing)))
            check(AdbManager.get(this).pair(target.host, target.port, code)) { getString(R.string.err_wrong_code) }
            AppController.onPaired()
            notify(progressNotification(getString(R.string.busy_launching)))
            ServerLauncher.launch(this)
        }
        pairing = false
        result.onSuccess {
            Log.i(TAG, "paired, server: $it")
            stopForeground(STOP_FOREGROUND_REMOVE)
            notify(doneNotification())
            stopSelf()
        }.onFailure {
            Log.w(TAG, "pairing failed", it)
            notify(codeNotification(it.message ?: it.toString()))
        }
    }

    /** Одно уведомление на всё: два система сворачивает в группу и кнопка ввода прячется. */
    private fun notify(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

    /** Тихое состояние уведомления: ждём, пока в настройках откроют диалог. */
    private fun statusNotification(): Notification =
        builder(STATUS_CHANNEL)
            .setContentTitle(getString(R.string.pair_title))
            .setContentText(getString(R.string.pair_hint))
            .setContentIntent(PendingIntent.getActivity(this, 1, settingsIntent(), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()

    /** Всплывающее состояние с полем для кода; [error] — почему прошлая попытка не удалась. */
    private fun codeNotification(error: String?): Notification {
        val reply = PendingIntent.getForegroundService(
            this, 0, Intent(this, PairingService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.pair_code)).build()
        val action = Notification.Action.Builder(null, getString(R.string.pair_enter_code), reply).addRemoteInput(input).build()
        return builder(CODE_CHANNEL)
            .setContentTitle(if (error != null) getString(R.string.pair_failed) else getString(R.string.pair_dialog_open))
            .setContentText(error ?: getString(R.string.pair_dialog_open_hint))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun progressNotification(text: String) =
        builder(CODE_CHANNEL).setContentTitle(getString(R.string.pair_title)).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).build()

    private fun doneNotification() =
        builder(CODE_CHANNEL).setContentTitle(getString(R.string.pair_done)).setContentText(getString(R.string.pair_done_hint))
            .setContentIntent(PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).setAutoCancel(true).setTimeoutAfter(DONE_TIMEOUT_MS).build()

    private fun builder(channel: String): Notification.Builder {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CODE_CHANNEL, getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, getString(R.string.channel_pairing_status), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
    }

    companion object {
        private const val TAG = "RouterSpot"
        private const val CODE_CHANNEL = "pairing"
        private const val STATUS_CHANNEL = "pairing_status"
        private const val NOTIFICATION_ID = 1
        private const val FIND_TIMEOUT_MS = 5_000L
        private const val DONE_TIMEOUT_MS = 15_000L
        private const val LIFETIME_MS = 10 * 60_000L
        const val KEY_CODE = "code"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PairingService::class.java))
        }

        /** Настройки разработчика с подсветкой пункта «Отладка по Wi-Fi» (где прошивка это поддерживает). */
        fun settingsIntent(): Intent {
            val key = "toggle_adb_wireless"
            return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", key)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
