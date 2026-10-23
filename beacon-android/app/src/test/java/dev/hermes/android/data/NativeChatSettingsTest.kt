package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import org.junit.Assert.*
import org.junit.Test

class NativeChatSettingsTest {
    @Test fun sqliteFlagsAndJsonFlagsAgreeWithoutTreatingUnknownAsTrue() {
        for (value in listOf(true, 1, 1L)) assertTrue(nativeSessionFlag(json("hidden" to value), "hidden"))
        for (value in listOf(false, 0, 0L, 2)) assertFalse(nativeSessionFlag(json("hidden" to value), "hidden"))
        assertTrue(storedSessionSettings(json("hidden" to 1, "archived" to 0)).getBoolean("archived"))
    }
    @Test fun eachSavedChatRestoresItsOwnModelProviderAndReasoning() {
        fun chat(model: String, effort: String) = json("model_config" to json("model" to model, "provider" to "custom:fixture", "reasoning_config" to json("enabled" to true, "effort" to effort)).toString())
        val a = storedSessionSettings(chat("model-a", "high"))
        val b = storedSessionSettings(chat("model-b", "low"))
        assertEquals("model-a", a.string("model")); assertEquals("high", a.string("reasoning_effort"))
        assertEquals("model-b", b.string("model")); assertEquals("low", b.string("reasoning_effort"))
        assertEquals("custom:fixture", b.string("model_provider"))
        assertEquals("none", storedSessionSettings(json("model_config" to json("reasoning_config" to json("enabled" to false)))).string("reasoning_effort"))
    }

    @Test fun freshChatHasNoOverridesAndLiveValuesOutrankSavedOnes() {
        val fresh = storedSessionSettings(json())
        assertEquals("", fresh.string("model")); assertEquals("", fresh.string("reasoning_effort"))
        val live = storedSessionSettings(json("model" to "live-model", "reasoning_effort" to "low", "model_config" to json("model" to "old-model", "reasoning_config" to json("effort" to "max"))))
        assertEquals("live-model", live.string("model")); assertEquals("low", live.string("reasoning_effort"))
    }

    @Test fun profileSettingsAndGlobalFlagsCannotRunAsChatCommands() {
        for ((name, arg) in listOf("approvals" to "smart", "tools" to "disable all", "model" to "other --global", "reasoning" to "high --global", "memory" to "approval off", "snapshot" to "restore previous")) {
            assertTrue("/$name $arg", chatCommandChangesProfile(name, arg))
        }
        assertFalse(chatCommandChangesProfile("reasoning", "high"))
        assertFalse(chatCommandChangesProfile("plan", "Explain the --global flag"))
        assertFalse(chatCommandChangesProfile("model", "other --provider custom:fixture"))
        assertEquals("other" to "custom:fixture", chatModelChoice("other --provider custom:fixture"))
        assertTrue(runCatching { chatModelChoice("other --global") }.isFailure)
    }
}
