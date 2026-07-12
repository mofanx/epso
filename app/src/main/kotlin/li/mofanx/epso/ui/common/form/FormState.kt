package li.mofanx.epso.ui.common.form

sealed interface FormState<out T> {
    data class Editing<T>(
        val draft: T,
        val isDirty: Boolean = false,
        val errors: Map<String, String> = emptyMap(),
        val isValid: Boolean = true,
    ) : FormState<T>

    data class Submitting<T>(val draft: T) : FormState<T>
    data class Success<T>(val result: T) : FormState<T>
    data class Failed<T>(
        val draft: T,
        val error: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : FormState<T>
}

fun <T> FormState<T>.draft(): T? = when (this) {
    is FormState.Editing -> draft
    is FormState.Submitting -> draft
    is FormState.Success -> result
    is FormState.Failed -> draft
}

fun <T> FormState<T>.isDirty(): Boolean = when (this) {
    is FormState.Editing -> isDirty
    is FormState.Submitting -> true
    is FormState.Failed -> true
    is FormState.Success -> false
}

fun <T> FormState<T>.canSubmit(): Boolean = when (this) {
    is FormState.Editing -> isDirty && isValid
    is FormState.Failed -> true
    else -> false
}
