package li.mofanx.epso.expansion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文本扩展测试页
 *
 * - 服务状态卡片（运行中 / 已停止，当前扩展状态）
 * - 实时匹配预览：在页面内输入文本，展示哪条规则会被触发
 * - 扩展历史：最近 20 条成功 / 失败记录
 */
@Composable
fun ExpansionTestPage() {
    val expansionState by (ExpansionService.getInstance()?.expansionState
        ?.collectAsState()
        ?: remember { mutableStateOf(ExpansionState.Idle) })
    val matchDict by MatchStore.matchDict.collectAsState()

    // 实时匹配预览
    var previewInput by remember { mutableStateOf("") }

    // 同时检查精确触发词和正则规则，响应 matchDict 变化
    val previewResult by remember(previewInput, matchDict) {
        derivedStateOf {
            if (previewInput.isEmpty()) return@derivedStateOf null
            // 1. 精确触发词（按长度降序，优先最长匹配）
            val exactHit = matchDict.entries
                .filter { !it.key.startsWith("__regex__") }
                .sortedByDescending { it.key.length }
                .firstOrNull { (trigger, _) -> previewInput.endsWith(trigger) }
            if (exactHit != null) {
                val replace = exactHit.value.replace.take(60) +
                    if (exactHit.value.replace.length > 60) "…" else ""
                return@derivedStateOf "✓ 触发：${exactHit.key}\n→ $replace"
            }
            // 2. 正则规则
            val regexHit = matchDict.entries
                .filter { it.key.startsWith("__regex__") }
                .firstOrNull { (key, _) ->
                    val pattern = key.removePrefix("__regex__")
                    runCatching { Regex(pattern).containsMatchIn(previewInput) }.getOrElse { false }
                }
            if (regexHit != null) {
                val pattern = regexHit.key.removePrefix("__regex__")
                val replace = regexHit.value.replace.take(60) +
                    if (regexHit.value.replace.length > 60) "…" else ""
                return@derivedStateOf "✓ 正则：$pattern\n→ $replace"
            }
            null
        }
    }

    // 扩展历史（最近 20 条）
    var history by remember { mutableStateOf(listOf<HistoryEntry>()) }

    // 监听扩展状态写入历史
    LaunchedEffect(expansionState) {
        when (val s = expansionState) {
            is ExpansionState.Completed -> {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                history = (listOf(
                    HistoryEntry(ts, true, "${s.trigger} → ${s.expandedText}")
                ) + history).take(20)
            }
            is ExpansionState.Failed -> {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                history = (listOf(
                    HistoryEntry(ts, false, s.error)
                ) + history).take(20)
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2),
    ) {
        // ── 实时匹配预览 ──────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = surfaceCardColors,
        ) {
            Column(
                modifier = Modifier.padding(itemVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "实时匹配预览",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "在下方输入文本，查看哪条规则会被触发（仅预览，不实际替换）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = previewInput,
                    onValueChange = { previewInput = it },
                    label = { Text("输入文本") },
                    placeholder = { Text("例如：输入 :eml 查看匹配结果") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                if (previewResult != null) {
                    Text(
                        text = previewResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (previewInput.isNotEmpty()) {
                    Text(
                        text = "— 无匹配规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 扩展历史 ──────────────────────────────────────────────
        if (history.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = surfaceCardColors,
            ) {
                Column(
                    modifier = Modifier.padding(itemVerticalPadding),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "扩展历史",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { history = emptyList() }) {
                            Text("清空")
                        }
                    }
                    history.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = entry.timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                text = entry.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.success)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // ── 使用说明 ──────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = surfaceCardColors,
        ) {
            Column(
                modifier = Modifier.padding(itemVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                listOf(
                    "1. 在首页开启无障碍服务",
                    "2. 点击右上角列表图标管理规则（支持 espanso YAML 格式）",
                    "3. 在任意输入框输入触发词，即可自动替换",
                    "4. 替换文本支持 {{var}} 变量和 \$|\$ 光标定位",
                    "5. 规则文件保存在 matches/ 目录，可用 Syncthing 同步",
                ).forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class HistoryEntry(
    val timestamp: String,
    val success: Boolean,
    val text: String,
)
