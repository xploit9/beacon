package dev.hermes.android.uiux.gaps

import dev.hermes.android.core.json
import dev.hermes.android.ui.recordError
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test

/** The one rule every page's error row follows: which record counts as failed, and what the row says. */
class ErrorTextGapsTest {
    @Test fun noRecordAndAnEmptyRecordAreNotErrors() {
        assertEquals("", recordError(null))
        assertEquals("", recordError(JSONObject()))
        assertEquals("", recordError(json("items" to emptyList<String>())))
    }

    @Test fun theMarkedErrorWinsOverTheServersOwnFields() {
        assertEquals("Reconnect to refresh this page.", recordError(json("_error" to "Reconnect to refresh this page.", "error" to "server said no")))
        assertEquals("server said no", recordError(json("error" to "server said no")))
    }

    @Test fun aBlankOrNullErrorFieldIsNotAnError() {
        assertEquals("", recordError(json("error" to JSONObject.NULL)))
        assertEquals("", recordError(json("error" to "")))
        assertEquals("", recordError(json("_error" to "")))
    }

    @Test fun aRefusedRecordNamesItsMessageOrASafeDefault() {
        assertEquals("Hermes refused", recordError(json("success" to false, "message" to "Hermes refused")))
        assertEquals("The server could not load this content. Try again.", recordError(json("success" to false)))
        assertEquals("A success flag that is true is not an error", "", recordError(json("success" to true, "message" to "ignored")))
    }

    @Test fun aRecordWithDataAndAnErrorStillCountsAsFailed() {
        // A failed refresh keeps the saved rows and adds the marker, so pages can show the rows with one line about the failure.
        assertEquals("Couldn't refresh", recordError(json("items" to listOf("a"), "_error" to "Couldn't refresh")))
    }

    /**
     * Pages show an offline load failure as `uiText(error)`; the literal comes from BeaconViewModel.load and the safe default from recordError,
     * and neither is in the catalog, so a non-English phone reads English in the error row.
     */
    @Test fun theOfflineAndDefaultErrorTextsAreTranslated() {
        val texts = listOf("Reconnect to refresh this page.", "The server could not load this content. Try again.")
        val missing = texts.flatMap { text -> GapsAssets.languages.filter { it != "en" && GapsAssets.translated(it, text).isBlank() }.map { "$it: $text" } }
        assertTrue("Error text without a translation:\n" + missing.take(20).joinToString("\n"), missing.isEmpty())
    }
}
