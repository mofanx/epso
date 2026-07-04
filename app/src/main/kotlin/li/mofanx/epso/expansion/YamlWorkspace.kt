package li.mofanx.epso.expansion

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.util.LogUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = "YamlWorkspace"
private const val MAX_IMPORT_DEPTH = 10

/**
 * YAML 工作区
 *
 * 负责管理 espanso 兼容的 YAML 多文件工作区：
 * - 递归读取目录下所有 .yml/.yaml 文件
 * - 处理 imports 递归引用（最大深度 10，防循环）
 * - 将 MatchGroup 写回 YAML（保留 espanso 格式）
 * - 文件增删管理
 *
 * 文件结构示例：
 * ```
 * workspace/
 *   base.yml         # 包含 global_vars 和 matches
 *   email.yml        # 邮件相关规则
 *   packages/        # Hub 安装的包（可扫描子目录）
 *     espanso-package-lorem/
 *       package.yml
 * ```
 */
class YamlWorkspace(val dir: File) {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,         // 忽略未知字段，保持向前兼容
            encodeDefaults = false,     // 不输出默认值，保持 YAML 简洁
            encodingIndentationSize = 2,
        )
    )

    // ──────────────────────────────────────────────────────────────
    // 读取
    // ──────────────────────────────────────────────────────────────

    /**
     * 递归加载工作区所有 YAML，展开 imports，返回合并后的规则字典和全局变量。
     * 触发词冲突时，后加载的文件覆盖先加载的（与 espanso 行为一致）。
     */
    suspend fun loadAll(): Pair<Map<String, Match>, List<Var>> = withContext(Dispatchers.IO) {
        val matchDict = LinkedHashMap<String, Match>()
        val globalVars = mutableListOf<Var>()
        val visited = HashSet<String>()

        dir.autoMkdir()
        val rootFiles = listYamlFiles(dir)

        for (file in rootFiles) {
            loadRecursive(file, matchDict, globalVars, visited, depth = 0)
        }

        matchDict to globalVars
    }

    /**
     * 列出工作区根目录下所有直接 .yml/.yaml 文件（不含子目录）。
     * 子目录用于 Hub 包，由 imports 机制引用。
     */
    fun listFiles(): List<File> {
        dir.autoMkdir()
        return listYamlFiles(dir)
    }

    /**
     * 读取单个 YAML 文件 → MatchGroup
     */
    suspend fun readFile(file: File): MatchGroup = withContext(Dispatchers.IO) {
        parseYaml(file.readText(), file.absolutePath)
    }

    // ──────────────────────────────────────────────────────────────
    // 写入
    // ──────────────────────────────────────────────────────────────

    /**
     * 将 MatchGroup 写入指定 YAML 文件（原子写入，通过临时文件避免损坏）
     */
    suspend fun writeFile(file: File, group: MatchGroup) = withContext(Dispatchers.IO) {
        val text = encodeGroup(group)
        writeAtomic(file, text)
        LogUtils.d(TAG, "Written ${group.matches.size} matches to ${file.name}")
    }

    /**
     * 在工作区创建新规则文件
     * @param name 文件名（不含扩展名），如 "email"
     */
    suspend fun createFile(name: String): File = withContext(Dispatchers.IO) {
        dir.autoMkdir()
        val safeName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val file = dir.resolve("$safeName.yml")
        if (!file.exists()) {
            writeFile(file, MatchGroup())
        }
        file
    }

    /**
     * 删除规则文件
     */
    fun deleteFile(file: File) {
        if (file.exists() && file.isFile) {
            file.delete()
            LogUtils.d(TAG, "Deleted ${file.name}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 解析 / 序列化
    // ──────────────────────────────────────────────────────────────

    /**
     * 解析 YAML 文本 → MatchGroup
     */
    fun parseYaml(text: String, sourceFile: String = ""): MatchGroup {
        if (text.isBlank()) return MatchGroup().also { it.sourceFile = sourceFile }
        return try {
            yaml.decodeFromString(MatchGroup.serializer(), text).also {
                it.sourceFile = sourceFile
            }
        } catch (e: YamlException) {
            LogUtils.e(TAG, "Failed to parse YAML: $sourceFile", e)
            MatchGroup().also { it.sourceFile = sourceFile }
        }
    }

    /**
     * MatchGroup → YAML 文本
     */
    fun encodeGroup(group: MatchGroup): String {
        return yaml.encodeToString(MatchGroup.serializer(), group)
    }

    // ──────────────────────────────────────────────────────────────
    // 私有方法
    // ──────────────────────────────────────────────────────────────

    private suspend fun loadRecursive(
        file: File,
        dict: LinkedHashMap<String, Match>,
        vars: MutableList<Var>,
        visited: HashSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_IMPORT_DEPTH) {
            LogUtils.e(TAG, "Import depth exceeded ($MAX_IMPORT_DEPTH): ${file.absolutePath}")
            return
        }

        val canonical = file.canonicalPath
        if (!visited.add(canonical)) return  // 防止循环 import
        if (!file.exists() || !file.isFile) return

        val group = withContext(Dispatchers.IO) {
            parseYaml(file.readText(), file.absolutePath)
        }

        // 合并全局变量（同名后者覆盖）
        for (v in group.globalVars) {
            vars.removeAll { it.name == v.name }
            vars.add(v)
        }

        // 合并 match 规则（同触发词后者覆盖）
        for (match in group.matches) {
            for (trigger in match.allTriggers) {
                dict[trigger] = match
            }
            if (match.isRegex) {
                // 正则规则用 regex 内容作 key，避免重复
                dict["__regex__${match.regex}"] = match
            }
        }

        // 递归处理 imports
        if (group.imports.isNotEmpty()) {
            val baseDir = file.parentFile ?: dir
            for (importPath in group.imports) {
                val importFile = resolveImportPath(baseDir, importPath)
                loadRecursive(importFile, dict, vars, visited, depth + 1)
            }
        }
    }

    /**
     * 解析 import 路径（支持相对路径和绝对路径）
     */
    private fun resolveImportPath(baseDir: File, importPath: String): File {
        return if (importPath.startsWith("/")) {
            File(importPath)
        } else {
            baseDir.resolve(importPath)
        }
    }

    private fun listYamlFiles(directory: File): List<File> {
        return directory
            .listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File("${file.absolutePath}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}

private fun File.autoMkdir(): File {
    if (!exists()) mkdirs()
    return this
}
