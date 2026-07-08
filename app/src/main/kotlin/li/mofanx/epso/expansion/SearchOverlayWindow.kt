package li.mofanx.epso.expansion

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import li.mofanx.epso.service.OverlayWindowService

/**
 * 匹配词快速搜索悬浮窗
 *
 * 生命周期：
 * 1. ExpansionService 检测到搜索触发词（如 ":s"），调用 [requestSearch] 启动服务
 * 2. 悬浮窗展示搜索框 + 规则列表，可实时过滤
 * 3. 用户点击某条规则 → 发射 [resultFlow]，服务自行关闭
 * 4. 用户点击关闭按钮 → 发射 null，服务自行关闭
 * 5. ExpansionService 通过 collect [resultFlow] 获取结果并执行文本替换
 *
 * 通信模式与 [FormOverlayWindow] 一致：companion SharedFlow + pendingXxx 字段。
 */
class SearchOverlayWindow : OverlayWindowService(positionKey = "search_overlay") {

    // 需要软键盘（搜索框输入），去掉 FLAG_NOT_FOCUSABLE
    override val windowFlags: Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    companion object {
        private val _resultFlow = MutableSharedFlow<Match?>(replay = 0, extraBufferCapacity = 1)
        val resultFlow: SharedFlow<Match?> = _resultFlow.asSharedFlow()

        /**
         * 触发搜索时用户已输入的前缀（":s" 之后的内容），用于预填搜索框。
         * 由 ExpansionService 在启动前写入。
         */
        @Volatile
        var pendingPreQuery: String = ""

        /**
         * 启动搜索悬浮窗服务。
         * @param ctx     Context（通常是 ExpansionService）
         * @param preQuery 用户在触发词后继续输入的内容，预填到搜索框
         */
        fun requestSearch(ctx: Context, preQuery: String = "") {
            pendingPreQuery = preQuery
            ctx.startService(Intent(ctx, SearchOverlayWindow::class.java))
        }
    }

    private val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Composable
    override fun ComposeContent() {
        val groups by MatchStore.groups.collectAsState()

        // 搜索框内容，预填用户已输入的前缀
        var query by remember { mutableStateOf(pendingPreQuery) }

        // 实时过滤：trigger / regex / replace / label 四维搜索（与 MatchListPage 一致）
        val filteredMatches = remember(groups, query) {
            val q = query.trim().lowercase()
            if (q.isBlank()) {
                groups.flatMap { it.matches }
            } else {
                groups.flatMap { g ->
                    g.matches.filter { m ->
                        m.allTriggers.any { it.lowercase().contains(q) } ||
                            m.regex.lowercase().contains(q) ||
                            m.replace.lowercase().contains(q) ||
                            (m.label?.lowercase()?.contains(q) == true)
                    }
                }
            }
        }

        val focusRequester = remember { FocusRequester() }

        // 用 Surface 包裹，确保悬浮窗有不透明背景，不与底部内容重叠
        Surface(
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp,
        ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Bug 1 修复：关闭按钮需先发射 null 再关闭，否则 ExpansionService.first() 会挂起到超时
            ClosableTitle(title = "快速搜索规则", onClose = {
                windowScope.launch {
                    _resultFlow.emit(null)
                    stopSelf()
                }
            })

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("输入关键词搜索…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )

            if (filteredMatches.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "无匹配规则",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(
                        items = filteredMatches,
                        key = { m -> "${m.trigger}::${m.regex}::${m.replace.take(20)}" },
                    ) { match ->
                        SearchMatchItem(
                            match = match,
                            onClick = {
                                windowScope.launch {
                                    _resultFlow.emit(match)
                                    stopSelf()
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } // end Column
        } // end Surface

        // 悬浮窗显示后自动请求搜索框焦点
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 单条规则卡片
// ──────────────────────────────────────────────────────────────

@Composable
private fun SearchMatchItem(
    match: Match,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 触发词（加粗主色）
        val triggerText = when {
            match.isRegex -> "/${match.regex}/"
            else -> match.allTriggers.joinToString(" / ")
        }
        Text(
            text = triggerText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // 替换内容预览（次要色，最多两行）
        val replacePreview = when {
            match.isForm -> "[表单] ${match.form?.take(60) ?: ""}"
            else -> match.replace.take(80)
        }
        Text(
            text = replacePreview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // label（如果有）
        if (!match.label.isNullOrBlank()) {
            Text(
                text = match.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
