package dev.hermes.android.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lane V2C: the owner's own words (a title, a name, a time) in a card line or the activity list are isolated, so a name in the other
 * script can neither flip the sentence round it nor lose its punctuation; the contract's one line stays plain.
 */
class PhoneLineShownTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText()

    private val open = "⁨"
    private val close = "⁩"

    @Test fun shownIsolatesEachOfTheOwnersWordsAndTextStaysPlain() {
        val line = PhoneLine("Add '{0}' {1} to {2}", listOf("موعد الطبيب", "Tue 4 Nov 3:00 PM", "Personal"))
        assertEquals("Add '${open}موعد الطبيب$close' ${open}Tue 4 Nov 3:00 PM$close to ${open}Personal$close", line.shown())
        assertEquals("Add 'موعد الطبيب' Tue 4 Nov 3:00 PM to Personal", line.text())
    }

    @Test fun theContractsOneLineKeepsTheWordsPlain() {
        val card = PhoneCard(listOf(PhoneLine("Call {0}", listOf("Mom")), PhoneLine("Place: {0}", listOf("عيادة"))))
        assertEquals("Call Mom; Place: عيادة", card.describe())
        assertEquals("Call ${open}Mom$close", card.headline.shown())
    }

    @Test fun aNumberThatIsAlreadyIsolatedIsNotWrappedAgain() {
        val number = "⁦+966501234567⁩"
        assertEquals("Write to $number", PhoneLine("Write to {0}", listOf(number)).shown())
        assertEquals("Write to $number", PhoneLine("Write to {0}", listOf(number)).text())
    }

    @Test fun aLineWithoutWordsAndAnEmptyWordAreLeftAlone() {
        assertEquals("Read your calendars", PhoneLine("Read your calendars").shown())
        assertEquals("Call ", PhoneLine("Call {0}", listOf("")).shown())
        assertEquals("", isolateBidi(""))
        assertEquals("${open}x$close", isolateBidi("x"))
    }

    @Test fun eachHoleIsFilledOnceFromTheOriginalWords() {
        // A word that looks like a hole is the owner's text, not another place to fill.
        assertEquals("{1} and b", PhoneLine("{0} and {1}", listOf("{1}", "b")).text())
        assertEquals("${open}{1}$close and ${open}b$close", PhoneLine("{0} and {1}", listOf("{1}", "b")).shown())
        // A hole with no word stays as it is.
        assertEquals("a {2}", PhoneLine("{0} {2}", listOf("a")).text())
        assertEquals("{99999999999}", PhoneLine("{99999999999}", listOf("a")).text())
    }

    @Test fun theCardsAndTheActivityListDrawTheShownLine() {
        assertTrue(source("phone/PhoneTools.kt").contains("line.args).shown()"))
        assertTrue(source("ui/PhoneAccessSettings.kt").contains("PhoneLine(uiText(line.template), line.args).shown()"))
        // The card the owner sees and the activity list both draw through phoneLineText; the contract's line does not.
        assertTrue(source("ui/HermesPhoneApproval.kt").contains("phoneLineText(shown.headline)"))
        assertTrue(source("phone/PhoneModel.kt").contains("fun describe(): String = lines.joinToString(\"; \") { it.text() }"))
    }
}
