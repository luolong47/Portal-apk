@file:Suppress("LocalVariableName", "PrivateApi")
package moe.fuqiuluo.xposed.utils

import android.content.Context
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.XposedBridge


object BinderUtils {
    /** Portal 自己的包名。 */
    const val PORTAL_PACKAGE = "moe.fuqiuluo.portal"

    private fun getActivityContext(): Context? {
        // public static ActivityManagerService self()
        // frameworks/base/services/java/com/android/server/am/ActivityManagerService.java
        try {
            val cam = Class.forName("com.android.server.am.ActivityManagerService")
            val am = cam.getMethod("self").invoke(null) ?: return null
            val mContext = cam.getDeclaredField("mContext")
            mContext.isAccessible = true
            return mContext.get(am) as? Context
        } catch (e: Throwable) {
            return null
        }
    }

    fun getSystemContext(): Context? {
        try {
            val cActivityThread = Class.forName("android.app.ActivityThread")
            val activityThread = cActivityThread.getMethod("currentActivityThread")
                .invoke(null) ?: return null
            return (cActivityThread.getMethod("getSystemContext").invoke(activityThread) as? Context) ?: getActivityContext()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    fun getUidPackageNames(context: Context = getSystemContext()!!, uid: Int = getCallerUid()): Array<String>? {
        val packageManager = context.packageManager
        return packageManager.getPackagesForUid(uid)
    }

    fun getCallerUid(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kotlin.runCatching { Binder.getCallingUidOrThrow() }.getOrNull() ?: -1
        } else {
            Binder.getCallingUid()
        }
    }

    /**
     * Check whether the `PortalService` is started properly
     */
    fun isLocationProviderEnabled(uid: Int): Boolean {
        val packageNames = getUidPackageNames(uid = uid)
        if (uid > 10000 && packageNames?.any {
                !it.contains(PORTAL_PACKAGE)
            } == false) {
            return true
        }
        Logger.warn("Someone try to find Portal: uid = $uid, packageName = ${packageNames?.joinToString()}")
        return uid < 10000
    }

    fun isSystemPackages(packageNames: String): Boolean {
        if (packageNames.contains("com.xiaomi.location.fused") ||
            packageNames.contains("com.xiaomi.metoknlp") ||
            //packageNames.contains("com.android.phone") ||
            packageNames.contains("com.android.location.fused")
            ) {
            return false
        }
        return packageNames.contains("com.android") ||
                packageNames.contains("com.miui") ||
                packageNames.contains("com.xiaomi") ||
                packageNames.contains("android.framework") ||
                packageNames.contains("com.qualcomm") ||
                packageNames.contains("com.google.android.permissioncontroller")
    }

    /**
     * 这次调用是不是「系统内部调用」——是的话应当放行原值、不做改写。
     *
     * **Portal 自己的 App 也算系统调用。** 它必须永远看到真实数据，否则会自食其果：
     * 环境采集（基站 / WiFi 快照）会采到 Portal 自己刚伪造出来的值，
     * 地图和卫星雷达显示的也不是设备真实状态。
     *
     * 判据用**精确匹配**，不用 `contains`——`isSystemPackages` 那串子串判据本身不可靠
     * （清单 P1-6 记着），但至少这里可以和它共存、先把自己放行掉。
     */
    fun isSystemAppsCall(uid: Int = getCallerUid()): Boolean {
        if (uid <= 10000) {
            return true
        }

        val packages = kotlin.runCatching { getUidPackageNames(uid = uid) }.getOrNull() ?: return true
        if (packages.any { it == PORTAL_PACKAGE }) {
            return true
        }
        return isSystemPackages(packages.joinToString())
    }
}