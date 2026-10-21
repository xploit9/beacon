package dev.hermes.android.push

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRestartRecoveryTest {
    @Test fun eachRecoveryStepRunsOnceEvenWhenAnotherStepFails() {
        for (failure in -1..2) {
            val completed = mutableListOf<Int>()
            fun step(index: Int) { completed += index; if (index == failure) error("Synthetic recovery failure") }
            restoreAfterRestart({ step(0) }, { step(1) }, { step(2) })
            assertEquals(listOf(0, 1, 2), completed)
        }
    }
}
