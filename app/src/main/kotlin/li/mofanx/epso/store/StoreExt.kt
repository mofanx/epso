package li.mofanx.epso.store

import kotlinx.coroutines.Dispatchers
import li.mofanx.epso.appScope
import li.mofanx.epso.util.launchTry

val storeFlow by lazy {
    createAnyFlow(
        key = "store",
        default = { SettingsStore() }
    )
}

fun initStore() = appScope.launchTry(Dispatchers.IO) {
    // preload
    storeFlow.value
}
