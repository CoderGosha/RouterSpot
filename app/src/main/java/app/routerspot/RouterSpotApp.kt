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
        AppController.init(this)
    }
}
