package li.mofanx.epso.ui.common.state

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
    data object Empty : UiState<Nothing>
    data class Error(val reason: Throwable, val message: String? = null) : UiState<Nothing>
    data object PermissionDenied : UiState<Nothing>
    data object Offline : UiState<Nothing>
}

fun <T> UiState<T>.contentOrNull(): T? = (this as? UiState.Content<T>)?.data

fun <T, R> UiState<T>.map(transform: (T) -> R): UiState<R> = when (this) {
    is UiState.Loading -> UiState.Loading
    is UiState.Content -> UiState.Content(transform(data))
    is UiState.Empty -> UiState.Empty
    is UiState.Error -> UiState.Error(reason, message)
    is UiState.PermissionDenied -> UiState.PermissionDenied
    is UiState.Offline -> UiState.Offline
}
