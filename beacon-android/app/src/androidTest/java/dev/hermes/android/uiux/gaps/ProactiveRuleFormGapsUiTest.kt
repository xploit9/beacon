package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.core.json
import dev.hermes.android.ui.openProactiveRuleForm
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The Assistant's Add a rule form: its four fields and labels, and the 300-character check that runs before anything is sent. */
class ProactiveRuleFormGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tooLong = "Add a rule of 300 characters or fewer."
    private val noConnection = "Reconnect before adding a rule."

    private fun failure(onResult: suspend (JSONObject) -> Unit, text: String): String? =
        runCatching { runBlocking { onResult(json("text" to text, "signal" to "anything", "action" to "notify", "night" to false)) } }.exceptionOrNull()?.message

    @Test fun theFormHasTheFourFieldsTheAssistantPageDescribes() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { openProactiveRuleForm(vm, "default", "Explain the rule", onCreated = {}, sources = listOf("calendar", "mail")) }
        val form = requireNotNull(vm.form.value) { "The rule form did not open" }
        assertEquals("Add a rule", form.title)
        assertEquals("Add rule", form.actionLabel)
        assertEquals("Explain the rule", form.explanation)
        assertEquals(listOf("text", "signal", "action", "night"), form.fields.map { it.key })
        assertEquals(listOf("Your rule", "Source", "When it matches", "Even at night"), form.fields.map { it.label })
        assertEquals("Anything leads the sources", listOf("anything", "calendar", "mail"), form.fields.single { it.key == "signal" }.options)
        assertEquals("Anything", form.fields.single { it.key == "signal" }.optionLabels.getValue("anything"))
        assertEquals(listOf("notify", "call"), form.fields.single { it.key == "action" }.options)
        assertEquals(mapOf("notify" to "Notify", "call" to "Call"), form.fields.single { it.key == "action" }.optionLabels)
        assertEquals("anything", form.seed.getString("signal"))
        assertEquals("A new rule starts as a notification", "notify", form.seed.getString("action"))
        assertFalse("A new rule starts silent at night", form.seed.getBoolean("night"))
    }

    @Test fun aRuleOfMoreThanThreeHundredCharactersIsRefusedBeforeAnythingIsSent() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { openProactiveRuleForm(vm, "default", "x", onCreated = {}) }
        val submit = requireNotNull(vm.form.value?.onResult)
        assertEquals(tooLong, failure(submit, "x".repeat(301)))
        assertEquals("A blank rule is refused with the same words", tooLong, failure(submit, ""))
        assertEquals(tooLong, failure(submit, "   \n  "))
    }

    @Test fun threeHundredCharactersPassTheCheckAndAreCountedAsWrittenNotAsBytes() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { openProactiveRuleForm(vm, "default", "x", onCreated = {}) }
        val submit = requireNotNull(vm.form.value?.onResult)
        // Past the length check the next step needs a connection, which this phone does not have: that is the error that proves it passed.
        assertEquals(noConnection, failure(submit, "x".repeat(300)))
        assertEquals("Padding around the rule is not counted", noConnection, failure(submit, "  " + "x".repeat(300) + "  "))
        assertEquals("300 emoji are 300 characters", noConnection, failure(submit, "😀".repeat(300)))
        assertEquals("301 emoji are one too many", tooLong, failure(submit, "😀".repeat(301)))
    }
}
