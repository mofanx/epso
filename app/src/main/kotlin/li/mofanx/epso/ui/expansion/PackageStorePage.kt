package li.mofanx.epso.ui.expansion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.expansion.hub.HubClient
import li.mofanx.epso.expansion.hub.HubPackage
import li.mofanx.epso.expansion.hub.HubResult
import li.mofanx.epso.expansion.hub.InstalledPackage
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.openUri
import li.mofanx.epso.util.throttle
import java.io.File

@Serializable
data object PackageStoreRoute : NavKey

// 每页包含的 tag 快速筛选
private val POPULAR_TAGS = listOf("emoji", "development", "languages", "fun", "math", "shell")

private data class FriendlyError(val title: String, val description: String)

/** 把网络/HTTP 等技术错误翻译成用户能看懂的话 */
private fun friendlyError(raw: String): FriendlyError {
    val lowered = raw.lowercase()
    return when {
        "timeout" in lowered || "socket" in lowered ->
            FriendlyError("连接超时", "访问 GitHub 包商店超时，国内网络可能受限。")
        "unable to resolve host" in lowered || "unknownhost" in lowered || "nodename" in lowered ->
            FriendlyError("网络不可用", "无法解析服务器地址，请检查网络连接。")
        "ssl" in lowered || "certificate" in lowered || "cert" in lowered ->
            FriendlyError("安全连接失败", "HTTPS 证书校验失败，可能是网络被拦截或设备时间不同步。")
        "404" in raw || "not found" in lowered ->
            FriendlyError("索引不存在", "远程包索引文件未找到，可能是上游正在更新。")
        "http" in lowered ->
            FriendlyError("服务器返回错误", raw)
        else ->
            FriendlyError("加载失败", "无法连接到包商店：$raw")
    }
}

/** 安装/卸载失败时，把原始错误转成短提示 */
private fun friendlyActionError(raw: String): String {
    val lowered = raw.lowercase()
    return when {
        "timeout" in lowered || "socket" in lowered -> "连接超时，请检查网络或开启代理"
        "unable to resolve host" in lowered || "unknownhost" in lowered -> "无法解析服务器地址"
        "http" in lowered -> "下载失败（$raw）"
        else -> raw
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PackageStorePage() {
    val mainVm = LocalMainViewModel.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val workspaceDir = remember { MatchStore.getWorkspaceDir() }
    val cacheFile = remember { File(workspaceDir.parent ?: workspaceDir.absolutePath, "hub_index.json") }

    // 状态
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var allPackages by remember { mutableStateOf(emptyList<HubPackage>()) }
    var installed: List<InstalledPackage> by remember { mutableStateOf(emptyList()) }
    var search by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf("") }
    var actionPkg by remember { mutableStateOf<HubPackage?>(null) }
    var actionMsg by remember { mutableStateOf("") }   // 操作状态（安装/卸载反馈）
    var isActing by remember { mutableStateOf(false) }

    // 初始加载
    LaunchedEffect(Unit) {
        isLoading = true
        when (val r = HubClient.fetchIndex(cacheFile)) {
            is HubResult.Success -> {
                // 同名包只保留最新版本
                allPackages = r.data.packages
                    .groupBy { it.name }
                    .values
                    .map { pkgs -> pkgs.maxByOrNull { it.version } ?: pkgs.first() }
                    .sortedBy { it.title.lowercase() }
                loadError = ""
            }
            is HubResult.Failure -> loadError = r.error
        }
        installed = HubClient.installedPackages(workspaceDir)
        isLoading = false
    }

    val installedNames = remember(installed) { installed.map { it.name }.toSet() }

    // 过滤
    val displayed = remember(allPackages, search, selectedTag) {
        allPackages.filter { pkg ->
            val matchesSearch = search.isBlank() ||
                pkg.title.contains(search, ignoreCase = true) ||
                pkg.name.contains(search, ignoreCase = true) ||
                pkg.description.contains(search, ignoreCase = true)
            val matchesTag = selectedTag.isBlank() || pkg.tags.any { it.contains(selectedTag, ignoreCase = true) }
            matchesSearch && matchesTag
        }
    }

    // 安装/卸载确认对话框
    actionPkg?.let { pkg ->
        val isInstalled = pkg.name in installedNames
        AlertDialog(
            onDismissRequest = { if (!isActing) actionPkg = null },
            title = { Text(if (isInstalled) "卸载包" else "安装包") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pkg.title, style = MaterialTheme.typography.titleSmall)
                    Text(pkg.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("作者：${pkg.author}  版本：${pkg.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (actionMsg.isNotEmpty()) {
                        Text(
                            text = actionMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (actionMsg.startsWith("✓"))
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isActing = true
                        actionMsg = if (isInstalled) "卸载中…" else "下载安装中…"
                        scope.launch(Dispatchers.IO) {
                            val result = if (isInstalled) {
                                val r = HubClient.uninstall(pkg.name, workspaceDir)
                                if (r is HubResult.Success) MatchStore.removePackageImport(pkg.name)
                                r
                            } else {
                                val r = HubClient.install(pkg, workspaceDir)
                                if (r is HubResult.Success) MatchStore.addPackageImport(pkg.name)
                                r
                            }
                            installed = HubClient.installedPackages(workspaceDir)
                            when (result) {
                                is HubResult.Success -> {
                                    // 成功后短暂显示提示，再自动关闭对话框
                                    actionMsg = if (isInstalled) "✓ 已卸载 ${pkg.name}" else "✓ 已安装 ${pkg.name}"
                                    isActing = false
                                    delay(800)
                                    actionPkg = null
                                    actionMsg = ""
                                }
                                is HubResult.Failure -> {
                                    actionMsg = "✗ ${friendlyActionError(result.error)}"
                                    isActing = false
                                }
                            }
                        }
                    },
                    enabled = !isActing,
                ) {
                    if (isActing) CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(if (isInstalled) "卸载" else "安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { actionPkg = null; actionMsg = "" }, enabled = !isActing) {
                    Text("关闭")
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Text(
                        when {
                            isLoading -> "包商店"
                            loadError.isNotEmpty() -> "包商店"
                            else -> "包商店 (${displayed.size}/${allPackages.size})"
                        }
                    )
                },
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = throttle { mainVm.popPage() },
                    )
                },
                actions = {
                    PerfIconButton(
                        imageVector = PerfIcon.Autorenew,
                        contentDescription = "刷新包列表",
                        onClick = throttle {
                            scope.launch {
                                isLoading = true
                                cacheFile.delete()  // 强制重新拉取
                                when (val r = HubClient.fetchIndex(cacheFile)) {
                                    is HubResult.Success -> {
                                        allPackages = r.data.packages
                                            .groupBy { it.name }
                                            .values
                                            .map { pkgs -> pkgs.maxByOrNull { it.version } ?: pkgs.first() }
                                            .sortedBy { it.title.lowercase() }
                                        loadError = ""
                                    }
                                    is HubResult.Failure -> loadError = r.error
                                }
                                isLoading = false
                            }
                        },
                    )
                },
            )
        },
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("加载包列表中…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }

        if (loadError.isNotEmpty()) {
            val err = friendlyError(loadError)
            Box(
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    PerfIcon(
                        imageVector = PerfIcon.CloudOff,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = err.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "建议：检查网络、开启 VPN/代理，或稍后再试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            isLoading = true
                            loadError = ""
                            when (val r = HubClient.fetchIndex(cacheFile)) {
                                is HubResult.Success -> allPackages = r.data.packages
                                    .groupBy { it.name }
                                    .values
                                    .map { pkgs -> pkgs.maxByOrNull { it.version } ?: pkgs.first() }
                                    .sortedBy { it.title.lowercase() }
                                is HubResult.Failure -> loadError = r.error
                            }
                            isLoading = false
                        }
                    }) { Text("重新加载") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        openUri("https://github.com/espanso/hub/releases")
                    }) { Text("去浏览器手动下载") }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = itemHorizontalPadding,
                vertical = itemHorizontalPadding / 2,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 搜索框
            item(key = "search") {
                androidx.compose.material3.OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索包名/描述") },
                    leadingIcon = { PerfIcon(PerfIcon.Edit) },
                    trailingIcon = {
                        if (search.isNotEmpty()) PerfIconButton(
                            imageVector = PerfIcon.Close,
                            onClick = { search = "" },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // 标签快速筛选
            item(key = "tags") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    POPULAR_TAGS.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) "" else tag },
                            label = { Text(tag) },
                        )
                    }
                }
            }

            // 已安装分区标题
            if (installedNames.isNotEmpty()) {
                item(key = "installed_header") {
                    Text(
                        "已安装 (${installedNames.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
            }

            // 列表
            items(
                items = displayed,
                key = { it.name + it.version },
            ) { pkg ->
                val isInstalled = pkg.name in installedNames
                PackageCard(
                    pkg = pkg,
                    isInstalled = isInstalled,
                    onClick = { actionPkg = pkg; actionMsg = "" },
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// PackageCard
// ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackageCard(
    pkg: HubPackage,
    isInstalled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = pkg.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (isInstalled) {
                            Text(
                                text = "已安装",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "by ${pkg.author}  v${pkg.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 安装/卸载按钮
                if (isInstalled) {
                    TextButton(onClick = onClick) { Text("卸载") }
                } else {
                    Button(onClick = onClick) { Text("安装") }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = pkg.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )

            // 标签
            if (pkg.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pkg.tags.take(5).forEach { tag ->
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}
