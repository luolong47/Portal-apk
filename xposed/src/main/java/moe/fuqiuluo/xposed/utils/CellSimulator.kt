@file:Suppress("PrivateApi", "unused")
package moe.fuqiuluo.xposed.utils

import android.telephony.CellInfo
import de.robv.android.xposed.XposedHelpers

/**
 * 基站模拟器。
 *
 * ## 为什么是「回放」而不是「凭空造」
 *
 * 高德的网络定位（`Battery_Saving` 模式）本质是**指纹反查**：把设备当前的 WiFi / 基站指纹
 * 上传到高德服务器，由服务器在它的指纹库里查出坐标。
 *
 * 所以凭空生成的小区——**哪怕 MCC/MNC 都用真实的**——在高德服务器库里并不存在，
 * 反查必然失败。造得再"像"，也只是从"明显是假的"变成"查不到"，依然不生效。
 *
 * 因此本类只做一件事：把一份**在目标地点采到的真实基站快照**回放出去。
 * **没有快照就不造假**（放行真实值），并在日志里说清楚——造假比放行更危险，
 * 尤其是原来的 CDMA 空壳（中国移动的卡配 CDMA 小区是硬矛盾）。
 *
 * ## 快照格式
 *
 * 一条小区用 `|` 分隔，字段用 `;` 分隔，键值用 `=`：
 *
 * ```
 * type=lte;mcc=460;mnc=0;ci=1234567;pci=300;tac=650;earfcn=1650;rsrp=-95;rsrq=-12;rssnr=8;level=15
 * type=nr;mcc=460;mnc=0;nci=12345678;pci=500;tac=700;arfcn=504990;rsrp=-100;rsrq=-13;level=20
 * ```
 *
 * 目前只做 `lte` / `nr`（国内主力）。其它 type 会被跳过并记日志。
 * 未知字段忽略；缺失字段用一组"看起来合理"的默认值补齐。
 *
 * ## 框架签名不确定性
 *
 * `CellIdentityLte` / `CellSignalStrengthLte` / `CellInfoNr` 这些构造器在不同 API 版本上
 * 签名会变（例如 `CellIdentityNr` 的 MCC/MNC 是 **String** 而不是 int）。这里不猜死一个签名，
 * 而是走**候选链**（逐个尝试，第一个成功即用），全部失败就跳过该条并记日志。
 *
 * 开 `FakeLoc.cellProbe` 会把运行中这些类的真实构造器 / setter 签名 dump 到日志，
 * **真机跑一次就能把候选链收敛成确定签名**。这是 EnvSim 那条纪律：
 * 不猜签名，让运行中的 system_server 自己报。
 */
object CellSimulator {

    /**
     * 一条小区。`fields` 里是原始键值，缺省值在构造时补。
     *
     * 用 `Long` 存而不是 `Int`：NR 的 **NCI 是 36 位**，`Int` 装不下，截断后就是另一个小区了。
     */
    data class CellSpec(val type: String, val fields: Map<String, Long>) {
        fun int(key: String, fallback: Int): Int = (fields[key] ?: fallback.toLong()).toInt()
        fun long(key: String, fallback: Long): Long = fields[key] ?: fallback
    }

    private var warnedBuildFailed = false
    private var warnedUnsupportedType = false

    // ---------------------------------------------------------------- 解析

    fun parse(snapshot: String?): List<CellSpec> {
        if (snapshot.isNullOrBlank()) return emptyList()
        return snapshot.split('|').mapNotNull { raw ->
            val kv = LinkedHashMap<String, String>()
            raw.trim().split(';').forEach { pair ->
                val i = pair.indexOf('=')
                if (i > 0) {
                    kv[pair.substring(0, i).trim().lowercase()] = pair.substring(i + 1).trim()
                }
            }
            val type = kv["type"]?.lowercase() ?: return@mapNotNull null
            if (type != "lte" && type != "nr") {
                if (!warnedUnsupportedType) {
                    warnedUnsupportedType = true
                    Logger.warn("CellSimulator: 暂不支持 type=$type，该条已跳过")
                }
                return@mapNotNull null
            }
            CellSpec(type, kv.mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to it } }.toMap())
        }
    }

    // ---------------------------------------------------------------- 构造

    /**
     * 把快照回放成 `CellInfo` 列表。
     *
     * @return `null` 表示「这次不该改写」——没有快照，或构造全部失败。
     *         调用方拿到 null 时**必须放行真实值**，不要用 null 覆盖返回值。
     */
    fun buildCellInfos(snapshot: String?, classLoader: ClassLoader?): List<CellInfo>? {
        val specs = parse(snapshot)
        if (specs.isEmpty()) return null

        val built = ArrayList<CellInfo>(specs.size)
        specs.forEach { spec ->
            runCatching {
                when (spec.type) {
                    "lte" -> buildLte(spec, classLoader)
                    "nr" -> buildNr(spec, classLoader)
                    else -> null
                }
            }.onFailure {
                Logger.error("CellSimulator: 构造 ${spec.type} 失败（签名可能对不上，跑一次 cellProbe）", it)
            }.getOrNull()?.let { built.add(it) }
        }

        if (built.isEmpty()) {
            if (!warnedBuildFailed) {
                warnedBuildFailed = true
                Logger.error("CellSimulator: 全部 ${specs.size} 条小区构造失败，本次放行真实值。开 cellProbe 看签名")
            }
            return null
        }

        if (built.size < specs.size) {
            Logger.warn("CellSimulator: ${specs.size} 条里只成功构造 ${built.size} 条")
        }
        return built
    }

    /** 构造单条 `CellIdentity`（给 `getCellLocation` 那条老 API 用）。没有快照返回 null。 */
    fun buildCellIdentity(snapshot: String?, classLoader: ClassLoader?): Any? {
        val spec = parse(snapshot).firstOrNull() ?: return null
        return runCatching {
            when (spec.type) {
                "lte" -> newCellIdentityLte(spec, classLoader)
                else -> null
            }
        }.onFailure {
            Logger.error("CellSimulator: 构造 CellIdentity 失败（跑一次 cellProbe）", it)
        }.getOrNull()
    }

    private fun buildLte(spec: CellSpec, classLoader: ClassLoader?): CellInfo =
        CellInfoBuilder(spec, classLoader).create()

    private fun buildNr(spec: CellSpec, classLoader: ClassLoader?): CellInfo =
        CellInfoBuilder(spec, classLoader).create()

    /**
     * 构造器候选链跑一遍。
     *
     * 先试"无参构造 + setter"，再试隐藏的 5 参构造（与项目里 `CellInfoCdma` 的写法同源）。
     * 两边都失败就抛，由 [buildCellInfos] 统一兜住。
     */
    private class CellInfoBuilder(private val spec: CellSpec, private val classLoader: ClassLoader?) {

        fun create(): CellInfo {
            val infoClass = XposedHelpers.findClass(cls(), classLoader)
            val identity = identity()
            val signal = signal()

            // 路线 1：无参构造 + setter（setter 是 @hide，靠 callMethod 拿到）
            runCatching {
                val info = infoClass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                if (XposedHelpers.callMethod(info, "setCellIdentity", identity) != null) { /* 只为触发调用 */ }
                XposedHelpers.callMethod(info, "setCellSignalStrength", signal)
                decorate(info)
                return info as CellInfo
            }

            // 路线 2：隐藏的 5 参构造 (type, registered, timestamp, identity, signal)
            val ctor = infoClass.getDeclaredConstructor(
                Int::class.java,
                Boolean::class.java,
                Long::class.java,
                XposedHelpers.findClass(identityCls(), classLoader),
                XposedHelpers.findClass(signalCls(), classLoader)
            )
            ctor.isAccessible = true
            val info = ctor.newInstance(0, true, System.nanoTime(), identity, signal)
            decorate(info)
            return info as CellInfo
        }

        private fun decorate(info: Any) {
            runCatching { XposedHelpers.callMethod(info, "setRegistered", true) }
            runCatching { XposedHelpers.callMethod(info, "setTimeStamp", System.nanoTime()) }
            runCatching { XposedHelpers.callMethod(info, "setCellConnectionStatus", 0) }
        }

        private fun cls() = if (spec.type == "nr") "android.telephony.CellInfoNr" else "android.telephony.CellInfoLte"
        private fun identityCls() = if (spec.type == "nr") "android.telephony.CellIdentityNr" else "android.telephony.CellIdentityLte"
        private fun signalCls() = if (spec.type == "nr") "android.telephony.CellSignalStrengthNr" else "android.telephony.CellSignalStrengthLte"

        private fun identity(): Any =
            if (spec.type == "nr") newCellIdentityNr(spec, classLoader) else newCellIdentityLte(spec, classLoader)

        private fun signal(): Any =
            if (spec.type == "nr") newSignalStrength(spec, "android.telephony.CellSignalStrengthNr", classLoader)
            else newSignalStrength(spec, "android.telephony.CellSignalStrengthLte", classLoader)
    }

    private fun newCellIdentityLte(spec: CellSpec, classLoader: ClassLoader?): Any {
        val mcc = spec.int("mcc", 460)
        val mnc = spec.int("mnc", 0)
        val ci = spec.int("ci", 0)
        val pci = spec.int("pci", 0)
        val tac = spec.int("tac", 0)
        val earfcn = spec.int("earfcn", 0)

        val c = XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader)
        val chain: List<Pair<Array<Class<*>>, Array<Any>>> = listOf(
            arrayOf<Class<*>>(
                Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, Int::class.java
            ) to arrayOf<Any>(mcc, mnc, ci, pci, tac, earfcn),
            arrayOf<Class<*>>(
                Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java
            ) to arrayOf<Any>(mcc, mnc, ci, pci, tac)
        )

        chain.forEach { (sig, args) ->
            runCatching {
                val ctor = c.getDeclaredConstructor(*sig)
                ctor.isAccessible = true
                return ctor.newInstance(*args) as Any
            }
        }
        throw NoSuchMethodException("CellIdentityLte 没有可用的构造器，请跑 cellProbe")
    }

    private fun newCellIdentityNr(spec: CellSpec, classLoader: ClassLoader?): Any {
        val mcc = spec.int("mcc", 460).toString()
        val mnc = spec.int("mnc", 0).toString()
        val nci = spec.long("nci", 0L)
        val pci = spec.int("pci", 0)
        val tac = spec.int("tac", 0)
        val arfcn = spec.int("arfcn", 0)

        val c = XposedHelpers.findClass("android.telephony.CellIdentityNr", classLoader)
        // API 30+：(pci, tac, nrArfcn, mccStr, mncStr, nci) —— MCC/MNC 是 String，容易记错
        val chain: List<Pair<Array<Class<*>>, Array<Any>>> = listOf(
            arrayOf<Class<*>>(
                Int::class.java, Int::class.java, Int::class.java,
                String::class.java, String::class.java, Long::class.java
            ) to arrayOf<Any>(pci, tac, arfcn, mcc, mnc, nci),
            arrayOf<Class<*>>(
                Int::class.java, Int::class.java, Int::class.java,
                String::class.java, String::class.java
            ) to arrayOf<Any>(pci, tac, arfcn, mcc, mnc)
        )

        chain.forEach { (sig, args) ->
            runCatching {
                val ctor = c.getDeclaredConstructor(*sig)
                ctor.isAccessible = true
                return ctor.newInstance(*args) as Any
            }
        }
        throw NoSuchMethodException("CellIdentityNr 没有可用的构造器，请跑 cellProbe")
    }

    /**
     * 信号强度：无参构造 + 逐个 setter（每个 setter 单独 runCatching，
     * 部分字段不存在不影响其余字段落地）。
     */
    private fun newSignalStrength(spec: CellSpec, className: String, classLoader: ClassLoader?): Any {
        val c = XposedHelpers.findClass(className, classLoader)
        val s = c.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

        val set: List<Pair<String, Int>> = if (className.endsWith("Nr")) {
            listOf(
                "setSsRsrp" to spec.int("rsrp", -100),
                "setSsRsrq" to spec.int("rsrq", -13),
                "setSsSinr" to spec.int("sinr", 8),
                "setLevel" to spec.int("level", 20),
                "setRsrp" to spec.int("rsrp", -100),
                "setRsrq" to spec.int("rsrq", -13),
                "setRssnr" to spec.int("rssnr", 8)
            )
        } else {
            listOf(
                "setRsrp" to spec.int("rsrp", -95),
                "setRsrq" to spec.int("rsrq", -12),
                "setRssnr" to spec.int("rssnr", 8),
                "setTimingAdvance" to spec.int("ta", 0),
                "setLevel" to spec.int("level", 15)
            )
        }

        set.forEach { (name, value) ->
            runCatching { XposedHelpers.callMethod(s, name, value) }
        }
        return s
    }

    // ---------------------------------------------------------------- 探针

    /**
     * 把运行中真实可用的构造器 / setter 签名打到日志。
     *
     * 真机跑一次 → 把日志里的签名填进上面的候选链 → 收敛成确定签名。
     * 这是唯一能避免"猜框架签名"的办法。
     */
    fun probe(classLoader: ClassLoader?) {
        Logger.info("=== CellSimulator probe start ===")
        listOf(
            "android.telephony.CellInfo",
            "android.telephony.CellInfoLte",
            "android.telephony.CellInfoNr",
            "android.telephony.CellIdentityLte",
            "android.telephony.CellIdentityNr",
            "android.telephony.CellSignalStrengthLte",
            "android.telephony.CellSignalStrengthNr"
        ).forEach { name ->
            val c = XposedHelpers.findClassIfExists(name, classLoader)
            if (c == null) {
                Logger.info("$name : NOT FOUND")
                return@forEach
            }
            Logger.info("--- $name")
            c.declaredConstructors.forEach { ctor ->
                Logger.info("    ctor(${ctor.parameterTypes.joinToString(", ") { it.simpleName }})")
            }
            c.declaredMethods
                .filter { it.name.startsWith("set") }
                .sortedBy { it.name }
                .forEach { m ->
                    Logger.info("    ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})")
                }
        }
        Logger.info("=== CellSimulator probe end ===")
    }
}
