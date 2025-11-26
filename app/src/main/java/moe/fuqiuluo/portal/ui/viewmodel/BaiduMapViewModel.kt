package moe.fuqiuluo.portal.ui.viewmodel

import android.app.Notification
import androidx.lifecycle.ViewModel
import com.baidu.location.LocationClient
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MyLocationConfiguration
import moe.fuqiuluo.portal.R
import com.baidu.mapapi.search.geocode.GeoCoder
import moe.fuqiuluo.portal.bdmap.setMapConfig

/**
 * 百度地图视图模型
 * 
 * 管理百度地图相关的状态和数据，包括地图实例、位置信息、标记点等
 */
class BaiduMapViewModel: ViewModel() {
    var isExists = false
    lateinit var baiduMap: BaiduMap
    lateinit var mLocationClient: LocationClient

    /**
     * Current location
     * WGS84
     */
    var currentLocation: Pair<Double, Double>? = null

    var markName: String? = null

    /**
     * Marked location
     * WGS84
     * first => latitude
     * second => longitude
     */
    var markedLoc: Pair<Double, Double>? = null
    var showDetailView = false

    /* Notification */
    var mNotification: Notification? = null

    /**
     * 2024.10.10: Cancels the default follow perspective
     */
    var perspectiveState = MyLocationConfiguration.LocationMode.NORMAL
        set(value) {
            field = value
            baiduMap.setMapConfig(value, null)
        }

    val mMapIndicator: BitmapDescriptor? by lazy {
        BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
    }

    /**
     * 地理编码器，用于地址和坐标之间的转换
     */
    var mGeoCoder: GeoCoder? = null
}