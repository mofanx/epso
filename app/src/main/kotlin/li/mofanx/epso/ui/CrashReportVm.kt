package li.mofanx.epso.ui

import li.mofanx.epso.MainViewModel
import li.mofanx.epso.ui.share.BaseViewModel

class CrashReportVm : BaseViewModel() {
    val crashDataList = MainViewModel.instance.run {
        val v = tempCrashDataList
        tempCrashDataList = emptyList()
        v
    }
}