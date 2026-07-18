package li.mofanx.epso.expansion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.app
import li.mofanx.epso.util.LogUtils
import java.io.File

private const val TAG = "ScriptRunner"

/**
 * 执行 script 类型变量。
 *
 * 与官方 espanso 行为对齐：
 * - args[0] 为可执行文件或脚本路径
 * - 替换 %HOME% / %CONFIG% / %PACKAGES% 路径占位符
 * - 支持 inject_vars 注入环境变量
 * - 支持 trim / debug / ignore_error
 *
 * Android 限制：
 * - 脚本需要可执行权限或被 sh 解释
 * - Python/Node 等脚本需目标解释器已安装（可通过 Termux）
 */
object ScriptRunner {

    suspend fun run(
        args: List<String>,
        values: Map<String, Any?> = emptyMap(),
        injectVars: Boolean = true,
        trim: Boolean = true,
        debug: Boolean = false,
        ignoreError: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (args.isEmpty()) {
            LogUtils.e(TAG, "Empty script args")
            return@withContext null
        }

        val resolvedArgs = args.map { resolvePathPlaceholders(it) }
        val command = resolvedArgs[0]
        val commandArgs = if (resolvedArgs.size > 1) resolvedArgs.subList(1, resolvedArgs.size) else emptyList()

        if (debug) LogUtils.d(TAG, "Script exec: $command ${commandArgs.joinToString(" ")}")

        val pb = ProcessBuilder(listOf(command) + commandArgs)
        pb.directory(safeWorkingDir())
        if (injectVars) {
            pb.environment().putAll(envFromValues(values))
        }

        try {
            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()

            if (debug) {
                LogUtils.d(TAG, "Script exit=$exit stdout=${stdout.take(200)} stderr=${stderr.take(200)}")
            }

            if (!ignoreError && exit != 0) {
                LogUtils.e(TAG, "Script failed (exit=$exit): $stderr")
                return@withContext null
            }

            if (stderr.isNotBlank()) {
                if (ignoreError) {
                    LogUtils.d(TAG, "Script stderr ignored: $stderr")
                } else {
                    LogUtils.e(TAG, "Script stderr: $stderr")
                }
            }

            if (trim) stdout.trim() else stdout
        } catch (e: Exception) {
            LogUtils.e(TAG, "Script execution failed", e)
            null
        }
    }

    /**
     * 将 %HOME% / %CONFIG% / %PACKAGES% 替换为实际路径。
     * 应用未初始化时（如单元测试）保持原样。
     */
    private fun resolvePathPlaceholders(arg: String): String {
        return try {
            val home = app.filesDir.absolutePath
            val config = MatchStore.getWorkspaceDir().absolutePath
            val packages = File(config, "packages").absolutePath
            arg
                .replace("%HOME%", home)
                .replace("%CONFIG%", config)
                .replace("%PACKAGES%", packages)
        } catch (e: Throwable) {
            arg
        }
    }

    /**
     * 获取工作目录，优先使用 MatchStore 工作区；未初始化时（如单元测试）回退到临时目录。
     */
    private fun safeWorkingDir(): File {
        return try {
            MatchStore.getWorkspaceDir()
        } catch (e: Throwable) {
            File(System.getProperty("java.io.tmpdir") ?: ".")
        }
    }
}
