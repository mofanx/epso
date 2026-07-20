package li.mofanx.epso.expansion

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
 * 选择悬浮窗（choice 类型变量使用）
 *
 * 与 FormOverlayWindow / SearchOverlayWindow 类似：
 * 1. ExpansionService 调用 requestChoice 启动服务
 * 2. 悬浮窗展示选项列表
 * 3. 用户点击某一项 → 发射 resultFlow，服务自行关闭
 * 4. 用户取消/关闭 → 发射 null，服务自行关闭
 */
class ChoiceOverlayWindow : OverlayWindowService(positionKey = "choice_overlay") {

    override val windowFlags: Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    companion object {
        private val _resultFlow = MutableSharedFlow<String?>(replay = 0, extraBufferCapacity = 1)
        val resultFlow: SharedFlow<String?> = _resultFlow.asSharedFlow()

        @Volatile
        var pendingOptions: List<String> = emptyList()

        @Volatile
        var pendingTitle: String = ""

        /**
         * 启动选择悬浮窗。
         */
        fun requestChoice(ctx: Context, title: String, options: List<String>) {
            pendingTitle = title
            pendingOptions = options
            ctx.startService(Intent(ctx, ChoiceOverlayWindow::class.java))
        }
    }

    private val choiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Composable
    override fun ComposeContent() {
        val options = pendingOptions
        if (options.isEmpty()) {
            LaunchedEffect(Unit) {
                _resultFlow.emit(null)
                stopSelf()
            }
            return
        }

        Surface(
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 240.dp, max = 320.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ClosableTitle(title = pendingTitle.takeIf { it.isNotBlank() } ?: "请选择")

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    items(options) { option ->
                        Text(
                            text = option,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    choiceScope.launch {
                                        _resultFlow.emit(option)
                                        stopSelf()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        HorizontalDivider()
                    }
                }

                TextButton(
                    onClick = {
                        choiceScope.launch {
                            _resultFlow.emit(null)
                            stopSelf()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消")
                }
            }
        }
    }
}
