package li.mofanx.epso.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import li.mofanx.epso.R
import li.mofanx.epso.ui.WebViewRoute
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.util.copyText
import li.mofanx.epso.util.throttle

@Composable
fun ManualAuthDialog(
    commandText: String,
    show: Boolean,
    onUpdateShow: (Boolean) -> Unit,
) {
    if (show) {
        val mainVm = LocalMainViewModel.current
        AlertDialog(
            onDismissRequest = { onUpdateShow(false) },
            title = { Text(text = stringResource(R.string.auth_a11y_command)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.auth_a11y_command_steps))
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                        ) {
                            Text(
                                text = commandText,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        PerfIcon(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clickable(onClick = throttle {
                                    copyText(commandText)
                                })
                                .padding(4.dp)
                                .size(20.dp),
                            imageVector = PerfIcon.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                            tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        modifier = Modifier
                            .clickable(onClick = throttle {
                                onUpdateShow(false)
                                mainVm.navigatePage(WebViewRoute(initUrl = "https://github.com/mofanx/epso/issues/43"))
                            }),
                        text = stringResource(R.string.auth_a11y_command_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateShow(false)
                }) {
                    Text(text = stringResource(R.string.action_close))
                }
            },
        )
    }
}