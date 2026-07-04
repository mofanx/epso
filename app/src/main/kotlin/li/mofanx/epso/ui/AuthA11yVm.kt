package li.mofanx.epso.ui

import kotlinx.coroutines.flow.MutableStateFlow
import li.mofanx.epso.ui.share.BaseViewModel
import li.mofanx.epso.ui.share.asMutableState

class AuthA11yVm : BaseViewModel() {
    val showCopyDlgFlow = MutableStateFlow(false)
}
