package li.mofanx.epso.ui.common.feedback

import androidx.compose.material3.SnackbarDuration

sealed interface UiMessage {
    val text: String

    data class Info(
        override val text: String,
        val action: String? = null,
        val onAction: (() -> Unit)? = null,
        val duration: SnackbarDuration = SnackbarDuration.Short,
    ) : UiMessage

    data class Success(
        override val text: String,
        val action: String? = null,
        val onAction: (() -> Unit)? = null,
        val duration: SnackbarDuration = SnackbarDuration.Short,
    ) : UiMessage

    data class Warning(
        override val text: String,
        val action: String? = null,
        val onAction: (() -> Unit)? = null,
        val duration: SnackbarDuration = SnackbarDuration.Short,
    ) : UiMessage

    data class Error(
        override val text: String,
        val action: String? = null,
        val onAction: (() -> Unit)? = null,
        val duration: SnackbarDuration = SnackbarDuration.Long,
    ) : UiMessage
}
