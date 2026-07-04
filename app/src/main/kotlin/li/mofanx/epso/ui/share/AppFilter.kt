package li.mofanx.epso.ui.share

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import li.mofanx.epso.data.AppInfo
import li.mofanx.epso.util.AppGroupOption
import li.mofanx.epso.util.visibleAppInfosFlow

class AppFilter(
    val searchStrFlow: MutableStateFlow<String>,
    val appListFlow: StateFlow<List<AppInfo>>,
    val showAllAppFlow: StateFlow<Boolean>,
)

fun BaseViewModel.useAppFilter(
    appGroupTypeFlow: StateFlow<Int>,
    showBlockAppFlow: StateFlow<Boolean>? = null,
    blockAppListFlow: StateFlow<Set<String>>? = null,
): AppFilter {

    var tempListFlow: Flow<List<AppInfo>> = visibleAppInfosFlow

    if (showBlockAppFlow != null && blockAppListFlow != null) {
        tempListFlow = combine(
            tempListFlow,
            showBlockAppFlow,
            blockAppListFlow,
        ) { appInfos, showBlockApp, blockAppList ->
            if (showBlockApp) {
                appInfos
            } else {
                appInfos.filterNot { it.id in blockAppList }
            }
        }
    }

    tempListFlow = combine(
        tempListFlow,
        appGroupTypeFlow,
    ) { list, type ->
        if (type == 0) {
            return@combine emptyList()
        }
        if (AppGroupOption.normalObjects.all { it.include(type) }) {
            return@combine list
        }
        var resultList = list
        if (!AppGroupOption.SystemGroup.include(type)) {
            resultList = resultList.filterNot { it.isSystem }
        }
        if (!AppGroupOption.UserGroup.include(type)) {
            resultList = resultList.filterNot { !it.isSystem }
        }
        resultList
    }

    val showAllAppFlow = combine(
        tempListFlow,
        visibleAppInfosFlow,
    ) { a, b ->
        a.size == b.size
    }.stateInit(true)

    val searchStrFlow = MutableStateFlow("")
    val debounceSearchStrFlow = searchStrFlow.debounce(200)
        .stateInit(searchStrFlow.value)

    tempListFlow = tempListFlow.combine(debounceSearchStrFlow) { apps, str ->
        if (str.isBlank()) {
            apps
        } else {
            (apps.filter { a -> a.name.contains(str, true) } + apps.filter { a ->
                a.id.contains(
                    str,
                    true
                )
            }).distinct()
        }
    }.stateInit(emptyList())
    return AppFilter(
        searchStrFlow = searchStrFlow,
        appListFlow = tempListFlow,
        showAllAppFlow = showAllAppFlow,
    )
}
