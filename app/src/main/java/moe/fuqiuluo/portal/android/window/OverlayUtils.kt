package moe.fuqiuluo.portal.android.window

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * 悬浮窗权限工具类
 * 
 * 提供检查悬浮窗权限的功能，兼容不同Android版本
 */
object OverlayUtils {
    /**
     * 检查是否有悬浮窗权限
     * 
     * 兼容Android O及以上版本的AppOpsManager和旧版本的Settings.canDrawOverlays
     * 
     * @param context 应用上下文
     * @return 是否有悬浮窗权限
     */
    fun hasOverlayPermissions(context: Context): Boolean {
        kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appOpsMgr = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return false
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOpsMgr.unsafeCheckOpNoThrow("android:system_alert_window", Process.myUid(), context.packageName)
                } else {
                    appOpsMgr.checkOpNoThrow("android:system_alert_window", Process.myUid(), context.packageName)
                }
                return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_IGNORED
            } else {
                return Settings.canDrawOverlays(context)
            }
        }.onFailure {
            Log.e("OverlayUtils", "hasOverlayPermissions: ", it)
        }
        return false
    }
}