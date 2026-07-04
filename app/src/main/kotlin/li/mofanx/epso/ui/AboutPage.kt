package li.mofanx.epso.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import li.mofanx.epso.META
import li.mofanx.epso.MainActivity
import li.mofanx.epso.R
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.component.SettingItem
import li.mofanx.epso.ui.share.LocalDarkTheme
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.EmptyHeight
import li.mofanx.epso.ui.style.itemPadding
import li.mofanx.epso.ui.style.titleItemPadding
import li.mofanx.epso.util.openUri
import li.mofanx.epso.util.throttle
import li.mofanx.epso.util.toast

@Serializable
data object AboutRoute : NavKey

@Composable
fun AboutPage() {
    val context = LocalActivity.current as MainActivity
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<AboutVm>()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = { mainVm.popPage() },
                    )
                },
                title = { Text(text = "关于") },
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedLogoIcon(
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = throttle { toast("你干嘛~ 哎呦~") }
                        )
                        .fillMaxWidth(0.33f)
                        .aspectRatio(1f)
                )
                Column(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = META.appName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = META.versionName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            SettingItem(
                imageVector = null,
                title = "开源代码",
                onClick = { mainVm.openUrl("https://github.com/mofanx/epso") },
            )

            SettingItem(
                imageVector = null,
                title = "使用协议",
                onClick = { mainVm.openUrl("https://github.com/mofanx/epso/blob/main/LICENSE") },
            )

            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}

@Composable
private fun AnimatedLogoIcon(
    modifier: Modifier = Modifier
) {
    val darkTheme = LocalDarkTheme.current
    val colorRid = if (darkTheme) R.color.better_white else R.color.better_black
    var atEnd by remember { mutableStateOf(false) }
    val animation = AnimatedImageVector.animatedVectorResource(id = R.drawable.ic_anim_logo)
    val painter = rememberAnimatedVectorPainter(animation, atEnd)
    LaunchedEffect(Unit) {
        while (isActive) {
            atEnd = !atEnd
            delay(animation.totalDuration.toLong())
        }
    }
    Icon(
        modifier = modifier,
        painter = painter,
        contentDescription = null,
        tint = colorResource(colorRid),
    )
}
