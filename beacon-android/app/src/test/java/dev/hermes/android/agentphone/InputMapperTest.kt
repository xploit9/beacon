package dev.hermes.android.agentphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMapperTest {
    @Test fun aPortraitFrameIsLetterboxedOnWholePixelsAndCentred() {
        // A 1080x2160 view: the 720x1600 frame is height-bound, 972 wide, with even side bars.
        val fit = fitFrame(1080, 2160, 720, 1600)
        assertEquals(Fit(54, 0, 972, 2160), fit)
        // The unfolded phone: a nearly square view leaves wide side bars.
        assertEquals(Fit(510, 0, 1080, 2400), fitFrame(2100, 2400, 720, 1600))
        // A wide view: the frame is width-bound when the view is short.
        assertEquals(Fit(0, 20, 400, 888), fitFrame(400, 928, 720, 1600))
    }

    @Test fun aLandscapeFrameFillsTheWidth() {
        val fit = fitFrame(1080, 2160, 1600, 720)
        assertEquals(1080, fit.width)
        assertEquals(486, fit.height)
        assertEquals((2160 - 486) / 2, fit.top)
    }

    @Test fun nothingFitsWithoutAFrameOrASize() {
        assertTrue(fitFrame(0, 100, 720, 1600).empty)
        assertTrue(fitFrame(1080, 2160, 0, 0).empty)
        assertFalse(Fit(0, 0, 0, 0).contains(0f, 0f))
    }

    @Test fun pointsBecomeFractionsOfTheFrameAsShownAndStayOnItsEdges() {
        val fit = Fit(54, 0, 972, 2160)
        assertEquals(0.5 to 0.5, fit.fraction(54 + 486f, 1080f))
        assertEquals(0.0 to 0.0, fit.fraction(10f, -50f))
        assertEquals(1.0 to 1.0, fit.fraction(5000f, 9000f))
        assertTrue(fit.contains(54f, 0f))
        assertFalse(fit.contains(53.9f, 10f))
        assertFalse(fit.contains(54f + 972f, 10f))
    }

    @Test fun theViewTurnsWithThePictureOnAPhoneAndStaysFreeOnALargeScreen() {
        assertEquals(ViewOrientation.Portrait, preferredOrientation(720, 1600, 411))
        assertEquals(ViewOrientation.Landscape, preferredOrientation(1600, 720, 411))
        assertEquals(ViewOrientation.Any, preferredOrientation(1600, 720, 720))
        assertEquals(ViewOrientation.Any, preferredOrientation(0, 0, 411))
    }

    @Test fun fingersGetIdsZeroToNineAndAnEleventhIsIgnored() {
        val slots = PointerSlots()
        val downs = (0L until 11L).map { slots.down(100 + it, (it % 10) / 10.0 to .5) }
        assertEquals((0..9).toList(), downs.take(10).map { it!!.id })
        assertNull(downs[10])
        assertEquals(TouchPoint(3, .3, .5, "up"), slots.up(103))
        // A freed id is the next one given out.
        assertEquals(3, slots.down(200, .2 to .2)!!.id)
    }

    @Test fun movesAreSentOnlyWhenAFingerMovesAndOnlyForFingersThatAreDown() {
        val slots = PointerSlots()
        assertNull(slots.move(7, .5 to .5))
        slots.down(7, .5 to .5)
        assertNull(slots.move(7, .5 to .5))
        assertEquals(TouchPoint(0, .6, .5, "move"), slots.move(7, .6 to .5))
        assertEquals(TouchPoint(0, .7, .5, "up"), slots.up(7, .7 to .5))
        assertNull(slots.up(7, .7 to .5))
        assertFalse(slots.active)
    }

    @Test fun aCancelledGestureLiftsEveryFingerWhereItWas() {
        val slots = PointerSlots()
        slots.down(1, .1 to .1); slots.down(2, .2 to .2); slots.move(2, .25 to .3)
        assertEquals(listOf(TouchPoint(0, .1, .1, "up"), TouchPoint(1, .25, .3, "up")), slots.cancelAll())
        assertFalse(slots.active)
        assertEquals(emptyList<TouchPoint>(), slots.cancelAll())
    }

    private class Phone(var helperReady: Boolean = true) {
        val steps = mutableListOf<TextStep>()
        fun send(step: TextStep): Boolean {
            if (step is TextStep.Type && !isPrintableAscii(step.text) && !helperReady) return false
            steps += step
            return true
        }
    }

    @Test fun asciiIsMirroredAsItIsTyped() {
        val mirror = TextMirror()
        val phone = Phone()
        for (field in listOf("k", "ka", "kan", "kanto")) assertTrue(mirror.sync(field, false, phone::send))
        assertEquals(listOf(TextStep.Type("k"), TextStep.Type("a"), TextStep.Type("n"), TextStep.Type("to")), phone.steps)
        assertEquals("kanto", mirror.sent)
    }

    @Test fun deletionsAreBackspacesCountedInWholeCharacters() {
        val mirror = TextMirror()
        val phone = Phone()
        val face = "😀"
        mirror.sync("ok", false, phone::send)
        mirror.sync("ok$face", true, phone::send)
        mirror.sync("ok", false, phone::send)
        mirror.sync("", false, phone::send)
        assertEquals(listOf(TextStep.Type("ok"), TextStep.Type(face), TextStep.Backspace(1), TextStep.Backspace(2)), phone.steps)
    }

    @Test fun anAutocorrectionReplacesOnlyTheEnd() {
        val mirror = TextMirror()
        val phone = Phone()
        mirror.sync("teh", false, phone::send)
        mirror.sync("the ", false, phone::send)
        assertEquals(listOf(TextStep.Type("teh"), TextStep.Backspace(2), TextStep.Type("he ")), phone.steps)
        assertEquals("the ", mirror.sent)
    }

    @Test fun otherAlphabetsWaitForAPauseAndGoWhole() {
        val mirror = TextMirror()
        val phone = Phone()
        assertFalse(mirror.sync("مر", false, phone::send))
        assertFalse(mirror.sync("مرحبا", false, phone::send))
        assertEquals(emptyList<TextStep>(), phone.steps)
        assertEquals("مرحبا", mirror.held("مرحبا"))
        assertTrue(mirror.sync("مرحبا", true, phone::send))
        assertEquals(listOf(TextStep.Type("مرحبا")), phone.steps)
        // ASCII typed after held text waits with it, so the order on the phone is the order typed.
        assertFalse(mirror.sync("مرحبا é1", false, phone::send))
        assertTrue(mirror.sync("مرحبا é1", true, phone::send))
        assertEquals(TextStep.Type(" é1"), phone.steps.last())
    }

    @Test fun anAccentAddedToALetterReplacesThatLetter() {
        val mirror = TextMirror()
        val phone = Phone()
        mirror.sync("cafe", false, phone::send)
        assertFalse(mirror.sync("café", false, phone::send))
        assertEquals(listOf(TextStep.Type("cafe"), TextStep.Backspace(1)), phone.steps)
        assertTrue(mirror.sync("café", true, phone::send))
        assertEquals(TextStep.Type("é"), phone.steps.last())
    }

    @Test fun textTheHelperCannotTakeYetStaysHeldUntilItCan() {
        val mirror = TextMirror()
        val phone = Phone(helperReady = false)
        assertFalse(mirror.sync("naïve", true, phone::send))
        assertEquals("", mirror.sent)
        phone.helperReady = true
        assertTrue(mirror.sync("naïve", true, phone::send))
        assertEquals(listOf(TextStep.Type("naïve")), phone.steps)
    }

    @Test fun aRefusedBackspaceChangesNothing() {
        val mirror = TextMirror()
        mirror.sync("abc", false) { true }
        assertFalse(mirror.sync("a", false) { it !is TextStep.Backspace })
        assertEquals("abc", mirror.sent)
    }

    @Test fun theRowsOwnBackspaceRemovesOneWholeCharacter() {
        assertEquals("ok", TextMirror.dropLast("ok😀"))
        assertEquals("caf", TextMirror.dropLast("café"))
        assertEquals("", TextMirror.dropLast(""))
        assertEquals(3, TextMirror.graphemes("a👨‍👩b"))
    }
}
