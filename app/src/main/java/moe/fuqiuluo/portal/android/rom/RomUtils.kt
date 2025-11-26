package moe.fuqiuluo.portal.android.rom

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * ROM工具类
 * 
 * 提供检测不同Android定制ROM的功能，如MIUI、EMUI等
 */
object RomUtils {
    /**
     * 检测是否为MIUI系统
     * 
     * @return 是否为MIUI系统
     */
    fun isMiui(): Boolean {
        return !getProp("ro.miui.ui.version.name").isNullOrBlank()
    }

    /**
     * 检测是否为EMUI系统
     * 
     * @return 是否为EMUI系统
     */
    fun isEmui(): Boolean {
        return getProp("ro.build.version.emui") != null
    }

    /**
     * 获取系统属性值
     * 
     * @param name 属性名称
     * @return 属性值，获取失败返回null
     */
    private fun getProp(name: String): String? {
        return try {
            val p = Runtime.getRuntime().exec("getprop $name")
            val `in` = BufferedReader(InputStreamReader(p.inputStream))
            val value = `in`.readLine()
            `in`.close()
            value
        } catch (e: IOException) {
            null
        }
    }
}