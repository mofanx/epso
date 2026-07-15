package li.mofanx.epso.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.mofanx.epso.expansion.ExpansionTestPage
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.expansion.SyncSettingsPage
import li.mofanx.epso.ui.share.LocalMainViewModel

sealed class BottomNavItem(
    val key: Int,
    val label: String,
    val icon: ImageVector,
) {
    data object Home : BottomNavItem(
        key = 0,
        label = "首页",
        icon = PerfIcon.Home,
    )

    data object Expansion : BottomNavItem(
        key = 1,
        label = "扩展",
        icon = PerfIcon.TextFields,
    )

    data object Sync : BottomNavItem(
        key = 3,
        label = "同步",
        icon = PerfIcon.Autorenew,
    )

    data object Settings : BottomNavItem(
        key = 2,
        label = "设置",
        icon = PerfIcon.Settings,
    )

    companion object {
        val allSubObjects by lazy { arrayOf(Home, Expansion, Sync, Settings) }
    }
}

@Serializable
data object HomeRoute : NavKey

@Composable
private fun useSyncPage(): ScaffoldExt {
    return ScaffoldExt(
        navItem = BottomNavItem.Sync,
        topBar = {},
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            SyncSettingsPage(showBack = false)
        }
    }
}

@Composable
fun HomePage() {
    val mainVm = LocalMainViewModel.current
    viewModel<HomeVm>()
    val tab by mainVm.tabFlow.collectAsState()
    val pages = arrayOf(useControlPage(), useExpansionPage(), useSyncPage(), useSettingsPage())
    val page = pages.find { p -> p.navItem.key == tab } ?: pages.first()

    Scaffold(
        modifier = page.modifier,
        topBar = page.topBar,
        floatingActionButton = page.floatingActionButton,
        bottomBar = {
            NavigationBar {
                pages.forEach { page ->
                    NavigationBarItem(
                        selected = page.navItem.key == tab,
                        modifier = Modifier,
                        onClick = { mainVm.handleClickTab(page.navItem) },
                        icon = {
                            PerfIcon(
                                imageVector = page.navItem.icon,
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(text = page.navItem.label)
                        })
                }
            }
        },
        content = page.content
    )
}
