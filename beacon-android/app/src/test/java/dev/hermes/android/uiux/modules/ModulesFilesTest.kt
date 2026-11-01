package dev.hermes.android.uiux.modules

import dev.hermes.android.ui.FileCrumb
import dev.hermes.android.ui.fileBreadcrumbs
import dev.hermes.android.ui.fileModifiedMillis
import dev.hermes.android.ui.initialFilesFolder
import dev.hermes.android.ui.serverHostLabel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Files: the folder trail, the server name, where it opens and the modified time. Synthetic paths only. */
class ModulesFilesTest {
    @Test fun aWindowsPathBecomesADriveThenOneChipPerFolder() {
        assertEquals(
            listOf(FileCrumb("D:", "D:\\"), FileCrumb("Shared", "D:\\Shared"), FileCrumb("Team", "D:\\Shared\\Team"), FileCrumb("Documents", "D:\\Shared\\Team\\Documents")),
            fileBreadcrumbs("D:\\Shared\\Team\\Documents"),
        )
        assertEquals(listOf(FileCrumb("C:", "C:/"), FileCrumb("synthetic-workspace", "C:/synthetic-workspace"), FileCrumb("research", "C:/synthetic-workspace/research")), fileBreadcrumbs("C:/synthetic-workspace/research/"))
        assertEquals(listOf(FileCrumb("C:", "C:\\")), fileBreadcrumbs("C:\\"))
    }

    @Test fun aPosixPathStartsAtTheRoot() {
        assertEquals(listOf(FileCrumb("/", "/"), FileCrumb("home", "/home"), FileCrumb("someone", "/home/someone"), FileCrumb("notes", "/home/someone/notes")), fileBreadcrumbs("/home/someone/notes"))
        assertEquals(listOf(FileCrumb("/", "/")), fileBreadcrumbs("/"))
    }

    @Test fun aNetworkShareIsOneRootChip() {
        assertEquals(listOf(FileCrumb("server\\share", "\\\\server\\share"), FileCrumb("team", "\\\\server\\share\\team")), fileBreadcrumbs("\\\\server\\share\\team"))
    }

    @Test fun noPathHasNoTrail() {
        assertEquals(emptyList<FileCrumb>(), fileBreadcrumbs(""))
        assertEquals(emptyList<FileCrumb>(), fileBreadcrumbs("   "))
    }

    @Test fun theServerIsNamedByItsHostOnly() {
        assertEquals("hermes.example.com", serverHostLabel("https://hermes.example.com:9119/dashboard"))
        assertEquals("100.64.0.1", serverHostLabel("http://100.64.0.1:9119"))
        assertEquals("hermes.example.com", serverHostLabel("hermes.example.com:8080"))
        assertEquals("", serverHostLabel(""))
    }

    private fun project(id: String, vararg folders: Pair<String, Boolean>, archived: Boolean = false) = JSONObject().put("id", id).put("archived", archived)
        .put("folders", JSONArray(folders.map { (path, primary) -> JSONObject().put("path", path).put("is_primary", primary) }))

    @Test fun filesOpensWhereTheOwnerLastWasThenInTheFirstProjectsMainFolder() {
        val projects = JSONObject().put("projects", JSONArray().put(project("old", "D:/old" to true, archived = true)).put(project("a", "D:/a/docs" to false, "D:/a" to true)).put(project("b", "D:/b" to true)))
        assertEquals("D:/saved", initialFilesFolder("D:/saved", projects))
        assertEquals("D:/a", initialFilesFolder("", projects))
        assertEquals("D:/b", initialFilesFolder("", JSONObject().put("projects", JSONArray().put(project("none")).put(project("b", "D:/b" to false)))))
        assertEquals("", initialFilesFolder("", null))
        assertEquals("", initialFilesFolder("", JSONObject().put("projects", JSONArray())))
    }

    @Test fun theModifiedTimeIsReadFromWhicheverFieldHermesSends() {
        assertEquals(1_761_897_600_000L, fileModifiedMillis(JSONObject().put("modified", 1_761_897_600)))
        assertEquals(1_761_897_600_000L, fileModifiedMillis(JSONObject().put("mtime", 1_761_897_600_000L)))
        assertEquals(1_761_897_600_000L, fileModifiedMillis(JSONObject().put("modified_at", 1_761_897_600.5)))
        assertEquals(1_761_897_600_000L, fileModifiedMillis(JSONObject().put("updated_at", "2025-10-31T08:00:00Z")))
        assertEquals(0L, fileModifiedMillis(JSONObject().put("name", "notes.md").put("size", 28)))
        assertEquals(0L, fileModifiedMillis(JSONObject().put("modified", "yesterday")))
    }
}
