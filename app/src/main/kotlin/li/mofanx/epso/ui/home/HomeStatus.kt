package li.mofanx.epso.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import li.mofanx.epso.R
import li.mofanx.epso.expansion.ExpansionService
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.permission.appOpsRestrictedFlow
import li.mofanx.epso.permission.writeSecureSettingsState
import li.mofanx.epso.service.StatusService
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.ui.share.LocalMainViewModel

/**
 * 首页总状态决策
 */
sealed class HomeOverallStatus {
    abstract val title: String
    abstract val subtitle: String
    abstract val primaryAction: String
    abstract val secondaryAction: String?

    data class Available(
        override val title: String,
        override val subtitle: String,
        override val primaryAction: String,
        override val secondaryAction: String? = null,
    ) : HomeOverallStatus()

    data class NotAuthorized(
        override val title: String,
        override val subtitle: String,
        override val primaryAction: String,
        override val secondaryAction: String? = null,
    ) : HomeOverallStatus()

    data class Disabled(
        override val title: String,
        override val subtitle: String,
        override val primaryAction: String,
        override val secondaryAction: String? = null,
    ) : HomeOverallStatus()

    data class Fault(
        override val title: String,
        override val subtitle: String,
        override val primaryAction: String,
        override val secondaryAction: String? = null,
    ) : HomeOverallStatus()

    data class Restricted(
        override val title: String,
        override val subtitle: String,
        override val primaryAction: String,
        override val secondaryAction: String? = null,
    ) : HomeOverallStatus()
}

@Composable
fun resolveHomeOverallStatus(): HomeOverallStatus {
    val mainVm = LocalMainViewModel.current
    val appOpsRestricted by appOpsRestrictedFlow.collectAsState()
    val a11yRunning by ExpansionService.isRunning.collectAsState()
    val a11yServiceEnabled by mainVm.a11yServiceEnabledFlow.collectAsState()
    val writeSecureSettings by writeSecureSettingsState.stateFlow.collectAsState()
    val statusServiceRunning by StatusService.isRunning.collectAsState()
    val store by storeFlow.collectAsState()
    val matchDict by MatchStore.matchDict.collectAsState()
    val matchCount = matchDict.values.distinct().size

    return when {
        appOpsRestricted -> HomeOverallStatus.Restricted(
            title = stringResource(R.string.home_status_not_authorized),
            subtitle = stringResource(R.string.home_permission_restricted_title),
            primaryAction = stringResource(R.string.home_permission_restricted_action),
            secondaryAction = stringResource(R.string.action_learn_more),
        )

        a11yRunning -> HomeOverallStatus.Available(
            title = stringResource(R.string.home_status_available),
            subtitle = stringResource(R.string.home_status_reason_available),
            primaryAction = if (matchCount == 0) {
                stringResource(R.string.home_action_create_rule)
            } else {
                stringResource(R.string.home_action_test)
            },
            secondaryAction = stringResource(R.string.home_action_import_rule),
        )

        a11yServiceEnabled -> HomeOverallStatus.Fault(
            title = stringResource(R.string.home_status_fault),
            subtitle = stringResource(R.string.home_status_reason_fault),
            primaryAction = stringResource(R.string.home_action_fix),
            secondaryAction = stringResource(R.string.home_action_test),
        )

        writeSecureSettings -> HomeOverallStatus.Disabled(
            title = stringResource(R.string.home_status_disabled),
            subtitle = stringResource(R.string.home_status_reason_disabled),
            primaryAction = stringResource(R.string.home_action_open),
            secondaryAction = stringResource(R.string.home_action_test),
        )

        else -> HomeOverallStatus.NotAuthorized(
            title = stringResource(R.string.home_status_not_authorized),
            subtitle = stringResource(R.string.home_status_reason_not_authorized),
            primaryAction = stringResource(R.string.home_action_authorize),
            secondaryAction = stringResource(R.string.home_action_test),
        )
    }
}

data class HomeServiceState(
    val a11yRunning: Boolean,
    val a11yEnabled: Boolean,
    val writeSecureSettings: Boolean,
    val statusServiceRunning: Boolean,
    val statusServiceEnabled: Boolean,
)

@Composable
fun resolveHomeServiceState(): HomeServiceState {
    val mainVm = LocalMainViewModel.current
    val a11yRunning by ExpansionService.isRunning.collectAsState()
    val a11yEnabled by mainVm.a11yServiceEnabledFlow.collectAsState()
    val writeSecureSettings by writeSecureSettingsState.stateFlow.collectAsState()
    val statusServiceRunning by StatusService.isRunning.collectAsState()
    val store by storeFlow.collectAsState()
    return HomeServiceState(
        a11yRunning = a11yRunning,
        a11yEnabled = a11yEnabled,
        writeSecureSettings = writeSecureSettings,
        statusServiceRunning = statusServiceRunning,
        statusServiceEnabled = store.enableStatusService,
    )
}

@Composable
fun resolveQuickStartProgress(): QuickStartProgress {
    val a11yRunning by ExpansionService.isRunning.collectAsState()
    val matchDict by MatchStore.matchDict.collectAsState()
    val matchCount = matchDict.values.distinct().size
    val hasEverExpanded by ExpansionService.hasEverExpanded.collectAsState()
    val verified = a11yRunning && matchCount > 0 && hasEverExpanded
    return QuickStartProgress(
        steps = listOf(
            QuickStartStep(
                stringResource(R.string.home_quick_start_step_authorize),
                completed = a11yRunning,
            ),
            QuickStartStep(
                stringResource(R.string.home_quick_start_step_create),
                completed = matchCount > 0,
            ),
            QuickStartStep(
                stringResource(R.string.home_quick_start_step_verify),
                completed = verified,
            ),
        )
    )
}

data class QuickStartStep(
    val label: String,
    val completed: Boolean,
)

data class QuickStartProgress(
    val steps: List<QuickStartStep>,
    val allCompleted: Boolean = steps.all { it.completed },
)
