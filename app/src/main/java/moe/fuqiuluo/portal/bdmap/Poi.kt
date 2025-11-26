package moe.fuqiuluo.portal.bdmap

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 兴趣点数据类
 * 
 * 表示地图上的一个位置点，包含名称、地址、经纬度和标签信息
 */
data class Poi(
    val name: String,
    var address: String,
    val longitude: Double,
    val latitude: Double,

    val tag: String,
) {
    companion object {
        const val KEY_NAME = "name"
        const val KEY_ADDRESS = "address"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE_RAW = "longitude_raw"
        const val KEY_LATITUDE_RAW = "latitude_raw"
        const val KEY_TAG = "tag"
    }

    /**
     * 将POI对象转换为Map
     * 
     * @return 包含POI信息的Map
     */
    fun toMap(): Map<String, String> {
        return mapOf(
            KEY_NAME to name,
            KEY_ADDRESS to address,
            KEY_LONGITUDE to longitude.toString().take(5),
            KEY_LATITUDE to latitude.toString().take(5),
            KEY_TAG to tag,
            KEY_LONGITUDE_RAW to longitude.toString(),
            KEY_LATITUDE_RAW to latitude.toString(),
        )
    }

    /**
     * 计算与另一个POI点之间的距离
     * 
     * @param other 另一个POI点
     * @return 距离，单位：米
     */
    fun distanceTo(other: Poi): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLng = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * 计算与指定经纬度点之间的距离
     * 
     * @param lat 目标点纬度
     * @param lng 目标点经度
     * @return 距离，单位：米
     */
    fun distanceTo(lat: Double, lng: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat - latitude)
        val dLng = Math.toRadians(lng - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(latitude)) * cos(Math.toRadians(lat)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
