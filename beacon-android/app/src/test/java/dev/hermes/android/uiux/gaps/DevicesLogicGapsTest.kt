package dev.hermes.android.uiux.gaps

import dev.hermes.android.ui.devicesShown
import org.junit.Assert.assertEquals
import org.junit.Test

/** How many other phones the Devices section shows before "Show all". */
class DevicesLogicGapsTest {
    private fun phones(count: Int) = (1..count).map { "phone-$it" }

    @Test fun uptoFourOtherPhonesAreAllShown() {
        for (count in 0..4) assertEquals(phones(count), devicesShown(phones(count), showAll = false))
    }

    @Test fun moreThanFourFoldToTheFirstThreeUntilShowAll() {
        assertEquals(phones(3), devicesShown(phones(5), showAll = false))
        assertEquals(phones(3), devicesShown(phones(40), showAll = false))
        assertEquals(phones(5), devicesShown(phones(5), showAll = true))
        assertEquals(phones(40), devicesShown(phones(40), showAll = true))
    }

    @Test fun theFoldNeverHidesASingleRowBehindAButton() {
        // Four rows fit; hiding one row behind "Show all (5)" would cost a tap to save nothing, so five folds to three plus the button.
        assertEquals(4, devicesShown(phones(4), showAll = false).size)
        assertEquals(3, devicesShown(phones(5), showAll = false).size)
    }
}
