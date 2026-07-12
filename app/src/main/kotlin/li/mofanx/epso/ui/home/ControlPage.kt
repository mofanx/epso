package li.mofanx.epso.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import li.mofanx.epso.MainActivity
import li.mofanx.epso.R
import li.mofanx.epso.expansion.ExpansionService
import li.mofanx.epso.expansion.ExpansionTestPage
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.ui.expansion.FilesRoute
import li.mofanx.epso.ui.expansion.GlobalVarsRoute
import li.mofanx.epso.ui.expansion.MatchListRoute
import li.mofanx.epso.ui.expansion.PackageStoreRoute
import li.mofanx.epso.permission.writeSecureSettingsState
import li.mofanx.epso.service.StatusService
import li.mofanx.epso.service.switchAutomatorService
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.ui.AuthA11yRoute
import li.mofanx.epso.ui.component.PerfIcon
import li.mofanx.epso.ui.component.PerfIconButton
import li.mofanx.epso.ui.component.PerfSwitch
import li.mofanx.epso.ui.component.PerfTopAppBar
import li.mofanx.epso.ui.component.useScrollBehaviorState
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.EmptyHeight
import li.mofanx.epso.ui.style.itemHorizontalPadding
import li.mofanx.epso.ui.style.itemVerticalPadding
import li.mofanx.epso.ui.style.surfaceCardColors
import li.mofanx.epso.util.throttle

@Composable
fun useExpansionPage(): ScaffoldExt {
    val mainVm = LocalMainViewModel.current
    val scrollKey = rememberSaveable { mutableIntStateOf(0) }
    val (scrollBehavior, _) = useScrollBehaviorState(scrollKey)

    LaunchedEffect(null) {
        mainVm.resetPageScrollEvent.collect {
            if (it == BottomNavItem.Expansion) scrollKey.intValue++
        }
    }

    return ScaffoldExt(
        navItem = BottomNavItem.Expansion,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(stringResource(R.string.expansion_title)) },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .padding(horizontal = itemHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2),
        ) {
            EntryRow(
                onPackageStore = { mainVm.navigatePage(PackageStoreRoute) },
                onGlobalVars = { mainVm.navigatePage(GlobalVarsRoute) },
                onFiles = { mainVm.navigatePage(FilesRoute) },
                onRules = { mainVm.navigatePage(MatchListRoute()) },
                modifier = Modifier.padding(top = itemVerticalPadding),
            )
            ExpansionTestPage()
        }
    }
}

private data class ExpansionEntry(
    val icon: ImageVector,
    val label: String,
    val route: () -> Unit,
)

@Composable
private fun EntryRow(
    onPackageStore: () -> Unit,
    onGlobalVars: () -> Unit,
    onFiles: () -> Unit,
    onRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = listOf(
        ExpansionEntry(
            icon = PerfIcon.RocketLaunch,
            label = stringResource(R.string.expansion_entry_package_store),
            route = onPackageStore,
        ),
        ExpansionEntry(
            icon = PerfIcon.TextFields,
            label = stringResource(R.string.expansion_entry_global_vars),
            route = onGlobalVars,
        ),
        ExpansionEntry(
            icon = PerfIcon.Layers,
            label = stringResource(R.string.expansion_entry_files),
            route = onFiles,
        ),
        ExpansionEntry(
            icon = PerfIcon.FormatListBulleted,
            label = stringResource(R.string.expansion_entry_rules),
            route = onRules,
        ),
    )

    Row(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { EntryCard(modifier = Modifier.weight(1f), entry = it) }
    }
}

@Composable
private fun EntryCard(
    entry: ExpansionEntry,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle { entry.route() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PerfIcon(imageVector = entry.icon)
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun useControlPage(): ScaffoldExt {
    val context = LocalActivity.current as MainActivity
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<HomeVm>()
    val scrollKey = rememberSaveable { mutableIntStateOf(0) }
    val (scrollBehavior, scrollState) = useScrollBehaviorState(scrollKey)
    val status = resolveHomeOverallStatus()
    val serviceState = resolveHomeServiceState()
    val quickStart = resolveQuickStartProgress()
    val store by storeFlow.collectAsState()
    val manageRunning by StatusService.isRunning.collectAsState()
    val writeSecureSettings by writeSecureSettingsState.stateFlow.collectAsState()

    LaunchedEffect(null) {
        mainVm.resetPageScrollEvent.collect {
            if (it == BottomNavItem.Home) {
                scrollKey.intValue++
            }
        }
    }

    val secondaryAction = when (status) {
        is HomeOverallStatus.Available -> {
            { mainVm.navigatePage(FilesRoute) }
        }

        is HomeOverallStatus.Restricted -> null
        else -> {
            { mainVm.handleClickTab(BottomNavItem.Expansion) }
        }
    }

    return ScaffoldExt(
        navItem = BottomNavItem.Home,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Text(text = stringResource(R.string.home_title))
                },
                actions = {
                    PerfIconButton(
                        imageVector = PerfIcon.RocketLaunch,
                        onClickLabel = stringResource(R.string.auth_a11y_title),
                        contentDescription = stringResource(R.string.auth_a11y_title),
                        onClick = throttle {
                            mainVm.navigatePage(AuthA11yRoute)
                        },
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = itemHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2),
        ) {
            HomeHeroCard(
                status = status,
                onPrimaryAction = {
                    when (status) {
                        is HomeOverallStatus.Available -> {
                            if (MatchStore.matchCount == 0) {
                                mainVm.navigatePage(MatchListRoute())
                            } else {
                                mainVm.handleClickTab(BottomNavItem.Expansion)
                            }
                        }

                        is HomeOverallStatus.Disabled -> switchAutomatorService()
                        else -> mainVm.navigatePage(AuthA11yRoute)
                    }
                },
                onSecondaryAction = secondaryAction,
            )

            QuickStartProgressCard(progress = quickStart)

            val a11ySubtitle = when {
                serviceState.a11yRunning -> stringResource(R.string.home_service_a11y_running)
                serviceState.a11yEnabled -> stringResource(R.string.home_service_a11y_fault)
                serviceState.writeSecureSettings -> stringResource(R.string.home_service_a11y_off)
                else -> stringResource(R.string.home_service_a11y_unauthorized)
            }

            PageSwitchItemCard(
                imageVector = PerfIcon.Memory,
                title = stringResource(R.string.home_service_a11y),
                subtitle = a11ySubtitle,
                checked = serviceState.a11yRunning,
                onCheckedChange = { newEnabled ->
                    if (newEnabled && !writeSecureSettingsState.value) {
                        mainVm.navigatePage(AuthA11yRoute)
                    } else {
                        switchAutomatorService()
                    }
                },
            )

            PageSwitchItemCard(
                imageVector = PerfIcon.Notifications,
                title = stringResource(R.string.home_service_notification),
                subtitle = stringResource(R.string.home_service_notification_subtitle),
                checked = manageRunning && store.enableStatusService,
                onCheckedChange = {
                    if (it) {
                        context.lifecycleScope.launch { StatusService.requestStart(context) }
                    } else {
                        StatusService.stop()
                        storeFlow.value = store.copy(
                            enableStatusService = false
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}


@Composable
private fun PageSwitchItemCard(
    imageVector: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val onClick = throttle { onCheckedChange(!checked) }
    val clickLabel = stringResource(R.string.action_toggle, title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.onClick(label = clickLabel, action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PerfIcon(
                imageVector = imageVector,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(8.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(itemHorizontalPadding))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            PerfSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}
