package dev.hermes.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyIncidentJournalTest {
    private fun folder(): File = Files.createTempDirectory("beacon-journal").toFile().apply { deleteOnExit() }

    @Test fun theOldJournalAndItsAtomicFileCopiesAreDeleted() {
        val dir = folder()
        val names = listOf("beacon-incidents-v1", "beacon-incidents-v1.bak", "beacon-incidents-v1.new")
        names.forEach { File(dir, it).writeText("sealed") }
        val other = File(dir, "beacon-other-v1").apply { writeText("keep") }
        assertTrue(removeLegacyIncidentJournal(dir))
        names.forEach { assertFalse(it, File(dir, it).exists()) }
        assertEquals("keep", other.readText())
    }

    @Test fun nothingToDeleteIsNotAnError() {
        val dir = folder()
        assertFalse(removeLegacyIncidentJournal(dir))
        assertFalse(removeLegacyIncidentJournal(File(dir, "missing")))
    }

    @Test fun aFileThatCannotBeDeletedIsLeftAloneWithoutFailingStart() {
        val dir = folder()
        // A non-empty folder under the journal's name cannot be deleted as a file.
        val stuck = File(dir, "beacon-incidents-v1").apply { mkdirs() }
        File(stuck, "inner").writeText("x")
        File(dir, "beacon-incidents-v1.bak").writeText("sealed")
        removeLegacyIncidentJournal(dir)
        assertTrue(stuck.exists())
        assertFalse(File(dir, "beacon-incidents-v1.bak").exists())
    }
}
