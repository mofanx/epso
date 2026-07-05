package li.mofanx.epso.expansion

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 表单结果：用户填写完毕后触发
 * null = 用户取消
 */
data class FormResult(val values: Map<String, String>)

/**
 * 表单悬浮窗
 *
 * 继承 [OverlayWindowService]，当匹配到带 `form` 字段的规则时由 [ExpansionService] 启动。
 *
 * 生命周期：
 * 1. ExpansionService 调用 `FormOverlayWindow.requestForm(ctx, match)` 启动服务
 * 2. 服务展示表单悬浮窗（可拖动）
 * 3. 用户「确认」→ 发射 [resultFlow]，服务自行关闭
 * 4. 用户「取消」→ 发射 null，服务自行关闭
 * 5. ExpansionService 通过 collect [resultFlow] 获取结果并完成文本替换
 *
 * 表单占位符格式（espanso 标准）：`[[field_name]]`
 * 与 replace 中的 `{{var_name}}` 不同，form 字段用 `[[]]`。
 */
class FormOverlayWindow : OverlayWindowService(positionKey = "form_overlay") {

    // 表单需要软键盘，去掉 FLAG_NOT_FOCUSABLE
    override val windowFlags: Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN


    companion object {
        private val _resultFlow = MutableSharedFlow<FormResult?>(replay = 0, extraBufferCapacity = 1)
        val resultFlow: SharedFlow<FormResult?> = _resultFlow.asSharedFlow()

        /** 当前等待填写的 match（由 ExpansionService 写入） */
        @Volatile
        var pendingMatch: Match? = null

        /**
         * 启动表单悬浮窗服务，并设置待处理的 Match。
         * 必须在 [ExpansionService] 检测到 form 规则后调用。
         */
        fun requestForm(ctx: Context, match: Match) {
            pendingMatch = match
            ctx.startService(Intent(ctx, FormOverlayWindow::class.java))
        }

        /** 解析 form 字符串中所有 [[field_name]] 占位符的名称列表（保序去重） */
        fun parseFieldNames(form: String): List<String> {
            val regex = Regex("""\[\[([^\]]+)]]""")
            return regex.findAll(form).map { it.groupValues[1] }.distinct().toList()
        }

        /** 将 values 填入 form 模板，生成最终 replace 文本 */
        fun renderForm(form: String, values: Map<String, String>): String {
            var result = form
            for ((name, value) in values) {
                result = result.replace("[[$name]]", value)
            }
            return result
        }
    }

    private val formScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun ComposeContent() {
        val match = pendingMatch ?: run {
            formScope.launch { _resultFlow.emit(null); stopSelf() }
            return
        }

        val formTemplate = match.form ?: run {
            formScope.launch { _resultFlow.emit(null); stopSelf() }
            return
        }

        val fieldNames = remember { parseFieldNames(formTemplate) }
        val fieldValues = remember {
            mutableStateMapOf<String, String>().also { map ->
                fieldNames.forEach { name ->
                    map[name] = match.formFields[name]?.default ?: ""
                }
            }
        }

        Column(
            modifier = Modifier
                .widthIn(min = 260.dp, max = 340.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClosableTitle(title = "填写表单")

            // 表单模板预览（小字）
            Text(
                text = formTemplate.take(80) + if (formTemplate.length > 80) "…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // 每个字段渲染对应控件
            fieldNames.forEach { name ->
                val fieldConfig = match.formFields[name]
                val currentValue = fieldValues[name] ?: ""

                when {
                    fieldConfig?.type == "list" || fieldConfig?.values?.isNotEmpty() == true -> {
                        val options = fieldConfig.values
                        ListField(
                            label = name,
                            options = options,
                            selected = currentValue.ifEmpty { options.firstOrNull() ?: "" },
                            onSelect = { fieldValues[name] = it },
                        )
                    }
                    fieldConfig?.multiline == true || fieldConfig?.type == "multiline" -> {
                        OutlinedTextField(
                            value = currentValue,
                            onValueChange = { fieldValues[name] = it },
                            label = { Text(name) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                    else -> {
                        OutlinedTextField(
                            value = currentValue,
                            onValueChange = { fieldValues[name] = it },
                            label = { Text(name) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    formScope.launch {
                        _resultFlow.emit(null)
                        stopSelf()
                    }
                }) { Text("取消") }

                Spacer(Modifier.width(8.dp))

                TextButton(onClick = {
                    formScope.launch {
                        _resultFlow.emit(FormResult(fieldValues.toMap()))
                        stopSelf()
                    }
                }) { Text("确认") }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// List 类型字段（下拉菜单）
// ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
