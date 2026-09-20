@file:Suppress("UNCHECKED_CAST", "PrivateApi")
package moe.fuqiuluo.xposed.hooks.wlan

import android.net.wifi.WifiInfo
import android.os.Build
import android.util.ArrayMap
import dalvik.system.PathClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.hookMethodAfter
import moe.fuqiuluo.xposed.utils.toClass

object WlanHook {
    operator fun invoke(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cSystemServerClassLoaderFactory = XposedHelpers.findClassIfExists("com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                Logger.warn("Failed to find SystemServerClassLoaderFactory")
                return
            }
            val sLoadedPaths = XposedHelpers.getStaticObjectField(cSystemServerClassLoaderFactory, "sLoadedPaths") as ArrayMap<String, PathClassLoader>
            val wifiClassLoader = sLoadedPaths.firstNotNullOfOrNull {
                if (it.key.contains("service-wifi.jar")) it.value else null
            }
            if (wifiClassLoader == null) {
                Logger.warn("Failed to find wifiClassLoader")
                return
            }
            val wifiClazz = "com.android.server.wifi.WifiServiceImpl".toClass(wifiClassLoader)
            if (wifiClazz == null) {
                Logger.warn("Failed to find WifiServiceImpl class")
                return
            }
            hookWifiServiceImpl(wifiClazz)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cSystemServiceManager = XposedHelpers.findClassIfExists("com.android.server.SystemServiceManager", classLoader)
            if (cSystemServiceManager == null) {
                Logger.warn("Failed to find SystemServiceManager")
                return
            }
            cSystemServiceManager.hookAllMethods("loadClassFromLoader", afterHook {
                if (args[0] == "com.android.server.wifi.WifiService") {
                    kotlin.runCatching {
                        val classloader = args[1] as PathClassLoader
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        hookWifiServiceImpl(wifiClazz)
                    }.onFailure {
                        Logger.error("Failed to hook WifiService", it)
                    }
                }
            })
        }
    }

    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
        if (!FakeLoc.hookWifi) return

        wifiClazz.hookAllMethods("getConnectionInfo", beforeHook {
            val packageName = args[0] as String
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getConnectionInfo with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (FakeLoc.enableMockWifi && !BinderUtils.isSystemPackages(packageName)) {
                val wifiInfo = WifiInfo::class.java.getConstructor().newInstance()
                XposedHelpers.callMethod(wifiInfo, "setMacAddress", "02:00:00:00:00:00")
                XposedHelpers.callMethod(wifiInfo, "setBSSID", "02:00:00:00:00:00")
                result = wifiInfo
            }
        })

        wifiClazz.hookAllMethods("getScanResults", afterHook {
            val packageName = args[0] as? String
            if (packageName.isNullOrEmpty()) {
                return@afterHook
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("In getScanResults with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (FakeLoc.enableMockWifi) {
                emptyLike(result)?.let { result = it }
            }
        })

        // getConfiguredNetworks / getPrivilegedConfiguredNetworks 返回的是"设备上已保存的 WiFi 网络"
        // （含真实 SSID / BSSID）。只清空 getScanResults 挡住的是"周围有哪些 AP"，
        // 这两份会把用户真实连过的网络连着身份字段一起交出去。
        // 系统调用方放行，避免 Settings 之类的界面拿不到列表。
        val hookConfiguredNetworks = afterHook {
            val packageName = args[0] as? String
            if (packageName.isNullOrEmpty()) {
                return@afterHook
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("In ${method.name} with caller: $packageName, state: ${FakeLoc.enableMockWifi}")
            }

            if (!FakeLoc.enableMockWifi || BinderUtils.isSystemPackages(packageName)) {
                return@afterHook
            }

            emptyLike(result)?.let { result = it }
        }

        wifiClazz.hookAllMethods("getConfiguredNetworks", hookConfiguredNetworks)
        wifiClazz.hookAllMethods("getPrivilegedConfiguredNetworks", hookConfiguredNetworks)
    }

    /**
     * 造一个与 [origin] 同类型的空容器。
     *
     * 返回 `null` 表示类型不认识——调用方必须保留原值，不要用 null 覆盖。
     */
    private fun emptyLike(origin: Any?): Any? {
        if (origin == null) return null
        if (origin is List<*>) return arrayListOf<Any>() // 针对小米系列机型的返回
        if (origin is Array<*>) return arrayOf<Any>() // 针对一加系列机型的返回

        // 高于 Android 10 的版本用 APEX（Android Pony EXpress）封装系统服务，返回值是
        // ParceledListSlice 而不是 List，直接塞 ArrayList 会被当成坏值导致 hook 失效
        // （现象就是位置被网络 AGPS 拉回）。
        val resultClass = origin.javaClass
        if (resultClass.name.contains("ParceledListSlice")) {
            return runCatching {
                val constructor = resultClass.getConstructor(List::class.java)
                constructor.isAccessible = true
                constructor.newInstance(emptyList<Any>())
            }.onFailure {
                Logger.error("emptyLike: ParceledListSlice failed", it)
            }.getOrNull()
        }

        if (FakeLoc.enableDebugLog) {
            Logger.error("emptyLike: Unknown return type: ${resultClass.name}")
        }
        return null
    }
}
