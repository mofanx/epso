package li.mofanx.epso.expansion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.mofanx.epso.appScope
import li.mofanx.epso.expansion.sync.SyncManager
import li.mofanx.epso.expansion.sync.toSyncConfig
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.util.LogUtils
import li.mofanx.epso.util.NetworkUtils
import li.mofanx.epso.util.launchTry
import li.mofanx.epso.util.matchesFolder
import java.io.File

private const val TAG = "MatchStore"

/**
 * 运行时规则仓库（单例）
 *
 * 职责：
 * 1. 持有当前已加载的 [matchDict]（trigger → Match）和 [globalVars]
 * 2. 持有 [groups]（文件管理 UI 使用）
 * 3. 对外暴露 StateFlow，供 ExpansionService 和 UI 订阅
 * 4. 提供规则的增删改接口，修改后立即持久化到对应 YAML 文件
 *
 * 工作区目录默认为 `<filesDir>/matches/`，
 * 用户可通过 SettingsStore.expansionWorkspacePath 自定义（空=默认路径）。
 */
object MatchStore {

    private var workspace: YamlWorkspace = YamlWorkspace(matchesFolder)

    /** 序列化所有文件写入操作，防止并发 read-modify-write 竞争 */
    private val writeMutex = Mutex()

    /** 序列化自动同步请求，避免多个写操作并发 push 导致状态文件竞争 */
    private val autoPushMutex = Mutex()

    // ── 对外 StateFlow ──────────────────────────────────────────────

    /** 触发词 → Match 的扁平化字典（含正则规则，key 为 __regex__<pattern>） */
    private val _matchDict = MutableStateFlow<Map<String, Match>>(emptyMap())
    val matchDict: StateFlow<Map<String, Match>> = _matchDict.asStateFlow()

    /** 全局变量列表（合并所有文件） */
    private val _globalVars = MutableStateFlow<List<Var>>(emptyList())
    val globalVars: StateFlow<List<Var>> = _globalVars.asStateFlow()

    /** 所有 YAML 文件对应的 MatchGroup（供文件管理 UI 使用） */
    private val _groups = MutableStateFlow<List<MatchGroup>>(emptyList())
    val groups: StateFlow<List<MatchGroup>> = _groups.asStateFlow()

    /** 仅包含精确触发词规则（正则规则被过滤掉） */
    val exactMatches: Map<String, Match>
        get() = _matchDict.value.filterKeys { !it.startsWith("__regex__") }

    /** 仅包含正则规则 */
    val regexMatches: Map<String, Match>
        get() = _matchDict.value.filterKeys { it.startsWith("__regex__") }

    val matchCount: Int
        get() = _matchDict.value.values.distinct().size

    // ── 初始化 ──────────────────────────────────────────────────────

    /**
     * 初始化工作区，通常在 App.onCreate 中调用。
     * 如果工作区为空，自动创建一个默认的 base.yml。
     */
    fun init(workspacePath: String = "") {
        updateWorkspace(workspacePath)
        appScope.launchTry(Dispatchers.IO) {
            ensureDefaultFile()
            runInitialPrefixMigration()
            reload()
            // 全局前缀/工作区路径变化时自动重载
            collectSettings()
        }
    }

    /**
     * 应用启动时检测触发前缀是否发生过变化；若变化则对旧规则做一次一次性迁移。
     */
    private suspend fun runInitialPrefixMigration() {
        val store = storeFlow.value
        if (store.triggerPrefix != store.lastTriggerPrefix) {
            writeMutex.withLock {
                workspace.migratePrefix(store.lastTriggerPrefix, store.triggerPrefix)
            }
            storeFlow.value = store.copy(lastTriggerPrefix = store.triggerPrefix)
        }
    }

    /**
     * 确保 base.yml 存在并返回该文件（供 UI 兜底使用）
     */
    suspend fun ensureBaseFile(): File = withContext(Dispatchers.IO) {
        ensureDefaultFile()
        workspace.listRootFiles().firstOrNull() ?: workspace.createFile("base")
    }

    /**
     * 重新从磁盘加载所有规则
     */
    suspend fun reload() {
        LogUtils.d(TAG, "Reloading workspace: ${workspace.dir.absolutePath}")
        val (dict, vars) = workspace.loadAll(defaultPrefix)
        _matchDict.value = dict
        _globalVars.value = vars
        refreshGroups()
        LogUtils.d(TAG, "Loaded ${dict.values.distinct().size} matches, ${vars.size} global vars")
    }

    // ── 规则 CRUD ───────────────────────────────────────────────────

    /**
     * 向指定文件添加规则
     */
    suspend fun addMatch(groupFile: File, match: Match) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile, defaultPrefix)
                val normalized = match.normalizedForGroup(group, defaultPrefix)
                workspace.writeFile(groupFile, group.copy(matches = group.matches + normalized))
            }
        }
        reload()
        autoPush()
    }

    /**
     * 更新规则（通过对象相等性定位旧规则）
     */
    suspend fun updateMatch(groupFile: File, oldMatch: Match, newMatch: Match) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile, defaultPrefix)
                val search = oldMatch.normalizedForGroup(group, defaultPrefix)
                val replacement = newMatch.normalizedForGroup(group, defaultPrefix)
                workspace.writeFile(groupFile, group.copy(
                    matches = group.matches.map { if (it == search) replacement else it }
                ))
            }
        }
        reload()
        autoPush()
    }

    /**
     * 删除规则
     */
    suspend fun deleteMatch(groupFile: File, match: Match) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile, defaultPrefix)
                val normalized = match.normalizedForGroup(group, defaultPrefix)
                workspace.writeFile(groupFile, group.copy(
                    matches = group.matches.filter { it != normalized }
                ))
            }
        }
        reload()
        autoPush()
    }

    /**
     * 按索引更新指定文件中的规则
     */
    suspend fun updateMatchByIndex(groupFile: File, index: Int, match: Match) {
        require(index >= 0) { "rule index must be non-negative" }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile, defaultPrefix)
                require(index < group.matches.size) { "rule index out of bounds" }
                val normalized = match.normalizedForGroup(group, defaultPrefix)
                val newMatches = group.matches.toMutableList().apply { this[index] = normalized }
                workspace.writeFile(groupFile, group.copy(matches = newMatches))
            }
        }
        reload()
        autoPush()
    }

    /**
     * 按索引删除指定文件中的规则
     */
    suspend fun deleteMatchByIndex(groupFile: File, index: Int) {
        require(index >= 0) { "rule index must be non-negative" }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile, defaultPrefix)
                require(index < group.matches.size) { "rule index out of bounds" }
                workspace.writeFile(groupFile, group.copy(
                    matches = group.matches.filterIndexed { i, _ -> i != index }
                ))
            }
        }
        reload()
        autoPush()
    }

    /**
     * 更新某文件的全局变量
     */
    suspend fun updateGlobalVars(groupFile: File, vars: List<Var>) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile, defaultPrefix)
                workspace.writeFile(groupFile, group.copy(globalVars = vars))
            }
        }
        reload()
        autoPush()
    }

    // ── 文件管理 ────────────────────────────────────────────────────

    /**
     * 创建新规则文件
     */
    suspend fun createFile(name: String): File {
        val file = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.createFile(name)
            }
        }
        reload()
        autoPush()
        return file
    }

    /**
     * 删除规则文件
     */
    suspend fun deleteFile(file: File) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.deleteFile(file)
            }
        }
        reload()
        autoPush()
    }

    /**
     * 创建空文件夹
     */
    suspend fun createFolder(name: String): File {
        val folder = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.createFolder(name)
            }
        }
        reload()
        autoPush()
        return folder
    }

    /**
     * 导入外部 YAML 文件到工作区
     */
    suspend fun importFile(name: String, input: java.io.InputStream) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.importFile(name, input)
            }
        }
        reload()
        autoPush()
    }

    /**
     * 删除文件夹（含递归内容）
     */
    suspend fun deleteFolder(folder: File) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.deleteFolder(folder)
            }
        }
        reload()
        autoPush()
    }

    /**
     * 移动规则文件到目标文件夹，并同步更新引用它的 imports
     */
    suspend fun moveFile(sourceFile: File, targetDir: String) {
        val newFile = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.moveFile(sourceFile, targetDir, defaultPrefix)
            }
        }
        if (newFile.absoluteFile == sourceFile.absoluteFile) {
            return
        }
        reload()
        autoPush()
    }

    /**
     * 复制规则文件到目标文件夹
     */
    suspend fun copyFile(sourceFile: File, targetDir: String): File {
        val file = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.copyFile(sourceFile, targetDir, defaultPrefix)
            }
        }
        reload()
        autoPush()
        return file
    }

    /**
     * 复制文件夹到目标目录
     */
    suspend fun copyFolder(sourceFolder: File, targetDir: String): File {
        val folder = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.copyFolder(sourceFolder, targetDir, defaultPrefix)
            }
        }
        reload()
        autoPush()
        return folder
    }

    /**
     * 移动文件夹到目标目录
     */
    suspend fun moveFolder(sourceFolder: File, targetDir: String): File {
        val folder = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                workspace.moveFolder(sourceFolder, targetDir, defaultPrefix)
            }
        }
        if (folder.absoluteFile == sourceFolder.absoluteFile) {
            return folder
        }
        reload()
        autoPush()
        return folder
    }

    /**
     * 获取工作区目录
     */
    fun getWorkspaceDir(): File = workspace.dir

    /**
     * 列出工作区内所有 YAML 文件的相对路径（不含扩展名）
     */
    fun listFilePaths(): List<String> = workspace.listFiles().map { file ->
        file.relativeTo(workspace.dir).path
            .removeSuffix(".yml")
            .removeSuffix(".yaml")
    }

    /**
     * 读取 YAML 文件原始文本
     */
    suspend fun readRawFile(file: File): String = withContext(Dispatchers.IO) {
        file.readText()
    }

    /**
     * 验证 YAML 文本是否能解析为 MatchGroup
     */
    fun validateRawYaml(content: String, sourceFile: String = ""): MatchGroup {
        return workspace.parseYaml(content, sourceFile)
    }

    /**
     * 直接写入原始 YAML 文本并重新加载
     */
    suspend fun writeRawFile(file: File, content: String): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            validateRawYaml(content, file.absolutePath)
            workspace.writeRawFile(file, content)
        }
        reload()
        autoPush()
        true
    }

    /**
     * 删除文件并重新加载
     */
    suspend fun deleteRawFile(file: File): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            workspace.deleteFile(file)
        }
        reload()
        autoPush()
        true
    }

    /**
     * 保存后自动推送（在每次写操作后调用）
     */
    private fun autoPush() {
        val store = storeFlow.value
        if (!store.syncAutoOnSave || store.syncMethod == "None") return
        if (store.syncWifiOnly && !NetworkUtils.isWifiConnected()) return
        val config = store.toSyncConfig()
        val manager = SyncManager(config) ?: return
        val localDir = getWorkspaceDir()
        appScope.launchTry(Dispatchers.IO) {
            autoPushMutex.withLock {
                manager.push(localDir)
            }
        }
    }

    // ── 包管理（Hub） ────────────────────────────────────────────

    /**
     * 安装包后将其 package.yml 路径加入 base.yml 的 imports 列表
     *
     * @param packageName 包名，对应 packages/<packageName>/package.yml
     */
    suspend fun addPackageImport(packageName: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val baseFile = workspace.listRootFiles().firstOrNull() ?: workspace.createFile("base")
                val group = workspace.readFile(baseFile, defaultPrefix)
                val importPath = "packages/$packageName/package.yml"
                if (importPath !in group.imports) {
                    workspace.writeFile(baseFile, group.copy(imports = group.imports + importPath))
                }
            }
        }
        reload()
        autoPush()
    }

    /**
     * 卸载包后从 base.yml imports 中移除对应路径
     */
    suspend fun removePackageImport(packageName: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val baseFile = workspace.listRootFiles().firstOrNull() ?: return@withContext
                val group = workspace.readFile(baseFile, defaultPrefix)
                val importPath = "packages/$packageName/package.yml"
                if (importPath in group.imports) {
                    workspace.writeFile(baseFile, group.copy(imports = group.imports - importPath))
                }
            }
        }
        reload()
        autoPush()
    }

    // ── 私有 ────────────────────────────────────────────────────────

    /**
     * 刷新 groups（从磁盘读取每个文件的 MatchGroup，用于 UI 展示）
     */
    private suspend fun refreshGroups() = withContext(Dispatchers.IO) {
        val files = workspace.listFiles()
        _groups.value = files.map { file ->
            workspace.readFile(file, defaultPrefix)
        }
    }

    /**
     * 工作区为空时，创建默认的 base.yml（避免用户面对空目录无从下手）
     */
    private suspend fun ensureDefaultFile() = withContext(Dispatchers.IO) {
        if (workspace.listFiles().isEmpty()) {
            workspace.createFile("base")
            LogUtils.d(TAG, "Created default base.yml")
        }
    }

    private val defaultPrefix: String
        get() = storeFlow.value.triggerPrefix

    private fun updateWorkspace(path: String) {
        val dir = if (path.isNotEmpty()) File(path) else matchesFolder
        workspace = if (dir.isValidWorkspace()) {
            YamlWorkspace(dir)
        } else {
            LogUtils.e(TAG, "工作区不可用，使用默认路径: $matchesFolder")
            YamlWorkspace(matchesFolder)
        }
    }

    private fun collectSettings() {
        appScope.launchTry(Dispatchers.IO) {
            var previousPrefix = storeFlow.value.triggerPrefix
            storeFlow.collect { store ->
                updateWorkspace(store.expansionWorkspacePath)
                ensureDefaultFile()
                val currentPrefix = store.triggerPrefix
                if (currentPrefix != previousPrefix) {
                    writeMutex.withLock {
                        workspace.migratePrefix(previousPrefix, currentPrefix)
                    }
                    val migrated = store.copy(lastTriggerPrefix = currentPrefix)
                    appScope.launch(Dispatchers.IO) { storeFlow.value = migrated }.join()
                    previousPrefix = currentPrefix
                }
                reload()
            }
        }
    }
}
