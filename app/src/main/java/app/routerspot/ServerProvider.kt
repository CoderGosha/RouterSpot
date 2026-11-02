package app.routerspot

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Точка входа для служебного процесса: он вызывает call("attach") и передаёт свой binder.
 * Принимаем только от shell и root — остальным приложениям здесь делать нечего.
 */
class ServerProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("uid $uid")
        if (method != METHOD_ATTACH) return null
        val binder = extras?.getBinder(EXTRA_BINDER) ?: return null
        ServerConnection.attach(context!!, binder)
        return Bundle()
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "app.routerspot.server"
        const val METHOD_ATTACH = "attach"
        const val EXTRA_BINDER = "binder"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
    }
}
