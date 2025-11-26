package moe.fuqiuluo.portal.android.root

/**
 * Shell命令工具类
 * 
 * 提供Root权限检查和Shell命令执行功能，用于系统级操作
 */
object ShellUtils {
    /**
     * 检查设备是否具有Root权限
     * 
     * @return 是否具有Root权限
     */
    fun hasRoot(): Boolean {
        val runtime = Runtime.getRuntime()
        try {
            val process = runtime.exec("su")
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor()
            return process.exitValue() == 0
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 设置SELinux强制模式
     * 
     * @param enabled 是否启用强制模式
     */
    fun setEnforceMode(enabled: Boolean) {
        val runtime = Runtime.getRuntime()
        try {
            val process = runtime.exec("su")
            process.outputStream.write("setenforce ${if (enabled) "1" else "0"}\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 执行Shell命令并返回字符串结果
     * 
     * @param command 要执行的Shell命令
     * @return 命令执行结果字符串
     */
    fun executeCommand(command: String): String {
        val runtime = Runtime.getRuntime()
        try {
            val process = runtime.exec("su")
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor()
            if (process.exitValue() != 0) {
                return process.errorStream.bufferedReader().readText()
            }
            return process.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    /**
     * 执行Shell命令并返回字节数组结果
     * 
     * @param command 要执行的Shell命令
     * @return 命令执行结果字节数组
     */
    fun executeCommandToBytes(command: String): ByteArray {
        val runtime = Runtime.getRuntime()
        try {
            val process = runtime.exec("su")
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor()
            if (process.exitValue() != 0) {
                return process.errorStream.use { it.readBytes() }
            }
            return process.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            return ByteArray(0)
        }
    }
}