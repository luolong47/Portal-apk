package moe.fuqiuluo.portal.service

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 基站环境快照采集器。
 *
 * ## 为什么要采，而不是造
 *
 * 网络定位（高德 `Battery_Saving`、GMS NLP 等等）本质是**指纹反查**：
 * 把当前的基站 / WiFi 指纹上传到服务商服务器，由服务器在它的指纹库里查出坐标。
 *
 * 所以凭空生成的小区——哪怕 MCC/MNC 都用真实的——在服务器库里并不存在，反查必然失败。
 * 要模拟某个地点，就必须先**在那个地点采一份真实指纹**，之后由 xposed 侧回放。
 *
 * ## 输出格式
 *
 * 与 xposed 侧 `moe.fuqiuluo.xposed.utils.CellSimulator` 的解析格式严格对应：
 * 一条小区用 `|` 分隔，字段用 `;` 分隔，键值用 `=`：
 *
 * ```
 * type=lte;mcc=460;mnc=0;ci=1234567;pci=300;tac=650;earfcn=1650;rsrp=-95;rsrq=-12;rssnr=8;level=15
 * type=nr;mcc=460;mnc=0;nci=12345678;pci=500;tac=700;arfcn=504990;rsrp=-100;rsrq=-13;level=20
 * ```
 *
 * ## 为什么全用反射读字段
 *
 * `CellIdentityLte.getEarfcn()`、`CellSignalStrengthLte.getRsrp()`、NR 那一套 getter
 * 在不同 API 版本上的可见性 / 命名都变过（`getMcc()` 返回 int 还是 `getMccString()` 返回 String
 * 就是典型例子）。这里不依赖任何编译期符号，能读到几个写几个，读不到的字段直接不写进快照，
 * 由回放侧的默认值补齐。
 */
object CellSnapshotCollector {

    private const val TAG = "CellSnapshotCollector"

    /** 一条小区都读不到时返回 `null`（而不是空串），调用方据此报"采集失败"。 */
    fun collect(context: Context): String? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null

        val cellInfos = runCatching { telephony.allCellInfo }.getOrElse {
            // 没有 ACCESS_FINE_LOCATION、或系统定位总开关没开，都会走到这里
            Log.w(TAG, "getAllCellInfo failed: ${it.javaClass.simpleName} ${it.message}")
            return null
        } ?: return null

        val lines = cellInfos.mapNotNull { info ->
            when (info.javaClass.simpleName) {
                "CellInfoLte" -> lte(info)
                "CellInfoNr" -> nr(info)
                // gsm / wcdma / cdma 先不采：国内主力是 LTE + NR，回放侧也只支持这两种
                else -> null
            }
        }

        if (lines.isEmpty()) {
            Log.w(TAG, "读到 ${cellInfos.size} 条小区，但没有一条是 LTE/NR")
            return null
        }
        Log.i(TAG, "collected ${lines.size} cell(s)")
        return lines.joinToString("|")
    }

    fun count(snapshot: String?): Int =
        snapshot?.split('|')?.count { it.isNotBlank() } ?: 0

    // ------------------------------------------------------------ 分制式

    private fun lte(info: Any): String? {
        val identity = invoke(info, "getCellIdentity") ?: return null
        val signal = invoke(info, "getCellSignalStrength") ?: return null

        val mcc = intOf(identity, "getMccString", "getMcc") ?: return null
        if (mcc <= 0 || mcc == Int.MAX_VALUE) return null
        val mnc = intOf(identity, "getMncString", "getMnc") ?: 0

        val parts = mutableListOf("type=lte", "mcc=$mcc", "mnc=$mnc")
        put(parts, "ci", intOf(identity, "getCi"))
        put(parts, "pci", intOf(identity, "getPci"))
        put(parts, "tac", intOf(identity, "getTac"))
        put(parts, "earfcn", intOf(identity, "getEarfcn"), min = 1)
        put(parts, "rsrp", intOf(signal, "getRsrp"))
        put(parts, "rsrq", intOf(signal, "getRsrq"))
        put(parts, "rssnr", intOf(signal, "getRssnr"))
        put(parts, "level", intOf(signal, "getLevel"))
        return parts.joinToString(";")
    }

    private fun nr(info: Any): String? {
        val identity = invoke(info, "getCellIdentity") ?: return null
        val signal = invoke(info, "getCellSignalStrength") ?: return null

        val mcc = intOf(identity, "getMccString", "getMcc") ?: return null
        if (mcc <= 0 || mcc == Int.MAX_VALUE) return null
        val mnc = intOf(identity, "getMncString", "getMnc") ?: 0

        val parts = mutableListOf("type=nr", "mcc=$mcc", "mnc=$mnc")

        // NCI 是 36 位，必须用 Long，Int 截断后就是另一个小区
        longOf(identity, "getNci")?.let { if (it >= 0) parts += "nci=$it" }
        put(parts, "pci", intOf(identity, "getPci"))
        put(parts, "tac", intOf(identity, "getTac"))
        put(parts, "arfcn", intOf(identity, "getNrArfcn", "getArfcn"), min = 1)

        // NR 的信号强度 getter 名在版本间变过（SsRsrp / Rsrp 两套），能读哪个算哪个
        put(parts, "rsrp", intOf(signal, "getSsRsrp", "getRsrp"))
        put(parts, "rsrq", intOf(signal, "getSsRsrq", "getRsrq"))
        put(parts, "sinr", intOf(signal, "getSsSinr", "getSinr"))
        put(parts, "level", intOf(signal, "getLevel"))
        return parts.joinToString(";")
    }

    /** 值有效才写进快照；`min` 用来排除"0 表示无效"的字段（如 earfcn）。 */
    private fun put(parts: MutableList<String>, key: String, value: Int?, min: Int = Int.MIN_VALUE) {
        if (value == null) return
        if (value == Int.MAX_VALUE || value == Int.MIN_VALUE) return
        if (value < min) return
        parts += "$key=$value"
    }

    // ------------------------------------------------------------ 反射

    private fun invoke(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()

    private fun intOf(target: Any, vararg names: String): Int? {
        for (name in names) {
            val v = invoke(target, name) ?: continue
            when (v) {
                is Int -> return v
                is Number -> return v.toInt()
                is String -> v.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun longOf(target: Any, vararg names: String): Long? {
        for (name in names) {
            val v = invoke(target, name) ?: continue
            when (v) {
                is Long -> return v
                is Number -> return v.toLong()
                is String -> v.toLongOrNull()?.let { return it }
            }
        }
        return null
    }
}
