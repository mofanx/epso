package li.mofanx.epso.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import li.mofanx.epso.R
import li.mofanx.epso.ui.common.feedback.BannerType
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.util.throttle

@Composable
fun HomeHeroCard(
    status: HomeOverallStatus,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val (bannerType, semantic) = when (status) {
        is HomeOverallStatus.Available -> BannerType.Success() to "服务可用"
        is HomeOverallStatus.NotAuthorized -> BannerType.Warning() to "未授权"
        is HomeOverallStatus.Disabled -> BannerType.Warning() to "已关闭"
        is HomeOverallStatus.Fault -> BannerType.Warning() to "故障"
        is HomeOverallStatus.Restricted -> BannerType.Warning() to "权限受限"
    }
    val title = status.title
    val subtitle = status.subtitle
    val primaryAction = status.primaryAction
    val secondaryAction = status.secondaryAction

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$title: $subtitle"
            },
        colors = CardDefaults.cardColors(
            containerColor = bannerType.containerColor,
            contentColor = bannerType.contentColor,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemHorizontalPadding, itemVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = throttle { onPrimaryAction() }) {
                    Text(text = primaryAction)
                }
                if (secondaryAction != null && onSecondaryAction != null) {
                    TextButton(onClick = throttle { onSecondaryAction() }) {
                        Text(text = secondaryAction, color = bannerType.contentColor)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickStartProgressCard(
    progress: QuickStartProgress,
    modifier: Modifier = Modifier,
) {
    if (progress.allCompleted) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemHorizontalPadding, itemVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_quick_start_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                progress.steps.forEachIndexed { index, step ->
                    QuickStartStepItem(step)
                    if (index < progress.steps.lastIndex) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(
                                    if (progress.steps[index + 1].completed) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickStartStepItem(step: QuickStartStep) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val background = if (step.completed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
        val iconColor = if (step.completed) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(
            imageVector = if (step.completed) PerfIcon.Check else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier
                .clip(CircleShape)
                .background(background)
                .padding(4.dp),
            tint = iconColor,
        )
        Text(
            text = step.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (step.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
