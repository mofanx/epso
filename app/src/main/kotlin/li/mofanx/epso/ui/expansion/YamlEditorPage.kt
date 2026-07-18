package li.mofanx.epso.ui.expansion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.util.throttle
import li.mofanx.epso.util.toast
import java.io.File

@Serializable
data class YamlEditorRoute(val sourceFilePath: String = "") : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YamlEditorPage(route: YamlEditorRoute) {
    val mainVm = LocalMainViewModel.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var content by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var hasChanges by remember { mutableStateOf(false) }

    LaunchedEffect(route.sourceFilePath) {
        isLoading = true
        loadError = ""
        withContext(Dispatchers.IO) {
            try {
                val file = File(route.sourceFilePath)
                if (file.exists()) {
                    content = file.readText()
                } else {
                    loadError = "文件不存在：${route.sourceFilePath}"
                }
            } catch (e: Exception) {
                loadError = e.message ?: "读取失败"
            }
        }
        isLoading = false
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("编辑 YAML") },
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = throttle { mainVm.popPage() },
                    )
                },
                actions = {
                    TextButton(
                        onClick = throttle {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        File(route.sourceFilePath).writeText(content)
                                        MatchStore.reload()
                                        withContext(Dispatchers.Main) {
                                            hasChanges = false
                                            toast("已保存")
                                            mainVm.popPage()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            toast("保存失败：${e.message}")
                                        }
                                    }
                                }
                            }
                        },
                        enabled = hasChanges && loadError.isEmpty() && !isLoading,
                    ) { Text("保存") }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                isLoading -> CircularProgressIndicator()
                loadError.isNotEmpty() -> Text(
                    text = loadError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> OutlinedTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        hasChanges = true
                    },
                    label = { Text("YAML 内容") },
                    modifier = Modifier.fillMaxSize(),
                    singleLine = false,
                    minLines = 15,
                )
            }
        }
    }
}
