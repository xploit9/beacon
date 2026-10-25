package dev.hermes.android

import dev.hermes.android.data.*
import dev.hermes.android.ui.NativeSkillsState
import dev.hermes.android.core.*
import androidx.lifecycle.SavedStateHandle
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Profile editors outlive their page; late responses never replace another account's draft. */
class NativePhoneSettings(private val jobs: CoroutineScope, private val saved: SavedStateHandle, private val draftChanged: () -> Unit = {}, private val screens: ScreenCache? = null, private val client: () -> BeaconClient?) {
    private val _skills = MutableStateFlow(NativeSkillsState())
    val skills = _skills.asStateFlow()
    private val _memory = MutableStateFlow(NativeMemoryState(""))
    val memory = _memory.asStateFlow()
    private val _server = MutableStateFlow(NativeServerState())
    val server = _server.asStateFlow()
    private val _bots = MutableStateFlow(NativeBotsState())
    val bots = _bots.asStateFlow()
    private val _botModels = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val botModels = _botModels.asStateFlow()
    var scopeKey: String = ""; private set
    private var skillJob: Job? = null
    private var memoryJob: Job? = null
    private var serverJob: Job? = null
    private var botJob: Job? = null
    private var skillsAt = 0L
    private var memoryAt = 0L
    private val skillDrafts = mutableMapOf<String, NativeSkillsState>()
    private val memoryDrafts = mutableMapOf<String, NativeMemoryState>()
    private val botDrafts = mutableMapOf<String, NativeBotsState>()
    private suspend fun <T> cacheAttempt(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    private fun updateKey(c: BeaconClient) = c.scope.copy(profile = "", session = "").key
    private suspend fun keepSkills(scope: Scope, api: NativeSkills) { api.snapshot?.let { cacheAttempt { screens?.set(scope, "settings:skills", it) } } }

    /** Original bases survive recovery; a restored draft never adopts a newer host revision. */
    fun snapshotDraft(): JSONObject {
        val result = json("version" to 1, "scope" to scopeKey)
        if (scopeKey.isBlank() || client()?.scope?.key != scopeKey) return result
        val skill = _skills.value
        skill.content?.takeIf { skill.editing && skill.draft != it.content }?.let { base ->
            result.put("skill", json("name" to base.name, "draft" to skill.draft, "base" to base.content, "revision" to base.revision,
                "description" to skill.selected?.description.orEmpty(), "category" to skill.selected?.category.orEmpty(), "enabled" to (skill.selected?.enabled == true), "essential" to (skill.selected?.essential == true),
                "provenance" to skill.selected?.provenance.orEmpty(), "requirements" to org.json.JSONArray(skill.selected?.requirements.orEmpty())))
        }
        val memory = _memory.value
        memory.documents.firstOrNull { it.target == memory.editing && memory.draft != it.content }?.let { base ->
            result.put("memory", json("target" to base.target, "draft" to memory.draft, "base" to base.content, "revision" to base.revision,
                "enabled" to base.enabled, "limit" to base.limit, "provider" to memory.provider, "providerReady" to memory.providerReady))
        }
        val bot = _bots.value
        bot.selected?.takeIf { bot.editing && (bot.draftProvider != it.provider || bot.draftModel != it.model) }?.let { base ->
            result.put("bot", json("profile" to base.profile, "title" to base.title, "description" to base.description, "soul" to base.soul, "draftProvider" to bot.draftProvider, "draftModel" to bot.draftModel,
                "baseProvider" to base.provider, "baseModel" to base.model, "revision" to base.revision))
        }
        require(result.toString().toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Settings draft is too large to recover" }
        return result
    }

    fun restoreDraft(value: JSONObject) {
        if (scopeKey.isBlank() || client()?.scope?.key != scopeKey) return
        val restored = runCatching {
            require(value.toString().toByteArray(Charsets.UTF_8).size <= 1_048_576 && value.get("version") == 1 && value.get("scope") == scopeKey)
            fun text(row: JSONObject, key: String, limit: Int = 1_048_576): String = row.get(key).let { raw ->
                require(raw is String && raw.length <= limit); raw
            }
            fun revision(row: JSONObject): String = text(row, "revision", 64).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            fun flag(row: JSONObject, key: String): Boolean = row.get(key).let { require(it is Boolean); it }
            val skill = if (value.has("skill")) value.getJSONObject("skill").let { row ->
                val name = text(row, "name", 160).also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
                val base = NativeSkillContent(name, text(row, "base"), revision(row))
                val requirements = row.getJSONArray("requirements").also { require(it.length() <= 100) }.let { items -> (0 until items.length()).map { index -> (items.get(index) as? String)?.also { require(it.length <= 16_000) } ?: error("Invalid requirement") } }
                val selected = NativeSkill(name, text(row, "description", 16_000), text(row, "category", 160), flag(row, "enabled"), text(row, "provenance", 1000), requirements, flag(row, "essential"))
                val draft = text(row, "draft").also { require(it != base.content) }
                NativeSkillsState(profile = client()!!.auth.profile, skills = listOf(selected), selected = selected, content = base, draft = draft, editing = true)
            } else null
            val memory = if (value.has("memory")) value.getJSONObject("memory").let { row ->
                val target = text(row, "target", 16).also { require(it in setOf("memory", "user")) }
                val limit = (row.get("limit") as? Int)?.also { require(it in 1..256_000) } ?: error("Invalid note limit")
                val base = NativeMemoryDocument(target, text(row, "base"), revision(row), flag(row, "enabled"), limit)
                val draft = text(row, "draft").also { require(it != base.content) }
                NativeMemoryState(scopeKey, text(row, "provider", 160), flag(row, "providerReady"), listOf(base), editing = target, draft = draft, message = "Recovered draft. Refresh notes after saving or cancelling to reload all documents.")
            } else null
            val bot = if (value.has("bot")) value.getJSONObject("bot").let { row ->
                val profile = text(row, "profile", 160).also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
                val base = NativeBot(profile, text(row, "title", 1000), text(row, "description", 16_000), text(row, "soul"), text(row, "baseProvider", 1000), text(row, "baseModel", 1000), revision(row))
                val provider = text(row, "draftProvider"); val model = text(row, "draftModel")
                require(provider != base.provider || model != base.model)
                NativeBotsState(profiles = listOf(NativeBotSummary(profile, base.title, base.description)), selected = base, editing = true, draftProvider = provider, draftModel = model)
            } else null
            Triple(skill, memory, bot)
        }.getOrNull() ?: return
        restored.first?.let { if (!_skills.value.editing && !_skills.value.saving) { skillJob?.cancel(); _skills.value = it } }
        restored.second?.let { if (_memory.value.editing.isBlank() && !_memory.value.saving) { memoryJob?.cancel(); _memory.value = it } }
        restored.third?.let { if (!_bots.value.editing && !_bots.value.saving) { botJob?.cancel(); _bots.value = it } }
        draftChanged()
    }

    fun bind() {
        val key = client()?.scope?.key.orEmpty()
        if (scopeKey == key) return
        if (_skills.value.editing) skillDrafts[scopeKey] = _skills.value.copy(loading = false, saving = false)
        if (_memory.value.editing.isNotBlank()) memoryDrafts[scopeKey] = _memory.value.copy(loading = false, saving = false)
        if (_bots.value.editing) botDrafts[scopeKey] = _bots.value.copy(loading = false, saving = false)
        skillJob?.cancel(); memoryJob?.cancel(); serverJob?.cancel(); botJob?.cancel()
        scopeKey = key; skillsAt = 0L; memoryAt = 0L
        _skills.value = skillDrafts.remove(key) ?: NativeSkillsState(profile = client()?.auth?.profile.orEmpty())
        _memory.value = memoryDrafts.remove(key) ?: NativeMemoryState(key)
        _bots.value = botDrafts.remove(key) ?: NativeBotsState(); _botModels.value = emptyList()
        val host = client()?.let(::updateKey).orEmpty()
        _server.value = NativeServerState(requestedActionId = saved["server-update:$host:id"] ?: "", requestedAt = saved["server-update:$host:at"] ?: "")
    }

    private fun skillsRequest(write: Boolean = false, block: suspend (NativeSkills) -> NativeSkillsState) {
        val active = client() ?: return
        bind()
        if (_skills.value.saving) return
        skillJob?.cancel()
        _skills.update { it.copy(loading = !write, saving = write, error = null, notice = null) }
        skillJob = jobs.launch {
            val c = active.detached()
            try {
                val result = block(NativeSkills(c))
                if (client() === active && scopeKey == c.scope.key) { _skills.value = result.copy(loading = false, saving = false); draftChanged() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _skills.update { it.copy(loading = false, saving = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
    }
    fun loadSkills(force: Boolean = true) {
        val active = client() ?: return
        bind()
        if (_skills.value.editing || !force && (skillJob?.isActive == true || startupReadFresh(skillsAt, System.currentTimeMillis()))) return
        skillsRequest { api ->
            val c = active
            if (!force && _skills.value.skills.isEmpty()) cacheAttempt {
                screens?.get(c.scope, "settings:skills")?.let { stored ->
                    val cached = parseNativeSkills(stored.copy())
                    if (client() === active && scopeKey == c.scope.key) _skills.update { it.copy(skills = cached) }
                }
            }
            val list = api.list()
            keepSkills(c.scope, api)
            if (scopeKey == c.scope.key) skillsAt = System.currentTimeMillis()
            val old = _skills.value
            old.copy(skills = list, selected = list.firstOrNull { it.name == old.selected?.name }, notice = api.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        }
    }
    fun openSkill(skill: NativeSkill) {
        if (_skills.value.saving) return
        _skills.update { it.copy(selected = skill, content = null, editing = false, draft = "") }
        skillsRequest { api -> _skills.value.copy(content = api.read(skill.name)) }
    }
    fun skillSearch(value: String) { _skills.update { it.copy(search = value) } }
    fun skillDraft(value: String) { _skills.update { it.copy(draft = value) }; draftChanged() }
    fun skillEdit(editing: Boolean) { if (!_skills.value.saving && (!editing || !_skills.value.loading && _skills.value.content != null)) { _skills.update { it.copy(editing = editing, draft = if (editing) it.content?.content.orEmpty() else "", error = null) }; draftChanged() } }
    fun closeSkill() { if (!_skills.value.saving) { skillJob?.cancel(); _skills.update { it.copy(selected = null, content = null, editing = false, draft = "", loading = false, error = null) }; draftChanged() } }
    fun toggleSkill(skill: NativeSkill, enabled: Boolean) = skillsRequest(true) { api ->
        api.toggle(skill.name, enabled)
        val list = api.list()
        client()?.let { keepSkills(it.scope, api) }
        _skills.value.copy(skills = list, selected = list.firstOrNull { it.name == _skills.value.selected?.name }, notice = "Saved. New chats use this change.")
    }
    fun saveSkill() {
        val original = _skills.value.content ?: return
        val draft = _skills.value.draft
        skillsRequest(true) { api ->
            api.save(original.name, draft, original.revision)
            val saved = api.read(original.name)
            _skills.value.copy(content = saved, draft = "", editing = false, notice = "Instructions saved. New chats use this change.")
        }
    }

    fun loadMemory(force: Boolean = true) {
        val active = client() ?: return
        bind()
        if (_memory.value.saving || _memory.value.editing.isNotBlank() || !force && (memoryJob?.isActive == true || startupReadFresh(memoryAt, System.currentTimeMillis()))) return
        memoryJob?.cancel()
        _memory.update { it.copy(loading = it.documents.isEmpty(), error = "", message = "") }
        memoryJob = jobs.launch {
            val c = active.detached()
            try {
                if (!force && _memory.value.documents.isEmpty()) cacheAttempt {
                    screens?.get(c.scope, "settings:memory")?.let { stored ->
                        val cached = stored.copy()
                        val value = NativeMemoryState.parse(c.scope.key, cached.getJSONObject("status"), cached.getJSONObject("notes"))
                        if (client() === active && _memory.value.editing.isBlank()) _memory.value = value
                    }
                }
                val profile = mapOf("profile" to c.auth.profile)
                val status = c.nativeGet("/api/memory", profile)
                val notes = c.nativeGet("/api/plugins/beacon/memory/documents", profile)
                val result = NativeMemoryState.parse(c.scope.key, status, notes)
                if (client() === active) {
                    if (_memory.value.editing.isBlank()) { _memory.value = result; memoryAt = System.currentTimeMillis() }
                    else memoryAt = 0L
                }
                cacheAttempt { screens?.set(c.scope, "settings:memory", json("status" to status, "notes" to notes)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _memory.update { it.copy(loading = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
    }
    fun editMemory(target: String) {
        if (_memory.value.saving || target.isNotBlank() && _memory.value.loading) return
        val state = _memory.value
        val document = state.documents.firstOrNull { it.target == target }
        if (document != null) { memoryJob?.cancel(); memoryAt = 0L }
        _memory.value = state.copy(editing = document?.target.orEmpty(), draft = document?.content.orEmpty(), error = "", message = "")
        draftChanged()
        if (target.isBlank() && memoryAt == 0L) loadMemory(force = false)
    }
    fun memoryDraft(value: String) { _memory.update { it.copy(draft = value) }; draftChanged() }
    fun saveMemory() {
        val active = client() ?: return
        val state = _memory.value
        if (state.saving || state.scope != active.scope.key) return
        val document = state.documents.firstOrNull { it.target == state.editing } ?: return
        _memory.update { it.copy(saving = true, error = "", message = "") }
        memoryJob = jobs.launch {
            val c = active.detached()
            try {
                val result = c.nativeGet("/api/plugins/beacon/memory/documents", mapOf("profile" to c.auth.profile), method = "PUT", data = document.saveBody(state.draft))
                require(result.optBoolean("ok")) { "Memory save wasn't confirmed. Your draft is kept." }
                val saved = result.getJSONObject("document")
                val confirmed = NativeMemoryDocument(saved.getString("target"), saved.getString("content"), saved.getString("revision"), saved.getBoolean("enabled"), saved.getInt("limit"))
                require(confirmed.target == document.target) { "Saved memory does not match this document" }
                cacheAttempt { screens?.remove(c.scope, "settings:memory") }
                if (client() === active) { memoryAt = System.currentTimeMillis(); _memory.update { it.copy(documents = it.documents.map { old -> if (old.target == confirmed.target) confirmed else old }, saving = false, editing = "", draft = "", message = "Notes saved. New chats use this change.") }; draftChanged() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _memory.update { it.copy(saving = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
    }

    fun loadServer(page: String, force: Boolean = false) {
        val active = client() ?: return
        bind(); serverJob?.cancel()
        _server.update { it.copy(loading = true, error = "") }
        serverJob = jobs.launch {
            val c = active.detached()
            try {
                val q = mapOf("profile" to c.auth.profile)
                val result = when (page) {
                    "Health" -> parseServerStatus(c.nativeGet("/api/status", q), _server.value)
                    "Logs" -> _server.value.copy(logs = c.nativeGet("/api/logs", q + mapOf("file" to _server.value.logFile, "search" to _server.value.logSearch, "lines" to "100")).optJSONArray("lines")?.strings().orEmpty())
                    "Session checkpoints" -> parseCheckpoints(c.nativeGet("/api/ops/checkpoints", q), _server.value)
                    "Server updates" -> {
                        val check = if (force || _server.value.update == null) c.nativeGet("/api/hermes/update/check", q + ("force" to force.toString())) else _server.value.update
                        val result = c.nativeGet("/api/plugins/beacon/admin/update/status", mapOf("requested_at" to _server.value.requestedAt, "action_id" to _server.value.requestedActionId))
                        val next = _server.value.copy(update = check, action = result.optJSONObject("action"), receipt = result.optJSONObject("receipt"))
                        next.copy(applying = next.action?.optBoolean("running") == true || next.requestedAt.isNotBlank() && next.outcome in setOf("running", "unconfirmed"))
                    }
                    else -> error("Unknown server page")
                }
                if (client() === active) _server.value = result.copy(loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _server.update { it.copy(loading = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
    }
    fun logFile(value: String) { _server.update { it.copy(logFile = value) }; loadServer("Logs") }
    fun logSearch(value: String) { _server.update { it.copy(logSearch = value) } }
    fun clearReviewedUpdate() {
        val c = client() ?: return
        if (!_server.value.canReviewUnknown) return
        serverJob?.cancel()
        val host = updateKey(c)
        saved["server-update:$host:at"] = ""; saved["server-update:$host:id"] = ""
        _server.update { it.copy(requestedAt = "", requestedActionId = "", applying = false, update = null, notice = "Previous update result remains unconfirmed. Checking the current server version.") }
        loadServer("Server updates", force = true)
    }
    fun applyServerUpdate() {
        val active = client() ?: return
        if (!_server.value.canApply) return
        val host = updateKey(active)
        _server.update { it.copy(applying = true, action = null, receipt = null, error = "") }
        serverJob?.cancel()
        serverJob = jobs.launch {
            val c = active.detached()
            try {
                val before = c.nativeGet("/api/plugins/beacon/admin/update/status")
                if (before.optJSONObject("action")?.optBoolean("running") == true) {
                    if (client() === active) _server.update { it.copy(action = before.optJSONObject("action"), applying = true, notice = "A server update is already running. Refresh to follow its status.") }
                    return@launch
                }
                val baseline = before.getString("server_now")
                saved["server-update:$host:at"] = baseline
                saved["server-update:$host:id"] = ""
                if (client() !== active) return@launch
                _server.update { it.copy(requestedAt = baseline, requestedActionId = "") }
                val response = c.nativeGet("/api/plugins/beacon/admin/update", method = "POST", data = JSONObject())
                if (!response.optBoolean("accepted")) {
                    saved["server-update:$host:at"] = ""
                    if (client() === active) _server.update { it.copy(applying = response.optBoolean("already_running"), requestedAt = "", error = response.string("message", response.string("error", "Hermes refused this update."))) }
                    return@launch
                }
                val id = response.string("action_id")
                saved["server-update:$host:id"] = id
                saved["server-update:$host:at"] = response.getString("requested_at")
                if (client() !== active) return@launch
                _server.update { it.copy(requestedActionId = id, requestedAt = response.getString("requested_at")) }
                repeat(60) {
                    delay(2000)
                    try {
                        val result = c.nativeGet("/api/plugins/beacon/admin/update/status", mapOf("requested_at" to _server.value.requestedAt, "action_id" to id))
                        if (client() !== active) return@launch
                        val next = _server.value.copy(action = result.optJSONObject("action"), receipt = result.optJSONObject("receipt"), error = "")
                        _server.value = next.copy(applying = next.outcome in setOf("running", "unconfirmed"))
                        if (!_server.value.applying) return@launch
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { if (client() === active) _server.update { it.copy(notice = "Waiting for Hermes to reconnect. Refresh to check the update receipt.") } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _server.update { it.copy(error = "Update result is not confirmed. Refresh the update status before starting another update.") } }
            finally { c.closeNative() }
        }
    }

    private fun botSummaries(response: JSONObject) = response.rows("bots").map { NativeBotSummary(it.getString("profile"), it.string("title", it.getString("profile")), it.string("purpose")) }
    fun loadBots(): Job? {
        val active = client() ?: return null
        bind(); if (_bots.value.saving || _bots.value.editing) return null
        botJob?.cancel(); _bots.update { it.copy(loading = true, error = "") }
        botJob = jobs.launch {
            val c = active.detached()
            try {
                if (_bots.value.profiles.isEmpty()) cacheAttempt {
                    screens?.get(c.scope, "settings:bots")?.let { stored ->
                        val cached = botSummaries(stored.copy())
                        if (client() === active && _bots.value.profiles.isEmpty()) _bots.update { it.copy(profiles = cached) }
                    }
                }
                val response = c.nativeGet("/api/plugins/beacon/admin/bots")
                val profiles = botSummaries(response)
                cacheAttempt { screens?.set(c.scope, "settings:bots", response) }
                if (client() === active) _bots.update { it.copy(profiles = profiles, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _bots.update { it.copy(loading = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
        return botJob
    }
    fun openBot(profile: String) {
        val active = client() ?: return
        if (_bots.value.saving || _bots.value.profiles.none { it.profile == profile }) return
        botJob?.cancel(); _bots.update { it.copy(loading = true, selected = null, editing = false, error = "", notice = "") }; _botModels.value = emptyList()
        botJob = jobs.launch {
            val c = active.detached()
            try {
                c.bindProfile(profile)
                val bot = NativeBot.parse(c.nativeGet("/api/plugins/beacon/admin/bot"))
                require(bot.profile == profile) { "Bot response belongs to another profile" }
                if (client() === active) _bots.update { it.copy(selected = bot) }
                val options = c.nativeGet("/api/model/options", mapOf("profile" to profile, "include_unconfigured" to "false"))
                val choices = options.rows("providers").flatMap { row -> row.optJSONArray("models")?.strings().orEmpty().map { row.string("slug") to it } }.distinct()
                if (client() === active && _bots.value.selected?.profile == profile) { _botModels.value = choices; _bots.update { it.copy(loading = false) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _bots.update { it.copy(loading = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
    }
    fun closeBot() { if (!_bots.value.saving) { botJob?.cancel(); _bots.update { it.copy(selected = null, editing = false, loading = false) }; draftChanged() } }
    fun editBot(editing: Boolean) { if (!_bots.value.saving && (!editing || !_bots.value.loading && _bots.value.selected != null)) { _bots.update { it.copy(editing = editing, draftProvider = it.selected?.provider.orEmpty(), draftModel = it.selected?.model.orEmpty(), error = "") }; draftChanged() } }
    fun botDraft(provider: String, model: String) { _bots.update { it.copy(draftProvider = provider, draftModel = model) }; draftChanged() }
    fun saveBot() {
        val active = client() ?: return
        val state = _bots.value; val bot = state.selected ?: return
        if (state.saving) return
        _bots.update { it.copy(saving = true, error = "") }
        botJob = jobs.launch {
            val c = active.detached()
            try {
                c.bindProfile(bot.profile)
                val response = c.nativeGet("/api/plugins/beacon/admin/bot", method = "PUT", data = bot.saveModel(state.draftProvider, state.draftModel))
                require(response.optBoolean("ok")) { "Bot save was not confirmed" }
                val confirmed = NativeBot.parse(response.getJSONObject("bot"))
                require(confirmed.profile == bot.profile) { "Saved bot belongs to another profile" }
                if (client() === active) { _bots.update { it.copy(selected = confirmed, saving = false, editing = false, notice = "Default model saved for new chats.") }; draftChanged() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (client() === active) _bots.update { it.copy(saving = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
    }
}
