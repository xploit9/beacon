package dev.hermes.android.agentphone

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.AgentPhoneCheckRow
import dev.hermes.android.data.AgentPhoneCheckStatus
import dev.hermes.android.data.AgentPhoneChecks
import dev.hermes.android.data.AgentPhoneInstall
import dev.hermes.android.data.AgentPhoneInstallPhase
import dev.hermes.android.data.AgentPhoneInstallState
import dev.hermes.android.ui.AgentPhoneSetupActions
import dev.hermes.android.ui.AgentPhoneSetupScreen
import dev.hermes.android.ui.AgentPhoneSetupState
import dev.hermes.android.ui.AgentPhoneStage
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLocalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AgentPhoneSetupUsabilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(AgentPhoneSetupState())

    @Test fun acknowledgementAndCheckStayVisibleAtTwiceTextOnANarrowPhone() {
        var checks = 0
        showSetup(fontScale = 2f, widthDp = 320, actions = AgentPhoneSetupActions(
            confirm = { state = state.copy(confirmed = it) },
            check = { checks++; state = state.copy(stage = AgentPhoneStage.Checks) },
        ))
        val acknowledgement = compose.onNodeWithTag("agent-phone-confirm")
        val check = compose.onNodeWithTag("agent-phone-check")
        acknowledgement.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOff()
        val checkbox = acknowledgement.fetchSemanticsNode()
        assertFalse("The acknowledgement is inside the fading scroll area",
            generateSequence(checkbox.parent) { it.parent }.any { SemanticsProperties.VerticalScrollAxisRange in it.config })
        acknowledgement.assertFullyVisible()
        val label = layout(text("This computer has these"))
        assertEquals(2f, label.layoutInput.density.fontScale, 0f)
        val density = label.layoutInput.density.density
        assertTrue("Checkbox target height", checkbox.size.height >= 48f * density - 1f)
        val page = compose.onNodeWithTag("agent-phone-stage:Before").fetchSemanticsNode()
        assertTrue("The fixture is at most 320 dp wide", page.size.width <= 320f * density + 1f)
        check.assertIsNotEnabled().assertFullyVisible()
        layout(text("Check this computer"))

        for (copy in listOf("A real computer with about", "Hermes stays on, with sign-in turned on",
            "Bank and wallet apps don't work on it",
            "Apps on the phone can reach programs on your Hermes computer that don't ask for a sign-in.")) {
            layout(text(copy).performScrollTo())
        }
        acknowledgement.assertFullyVisible()
        check.assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, checks) }
        acknowledgement.performClick().assertIsOn()
        check.assertIsEnabled()
        acknowledgement.performClick().assertIsOff()
        check.assertIsNotEnabled()
        acknowledgement.performClick()
        check.performClick()
        compose.onNodeWithTag("agent-phone-stage:Checks").assertExists()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun arabicPathAndStorageReadTogetherAtOnePointEightTimesText() {
        showSetup(language = "ar", fontScale = 1.8f)
        val prerequisites = "كمبيوتر حقيقي فيه حوالي"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(prerequisites, substring = true).fetchSemanticsNodes().isNotEmpty() }
        val needs = layout(text(prerequisites).performScrollTo())
        assertEquals(1.8f, needs.layoutInput.density.fontScale, 0f)
        assertTokenFits(needs, "16\u00A0GB")
        assertTokenFits(needs, "8\u00A0GB")

        val path = "/dev/kvm"
        val fix = "Give the container read and write access to /dev/kvm, or run Hermes on a computer instead."
        for (fixCode in listOf(null, "check.container")) {
            compose.runOnIdle {
                state = AgentPhoneSetupState(stage = AgentPhoneStage.Checks, checks = AgentPhoneChecks(false, false,
                    listOf(AgentPhoneCheckRow("container", AgentPhoneCheckStatus.Fail, "This computer can't run a virtual phone.", fix, fixCode = fixCode))))
            }
            val remediation = layout(text("امنح الحاوية صلاحية القراءة والكتابة").performScrollTo())
            val start = assertTokenFits(remediation, path)
            assertEquals(ResolvedTextDirection.Rtl, remediation.getParagraphDirection(start))
            assertTrue("The leading slash is visually before dev",
                remediation.getBoundingBox(start).center.x < remediation.getBoundingBox(start + 1).center.x)
        }

        for ((code, quantity) in listOf(null to "3\u00A0GB", "install.space" to "3\u00A0غيغابايت")) {
            compose.runOnIdle {
                state = AgentPhoneSetupState(stage = AgentPhoneStage.Download, install = AgentPhoneInstall(
                    state = AgentPhoneInstallState.Failed, phase = AgentPhoneInstallPhase.Download,
                    line = "Not enough free space. Free up 3 GB on the Hermes computer.", lineCode = code, lineArgs = mapOf("n" to "3"),
                ))
            }
            assertTokenFits(layout(text("لا توجد مساحة فارغة كافية.").performScrollTo()), quantity)
        }
    }

    private val developerToolsWarning = AgentPhoneCheckRow("developer_tools", AgentPhoneCheckStatus.Warn,
        "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.",
        needsContinue = true, lineCode = "check.developer_tools")

    @Test fun warningAcknowledgementAndContinueHaveSeparateActionsAtNormalText() {
        val warning = developerToolsWarning
        val blocker = AgentPhoneCheckRow("sign_in", AgentPhoneCheckStatus.Fail, "Turn on Hermes sign-in first.")
        val readyWithWarning = AgentPhoneChecks(true, true, listOf(warning))
        state = AgentPhoneSetupState(stage = AgentPhoneStage.Checks, checks = AgentPhoneChecks(false, true, listOf(blocker, warning)))
        showSetup(actions = AgentPhoneSetupActions(
            continueWarning = { state = state.copy(continued = true) },
            check = { state = state.copy(checks = readyWithWarning, continued = false) },
            toTerms = { state = state.copy(stage = AgentPhoneStage.Terms) },
        ))
        val acknowledge = compose.onNodeWithTag("agent-phone-continue:developer_tools")
        val advance = compose.onNodeWithTag("agent-phone-checks-continue")
        advance.assertTextEquals("Continue").assertIsNotEnabled()
        compose.onNodeWithTag("agent-phone-details:developer_tools").performScrollTo().performClick()
        layout(text("Close Android developer tools on this computer, or apps on Maya's phone can control the devices they connect to.").performScrollTo())
        acknowledge.performScrollTo().assertTextEquals("I understand").performClick()
        acknowledge.assertDoesNotExist()
        advance.assertIsNotEnabled()
        compose.onNodeWithTag("agent-phone-stage:Checks").assertExists()

        compose.onNodeWithTag("agent-phone-recheck:developer_tools").performScrollTo().performClick()
        advance.assertIsNotEnabled()
        acknowledge.performScrollTo().assertTextEquals("I understand").performClick()
        acknowledge.assertDoesNotExist()
        advance.assertIsEnabled()
        compose.onNodeWithTag("agent-phone-stage:Checks").assertExists()
        advance.performClick()
        compose.onNodeWithTag("agent-phone-stage:Terms").assertExists()
    }

    @Test fun arabicWarningAcknowledgementShowsItsOwnLabelAndGatesContinue() {
        state = AgentPhoneSetupState(stage = AgentPhoneStage.Checks,
            checks = AgentPhoneChecks(true, true, listOf(developerToolsWarning)))
        showSetup(language = "ar", actions = AgentPhoneSetupActions(
            continueWarning = { state = state.copy(continued = true) },
            toTerms = { state = state.copy(stage = AgentPhoneStage.Terms) },
        ))
        val checks = compose.onNodeWithTag("agent-phone-stage:Checks")
        val acknowledge = compose.onNodeWithTag("agent-phone-continue:developer_tools")
        val advance = compose.onNodeWithTag("agent-phone-checks-continue")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("متابعة").fetchSemanticsNodes().isNotEmpty() }
        advance.assertTextEquals("متابعة").assertIsNotEnabled().assertIsDisplayed()
        acknowledge.performScrollTo().assertTextEquals("فهمت").assertIsEnabled().assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("captureWarning") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = requireNotNull(context.getExternalFilesDir("phone-warning-encoding-122"))
            File(directory, "arabic-warning.png").outputStream().use {
                check(checks.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }

        acknowledge.performClick()
        acknowledge.assertDoesNotExist()
        checks.assertIsDisplayed()
        advance.assertTextEquals("متابعة").assertIsEnabled().performClick()
        compose.onNodeWithTag("agent-phone-stage:Terms").assertExists()
    }

    private fun showSetup(
        language: String = "en",
        fontScale: Float = 1f,
        widthDp: Int? = null,
        actions: AgentPhoneSetupActions = AgentPhoneSetupActions(),
    ) {
        compose.setContent {
            val density = if (widthDp == null) LocalDensity.current.density
                else compose.activity.resources.displayMetrics.widthPixels.toFloat() / widthDp
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalAssistantName provides "Maya",
            ) {
                NativeLocalization(language) { BeaconTheme(dark = true) { AgentPhoneSetupScreen(state, actions) } }
            }
        }
    }

    private fun text(value: String) = compose.onNodeWithText(value, substring = true, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.assertFullyVisible() {
        assertIsDisplayed()
        val node = fetchSemanticsNode()
        assertTrue("Content is clipped: $node",
            node.boundsInRoot.height + 1f >= node.size.height && node.boundsInRoot.width + 1f >= node.size.width)
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        node.assertFullyVisible()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().also { assertFalse("Text overflow in ${it.layoutInput.text}", it.hasVisualOverflow) }
    }

    private fun assertTokenFits(layout: TextLayoutResult, token: String): Int {
        val text = layout.layoutInput.text.text
        val first = text.indexOf(token)
        assertTrue("Missing $token in $text", first >= 0)
        val last = first + token.lastIndex
        assertEquals("Wrapped $token in $text", layout.getLineForOffset(first), layout.getLineForOffset(last))
        for (offset in first..last) {
            val box = layout.getBoundingBox(offset)
            assertTrue("Clipped $token in $text", box.left >= -1f && box.right <= layout.size.width + 1f)
        }
        return first
    }
}
