package li.mofanx.epso.expansion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.util.LogUtils
import java.io.File

private const val TAG = "ShellRunner"

/**
 * 执行 shell 类型变量。
 *
 * Android 上没有完整 Linux 环境，因此：
 * - 默认使用 /system/bin/sh
 * - 指定 bash/zsh 时优先查找系统已知路径，找不到则降级到 sh
 * - Windows 专用 shell（cmd/powershell/wsl）统一回退到 sh 并打印警告
 */
object ShellRunner {

    suspend fun run(
        cmd: String,
        shell: String? = null,
        values: Map<String, Any?> = emptyMap(),
        injectVars: Boolean = true,
        trim: Boolean = true,
        debug: Boolean = false,
        ignoreError: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (cmd.isBlank()) {
            LogUtils.e(TAG, "Empty shell command")
            return@withContext null
        }

        val resolvedShell = resolveShell(shell)
        if (debug) LogUtils.d(TAG, "Shell exec: $resolvedShell -c $cmd")

        val pb = ProcessBuilder(listOf(resolvedShell, "-c", cmd))
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
                LogUtils.d(TAG, "Shell exit=$exit stdout=${stdout.take(200)} stderr=${stderr.take(200)}")
            }

            if (!ignoreError && exit != 0) {
                LogUtils.e(TAG, "Shell failed (exit=$exit): $stderr")
                return@withContext null
            }

            if (stderr.isNotBlank()) {
                if (ignoreError) {
                    LogUtils.d(TAG, "Shell stderr ignored: $stderr")
                } else {
                    LogUtils.e(TAG, "Shell stderr: $stderr")
                }
            }

            if (trim) stdout.trim() else stdout
        } catch (e: Exception) {
            LogUtils.e(TAG, "Shell execution failed", e)
            null
        }
    }

    private fun resolveShell(shell: String?): String {
        val requested = shell?.lowercase()?.trim() ?: "sh"
        if (requested == "sh") return "/system/bin/sh"

        // Windows / WSL 专用 shell 在 Android 上不可用，降级到 sh
        if (requested in setOf("cmd", "powershell", "pwsh", "wsl", "wsl2", "nu")) {
            LogUtils.e(TAG, "Shell '$requested' is not available on Android, fallback to sh")
            return "/system/bin/sh"
        }

        return findExecutable(requested) ?: "/system/bin/sh"
    }

    private fun findExecutable(name: String): String? {
        val searchPaths = listOf(
            "/system/bin",
            "/system/xbin",
            "/vendor/bin",
            "/data/data/com.termux/files/usr/bin",
        )
        for (dir in searchPaths) {
            val file = File(dir, name)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }
        LogUtils.e(TAG, "Shell executable '$name' not found, fallback to sh")
        return null
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
