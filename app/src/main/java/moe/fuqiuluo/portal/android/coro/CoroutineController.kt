package moe.fuqiuluo.portal.android.coro

import kotlinx.coroutines.channels.Channel

/**
 * 协程控制器类
 * 
 * 提供协程的暂停和恢复控制功能，用于管理协程执行流程
 */
class CoroutineController {
    private val controlChannel = Channel<ControlCommand>(Channel.UNLIMITED)
    var isPaused = false

    /**
     * 受控协程挂起点
     * 
     * 在协程中调用此方法，可以检查并响应暂停/恢复控制命令
     */
    suspend fun controlledCoroutine() {
        checkControl()
    }

    /**
     * 检查控制命令
     * 
     * 处理暂停和恢复命令，控制协程执行流程
     */
    private suspend fun checkControl() {
        controlChannel.tryReceive().getOrNull()?.let {
            when (it) {
                ControlCommand.Pause -> {
                    isPaused = true
                    while (controlChannel.receive() != ControlCommand.Resume) {
                        // do nothing
                    }
                    isPaused = false
                }
                ControlCommand.Resume -> {}
            }
        }
    }

    /**
     * 暂停协程执行
     */
    fun pause() {
        controlChannel.trySend(ControlCommand.Pause)
    }

    /**
     * 恢复协程执行
     */
    fun resume() {
        controlChannel.trySend(ControlCommand.Resume)
    }
}

/**
 * 协程控制命令枚举
 * 
 * 定义协程控制命令类型
 */
enum class ControlCommand {
    Pause,
    Resume
}