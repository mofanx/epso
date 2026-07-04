package li.mofanx.epso.expansion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.mofanx.epso.app
import li.mofanx.epso.appScope
import li.mofanx.epso.expansion.ExpansionService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpansionTestPage() {
    val scope = rememberCoroutineScope()
    val expansionService = ExpansionService.getInstance()
    
    // 测试匹配规则
    var trigger by remember { mutableStateOf("eml") }
    var replace by remember { mutableStateOf("myemail@example.com") }
    var isRegex by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf(true) }
    
    // 状态显示
    val isRunning by ExpansionService.isRunning.collectAsState()
    val matchCount by produceState(initialValue = 0) {
        value = expansionService?.getMatchCount() ?: 0
    }
    
    // 测试日志
    var logs by remember { mutableStateOf(listOf<String>()) }
    
    fun addLog(message: String) {
        logs = logs + "[${System.currentTimeMillis()}] $message"
        if (logs.size > 20) logs = logs.drop(1)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文本扩展测试") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 服务状态
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "服务状态: ${if (isRunning) "运行中" else "已停止"}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "当前匹配规则数: $matchCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            // 添加匹配规则
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "添加匹配规则",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    OutlinedTextField(
                        value = trigger,
                        onValueChange = { trigger = it },
                        label = { Text("触发器") },
                        placeholder = { Text("例如: eml") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = replace,
                        onValueChange = { replace = it },
                        label = { Text("替换文本") },
                        placeholder = { Text("例如: myemail@example.com") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = isRegex,
                            onCheckedChange = { isRegex = it }
                        )
                        Text("正则表达式")
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Checkbox(
                            checked = word,
                            onCheckedChange = { word = it }
                        )
                        Text("单词边界")
                    }
                    
                    Button(
                        onClick = {
                            scope.launch {
                                val match = Match(
                                    trigger = if (isRegex) "" else trigger,
                                    replace = replace,
                                    regex = if (isRegex) trigger else "",
                                    word = word
                                )
                                expansionService?.addMatch(match)
                                addLog("添加规则: $trigger -> $replace")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("添加规则")
                    }
                }
            }
            
            // 测试操作
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "测试操作",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    expansionService?.clearMatches()
                                    addLog("清空所有规则")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("清空规则")
                        }
                        
                        Button(
                            onClick = {
                                scope.launch {
                                    val count = expansionService?.getMatchCount() ?: 0
                                    addLog("当前规则数: $count")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("查询数量")
                        }
                    }
                }
            }
            
            // 测试日志
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "操作日志",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    if (logs.isEmpty()) {
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        logs.forEach { log ->
                            Text(
                                log,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // 使用说明
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "使用说明",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Text(
                        "1. 确保无障碍服务已启动",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "2. 添加匹配规则（触发器 -> 替换文本）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "3. 在任何应用中输入触发器文本",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "4. 触发器会自动扩展为替换文本",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "5. 例如输入 'eml' 会自动变为 'myemail@example.com'",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}