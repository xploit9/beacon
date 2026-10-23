package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test

class NativeCatalogSearchTest {
    @Test fun duplicateModelIdsHaveNoSubtitleAndLongReasoningLevelsHaveSpecificCopy() {
        assertEquals("", modelSubtitle("model-1", "model-1"))
        assertEquals("model-1", modelSubtitle("Friendly model", "model-1"))
        assertEquals("Extra high", reasoningLevelLabel("xhigh"))
        assertTrue(reasoningLevelDescription("xhigh").contains("difficult"))
        assertTrue(reasoningLevelDescription("max").contains("maximum"))
    }
    @Test fun providerAndVisibleModelFieldsDefineResultsAndEmptyState() {
        val rows = listOf(Triple("Configured provider", "model-1", "Friendly model"))
        fun matches(query: String) = rows.filter { modelSearchMatches(it.first, it.second, it.third, query) }
        assertEquals(1, matches("configured provider").size)
        assertEquals(1, matches("MODEL-1").size)
        assertEquals(1, matches("friendly").size)
        assertEquals(1, matches("model-1  configured").size)
        assertEquals(1, matches("friendly\tprovider").size)
        assertEquals(1, matches("").size)
        assertTrue(matches("hidden metadata").isEmpty())
        assertTrue(matches("unconfigured").isEmpty())
    }
}
