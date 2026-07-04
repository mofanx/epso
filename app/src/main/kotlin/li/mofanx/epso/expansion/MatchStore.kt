package li.mofanx.epso.expansion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.mofanx.epso.appScope
import li.mofanx.epso.util.LogUtils
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
        val dir = if (workspacePath.isNotEmpty()) File(workspacePath) else matchesFolder
        workspace = YamlWorkspace(dir)
        appScope.launchTry(Dispatchers.IO) {
            ensureDefaultFile()
            reload()
        }
    }

    /**
     * 重新从磁盘加载所有规则
     */
    suspend fun reload() {
        LogUtils.d(TAG, "Reloading workspace: ${workspace.dir.absolutePath}")
        val (dict, vars) = workspace.loadAll()
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
                val group = workspace.readFile(groupFile)
                workspace.writeFile(groupFile, group.copy(matches = group.matches + match))
            }
        }
        reload()
    }

    /**
     * 更新规则（通过对象相等性定位旧规则）
     */
    suspend fun updateMatch(groupFile: File, oldMatch: Match, newMatch: Match) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile)
                workspace.writeFile(groupFile, group.copy(
                    matches = group.matches.map { if (it == oldMatch) newMatch else it }
                ))
            }
        }
        reload()
    }

    /**
     * 删除规则
     */
    suspend fun deleteMatch(groupFile: File, match: Match) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile)
                workspace.writeFile(groupFile, group.copy(
                    matches = group.matches.filter { it != match }
                ))
            }
        }
        reload()
    }

    /**
     * 更新某文件的全局变量
     */
    suspend fun updateGlobalVars(groupFile: File, vars: List<Var>) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val group = workspace.readFile(groupFile)
                workspace.writeFile(groupFile, group.copy(globalVars = vars))
            }
        }
        reload()
    }

    // ── 文件管理 ────────────────────────────────────────────────────

    /**
     * 创建新规则文件
     */
    suspend fun createFile(name: String): File {
        val file = workspace.createFile(name)
        reload()
        return file
    }

    /**
     * 删除规则文件
     */
    suspend fun deleteFile(file: File) {
        workspace.deleteFile(file)
        reload()
    }

    /**
     * 获取工作区目录
     */
    fun getWorkspaceDir(): File = workspace.dir

    // ── 包管理（Hub） ────────────────────────────────────────────

    /**
     * 安装包后将其 package.yml 路径加入 base.yml 的 imports 列表
     *
     * @param packageName 包名，对应 packages/<packageName>/package.yml
     */
    suspend fun addPackageImport(packageName: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val baseFile = workspace.listFiles().firstOrNull() ?: return@withContext
                val group = workspace.readFile(baseFile)
                val importPath = "packages/$packageName/package.yml"
                if (importPath !in group.imports) {
                    workspace.writeFile(baseFile, group.copy(imports = group.imports + importPath))
                }
            }
        }
        reload()
    }

    /**
     * 卸载包后从 base.yml imports 中移除对应路径
     */
    suspend fun removePackageImport(packageName: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val baseFile = workspace.listFiles().firstOrNull() ?: return@withContext
                val group = workspace.readFile(baseFile)
                val importPath = "packages/$packageName/package.yml"
                if (importPath in group.imports) {
                    workspace.writeFile(baseFile, group.copy(imports = group.imports - importPath))
                }
            }
        }
        reload()
    }

    // ── 私有 ────────────────────────────────────────────────────────

    /**
     * 刷新 groups（从磁盘读取每个文件的 MatchGroup，用于 UI 展示）
     */
    private suspend fun refreshGroups() = withContext(Dispatchers.IO) {
        val files = workspace.listFiles()
        _groups.value = files.map { file ->
            workspace.readFile(file)
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
}
