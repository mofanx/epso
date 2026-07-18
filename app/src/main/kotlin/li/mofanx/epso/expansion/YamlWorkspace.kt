package li.mofanx.epso.expansion

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.mofanx.epso.util.LogUtils
import java.io.File
import java.io.IOException
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
            strictMode = false,                         // 忽略未知字段，保持向前兼容
            encodeDefaults = false,                     // 不输出默认值，保持 YAML 简洁
            encodingIndentationSize = 2,
            sequenceBlockIndent = 2,                    // 列表项缩进两格，和官方 espanso 格式对齐
            allowAnchorsAndAliases = true,              // 启用 YAML anchors/aliases
        )
    )

    // ──────────────────────────────────────────────────────────────
    // 读取
    // ──────────────────────────────────────────────────────────────

    /**
     * 递归加载工作区所有 YAML，展开 imports，返回合并后的规则字典和全局变量。
     * 触发词冲突时，后加载的文件覆盖先加载的（与 espanso 行为一致）。
     *
     * @param defaultPrefix 全局默认触发前缀，用于拆分老数据 trigger 中拼写的前缀
     */
    suspend fun loadAll(defaultPrefix: String = ""): Pair<Map<String, Match>, List<Var>> = withContext(Dispatchers.IO) {
        val matchDict = LinkedHashMap<String, Match>()
        val globalVars = mutableListOf<Var>()
        val visited = HashSet<String>()

        dir.autoMkdir()
        // 递归扫描工作区内所有 YAML 文件（与官方 espanso 默认 includes：
        // ../match/**/[!_]*.yml 行为对齐，文件名以下划线开头的作为私有文件跳过）
        val rootFiles = listAllYamlFiles(dir).filter { !it.name.startsWith("_") }

        for (file in rootFiles) {
            loadRecursive(file, matchDict, globalVars, visited, defaultPrefix, depth = 0)
        }

        matchDict to globalVars
    }

    /**
     * 列出工作区根目录下所有直接 .yml/.yaml 文件。
     */
    fun listRootFiles(): List<File> = listYamlFiles(dir)

    /**
     * 递归列出工作区所有 .yml/.yaml 文件（含子目录），用于文件树 UI。
     */
    fun listFiles(): List<File> = listAllYamlFiles(dir)

    /**
     * 列出工作区根目录下所有直接 .yml/.yaml 文件（兼容旧调用）。
     */
    fun listRootFilesLegacy(): List<File> = listYamlFiles(dir)

    /**
     * 读取单个 YAML 文件 → MatchGroup
     *
     * @param defaultPrefix 全局默认触发前缀，用于拆分老数据 trigger 中拼写的前缀
     */
    suspend fun readFile(file: File, defaultPrefix: String = ""): MatchGroup = withContext(Dispatchers.IO) {
        resolveEffectiveDefaults(parseYaml(file.readText(), file.absolutePath), defaultPrefix)
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
        val segments = name.split('/').filter { it.isNotEmpty() }
        val safeSegments = segments.map { it.replace(Regex("[^a-zA-Z0-9_\\-]"), "_") }
        val file = if (safeSegments.isEmpty()) {
            dir.resolve("untitled.yml")
        } else {
            val parent = safeSegments.dropLast(1).fold(dir) { acc, segment ->
                acc.resolve(segment).autoMkdir()
            }
            parent.resolve("${safeSegments.last()}.yml")
        }
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

    /**
     * 在工作区创建空文件夹
     */
    suspend fun createFolder(name: String): File = withContext(Dispatchers.IO) {
        dir.autoMkdir()
        dir.resolve(name).autoMkdir()
    }

    /**
     * 导入外部 YAML 文件到工作区
     */
    suspend fun importFile(name: String, input: java.io.InputStream): File = withContext(Dispatchers.IO) {
        dir.autoMkdir()
        val file = dir.resolve(name)
        file.parentFile?.autoMkdir()
        file.outputStream().use { output ->
            input.use { it.copyTo(output) }
        }
        file
    }

    /**
     * 删除文件夹（含递归内容）
     */
    suspend fun deleteFolder(folder: File) = withContext(Dispatchers.IO) {
        folder.deleteRecursively()
    }

    /**
     * 移动文件到目标子目录；YAML 文件会同步更新工作区中引用它的 imports 路径
     */
    suspend fun moveFile(source: File, targetDirPath: String, defaultPrefix: String): File = withContext(Dispatchers.IO) {
        val sourceFile = source.absoluteFile
        if (!sourceFile.exists() || sourceFile.isDirectory) {
            throw IOException("Source is not a file: $sourceFile")
        }
        val targetDir = dir.resolve(targetDirPath).autoMkdir()
        val newFile = targetDir.resolve(sourceFile.name)
        if (newFile.absoluteFile == sourceFile.absoluteFile) return@withContext sourceFile
        if (newFile.exists()) throw IOException("Target file already exists: $newFile")

        // 非 YAML 文件直接原样移动，不做 imports 处理
        if (!sourceFile.isYamlFile()) {
            sourceFile.copyTo(newFile)
            if (!sourceFile.delete()) {
                throw IOException("Cannot delete source file: $sourceFile")
            }
            return@withContext newFile
        }

        val group = readFile(sourceFile, defaultPrefix)
        val oldPath = sourceFile.canonicalFile

        val updatedImports = group.imports.map { import ->
            val resolved = resolveImportPath(sourceFile.parentFile ?: dir, import)
            if (resolved.exists()) {
                newRelativePath(resolved.canonicalFile, newFile.parentFile ?: dir, import)
            } else {
                import
            }
        }

        // 先写入目标文件（保留原文件，确保写入失败仍可回退/重试）
        writeFile(newFile, group.copy(imports = updatedImports))
        val newFileCanonical = newFile.canonicalFile

        // 更新工作区其它文件中引用该文件的路径
        val allFiles = listAllYamlFiles(dir).filter { it != newFile }
        for (f in allFiles) {
            val g = readFile(f, defaultPrefix)
            val newImports = g.imports.map { import ->
                val resolved = resolveImportPath(f.parentFile ?: dir, import)
                if (resolved.exists() && resolved.canonicalFile == oldPath) {
                    newRelativePath(newFileCanonical, f.parentFile ?: dir, import)
                } else {
                    import
                }
            }
            if (newImports != g.imports) {
                writeFile(f, g.copy(imports = newImports))
            }
        }

        // 其它文件引用更新完成后再删除原文件
        if (!sourceFile.delete()) {
            throw IOException("Cannot delete source file: $sourceFile")
        }

        newFile
    }

    /**
     * 复制文件到目标子目录；YAML 文件会更新 imports 为相对新位置的相对路径
     */
    suspend fun copyFile(source: File, targetDirPath: String, defaultPrefix: String): File = withContext(Dispatchers.IO) {
        val sourceFile = source.absoluteFile
        if (!sourceFile.exists() || sourceFile.isDirectory) {
            throw IOException("Source is not a file: $sourceFile")
        }
        val targetDir = dir.resolve(targetDirPath).autoMkdir()
        val newFile = uniqueFile(targetDir, sourceFile.nameWithoutExtension, sourceFile.extension)

        // 非 YAML 文件直接原样复制
        if (!sourceFile.isYamlFile()) {
            sourceFile.copyTo(newFile)
            return@withContext newFile
        }

        val group = readFile(sourceFile, defaultPrefix)
        val updatedImports = group.imports.map { import ->
            val resolved = resolveImportPath(sourceFile.parentFile ?: dir, import).normalized()
            if (resolved.exists()) {
                newRelativePath(resolved, newFile.parentFile ?: dir, import)
            } else {
                import
            }
        }

        writeFile(newFile, group.copy(imports = updatedImports))
        newFile
    }

    /**
     * 复制文件夹到目标目录，保持内部 YAML 文件之间的 imports 关系
     */
    suspend fun copyFolder(source: File, targetDirPath: String, defaultPrefix: String): File = withContext(Dispatchers.IO) {
        val sourceFolder = source.absoluteFile
        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            throw IOException("Source is not a folder: $sourceFolder")
        }
        val targetRoot = dir.resolve(targetDirPath).autoMkdir()
        if (targetRoot.normalized().isInside(sourceFolder.normalized())) {
            throw IOException("Cannot copy folder into itself: $sourceFolder")
        }
        val targetFolder = targetFolderForCopyOrMove(sourceFolder, targetRoot, forCopy = true)
        sourceFolder.copyRecursively(targetFolder, overwrite = false, onError = { _, exception -> throw exception })

        val sourceFiles = listAllYamlFiles(sourceFolder)
        val newToSource = sourceFiles.map { it.normalized() }.associate { oldFile ->
            val newFile = targetFolder.resolve(oldFile.relativeTo(sourceFolder.normalized())).normalized()
            newFile to oldFile
        }

        updateFolderImportsAfterCopyOrMove(sourceFolder, targetFolder, newToSource, defaultPrefix)
        targetFolder
    }

    /**
     * 移动文件夹到目标目录，并同步更新工作区其它文件中引用它的 imports 路径
     */
    suspend fun moveFolder(source: File, targetDirPath: String, defaultPrefix: String): File = withContext(Dispatchers.IO) {
        val sourceFolder = source.absoluteFile
        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            throw IOException("Source is not a folder: $sourceFolder")
        }
        val targetRoot = dir.resolve(targetDirPath).autoMkdir()
        if (targetRoot.normalized().isInside(sourceFolder.normalized())) {
            throw IOException("Cannot move folder into itself: $sourceFolder")
        }
        val targetFolder = targetFolderForCopyOrMove(sourceFolder, targetRoot, forCopy = false)
        if (targetFolder.normalized() == sourceFolder.normalized()) {
            return@withContext sourceFolder
        }
        sourceFolder.copyRecursively(targetFolder, overwrite = false, onError = { _, exception -> throw exception })

        val sourceFiles = listAllYamlFiles(sourceFolder)
        val oldToNew = sourceFiles.map { it.normalized() }.associate { oldFile ->
            val newFile = targetFolder.resolve(oldFile.relativeTo(sourceFolder.normalized())).normalized()
            oldFile to newFile
        }
        val newToSource = oldToNew.map { (old, new) -> new to old }.toMap()

        updateFolderImportsAfterCopyOrMove(sourceFolder, targetFolder, newToSource, defaultPrefix)
        updateFilesReferencingMovedFolder(sourceFolder, targetFolder, oldToNew, defaultPrefix)

        sourceFolder.deleteRecursively()
        targetFolder
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
        defaultPrefix: String,
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
            resolveEffectiveDefaults(parseYaml(file.readText(), file.absolutePath), defaultPrefix)
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
                loadRecursive(importFile, dict, vars, visited, defaultPrefix, depth + 1)
            }
        }
    }

    /**
     * 计算每条 Match 的 effectivePrefix / effectiveFilter / effectiveEnable，
     * 并把已拼入 trigger 的老前缀剥离到 prefix 字段外。
     */
    private fun resolveEffectiveDefaults(
        group: MatchGroup,
        defaultPrefix: String,
    ): MatchGroup {
        val groupPrefix = group.prefix ?: defaultPrefix
        val resolved = group.matches.map { match ->
            val effectivePrefix = match.prefix ?: groupPrefix
            val (newTrigger, newTriggers) = if (match.prefix == null) {
                match.trigger.removePrefix(effectivePrefix) to
                    match.triggers.map { it.removePrefix(effectivePrefix) }
            } else {
                match.trigger to match.triggers
            }
            match.copy(trigger = newTrigger, triggers = newTriggers).apply {
                this.effectivePrefix = effectivePrefix
                this.effectiveFilterTitle = match.filterTitle ?: group.filterTitle
                this.effectiveFilterExec = match.filterExec ?: group.filterExec
                this.effectiveFilterClass = match.filterClass ?: group.filterClass
                this.effectiveFilterOs = match.filterOs ?: group.filterOs
                this.effectiveEnable = match.enable ?: group.enable ?: true
                this.effectiveBackend = match.forceMode.takeIf { it.isNotEmpty() } ?: group.backend
            }
        }
        return group.copy(matches = resolved).apply { sourceFile = group.sourceFile }
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

    private fun isYaml(f: File): Boolean =
        f.isFile && (f.extension == "yml" || f.extension == "yaml")

    private fun File.isYamlFile(): Boolean =
        extension.equals("yml", ignoreCase = true) || extension.equals("yaml", ignoreCase = true)

    private fun listYamlFiles(directory: File): List<File> {
        return directory
            .listFiles { f -> isYaml(f) && !f.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun listAllYamlFiles(directory: File): List<File> {
        return directory
            .walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { isYaml(it) && !it.name.startsWith(".") }
            .sortedBy { it.path }
            .toList()
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File("${file.absolutePath}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            if (file.exists() && !file.delete()) throw IOException("无法替换 ${file.name}", e)
            if (!tmp.renameTo(file)) throw IOException("无法写入 ${file.name}", e)
        }
    }

    private fun File.normalized(): File = toPath().normalize().toFile()

    private fun File.isInside(parent: File): Boolean = runCatching {
        toPath().normalize().startsWith(parent.toPath().normalize())
    }.getOrDefault(false)

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        val name = if (ext.isEmpty()) base else "$base.$ext"
        var file = dir.resolve(name)
        if (!file.exists()) return file
        var i = 1
        while (true) {
            val newName = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            file = dir.resolve(newName)
            if (!file.exists()) return file
            i++
        }
    }

    private fun uniqueFolder(parent: File, name: String): File {
        var folder = parent.resolve(name)
        if (!folder.exists()) return folder
        var i = 1
        while (true) {
            folder = parent.resolve("$name ($i)")
            if (!folder.exists()) return folder
            i++
        }
    }

    private fun targetFolderForCopyOrMove(sourceFolder: File, targetRoot: File, forCopy: Boolean): File {
        val candidate = targetRoot.resolve(sourceFolder.name)
        val sourceNormalized = sourceFolder.normalized()
        val candidateNormalized = candidate.normalized()
        if (candidateNormalized == sourceNormalized) {
            if (!forCopy) return sourceFolder
            return uniqueFolder(targetRoot, sourceFolder.name + " (copy)")
        }
        if (!candidate.exists()) return candidate
        if (forCopy) return uniqueFolder(targetRoot, sourceFolder.name + " (copy)")
        return uniqueFolder(targetRoot, sourceFolder.name)
    }

    private suspend fun updateFolderImportsAfterCopyOrMove(
        sourceFolder: File,
        targetFolder: File,
        newToSource: Map<File, File>,
        defaultPrefix: String,
    ) {
        for ((targetFile, sourceFile) in newToSource) {
            val group = readFile(targetFile, defaultPrefix)
            val updatedImports = group.imports.map { import ->
                val resolved = resolveImportPath(sourceFile.parentFile ?: sourceFolder, import).normalized()
                val newImported = when {
                    resolved.isInside(sourceFolder.normalized()) ->
                        targetFolder.resolve(resolved.relativeTo(sourceFolder.normalized())).normalized()
                    resolved.exists() -> resolved
                    else -> null
                }
                if (newImported != null) {
                    newRelativePath(newImported, targetFile.parentFile ?: targetFolder, import)
                } else {
                    import
                }
            }
            if (updatedImports != group.imports) {
                writeFile(targetFile, group.copy(imports = updatedImports))
            }
        }
    }

    private suspend fun updateFilesReferencingMovedFolder(
        sourceFolder: File,
        targetFolder: File,
        oldToNew: Map<File, File>,
        defaultPrefix: String,
    ) {
        val allFiles = listAllYamlFiles(dir).filter { !it.normalized().isInside(targetFolder.normalized()) }
        for (f in allFiles) {
            val group = readFile(f, defaultPrefix)
            val updatedImports = group.imports.map { import ->
                val resolved = resolveImportPath(f.parentFile ?: dir, import).normalized()
                val targetFile = oldToNew[resolved]
                if (targetFile != null) {
                    newRelativePath(targetFile, f.parentFile ?: dir, import)
                } else if (resolved.exists()) {
                    newRelativePath(resolved, f.parentFile ?: dir, import)
                } else {
                    import
                }
            }
            if (updatedImports != group.imports) {
                writeFile(f, group.copy(imports = updatedImports))
            }
        }
    }
}

private fun File.autoMkdir(): File {
    if (!exists()) mkdirs()
    return this
}

private fun newRelativePath(target: File, base: File, fallback: String): String {
    return runCatching { target.relativeTo(base).invariantSeparatorsPath }.getOrDefault(fallback)
}

internal fun File.isValidWorkspace(): Boolean {
    if (isFile) return false
    if (!exists() && !mkdirs()) return false
    return canWrite()
}
