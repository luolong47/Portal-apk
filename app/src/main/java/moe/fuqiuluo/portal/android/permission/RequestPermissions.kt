package moe.fuqiuluo.portal.android.permission

import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.channels.Channel
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import android.content.Context
import android.content.pm.PackageManager

// https://medium.com/@wind.orca.pe/handling-android-runtime-permissions-with-coroutines-and-suspend-functions-5b4aa4e74ee5

/**
 * 权限请求工具类
 * 
 * 使用协程简化Android运行时权限请求流程，提供挂起函数风格的权限请求API
 * 
 * @param activity 当前活动实例
 */
class RequestPermissions(
    activity: AppCompatActivity
) {
    private val activityResultLauncher = with(activity) {
        registerForActivityResult(RequestMultiplePermissions()) { result ->
            val m = result.mapValues { (key, value) ->
                if (value) {
                    PermissionChecker.State.Granted
                } else {
                    PermissionChecker.State.Denied(shouldShowRequestPermissionRationale(key))
                }
            }

            channel.trySend(PermissionChecker.Result(m))
        }
    }

    private val channel = Channel<PermissionChecker.Result>(1)

    /**
     * 请求权限
     * 
     * 使用协程挂起函数方式请求权限，等待用户响应后返回结果
     * 
     * @param permissions 需要请求的权限集合
     * @return 权限请求结果，包含已授予和已拒绝的权限
     */
    suspend fun request(permissions: Set<String>): PermissionChecker.Result {
        activityResultLauncher.launch(permissions.toTypedArray())

        return channel.receive()
    }

}

/**
 * 权限检查器接口
 * 
 * 提供权限状态检查和权限请求结果处理功能
 */
interface PermissionChecker {
    /**
         * 权限请求结果类
         * 
         * 封装权限请求结果，提供便捷的解构函数获取已授予和已拒绝的权限
         */
        class Result(m: Map<String, State>) : Map<String, State> by HashMap(m) {
        operator fun component1(): Set<String> = granted()
        operator fun component2(): Set<String> = denied()

        private fun denied() = filterValues(State::isDenied).keys
        private fun granted() = filterValues(State::isGranted).keys
    }

    /**
         * 权限状态接口
         * 
         * 定义权限的授予和拒绝状态
         */
        sealed interface State {
        val shouldShowRequestPermissionRationale: Boolean

        fun isGranted() = this is Granted
        fun isDenied() = this is Denied

        /**
             * 权限已授予状态
             */
            data object Granted : State {
            override val shouldShowRequestPermissionRationale: Boolean = false
        }

        /**
             * 权限被拒绝状态
             * 
             * @param shouldShowRequestPermissionRationale 是否应该显示权限请求说明
             */
            data class Denied(
                override val shouldShowRequestPermissionRationale: Boolean
            ) : State
    }

    /**
     * 检查多个权限是否已授予
     * 
     * @param permissions 需要检查的权限数组
     * @return 所有权限是否都已授予
     */
    fun Context.checkSelfMultiplePermissions(permissions: Array<out String>): Boolean {
        return permissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查单个权限是否已授予
     * 
     * @param permission 需要检查的权限
     * @return 权限是否已授予
     */
    fun Context.checkSelfSinglePermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}