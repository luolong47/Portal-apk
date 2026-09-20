package moe.fuqiuluo.xposed.hooks.telephony

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.telephony.CellIdentity
import android.telephony.CellIdentityCdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.NeighboringCellInfo
import android.telephony.SignalStrength
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.CellSimulator
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.hookBefore
import moe.fuqiuluo.xposed.utils.hookMethodBefore
import moe.fuqiuluo.xposed.utils.onceHookDoNothingMethod
import moe.fuqiuluo.xposed.utils.onceHookMethodBefore
import java.lang.reflect.Modifier


object TelephonyHook: BaseTelephonyHook() {
    operator fun invoke(classLoader: ClassLoader) {
        if(!initDivineService("TelephonyHook")) {
            Logger.error("Failed to init mock service in TelephonyHook")
            return
        }

        // 基站类的框架签名在不同 API 版本上会变（CellIdentityNr 的 MCC/MNC 甚至是 String）。
        // 开这个开关会把运行中真实的构造器 / setter dump 到日志，跑一次就能把
        // CellSimulator 的候选链收敛成确定签名——不猜，让 system_server 自己报。
        if (FakeLoc.cellProbe) {
            CellSimulator.probe(classLoader)
        }

//        kotlin.runCatching {
//            val cCellIdentityCdma =
//                XposedHelpers.findClass("android.telephony.CellIdentityCdma", classLoader)
//            val hookCdma = object: XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam?) {
//                    if (param == null) return
//
//                    if (!FakeLocationConfig.enable) return
//
//                    param.args[3] = Int.MAX_VALUE
//                    param.args[4] = Int.MAX_VALUE
//                }
//            }
//            //                                                             nid             sid                 bid            lon            lat                alphal              alphas
//            XposedHelpers.findAndHookConstructor(
//                cCellIdentityCdma,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                String::class.java,
//                String::class.java,
//                hookCdma
//            )
//        }.onFailure {
//            XposedBridge.log("[Portal] Hook CellIdentityCdma failed")
//        }

//        kotlin.runCatching {
//            val cCellIdentityGsm = XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
//
//        }.onFailure {
//            XposedBridge.log("[Portal] Hook CellIdentityGsm failed")
//        }

//        XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", classLoader)?.let {
//            XposedBridge.hookAllMethods(it, "getNeighboringCellInfo", hookGetNeighboringCellInfoList)
//            XposedBridge.hookAllMethods(it, "getCellLocation", hookGetCellLocation)
//        }

//        kotlin.runCatching {
//            XposedHelpers.findClass("com.android.internal.telephony.ITelephony\$Stub", classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                if (it.name == "onTransact") {
//                    hookOnTransactForServiceInstance(it)
//                    return@forEach
//                }
//            }
//        }.onFailure {
//            XposedBridge.log("[Portal] ITelephony.Stub not found: ${it.stackTraceToString()}")
//        }

        if (!FakeLoc.needDowngradeToCdma) return

        val cPhoneInterfaceManager = XposedHelpers.findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader)
            ?: return

        val hookGetPhoneTyp = beforeHook {
            if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getActivePhoneType: injected!")
                }

                result = 2
            }
        }

        if(cPhoneInterfaceManager.hookAllMethods("getActivePhoneType", hookGetPhoneTyp).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getActivePhoneType failed")
        }
        if(cPhoneInterfaceManager.hookAllMethods("getActivePhoneTypeForSlot", hookGetPhoneTyp).isEmpty()) {
            Logger.warn("Hook PhoneInterfaceManager.getActivePhoneTypeForSlot failed")
        }

        cPhoneInterfaceManager.declaredMethods.find { it.name == "getAllCellInfo" }?.let {
            val hookGetAllCellInfo = afterHook {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getAllCellInfo: injected! caller = ${BinderUtils.getCallerUid()}")
                }

                if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                    fakeCellInfoList()?.let { result = it }
                }
            }
            if (XposedBridge.hookMethod(it, hookGetAllCellInfo) == null) {
                Logger.error("Hook PhoneInterfaceManager.getAllCellInfo failed")
            }
        }

        if(XposedBridge.hookAllMethods(cPhoneInterfaceManager, "getCellLocation", afterHook {
                if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                    return@afterHook
                }

                // 老 API（ITelephony.getCellLocation，API 30 起返回 CellIdentity）。
                //
                // 原来这里造的是 CDMA 空壳（nid/sid/bid 全 Int.MAX_VALUE，经纬度 = 假坐标 × 14400），
                // 已经去掉：中国移动的卡配 CDMA 小区是硬矛盾，而且那个经纬度是用公式算出来的、
                // 分毫不差地等于用户坐标，比不造假更容易被抓。
                //
                // 现在：有基站快照就回放对应制式的 CellIdentity，没有就放行真实值。
                val identity = if (FakeLoc.enableMockCell) {
                    CellSimulator.buildCellIdentity(FakeLoc.cellSnapshot, null)
                } else null

                if (identity == null) {
                    return@afterHook
                }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${method.name}: injected! -> ${identity.javaClass.simpleName}")
                }

                result = identity
            }).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getCellLocation failed")
        }

        beforeHook {
            if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getDataNetworkType: injected!")
                }
                result = 4
            }
        }.let {
            cPhoneInterfaceManager.hookAllMethods("getDataNetworkType", it)
            cPhoneInterfaceManager.hookAllMethods("getNetworkType", it)
            cPhoneInterfaceManager.hookAllMethods("getDataNetworkTypeForSubscriber", it)
            cPhoneInterfaceManager.hookAllMethods("getNetworkTypeForSubscriber", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (cPhoneInterfaceManager.hookAllMethods("getNeighboringCellInfo", beforeHook {
                if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                    return@beforeHook
                }

                result = kotlin.runCatching {
                    val nCellInfo = NeighboringCellInfo::class.java.getConstructor().newInstance()
                    XposedHelpers.setIntField(nCellInfo, "mRssi", -46)
                    XposedHelpers.setIntField(nCellInfo, "mCid", -1)
                    XposedHelpers.setIntField(nCellInfo, "mLac", -1)
                    XposedHelpers.setIntField(nCellInfo, "mPsc", -1)
                    XposedHelpers.setIntField(nCellInfo, "mNetworkType", 3)
                    listOf(nCellInfo)
                }.getOrElse {
                    arrayListOf()
                }
            }).isEmpty()) {
                Logger.error("Hook PhoneInterfaceManager.getNeighboringCellInfo failed")
            }
        }

        hookCellInfoCallback(classLoader)

        val cTelephonyRegistry = XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)
        if (cTelephonyRegistry == null) {
            Logger.error("TelephonyRegistry not found")
        } else {
            hookTelephonyRegistry(cTelephonyRegistry)
        }

    }

    /**
     * 造一份用于替换的基站列表。
     *
     * 数据来自 [FakeLoc.cellSnapshot]（在**目标地点**采到的真实基站指纹），由 [CellSimulator] 回放。
     * 已经不再凭空造 CDMA 空壳——中国移动的卡配 CDMA 小区是硬矛盾，比不造假更容易被抓；
     * 而且凭空造的小区在高德服务器的指纹库里查不到，反查一样会失败，等于白造。
     *
     * @return `null` 表示**本次不该改写**：基站模拟关了、没有快照、或构造全部失败。
     *         调用方拿到 null 必须**放行真实值**——不要用 null 去覆盖返回值或参数，
     *         那会把原方法整个跳过。
     */
    private fun fakeCellInfoList(): ArrayList<CellInfo>? {
        if (!FakeLoc.enableMockCell) return null
        return CellSimulator.buildCellInfos(FakeLoc.cellSnapshot, null)?.let { ArrayList(it) }
    }

    /**
     * 拦 `requestCellInfoUpdate` 的回调。
     *
     * 这条 API 是 `getAllCellInfo` 的现代替代：`PhoneInterfaceManager` 收下 `ICellInfoCallback`
     * 后交给 RIL，结果由 RIL 在 `com.android.phone` 进程内**异步回调**，**不经过 TelephonyRegistry**，
     * 所以只能拦回调代理本身。
     *
     * 这里**刻意不做** `isSystemAppsCall()` 判断：回调的发起方是 RIL / phone 进程（uid 1001），
     * 那个判断会恒为 true 把 hook 挡死——`TelephonyRegistry` 那四个 `notify*` hook 就是栽在这上面。
     */
    private fun hookCellInfoCallback(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        val cProxy = XposedHelpers.findClassIfExists("android.telephony.ICellInfoCallback\$Stub\$Proxy", classLoader)
        if (cProxy == null) {
            Logger.warn("ICellInfoCallback.Stub.Proxy not found")
            return
        }

        if (cProxy.hookAllMethods("onCellInfo", beforeHook {
                if (!FakeLoc.enable) return@beforeHook

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("ICellInfoCallback.onCellInfo: injected!")
                }

                fakeCellInfoList()?.let { args[0] = it }
            }).isEmpty()) {
            Logger.error("Hook ICellInfoCallback.onCellInfo failed")
        }

        // 取基站失败时 App 会走 onError 拿到"调制解调器错误"。换成一份假的成功结果，
        // 否则真机报错本身就是一条暴露信号。
        if (cProxy.hookAllMethods("onError", beforeHook {
                if (!FakeLoc.enable) return@beforeHook

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("ICellInfoCallback.onError: code=${args.getOrNull(0)}, msg=${args.getOrNull(2)}")
                }

                kotlin.runCatching {
                    XposedHelpers.callMethod(thisObject, "onCellInfo", fakeCellInfoList())
                }.onSuccess {
                    result = null // 跳过原始 onError
                }.onFailure {
                    Logger.error("ICellInfoCallback.onError -> onCellInfo failed", it)
                }
            }).isEmpty()) {
            Logger.warn("Hook ICellInfoCallback.onError failed")
        }
    }

    fun hookTelephonyRegistry(cTelephonyRegistry: Class<*>) {
        cTelephonyRegistry.declaredMethods.filter { (it.name == "listen" || it.name == "listenWithEventList") && !Modifier.isAbstract(it.modifiers) }
            .map {
                it to it.parameterTypes.indexOfFirst { typ -> typ.simpleName == "IPhoneStateListener" }
            }.forEach {
                val (m, idx) = it
                if (idx == -1) {
                    Logger.error("IPhoneStateListener not found")
                    return@forEach
                }
                m.hookBefore {
                    if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                        return@hookBefore
                    }

                    val listener = args[idx] as Any
                    val hasHookOnCellLocationChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        listener.javaClass.onceHookMethodBefore("onCellLocationChanged", CellIdentity::class.java) {
                            if (FakeLoc.enable) {
                                result = CellIdentityCdma::class.java.getConstructor(
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    String::class.java,
                                    String::class.java
                                ).newInstance(
                                    Int.MAX_VALUE,
                                    Int.MAX_VALUE,
                                    Int.MAX_VALUE,
                                    (FakeLoc.latitude * 14400.0).toInt(),
                                    (FakeLoc.longitude * 14400.0).toInt(),
                                    null, null
                                )
                            }
                        }
                    } else {
                        listener.javaClass.onceHookMethodBefore("onCellLocationChanged", Bundle::class.java) {
                            if (FakeLoc.enable) {
                                result = Bundle().apply {
                                    putInt("cid", Int.MAX_VALUE)
                                    putInt("lac", Int.MAX_VALUE)
                                    putInt("psc", Int.MAX_VALUE)
                                    putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                                    putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                                    putBoolean("empty", false)
                                    putBoolean("emptyParcel", false)
                                    putInt("mFlags", 1536)
                                    putBoolean("parcelled", false)
                                    putInt("baseStationId", Int.MAX_VALUE)
                                    putInt("systemId", Int.MAX_VALUE)
                                    putInt("networkId", Int.MAX_VALUE)
                                    putInt("size", 0)
                                }
                            }
                        }
                    } != null
                    if (!hasHookOnCellLocationChanged) {
                        Logger.error("Hook onCellLocationChanged failed")
                    }
                    // onCellInfoChanged 是蜂窝回调的现代形态，注册了 LISTEN_CELL_INFO 的监听器走这条。
                    // registry 侧的 notifyCellInfo 已经生效（那里的 uid gate 已去掉），
                    // 这里留作第二道保险：监听器侧不依赖调用方身份，能兜住任何绕过 registry 的分发。
                    val hasHookOnCellInfoChanged = listener.javaClass
                        .onceHookMethodBefore("onCellInfoChanged", List::class.java) {
                            if (FakeLoc.enable) {
                                fakeCellInfoList()?.let { result = it }
                            }
                        } != null
                    if (!hasHookOnCellInfoChanged) {
                        Logger.warn("Hook onCellInfoChanged failed")
                    }
                    listener.javaClass.onceHookDoNothingMethod("onSignalStrengthChanged", Int::class.java) {
                        FakeLoc.enable
                    }
                    listener.javaClass.onceHookDoNothingMethod("onSignalStrengthsChanged", SignalStrength::class.java) { FakeLoc.enable }
                }
            }

        // notify* 这四处**刻意不做** isSystemAppsCall() 判断：
        // 它们的调用方是 phone 进程（uid 1001），isSystemAppsCall() 对 uid <= 10000 恒返回 true，
        // 加了必然被挡死——这四处曾经就是这样静默失效的（不报错、有日志、看着像在工作）。
        // 分发点该 gate 在"最终消费者"上，而 notify* 是广播给所有已注册监听器的，没有单一消费者。
        // 监听器侧的 onCellLocationChanged / onCellInfoChanged 是第二道保险，两道都留着。
        cTelephonyRegistry.hookMethodBefore("notifyCellInfo", List::class.java) {
            if (!FakeLoc.enable) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfo: injected!")
            }

            fakeCellInfoList()?.let { args[0] = it }
        }

        cTelephonyRegistry.hookMethodBefore("notifyCellInfoForSubscriber", Int::class.java, List::class.java) {
            if (!FakeLoc.enable) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfoForSubscriber: injected!")
            }

            fakeCellInfoList()?.let { args[1] = it }
        }

        cTelephonyRegistry.hookMethodBefore("notifyCellLocation", Bundle::class.java) {
            if (!FakeLoc.enable) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocation: injected!")
            }

            args[0] = Bundle().apply {
                putInt("cid", Int.MAX_VALUE)
                putInt("lac", Int.MAX_VALUE)
                putInt("psc", Int.MAX_VALUE)
                putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                putBoolean("empty", false)
                putBoolean("emptyParcel", false)
                putInt("mFlags", 1536)
                putBoolean("parcelled", false)
                putInt("baseStationId", Int.MAX_VALUE)
                putInt("systemId", Int.MAX_VALUE)
                putInt("networkId", Int.MAX_VALUE)
                putInt("size", 0)
            }
        }
        cTelephonyRegistry.hookMethodBefore("notifyCellLocationForSubscriber", Int::class.java, Bundle::class.java) {
            if (!FakeLoc.enable) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocationForSubscriber: injected!")
            }

            args[1] = Bundle().apply {
                putInt("cid", Int.MAX_VALUE)
                putInt("lac", Int.MAX_VALUE)
                putInt("psc", Int.MAX_VALUE)
                putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                putBoolean("empty", false)
                putBoolean("emptyParcel", false)
                putInt("mFlags", 1536)
                putBoolean("parcelled", false)
                putInt("baseStationId", Int.MAX_VALUE)
                putInt("systemId", Int.MAX_VALUE)
                putInt("networkId", Int.MAX_VALUE)
                putInt("size", 0)
            }
        }
    }

    @Suppress("LocalVariableName")
    fun hookSubOnTransact(classLoader: ClassLoader) {
        val cISub = XposedHelpers.findClassIfExists("com.android.internal.telephony.ISub\$Stub", classLoader)
        if (cISub == null) {
            Logger.error("ISub.Stub not found")
            return
        }

        val subClassName = "com.android.internal.telephony.ISub"
        val TRANSACTION_getActiveSubInfoCount = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubInfoCount") }.getOrDefault(-1)
        val TRANSACTION_getActiveSubInfoCountMax = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubInfoCountMax") }.getOrDefault(-1)
        val TRANSACTION_getActiveSubscriptionInfoList = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubscriptionInfoList") }.getOrDefault(-1)
        val TRANSACTION_getPhoneId = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getPhoneId") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIndex = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIndex") }.getOrDefault(-1)
        val TRANSACTION_isActiveSubId = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_isActiveSubId") }.getOrDefault(-1)
        val TRANSACTION_getNetworkCountryIsoForPhone = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getNetworkCountryIsoForPhone") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSubscriber = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSubscriber") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIdx = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIdx") }.getOrDefault(-1)

        val hookOnTransact = beforeHook {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@beforeHook
            }

            val code = args[0] as Int
            val data = args[1] as Parcel
            val reply = args[2] as Parcel
            val flags = args[3] as Int

            if (code == -1) return@beforeHook

            when (code) {
                TRANSACTION_isActiveSubId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        reply.writeBoolean(true)
                    } else {
                        reply.writeInt(1)
                    }
                    result = true
                }
                TRANSACTION_getSimStateForSlotIndex -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
                TRANSACTION_getSimStateForSubscriber -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
                TRANSACTION_getSimStateForSlotIdx -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
//                TRANSACTION_getActiveSubInfoCount -> {
//                    data.enforceInterface(subClassName)
//                    data.readString()
//                    reply.writeNoException()
//                    reply.writeInt(1)
//                    result = true
//                }
//                TRANSACTION_getActiveSubscriptionInfoList -> {
//                    data.enforceInterface(subClassName)
//                    data.readString()
//                    reply.writeNoException()
//                    reply.writeTypedList(arrayListOf())
//                    result = true
//                }
//                TRANSACTION_getActiveSubInfoCountMax -> {
//                    data.enforceInterface(subClassName)
//                    reply.writeNoException()
//                    reply.writeInt(1)
//                    result = true
//                }
                TRANSACTION_getNetworkCountryIsoForPhone -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeString("CHN")
                    result = true
                }
                TRANSACTION_getPhoneId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(0)
                    result = true
                }
            }
        }
        if(cISub.hookAllMethods("onTransact", hookOnTransact).isEmpty()) {
            Logger.error("Hook ISub.Stub.onTransact failed")
        }
    }

//    private fun hookOnTransactForServiceInstance(m: Method) {
//        var hook: XC_MethodHook.Unhook? = null
//        hook = XposedBridge.hookMethod(m, object : XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//
//                val thisObject = param.thisObject
//
//                if (hook == null || thisObject == null) return
//                onFetchServiceInstance(thisObject)
//                hook?.unhook()
//                hook = null
//            }
//        })
//    }

//    private fun onFetchServiceInstance(thisObject: Any) {
//        val cITelephony = thisObject.javaClass
//
//        println("[Portal] found " + cITelephony.declaredMethods.mapNotNull {
//            if (it.returnType.javaClass.name.contains("CellLocation")) {
//                if (FakeLocationConfig.DEBUG) {
//                    XposedBridge.log("[Portal] hook method: $it")
//                }
//                XposedBridge.hookMethod(it, hookGetCellLocation)
//            } else null
//        }.size + " methods(CellLocation) to hook in ITelephony\$Stub")
//
//        XposedBridge.hookAllMethods(cITelephony, "getNeighboringCellInfo", hookGetNeighboringCellInfoList)
//    }
}