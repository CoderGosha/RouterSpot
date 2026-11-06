package app.routerspot.server

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/**
 * Даёт процессу приложения права shell: их можно делегировать только инструментации, запущенной
 * из shell. Запускается --no-restart, поэтому работает прямо в процессе интерфейса; пока она активна,
 * права действуют, поэтому не завершается, пока не попросят ([finishHost]).
 */
class UsbInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        current = this
        start()
    }

    override fun onStart() {
        var waited = 0L
        while (targetContext.checkSelfPermission(SHELL_ONLY_PERMISSION) != 0 && waited < WAIT_MS) {
            Thread.sleep(STEP_MS)
            waited += STEP_MS
        }
        if (targetContext.checkSelfPermission(SHELL_ONLY_PERMISSION) != 0) {
            Log.w(TAG, "shell permissions were not delegated")
            return finishHost()
        }
        ShellServer.runInApp(targetContext)
    }

    companion object {
        private const val TAG = "RouterSpot"
        private const val SHELL_ONLY_PERMISSION = "android.permission.MANAGE_TEST_NETWORKS"
        private const val WAIT_MS = 15_000L
        private const val STEP_MS = 200L
        private var current: UsbInstrumentation? = null

        fun finishHost() {
            current?.finish(0, Bundle())
            current = null
        }
    }
}
