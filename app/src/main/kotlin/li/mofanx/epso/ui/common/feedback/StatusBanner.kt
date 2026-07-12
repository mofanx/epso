package li.mofanx.epso.ui.common.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.util.throttle

sealed class BannerType {
    abstract val containerColor: Color
    abstract val contentColor: Color

    data class Info(
        override val containerColor: Color = Color.Unspecified,
        override val contentColor: Color = Color.Unspecified,
    ) : BannerType()

    data class Warning(
        override val containerColor: Color = Color.Unspecified,
        override val contentColor: Color = Color.Unspecified,
    ) : BannerType()

    data class Success(
        override val containerColor: Color = Color.Unspecified,
        override val contentColor: Color = Color.Unspecified,
    ) : BannerType()
}

@Composable
fun StatusBanner(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    type: BannerType = BannerType.Info(),
    semanticDescription: String? = null,
) {
    val (containerColor, contentColor) = when (type) {
        is BannerType.Info -> if (type.containerColor == Color.Unspecified) {
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            type.containerColor to type.contentColor
        }
        is BannerType.Warning -> if (type.containerColor == Color.Unspecified) {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        } else {
            type.containerColor to type.contentColor
        }
        is BannerType.Success -> if (type.containerColor == Color.Unspecified) {
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            type.containerColor to type.contentColor
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                if (semanticDescription != null) {
                    contentDescription = semanticDescription
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        onClick = onAction?.let { throttle { it() } } ?: { },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = throttle { onAction() },
                ) {
                    Text(
                        text = actionLabel,
                        color = type.contentColor,
                    )
                }
            }
        }
    }
}
