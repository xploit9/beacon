package dev.hermes.android.data

import dev.hermes.android.ui.ActionForm
import dev.hermes.android.ui.Field
import dev.hermes.android.ui.FieldType
import dev.hermes.android.ui.formBody
import org.junit.Assert.*
import org.junit.Test

class NativeLocalErrorTest {
    @Test fun localConversationRefusalsKeepTheirRecoveryInstructions() {
        for (message in listOf(
            "Stop the current reply before deleting this conversation.",
            "End this call before changing its conversation.",
        )) assertEquals(message, appErrorText(IllegalArgumentException(message)))
        assertEquals("Hermes couldn't do that. Try again.", appErrorText(IllegalArgumentException("Stop the worker at C:/private/account")))
    }

    @Test fun requiredChoiceExplainsTheMissingFieldAndOptionalBlankStillSaves() {
        val field = Field("advanced.service_tier", "Service tier", FieldType.CHOICE, required = true, options = listOf("", "priority"))
        val form = ActionForm("Choice", "", listOf(field), values = mapOf(field.key to ""))
        val failure = runCatching { formBody(form) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("Enter Service tier.", appErrorText(requireNotNull(failure)))
        val body = formBody(form.copy(fields = listOf(field.copy(required = false))))
        assertEquals("", body.getJSONObject("advanced").getString("service_tier"))
    }
}
