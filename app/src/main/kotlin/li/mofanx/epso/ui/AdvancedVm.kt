package li.mofanx.epso.ui

import kotlinx.coroutines.flow.MutableStateFlow
import li.mofanx.epso.ui.share.BaseViewModel
import li.mofanx.epso.ui.share.asMutableState

class AdvancedVm : BaseViewModel() {
    val showEditPortDlgFlow = MutableStateFlow(false)
    val showShizukuStateFlow = MutableStateFlow(false)
}
