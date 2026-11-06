package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCanonicalControlsTest {
    @Test fun canonicalDefaultsUseTheSupportedProfileModelInventory() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { model = "chat-only-model" }
        try {
            val result = f.client.get("/api/profile/defaults", mapOf("session_id" to "stored-a"))
            assertEquals("old-model", result.string("model"))
            assertEquals("medium", result.string("reasoning_effort"))
            assertEquals("default", result.string("profile"))
            val inventory = f.httpRequests.single { it.url.encodedPath == "/api/model/options" }
            assertEquals("default", inventory.url.queryParameter("profile"))
            assertNull(inventory.url.queryParameter("session_id"))
            assertFalse(f.calls.any { it.string("method") == "config.get" && it.optJSONObject("params")?.string("key") == "provider" })
        } finally { f.client.closeNative() }
    }

    @Test fun profileDefaultsWorkBeforeAChatIsAttachedAndRefuseMalformedInventory() = runBlocking {
        for (canonical in listOf(false, true)) {
            val f = NativeOperationsTest.Fixture(canonical = canonical)
            try {
                assertEquals("old-model", f.client.get("/api/profile/defaults").string("model"))
                assertFalse(f.methods.contains("session.resume"))
                f.modelInventoryOverride = json("model" to 12)
                assertTrue(runCatching { f.client.get("/api/profile/defaults") }.isFailure)
            } finally { f.client.closeNative() }
        }
    }

    @Test fun renameUsesOneFencedCanonicalMutationAndConfirmsItsResult() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val result = f.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed"))
            assertEquals("Renamed", result.string("title"))
            val request = f.calls.single { it.string("method") == "session.mutate" }.getJSONObject("params")
            assertEquals("stored-a", request.string("session_id"))
            assertEquals("rename", request.string("operation"))
            assertEquals("Renamed", request.getJSONObject("payload").string("title"))
            assertEquals(4, request.getInt("expected_revision"))
            assertEquals(7, request.getInt("expected_generation"))
            assertTrue(request.string("request_id").isNotBlank())
            assertFalse(f.methods.contains("session.title"))
        } finally { f.client.closeNative() }
    }

    @Test fun missingFenceOrForeignReceiptNeverReportsRenameSuccess() = runBlocking {
        val missing = NativeOperationsTest.Fixture(canonical = true).apply { revision = "invalid" }
        try {
            assertTrue(runCatching { missing.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed")) }.isFailure)
            assertFalse(missing.methods.contains("session.mutate"))
        } finally { missing.client.closeNative() }
        val foreign = NativeOperationsTest.Fixture(canonical = true).apply {
            mutationOverride = json("session_id" to "foreign", "operation" to "rename", "revision" to 5, "title" to "Renamed")
        }
        try {
            assertTrue(runCatching { foreign.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed")) }.isFailure)
            assertEquals(1, foreign.methods.count { it == "session.mutate" })
        } finally { foreign.client.closeNative() }
    }

    @Test fun legacyRenameKeepsItsExistingMethod() = runBlocking {
        val f = NativeOperationsTest.Fixture()
        try {
            val result = f.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed"))
            assertEquals("Renamed", result.string("title"))
            assertTrue(f.methods.contains("session.title"))
            assertFalse(f.methods.contains("session.mutate"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalChatApprovalWriteUsesTheSessionYoloContract() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key, "yolo" to false)
            val result = f.client.post("/api/approval/mode", body)
            assertEquals("stored-a", result.string("session_id"))
            assertFalse(result.getBoolean("session_bypass"))
            val write = f.calls.single { it.string("method") == "config.set" }.getJSONObject("params")
            assertEquals("yolo", write.string("key"))
            assertFalse(write.has("scope"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalArchiveAndUsageUseTheOwnerContracts() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val archived = f.client.post("/api/session/archive", json("session_id" to "stored-a", "archived" to true))
            assertTrue(archived.getBoolean("hidden"))
            val request = f.calls.single { it.string("method") == "session.mutate" }.getJSONObject("params")
            assertEquals("sidebar", request.string("operation"))
            assertTrue(request.getJSONObject("payload").getBoolean("hidden"))
            assertEquals(12, f.client.get("/api/session/usage", mapOf("session_id" to "stored-a")).getInt("input_tokens"))
            assertTrue(f.methods.contains("session.stats"))
            assertFalse(f.methods.contains("session.set_hidden"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalFastCommandOmitsTheUnsupportedScopeField() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            f.client.post("/api/commands/exec", json("session_id" to "stored-a", "name" to "fast", "argument" to "normal"))
            val write = f.calls.single { it.string("method") == "config.set" }.getJSONObject("params")
            assertEquals("fast", write.string("key"))
            assertFalse(write.has("scope"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalModelConfirmationUsesTheExactOwnerTokenOnlyAfterApproval() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        val body = json("session_id" to "stored-a", "model" to "guarded-model", "model_provider" to "fixture")
        try {
            f.mutationOverride = json("session_id" to "stored-a", "operation" to "model", "status" to "confirmation_required",
                "confirm_required" to true, "confirm" to "owner-token", "confirm_message" to "Confirm this model")
            val refused = runCatching { f.client.post("/api/session/update", body) }.exceptionOrNull()
            assertTrue(refused is NativeModelConfirmation)
            assertFalse(f.calls.last { it.string("method") == "session.mutate" }.getJSONObject("params").getJSONObject("payload").has("confirm"))
            f.mutationOverride = json("session_id" to "stored-a", "operation" to "model", "revision" to 5,
                "execution_generation" to 7, "model" to "guarded-model", "provider" to "fixture")
            f.client.post("/api/session/update", body.copy().put("confirm_expensive_model", true))
            val payload = f.calls.last { it.string("method") == "session.mutate" }.getJSONObject("params").getJSONObject("payload")
            assertEquals("owner-token", payload.string("confirm"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalModelCannotInventAConfirmation() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val body = json("session_id" to "stored-a", "model" to "unconfirmed-model", "confirm_expensive_model" to true)
            assertTrue(runCatching { f.client.post("/api/session/update", body) }.isFailure)
            assertFalse(f.methods.contains("session.mutate"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalDeleteAndCompressUseFencedMutations() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { running = false }
        try {
            assertEquals("compressed", f.client.post("/api/session/compress", json("session_id" to "stored-a")).string("status"))
            assertEquals("stored-a", f.client.post("/api/session/delete", json("session_id" to "stored-a")).string("deleted"))
            val operations = f.calls.filter { it.string("method") == "session.mutate" }.map { it.getJSONObject("params").string("operation") }
            assertEquals(listOf("compress", "delete"), operations)
            assertFalse(f.methods.contains("session.compress"))
            assertFalse(f.methods.contains("session.delete"))
            assertFalse(f.methods.contains("session.close"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalStopNamesTheObservedExecutionGeneration() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            f.client.get("/api/chat/cancel", mapOf("stream_id" to "stored-a"))
            val stop = f.calls.single { it.string("method") == "session.interrupt" }.getJSONObject("params")
            assertEquals("stored-a", stop.string("session_id"))
            assertEquals(7, stop.getInt("execution_generation"))
        } finally { f.client.closeNative() }
    }

    @Test fun legacyStopStillAddressesItsLiveRuntimeWhenResumeIsUnavailable() = runBlocking {
        val f = NativeOperationsTest.Fixture().apply { refuseRuntimeResume = true }
        try {
            f.client.get("/api/chat/cancel", mapOf("stream_id" to "runtime-a"))
            val stop = f.calls.single { it.string("method") == "session.interrupt" }.getJSONObject("params")
            assertEquals("runtime-a", stop.string("session_id"))
            assertFalse(stop.has("execution_generation"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalMissingRuntimeNeverFallsBackToAnUnfencedInterrupt() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { refuseRuntimeResume = true }
        try {
            assertTrue(runCatching { f.client.get("/api/chat/cancel", mapOf("stream_id" to "runtime-a")) }.isFailure)
            assertFalse(f.methods.contains("session.interrupt"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalUnarchiveClearsBothFlagsThroughTheFencedSidebarMutation() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val result = f.client.post("/api/session/archive", json("session_id" to "stored-a", "archived" to false))
            assertFalse(result.getBoolean("hidden"))
            assertFalse(result.getBoolean("archived"))
            val request = f.calls.single { it.string("method") == "session.mutate" }.getJSONObject("params")
            assertEquals("sidebar", request.string("operation"))
            assertEquals(false, request.getJSONObject("payload").getBoolean("hidden"))
            assertEquals(false, request.getJSONObject("payload").getBoolean("archived"))
            assertEquals(4, request.getInt("expected_revision"))
            assertEquals(7, request.getInt("expected_generation"))
            assertFalse(f.methods.any { it in setOf("session.archive", "session.set_hidden") })
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalApprovalReadUsesSnapshotsWithoutLegacyLazyFields() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val result = f.client.get("/api/approval/mode", mapOf("session_id" to "stored-a"))
            assertEquals("stored-a", result.string("session_id"))
            assertFalse(result.getBoolean("session_status_known"))
            assertTrue(result.isNull("session_bypass"))
            val resumes = f.calls.filter { it.string("method") == "session.resume" }.map { it.getJSONObject("params") }
            assertTrue(resumes.isNotEmpty())
            assertTrue(resumes.all { !it.has("lazy") && !it.has("eager_build") && !it.has("close_on_disconnect") })
            assertFalse(f.methods.contains("config.set"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalWorkspaceUsesTheFencedOwnerMutation() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            val result = f.client.post("/api/session/update", json("session_id" to "stored-a", "workspace" to "/synthetic/workspace"))
            assertEquals("/synthetic/workspace", result.string("cwd"))
            assertEquals("workspace", f.calls.single { it.string("method") == "session.mutate" }.getJSONObject("params").string("operation"))
            assertFalse(f.methods.contains("session.cwd.set"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalBranchUsesTheCapturedRowAndRejectsAStaleSelection() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            messages = JSONArray().put(json("row_id" to 19, "role" to "user")).put(json("row_id" to 20, "role" to "assistant"))
                .put(json("row_id" to 21, "role" to "user"))
        }
        try {
            val body = json("session_id" to "stored-a", "keep_count" to 2, "through_message_id" to 20, "selected_role" to "assistant")
            assertEquals("branch-a", f.client.post("/api/session/branch", body).getJSONObject("session").string("session_id"))
            val payload = f.calls.single { it.string("method") == "session.mutate" }.getJSONObject("params").getJSONObject("payload")
            assertEquals(20, payload.getInt("through_message_id"))
            val before = body.copy().apply { remove("through_message_id"); put("before_message_id", 21); put("selected_role", "user") }
            f.client.post("/api/session/branch", before)
            assertEquals(20, f.calls.last { it.string("method") == "session.mutate" }.getJSONObject("params").getJSONObject("payload").getInt("through_message_id"))
            f.client.post("/api/session/branch", json("session_id" to "stored-a", "keep_count" to 2, "branch_row_ids" to JSONArray().put(19).put(20)))
            assertEquals(20, f.calls.last { it.string("method") == "session.mutate" }.getJSONObject("params").getJSONObject("payload").getInt("through_message_id"))
            val count = f.methods.count { it == "session.mutate" }
            assertTrue(runCatching { f.client.post("/api/session/branch", body.copy().put("through_message_id", 99)) }.isFailure)
            assertEquals(count, f.methods.count { it == "session.mutate" })
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalUndoTargetsTheLastActiveUserRowAndChecksTheNativeReceipt() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            running = false
            messages = JSONArray().put(json("row_id" to 19, "role" to "user"))
                .put(json("row_id" to 20, "role" to "assistant"))
                .put(json("row_id" to 21, "role" to "user", "archived" to true))
        }
        try {
            val result = f.client.post("/api/session/undo", json("session_id" to "stored-a"))
            assertEquals(2, result.getInt("removed"))
            val request = f.calls.single { it.string("method") == "session.mutate" }.getJSONObject("params")
            assertEquals("rewind", request.string("operation"))
            assertEquals(19, request.getJSONObject("payload").getInt("target_message_id"))
            assertFalse(f.methods.contains("session.undo"))
            f.mutationOverride = json("session_id" to "stored-a", "operation" to "rewind", "revision" to 5,
                "execution_generation" to 8, "rewound_count" to 2, "target_message" to json("id" to 99, "role" to "user"))
            assertTrue(runCatching { f.client.post("/api/session/undo", json("session_id" to "stored-a")) }.isFailure)
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalUndoCannotGuessAMissingArchivedOrAmbiguousTarget() = runBlocking {
        for (rows in listOf(JSONArray(), JSONArray().put(json("row_id" to 19, "role" to "user", "archived" to true)),
            JSONArray().put(json("role" to "user")),
            JSONArray().put(json("row_id" to 19, "role" to "user")).put(json("row_id" to 19, "role" to "user")))) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply { messages = rows; running = false }
            try {
                assertTrue(runCatching { f.client.post("/api/session/undo", json("session_id" to "stored-a")) }.isFailure)
                assertFalse(f.methods.contains("session.mutate"))
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalReceiptRequiresCurrentIntegerRevisionAndGeneration() = runBlocking {
        val valid = json("session_id" to "stored-a", "operation" to "rename", "title" to "Renamed", "revision" to 5, "execution_generation" to 7)
        val invalid = listOf(valid.copy().put("operation", "delete"), valid.copy().put("revision", 3),
            valid.copy().put("revision", 4.5), valid.copy().put("execution_generation", 6),
            valid.copy().put("execution_generation", true), valid.copy().apply { remove("execution_generation") },
            valid.copy().apply { remove("title") }, valid.copy().put("status", "failed"))
        for (receipt in invalid) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply { mutationOverride = receipt }
            try {
                assertTrue("Must refuse $receipt", runCatching { f.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed")) }.isFailure)
                assertEquals(1, f.methods.count { it == "session.mutate" })
                assertFalse(f.methods.contains("session.title"))
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalMutationRequiresAnIntegerSnapshotFenceAndTheOriginalProfile() = runBlocking {
        for (value in listOf<Any>(-1, 4.5, true, "4", JSONObject.NULL)) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply { generation = value }
            try {
                assertTrue(runCatching { f.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed")) }.isFailure)
                assertFalse(f.methods.contains("session.mutate"))
            } finally { f.client.closeNative() }
        }
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            snapshotOverride = json("session_id" to "stored-a", "stored_session_id" to "stored-a", "revision" to 4,
                "execution_generation" to 7, "info" to json("profile_name" to "other"))
        }
        try {
            assertTrue(runCatching { f.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed")) }.isFailure)
            assertFalse(f.methods.contains("session.mutate"))
        } finally { f.client.closeNative() }
    }

    @Test fun uncertainCanonicalMutationIsNeverReplayedOrRetriedThroughALegacyVerb() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { disconnectMutation = true }
        try {
            assertTrue(runCatching { f.client.post("/api/session/rename", json("session_id" to "stored-a", "title" to "Renamed")) }.isFailure)
            assertEquals(1, f.methods.count { it == "session.mutate" })
            assertFalse(f.methods.contains("session.title"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalReasoningKeepsItsSupportedSessionScope() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            assertEquals("high", f.client.post("/api/reasoning", json("session_id" to "stored-a", "effort" to "high")).string("value"))
            val write = f.calls.single { it.string("method") == "config.set" }.getJSONObject("params")
            assertEquals("session", write.string("scope"))
            assertEquals("reasoning", write.string("key"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalDeferredModelReceiptDoesNotInventAnAppliedSelection() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            mutationOverride = json("session_id" to "stored-a", "operation" to "model", "status" to "deferred", "model" to "next-model", "revision" to 4, "execution_generation" to 7)
        }
        try {
            val result = f.client.post("/api/session/update", json("session_id" to "stored-a", "model" to "next-model"))
            assertTrue(result.getBoolean("deferred"))
            assertEquals("old-model", result.getJSONObject("session").string("model"))
            assertEquals(1, f.methods.count { it == "session.mutate" })
            assertFalse(f.methods.contains("config.set"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalDeleteRefusesActiveOrQueuedWorkBeforeAnyMutation() = runBlocking {
        for (busy in listOf(true, false)) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply {
                running = busy
                if (!busy) pending = JSONArray().put(json("status" to "queued"))
            }
            try {
                assertTrue(runCatching { f.client.post("/api/session/delete", json("session_id" to "stored-a")) }.isFailure)
                assertFalse(f.methods.any { it in setOf("session.mutate", "session.delete", "session.close") })
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalCompressionReportsTheOwnersNoopWithoutClaimingCompression() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            mutationOverride = json("session_id" to "stored-a", "operation" to "compress", "status" to "noop", "summary" to json("aborted" to true))
        }
        try {
            assertEquals("aborted", f.client.post("/api/session/compress", json("session_id" to "stored-a")).string("status"))
            assertEquals(1, f.methods.count { it == "session.mutate" })
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalBranchRefusesAnEmptyEditOrArchivedBoundaryBeforeMutation() = runBlocking {
        for (archived in listOf(false, true)) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply {
                messages = JSONArray().put(json("row_id" to 19, "role" to "user", "archived" to archived))
            }
            try {
                val body = json("session_id" to "stored-a", "selected_role" to "user").put(if (archived) "through_message_id" else "before_message_id", 19)
                assertTrue(runCatching { f.client.post("/api/session/branch", body) }.isFailure)
                assertFalse(f.methods.contains("session.mutate"))
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalPendingControlsUseTheObservedPromptAndGeneration() = runBlocking {
        for (kind in listOf("approval", "clarify")) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply {
                prompts = JSONArray().put(json("kind" to kind, "prompt_id" to "prompt-a", "execution_generation" to 7,
                    "question" to "Synthetic question", "command" to "synthetic command", "choices" to JSONArray().put("once").put("deny")))
            }
            try {
                val pending = f.client.get("/api/$kind/pending", mapOf("session_id" to "stored-a")).getJSONObject("pending")
                assertEquals("prompt-a", pending.string("${kind}_id"))
                val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key,
                    "${kind}_id" to "prompt-a", (if (kind == "approval") "choice" else "response") to "once")
                assertEquals("resolved", f.client.post("/api/$kind/respond", body).string("status"))
                val request = f.calls.single { it.string("method") == "$kind.respond" }.getJSONObject("params")
                assertEquals("prompt-a", request.string("prompt_id"))
                assertEquals(7, request.getInt("execution_generation"))
                assertEquals("once", request.string(if (kind == "approval") "choice" else "answer"))
                assertEquals(setOf("session_id", "profile", "execution_generation", "prompt_id", if (kind == "approval") "choice" else "answer"), request.keys().asSequence().toSet())
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalPromptRefusesAStaleGenerationUnOfferedChoiceAndForeignReceipt() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply {
            prompts = JSONArray().put(json("kind" to "approval", "prompt_id" to "prompt-a", "execution_generation" to 6,
                "choices" to JSONArray().put("deny")))
        }
        val body = json("session_id" to "stored-a", "scope_key" to f.client.scope.session("stored-a").key, "approval_id" to "prompt-a", "choice" to "deny")
        try {
            assertTrue(runCatching { f.client.post("/api/approval/respond", body) }.isFailure)
            f.prompts.getJSONObject(0).put("execution_generation", 7)
            assertTrue(runCatching { f.client.post("/api/approval/respond", body.copy().put("choice", "always")) }.isFailure)
            assertFalse(f.methods.contains("approval.respond"))
            f.responseOverride = json("status" to "resolved", "prompt_id" to "foreign")
            assertTrue(runCatching { f.client.post("/api/approval/respond", body) }.isFailure)
            assertEquals(1, f.methods.count { it == "approval.respond" })
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalStopCannotUseTheUnfencedLegacyFallbackAfterARefusedResume() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true).apply { refuseRuntimeResume = true }
        try {
            assertTrue(runCatching { f.client.get("/api/chat/cancel", mapOf("stream_id" to "runtime-a")) }.isFailure)
            assertFalse(f.methods.contains("session.interrupt"))
        } finally { f.client.closeNative() }
    }

    @Test fun canonicalHistoryControlsRefuseOmittedOrIncompleteSnapshots() = runBlocking {
        val valid = json("session_id" to "stored-a", "stored_session_id" to "stored-a", "revision" to 4, "execution_generation" to 7,
            "info" to json("profile_name" to "default"), "message_count" to 1, "messages" to JSONArray().put(json("row_id" to 19, "role" to "user")))
        val incomplete = listOf(valid.copy().put("message_count", 2), valid.copy().put("messages_omitted", true),
            valid.copy().put("messages", JSONArray().put("malformed row")))
        for (snapshot in incomplete) for (operation in listOf("branch", "undo")) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply { snapshotOverride = snapshot }
            try {
                assertTrue(runCatching { f.client.post("/api/session/$operation", json("session_id" to "stored-a")) }.isFailure)
                assertFalse(f.methods.contains("session.mutate"))
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalPinAndUnpinUseTheFencedSidebarBeforeLegacyHttp() = runBlocking {
        for (pinned in listOf(true, false)) {
            val f = NativeOperationsTest.Fixture(canonical = true)
            try {
                val result = f.client.post("/api/session/pin", json("session_id" to "stored-a", "pinned" to pinned))
                assertEquals("stored-a", result.string("session_id"))
                assertEquals(pinned, result.getBoolean("pinned"))
                val request = f.calls.single { it.string("method") == "session.mutate" }.getJSONObject("params")
                assertEquals("stored-a", request.string("session_id"))
                assertEquals("default", request.string("profile"))
                assertEquals("sidebar", request.string("operation"))
                assertEquals(setOf("pinned"), request.getJSONObject("payload").keys().asSequence().toSet())
                assertEquals(pinned, request.getJSONObject("payload").opt("pinned"))
                assertEquals(4, request.getInt("expected_revision"))
                assertEquals(7, request.getInt("expected_generation"))
                assertTrue(request.string("request_id").isNotBlank())
                assertNoPinHttpWrites(f)
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalPinRefusesMalformedFencesAndForeignSnapshotsBeforeMutation() = runBlocking {
        val valid = json("session_id" to "stored-a", "stored_session_id" to "stored-a", "revision" to 4,
            "execution_generation" to 7, "info" to json("profile_name" to "default"))
        val invalid = listOf("revision", "execution_generation").flatMap { key ->
            listOf<Any>(-1, 4.5, true, "4", JSONObject.NULL).map { valid.copy().put(key, it) } + valid.copy().apply { remove(key) }
        } + listOf(valid.copy().put("session_id", "foreign"), valid.copy().put("info", json("profile_name" to "other")))
        for (pinned in listOf(true, false)) for (snapshot in invalid) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply { snapshotOverride = snapshot }
            try {
                assertTrue(runCatching { f.client.post("/api/session/pin", json("session_id" to "stored-a", "pinned" to pinned)) }.isFailure)
                assertFalse(f.methods.contains("session.mutate"))
                assertNoPinHttpWrites(f)
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalPinRejectsStaleForeignAndMismatchedBooleanReceipts() = runBlocking {
        for (pinned in listOf(true, false)) {
            val valid = json("session_id" to "stored-a", "operation" to "sidebar", "revision" to 5,
                "execution_generation" to 7, "pinned" to pinned)
            val invalid = listOf(valid.copy().put("session_id", "foreign"), valid.copy().put("operation", "rename"),
                valid.copy().put("revision", 3), valid.copy().put("execution_generation", 6), valid.copy().put("status", "refused"),
                valid.copy().put("pinned", !pinned), valid.copy().put("pinned", pinned.toString()),
                valid.copy().put("pinned", if (pinned) 1 else 0), valid.copy().apply { remove("pinned") })
            for (receipt in invalid) {
                val f = NativeOperationsTest.Fixture(canonical = true).apply { mutationOverride = receipt }
                try {
                    assertTrue("Must refuse $receipt", runCatching { f.client.post("/api/session/pin", json("session_id" to "stored-a", "pinned" to pinned)) }.isFailure)
                    assertEquals(1, f.methods.count { it == "session.mutate" })
                    assertNoPinHttpWrites(f)
                } finally { f.client.closeNative() }
            }
        }
    }

    @Test fun canonicalPinServerRefusalsAndStaleFencesNeverRetryOrUseLegacyHttp() = runBlocking {
        for (refusal in listOf("permission_denied", "revision_conflict", "stale_generation")) for (pinned in listOf(true, false)) {
            val f = NativeOperationsTest.Fixture(canonical = true)
            val client = BeaconClient(f.client.auth, null, socketFactory = object : okhttp3.WebSocket.Factory {
                override fun newWebSocket(request: okhttp3.Request, listener: okhttp3.WebSocketListener): okhttp3.WebSocket =
                    f.newWebSocket(request, object : okhttp3.WebSocketListener() {
                        override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) = listener.onOpen(ws, response)
                        override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                            val frame = JSONObject(text)
                            if (f.calls.any { it.string("id") == frame.string("id") && it.string("method") == "session.mutate" }) {
                                frame.remove("result")
                                frame.put("error", json("code" to 4001, "message" to refusal))
                            }
                            listener.onMessage(ws, frame.toString())
                        }
                        override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = listener.onFailure(ws, t, response)
                        override fun onClosing(ws: okhttp3.WebSocket, code: Int, reason: String) = listener.onClosing(ws, code, reason)
                        override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) = listener.onClosed(ws, code, reason)
                    })
            }, sharedHttp = f.client.http, configureHttp = {})
            try {
                val failure = runCatching { client.post("/api/session/pin", json("session_id" to "stored-a", "pinned" to pinned)) }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(1, f.methods.count { it == "session.mutate" })
                assertNoPinHttpWrites(f)
            } finally { client.closeNative(); f.client.closeNative() }
        }
    }

    @Test fun uncertainCanonicalPinIsNeverReplayedOrRetriedThroughLegacyHttp() = runBlocking {
        for (pinned in listOf(true, false)) {
            val f = NativeOperationsTest.Fixture(canonical = true).apply { disconnectMutation = true }
            try {
                assertTrue(runCatching { f.client.post("/api/session/pin", json("session_id" to "stored-a", "pinned" to pinned)) }.isFailure)
                assertEquals(1, f.methods.count { it == "session.mutate" })
                assertNoPinHttpWrites(f)
            } finally { f.client.closeNative() }
        }
    }

    @Test fun canonicalPinRejectsInvalidInputBeforeConnecting() = runBlocking {
        val f = NativeOperationsTest.Fixture(canonical = true)
        try {
            for (value in listOf<Any>("true", 1, JSONObject.NULL)) {
                assertTrue(runCatching { f.client.post("/api/session/pin", json("session_id" to "stored-a", "pinned" to value)) }.isFailure)
            }
            for (session in listOf("", "../foreign")) {
                assertTrue(runCatching { f.client.post("/api/session/pin", json("session_id" to session, "pinned" to true)) }.isFailure)
            }
            assertTrue(runCatching { f.client.get("/api/session/pin", mapOf("session_id" to "stored-a", "pinned" to "true")) }.isFailure)
            assertTrue(f.calls.isEmpty())
            assertTrue(f.httpRequests.isEmpty())
        } finally { f.client.closeNative() }
    }

    private fun assertNoPinHttpWrites(f: NativeOperationsTest.Fixture) {
        assertFalse(f.httpRequests.any { it.url.encodedPath == "/api/plugins/beacon/sessions/pin" ||
            it.url.encodedPath.endsWith("/mutation-snapshot") || it.method == "PATCH" })
    }
}
