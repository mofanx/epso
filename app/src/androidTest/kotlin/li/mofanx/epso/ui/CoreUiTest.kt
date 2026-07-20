package li.mofanx.epso.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import li.mofanx.epso.MainViewModel
import li.mofanx.epso.expansion.Match
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.ui.expansion.MatchEditorPage
import li.mofanx.epso.ui.expansion.MatchEditorRoute
import li.mofanx.epso.ui.expansion.MatchListPage
import li.mofanx.epso.ui.expansion.MatchListRoute
import li.mofanx.epso.ui.home.HomeHeroCard
import li.mofanx.epso.ui.home.HomeOverallStatus
import li.mofanx.epso.ui.share.LocalMainViewModel
import li.mofanx.epso.ui.style.AppTheme
import li.mofanx.epso.util.matchesFolder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoreUiTest {

    @get:Rule
    val composeTestRule: ComposeContentTestRule = createComposeRule()

    private lateinit var baseFile: File

    @Before
    fun setup() {
        runBlocking {
            matchesFolder.deleteRecursively()
            baseFile = MatchStore.createFile("base")
            MatchStore.addMatch(baseFile, Match(trigger = ":hello", replace = "world"))
        }
    }

    @Test
    fun homeHeroCard_displaysAvailableStatus() {
        composeTestRule.setContent {
            AppTheme {
                HomeHeroCard(
                    status = HomeOverallStatus.Available(
                        title = "Ready",
                        subtitle = "Text expansion service is running.",
                        primaryAction = "Test",
                        secondaryAction = "Import",
                    ),
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Ready").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text expansion service is running.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import").assertIsDisplayed()
    }

    @Test
    fun homeHeroCard_displaysDisabledStatus() {
        composeTestRule.setContent {
            AppTheme {
                HomeHeroCard(
                    status = HomeOverallStatus.Disabled(
                        title = "Disabled",
                        subtitle = "Accessibility service is turned off.",
                        primaryAction = "Open",
                        secondaryAction = "Test",
                    ),
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Disabled").assertIsDisplayed()
        composeTestRule.onNodeWithText("Accessibility service is turned off.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test").assertIsDisplayed()
    }

    @Test
    fun matchListPage_searchFiltersMatches() {
        composeTestRule.setContent {
            AppTheme {
                CompositionLocalProvider(LocalMainViewModel provides viewModel<MainViewModel>()) {
                    MatchListPage(route = MatchListRoute())
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(":hello").assertExists()
        composeTestRule.onNodeWithText("world").assertExists()

        composeTestRule.onNodeWithText("Search").performTextInput("xyz")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No matching rules").assertExists()

        composeTestRule.onNodeWithText("Clear").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(":hello").assertExists()
    }

    @Test
    fun matchEditorPage_validationShowsErrorsAndEnablesSave() {
        composeTestRule.setContent {
            AppTheme {
                CompositionLocalProvider(LocalMainViewModel provides viewModel<MainViewModel>()) {
                    MatchEditorPage(
                        route = MatchEditorRoute(sourceFilePath = baseFile.absolutePath),
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()

        composeTestRule.onNodeWithText("Replace with").performTextInput("world")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Trigger word cannot be empty").assertExists()

        composeTestRule.onNodeWithText("Trigger word").performTextInput("a")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun matchEditorPage_unsavedChangesDialogShownOnBack() {
        composeTestRule.setContent {
            AppTheme {
                CompositionLocalProvider(LocalMainViewModel provides viewModel<MainViewModel>()) {
                    MatchEditorPage(
                        route = MatchEditorRoute(sourceFilePath = baseFile.absolutePath),
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Replace with").performTextInput("x")
        composeTestRule.waitForIdle()

        Espresso.pressBack()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Unsaved changes").assertExists()
        composeTestRule.onNodeWithText("Discard").assertExists()
    }
}
