package moe.fuqiuluo.portal

import android.app.Application
import android.content.Context
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.tencent.bugly.crashreport.CrashReport
import moe.fuqiuluo.portal.android.Bugly

/**
 * 应用程序主类
 * 
 * 负责应用程序的全局初始化，包括百度地图SDK初始化、
 * 崩溃报告初始化和全局上下文保存
 */
class Portal: Application() {

    /**
     * 应用程序创建时的初始化操作
     * 
     * 初始化百度地图SDK、位置服务和崩溃报告系统
     */
    override fun onCreate() {
        super.onCreate()

        SDKInitializer.setAgreePrivacy(this, true)
        LocationClient.setAgreePrivacy(true)

        SDKInitializer.initialize(this)
        SDKInitializer.setCoordType(DEFAULT_COORD_TYPE)

        CrashReport.initCrashReport(applicationContext)

        CrashReport.setUserId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceModel(applicationContext, Bugly.getDeviceModel())
        CrashReport.setCollectPrivacyInfo(applicationContext, true)

        appContext = applicationContext

        //CrashReport.setAllThreadStackEnable(applicationContext, true, true)
    }

    /**
     * 伴生对象
     * 
     * 包含应用程序的全局常量和共享变量
     */
    companion object {
        val DEFAULT_COORD_TYPE = CoordType.GCJ02
        const val DEFAULT_COORD_STR = "GCJ02"

        lateinit var appContext: Context
        //val DEFAULT_COORD_TYPE = CoordType.BD09LL
        //const val DEFAULT_COORD_STR = "bd09ll"
    }
}