package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.BuildConfig
import dev.hermes.android.ui.LicenseScreen
import dev.hermes.android.ui.licenseDocuments
import dev.hermes.android.ui.openSourceWorks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** About & licenses on a device: every notice sheet opens with the source's own words and closes, the license texts open, and Source code opens the project page. */
class LicensesGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Recorder : UriHandler { val opened = mutableListOf<String>(); override fun openUri(uri: String) { opened += uri } }

    private fun show(vm: BeaconViewModel, handler: UriHandler = Recorder()) =
        compose.showPage { CompositionLocalProvider(LocalUriHandler provides handler) { LicenseScreen(vm) } }

    @Test fun theHeroNamesTheVersionAndLicenseAndEveryRowIsThere() = withClientlessVm(compose) { vm ->
        show(vm)
        compose.onNodeWithTag("licenses-page").assertIsDisplayed()
        compose.onNodeWithTag("license-hero").assertIsDisplayed()
        compose.onNodeWithText("Beacon ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        // The hero names the license and the Beacon row repeats it, so the hero's own text is the one asked for.
        compose.onNode(hasText("GPL-3.0-only") and hasAnyAncestor(hasTestTag("license-hero"))).assertIsDisplayed()
        compose.onNodeWithTag("license-source").assertIsDisplayed().assertHasClickAction()
        assertEquals(9, openSourceWorks.size)
        for (work in openSourceWorks) {
            compose.onNodeWithTag("licenses-page").performScrollToNode(hasTestTag("license-work-${work.name}"))
            compose.onNodeWithTag("license-work-${work.name}").assertIsDisplayed().assertTextContains(work.license)
        }
        assertEquals(12, licenseDocuments.size)
        // The page is a lazy list: a row far down is not composed until the list is scrolled to it.
        for (document in licenseDocuments) {
            compose.onNodeWithTag("licenses-page").performScrollToNode(hasTestTag("license-document-$document"))
            compose.onNodeWithTag("license-document-$document").assertIsDisplayed()
        }
    }

    @Test fun sourceCodeOpensTheProjectPage() = withClientlessVm(compose) { vm ->
        val recorder = Recorder()
        show(vm, recorder)
        compose.onNodeWithTag("license-source").performClick()
        compose.runOnIdle { assertEquals(listOf("https://github.com/xploit9/beacon"), recorder.opened) }
    }

    @Test fun everyBundledWorkOpensItsNoticeWordForWordAndClosesAgain() = withClientlessVm(compose) { vm ->
        show(vm)
        for (work in openSourceWorks) {
            compose.onNodeWithTag("license-work-${work.name}").performScrollTo().performClick()
            compose.onNodeWithText(work.notice).assertIsDisplayed()
            // The sheet repeats the work's name as its title, next to the row that opened it.
            compose.onAllNodesWithText(work.name).assertCountEquals(2)
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(work.notice).fetchSemanticsNodes().isEmpty() }
            compose.onAllNodesWithText(work.name).assertCountEquals(1)
        }
    }

    @Test fun everyLicenseTextOpensWithTheFilesOwnWords() = withClientlessVm(compose) { vm ->
        show(vm)
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        for (document in licenseDocuments) {
            val path = if (document.startsWith("rich/")) document else "licenses/$document"
            val firstLine = assets.open(path).bufferedReader().useLines { lines -> lines.map(String::trim).first(String::isNotBlank) }
            compose.onNodeWithTag("licenses-page").performScrollToNode(hasTestTag("license-document-$document"))
            compose.onNodeWithTag("license-document-$document").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(firstLine, substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("License text unavailable").assertDoesNotExist()
            compose.onAllNodesWithText(document).assertCountEquals(2)
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(document).fetchSemanticsNodes().size == 1 }
        }
    }
}
