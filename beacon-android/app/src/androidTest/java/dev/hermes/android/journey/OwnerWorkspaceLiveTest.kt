package dev.hermes.android.journey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconActivity
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.NativeBoards
import dev.hermes.android.data.NativeProjects
import dev.hermes.android.data.boardMoveFields
import dev.hermes.android.data.boardTaskFields
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.util.UUID

class OwnerWorkspaceLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 180_000) fun projectCreationAndRemovalAreScopedToTheNewProject() = runBlocking {
        val h = OwnerJourneyHarness(compose, "projects")
        h.checked {
            qa()
            val projects = NativeProjects(client)
            val name = "Beacon QA ${runId}"
            val request = UUID.randomUUID().toString()
            var id = ""
            var expectedName = name
            var writeInFlight = false
            suspend fun project(): JSONObject {
                current()
                return client.nativeRpc("projects.get", json("id" to id)).getJSONObject("project").also { current() }
            }
            try {
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                compose.onNodeWithTag("navigation-projects").performClick()
                await { vm.ui.value.tab == "Projects" }
                mark("W-02", "projects_navigation", "passed")
                val existing = projects.call("/api/projects", "GET", JSONObject()).rows("projects")
                check(existing.none { it.string("name") == name || it.string("slug") == "phone-$request" })
                mark("W-02", "project_request_recorded", "passed", qaId = request)
                current(); writeInFlight = true
                val created = projects.call("/api/projects/create", "POST", json("request_id" to request, "name" to name, "description" to "QA"))
                    .getJSONObject("project")
                current()
                val candidate = created.string("id")
                check(candidate.matches(Regex("p_[A-Za-z0-9_-]{1,100}")) && created.string("name") == name && created.string("slug") == "phone-$request")
                id = candidate; writeInFlight = false
                check(project().string("name") == name)
                mark("W-02", "project_create_readback", "passed")

                val newName = "$name edited"
                writeInFlight = true
                projects.call("/api/projects/update", "POST", json("id" to id, "name" to newName, "description" to "QA"))
                current()
                expectedName = newName
                check(project().string("name") == newName)
                writeInFlight = false
                mark("W-02", "project_edit_readback", "passed")
            } finally {
                try {
                    if (writeInFlight) mark("W-02", "project_write_unconfirmed", "needs_human")
                    else if (id.isNotBlank()) {
                        check(project().string("name") == expectedName)
                        current(); writeInFlight = true
                        projects.call("/api/projects/delete", "POST", json("id" to id))
                        current(); writeInFlight = false
                        check(projects.call("/api/projects", "GET", JSONObject()).rows("projects").none { it.string("id") == id })
                        mark("W-02", "project_cleanup", "passed")
                    }
                } catch (error: Throwable) {
                    runCatching { mark("W-02", "project_cleanup_unconfirmed", "needs_human") }; throw error
                }
            }
        }
    }

    @Test(timeout = 180_000) fun disposableKanbanCardIsReadMovedAndRemoved() = runBlocking {
        val h = OwnerJourneyHarness(compose, "kanban")
        h.checked {
            qa()
            val boards = NativeBoards(client)
            val name = "Beacon QA ${runId}"
            var board = ""
            var id = ""
            var writeInFlight = false
            try {
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                if (compose.onAllNodesWithTag("navigation-kanban").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("navigation-more").performClick()
                compose.onNodeWithTag("navigation-kanban").performClick()
                await { vm.ui.value.tab == "Kanban" }
                val (choices, selected) = boards.choices()
                check(choices.any { it.slug == selected })
                board = selected
                check(boards.read(board).none { it.title == name })
                val request = UUID.randomUUID().toString()
                mark("W-04", "kanban_request_recorded", "passed", qaId = request)
                current(); writeInFlight = true
                val created = boards.create(board, boardTaskFields(name, "Synthetic QA", ""), request)
                current()
                check(created.title == name && created.status != "running")
                id = created.id; writeInFlight = false
                check(boards.detail(board, id).title == name)
                mark("W-04", "kanban_create_readback", "passed")

                val destination = if (created.status == "todo") "triage" else "todo"
                current(); writeInFlight = true
                val moved = boards.update(board, id, boardMoveFields(destination))
                current()
                check(moved.title == name && moved.status == destination && boards.detail(board, id).status == destination)
                writeInFlight = false
                mark("W-04", "kanban_move_readback", "passed")
            } finally {
                try {
                    if (writeInFlight) mark("W-04", "kanban_write_unconfirmed", "needs_human")
                    else if (id.isNotBlank()) {
                        val confirmed = boards.detail(board, id)
                        check(confirmed.title == name && confirmed.status != "running")
                        current(); writeInFlight = true
                        boards.delete(board, id)
                        current(); writeInFlight = false
                        check(boards.read(board).none { it.id == id })
                        mark("W-04", "kanban_cleanup", "passed")
                    }
                } catch (error: Throwable) {
                    runCatching { mark("W-04", "kanban_cleanup_unconfirmed", "needs_human") }; throw error
                }
            }
        }
    }
}
