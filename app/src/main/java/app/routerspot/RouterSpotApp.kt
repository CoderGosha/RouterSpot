package app.routerspot

import android.app.Application
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass

class RouterSpotApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Сторож приложения работает с TetheringManager (@SystemApi) через рефлексию.
        HiddenApiBypass.addHiddenApiExemptions("L")
    }

    override fun onCreate() {
        super.onCreate()
        // В процессе сторожа USB (:guard) интерфейса и опроса службы нет.
        if (getProcessName().endsWith(GUARD_PROCESS)) return
        AppController.init(this)
    }

    private companion object {
        const val GUARD_PROCESS = ":guard"
    }
}
