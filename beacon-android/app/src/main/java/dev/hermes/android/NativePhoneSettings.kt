package dev.hermes.android

import dev.hermes.android.data.*
import dev.hermes.android.ui.NativeSkillsState
import dev.hermes.android.ui.MediaCache
import dev.hermes.android.ui.parseNativeMarkdown
import dev.hermes.android.ui.skillInstructionBody
import dev.hermes.android.ui.memoryEntries
import dev.hermes.android.ui.withSkillEnabled
import dev.hermes.android.core.*
import androidx.lifecycle.SavedStateHandle
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val SERVER_HEALTH_KEY = "settings:health"
private const val SERVER_UPDATE_KEY = "settings:update-check"
private const val SERVER_BACKUPS_KEY = "settings:backups"

/** Shown when the model Hermes has is no longer the one a model draft was made from: the draft is kept, the new base is shown, nothing is saved. */
internal const val MODEL_CHANGED_ELSEWHERE = "The model changed elsewhere. Review it and save again."
/** Shown when the model was written but reading it back failed: the typed pair is shown as saved, not as an error. */
internal const val MODEL_SAVED_UNCONFIRMED = "Default model saved for new chats. Not confirmed yet."
/**
 * The model a Save compares Hermes' current one with. Normally the pair the bot was opened with. After a save that could not be read back, Hermes may hold the pair in another form,
 * so for that same bot and pair what Hermes holds now is the base (the owner's own change, not another's). Any other bot or pair keeps its own base.
 */
internal fun modelSaveBase(unconfirmed: Triple<String, String, String>?, bot: NativeBot, now: Pair<String, String>): Pair<String, String> =
    if (unconfirmed == Triple(bot.profile, bot.provider, bot.model)) now else bot.provider to bot.model

/** The profile list as the plugin sends it, and as the saved copy of it holds it. */
internal fun parseBotSummaries(response: JSONObject) = response.rows("bots").map { NativeBotSummary(it.getString("profile"), it.string("title", it.getString("profile")), it.string("purpose")) }

/** Profile editors outlive their page; late responses never replace another account's draft. */
class NativePhoneSettings(private val jobs: CoroutineScope, private val saved: SavedStateHandle, private val draftChanged: () -> Unit = {}, private val screens: ScreenCache? = null,
    private val globalClient: (() -> BeaconClient?)? = null, private val authorizeProfile: suspend (BeaconClient, String) -> Unit = { _, _ -> },
    private val profileFailure: (Throwable) -> Unit = {}, private val cachedScope: () -> Scope? = { null }, private val client: () -> BeaconClient?) {
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
    private var scopeAccount = ""
    private var boundLists = ""
    private var boundHost = ""
    private var updateCheckedAt = 0L
    private var boundClient: BeaconClient? = null
    private var bindingRevision = 0L
    private var skillJob: Job? = null
    private var memoryJob: Job? = null
    private var serverJob: Job? = null
    private var updateJob: Job? = null
    private var updateObservationRevision = 0L
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
    private fun global() = if (globalClient == null) client() else globalClient.invoke()
    private fun current(active: BeaconClient, revision: Long) = client() === active && boundClient === active && bindingRevision == revision && scopeKey == active.scope.key
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
                "baseProvider" to base.provider, "baseModel" to base.model))
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
                NativeMemoryState(scopeKey, text(row, "provider", 160), flag(row, "providerReady"), listOf(base), editing = target, draft = draft, message = "Unsaved changes restored.")
            } else null
            val bot = if (value.has("bot")) value.getJSONObject("bot").let { row ->
                val profile = text(row, "profile", 160).also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
                val base = NativeBot(profile, text(row, "title", 1000), text(row, "description", 16_000), text(row, "soul"), text(row, "baseProvider", 1000), text(row, "baseModel", 1000))
                val provider = text(row, "draftProvider"); val model = text(row, "draftModel")
                require(provider != base.provider || model != base.model)
                NativeBotsState(profiles = listOf(NativeBotSummary(profile, base.title, base.description)), selected = base, editing = true, draftProvider = provider, draftModel = model)
            } else null
            Triple(skill, memory, bot)
        }.getOrNull() ?: return
        restored.first?.let { if (!_skills.value.editing && !_skills.value.saving) { skillJob?.cancel(); _skills.value = it } }
        restored.second?.let { if (_memory.value.editing.isBlank() && !_memory.value.saving) { memoryJob?.cancel(); _memory.value = it } }
        restored.third?.let { if (!_bots.value.editing && !_bots.value.saving) { botJob?.cancel(); _bots.value = it; it.selected?.let(::reviewRestoredModel) } }
        draftChanged()
    }

    /** The profile's model now, from its row in Hermes' profile list (GET /api/profiles has no probe; GET /api/model/info can take 5 s). */
    private suspend fun storedModel(c: BeaconClient, profile: String): Pair<String, String> {
        val row = c.nativeGet("/api/profiles").rows("profiles").firstOrNull { it.string("name") == profile } ?: throw ApiFailure(404, "Bot not found")
        return row.string("provider") to row.string("model")
    }
    /** The open editor of [bot] when Hermes has another model than the one the draft was made from: Hermes' model is the base now, the draft stays, and it says so. */
    private fun withModelChangedElsewhere(state: NativeBotsState, bot: NativeBot, now: Pair<String, String>): NativeBotsState =
        state.takeIf { it.selected == bot }?.copy(selected = bot.copy(provider = now.first, model = now.second), saving = false, error = MODEL_CHANGED_ELSEWHERE) ?: state
    /** A model draft that came back after a restart rests on the model of that day: if Hermes has another one now, show it before the owner saves. A failed read says nothing; Save reads again. */
    private fun reviewRestoredModel(bot: NativeBot) {
        val active = client() ?: return
        val revision = bindingRevision
        botJob = jobs.launch {
            val c = active.detached()
            try {
                authorizeProfile(active, bot.profile)
                if (!current(active, revision)) return@launch
                c.bindProfile(bot.profile)
                val now = storedModel(c, bot.profile)
                if (now != bot.provider to bot.model && current(active, revision)) { _bots.update { withModelChangedElsewhere(it, bot, now) }; draftChanged() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { c.closeNative() }
        }
    }

    /** The profile whose lists these are: the account plus the profile, so a reconnect or a trip through Settings keeps them and any other account or profile starts clean. */
    private fun listsOf(scope: Scope?): String = scope?.let { "${it.copy(profile = "", session = "").key}|${it.profile}" }.orEmpty()
    private fun savedSkills(scope: Scope?): List<NativeSkill> = scope?.let { screens?.exact(it, "settings:skills")?.value }
        ?.let { runCatching { parseNativeSkills(it.copy()) }.getOrNull() }.orEmpty()
    private fun savedMemory(scope: Scope?): NativeMemoryState? = scope?.let { screens?.exact(it, "settings:memory")?.value }
        ?.let { runCatching { val cached = it.copy(); NativeMemoryState.parse(scope.key, cached.getJSONObject("status"), cached.getJSONObject("notes")) }.getOrNull() }

    private suspend fun prepareMarkdown(texts: List<String>, current: () -> Boolean) {
        val missing = texts.distinct().filter { MediaCache.markdown.get(it) == null }
        if (missing.isEmpty()) return
        val parsed = withContext(Dispatchers.Default) {
            missing.mapNotNull { text ->
                try { text to parseNativeMarkdown(text) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
        }
        if (current()) parsed.forEach { (text, node) -> MediaCache.markdown.put(text, node) }
    }

    internal suspend fun prepareSavedMemory(scope: Scope, current: () -> Boolean) {
        if (screens?.exact(scope, "settings:memory") == null) return
        val entries = withContext(Dispatchers.Default) { savedMemory(scope)?.documents.orEmpty().flatMap { memoryEntries(it.content) } }
        if (current()) prepareMarkdown(entries, current)
    }

    /**
     * Points the editors at the client's profile (or, before any client exists, at the account the phone restored). [keepLists] and a plain rebind to the same
     * profile keep what the pages already show, the saved copy is the first thing a new profile shows, and only drafts and selections are dropped.
     */
    fun bind(force: Boolean = false, keepLists: Boolean = false) {
        val next = client()
        val scope = next?.scope ?: cachedScope()
        val key = scope?.key.orEmpty()
        if (!force && scopeKey == key && boundClient === next) return
        ++bindingRevision
        val account = scope?.let { it.copy(profile = "", session = "").key }.orEmpty()
        val lists = listsOf(scope)
        val same = lists.isNotBlank() && lists == boundLists && (!force || keepLists)
        val priorBots = _bots.value
        val priorModels = _botModels.value
        val priorSkills = _skills.value.skills
        val priorMemory = _memory.value
        val sameAccount = account.isNotBlank() && account == scopeAccount
        val carryBot = priorBots.takeIf { sameAccount && it.selected?.profile == scope?.profile }
        if (_skills.value.editing) skillDrafts[scopeKey] = _skills.value.copy(loading = false, saving = false)
        if (_memory.value.editing.isNotBlank()) memoryDrafts[scopeKey] = _memory.value.copy(loading = false, saving = false)
        if (_bots.value.editing && carryBot == null) botDrafts[scopeKey] = _bots.value.copy(loading = false, saving = false)
        skillJob?.cancel(); memoryJob?.cancel(); serverJob?.cancel(); botJob?.cancel(); updateJob?.cancel(); ++updateObservationRevision
        scopeKey = key; scopeAccount = account; boundClient = next; boundLists = lists
        if (!same) { skillsAt = 0L; memoryAt = 0L }
        // A job cancelled above never clears its own "loading", so a kept list starts quiet and the next load marks it again.
        _skills.value = skillDrafts.remove(key) ?: NativeSkillsState(profile = next?.auth?.profile ?: scope?.profile.orEmpty(), skills = if (same) priorSkills else if (force) emptyList() else savedSkills(scope))
        _memory.value = memoryDrafts.remove(key) ?: if (same) priorMemory.copy(scope = key, loading = false, saving = false, error = "", message = "", editing = "", draft = "") else (if (force) null else savedMemory(scope)) ?: NativeMemoryState(key)
        _bots.value = botDrafts.remove(key) ?: carryBot?.copy(loading = false, saving = false) ?: NativeBotsState(profiles = if (sameAccount) priorBots.profiles else emptyList())
        _botModels.value = if (carryBot != null && _bots.value.selected?.profile == carryBot.selected?.profile) priorModels else emptyList()
        val host = global()?.let(::updateKey).orEmpty()
        val hostScope = global()?.scope ?: cachedScope()
        val hostLists = listsOf(hostScope)
        val sameHost = hostLists.isNotBlank() && hostLists == boundHost && (!force || keepLists)
        val prior = _server.value
        boundHost = hostLists
        // What the pages last showed (not an update in flight, which the cancelled jobs above no longer track) stays until the new answer lands.
        val base = NativeServerState(requestedActionId = saved["server-update:$host:id"] ?: "", requestedAt = saved["server-update:$host:at"] ?: "")
        _server.value = when {
            sameHost -> base.copy(version = prior.version, overall = prior.overall, gatewayState = prior.gatewayState, activeAgents = prior.activeAgents, components = prior.components,
                recentError = prior.recentError, update = prior.update, checkpoints = prior.checkpoints, checkpointBytes = prior.checkpointBytes, logs = prior.logs, logFile = prior.logFile, logSearch = prior.logSearch, asked = prior.asked, answered = prior.answered, savedLogCount = prior.savedLogCount)
            force -> base
            else -> savedServer(hostScope, base).also { updateCheckedAt = 0L }
        }
        if (!sameHost) updateCheckedAt = 0L
    }

    /**
     * The saved copy for what is bound now, for when the account's saved screens finished loading after the pages bound (a page opened in the first moments of a start).
     * It only fills what is empty; anything already shown, edited or asked of Hermes stays.
     */
    fun seedSaved() {
        val scope = client()?.scope ?: cachedScope() ?: return
        if (scope.key != scopeKey) return
        if (_skills.value.skills.isEmpty() && !_skills.value.editing) savedSkills(scope).takeIf { it.isNotEmpty() }?.let { cached -> _skills.update { if (it.skills.isEmpty()) it.copy(skills = cached) else it } }
        if (_memory.value.documents.isEmpty() && _memory.value.editing.isBlank()) savedMemory(scope)?.let { cached -> if (cached.documents.isNotEmpty()) _memory.update { if (it.documents.isEmpty() && it.editing.isBlank()) cached else it } }
        if (!_server.value.asked) (global()?.scope ?: cachedScope())?.let { host -> _server.update { if (it.asked) it else savedServer(host, it) } }
    }

    /** The last health, update check and backups of this account's profile, as the pages last showed them. Log lines are never saved. */
    private fun savedServer(scope: Scope?, base: NativeServerState): NativeServerState {
        if (scope == null) return base
        var state = base
        runCatching { screens?.exact(scope, SERVER_HEALTH_KEY)?.value?.copy()?.let { state = parseServerStatus(it.getJSONObject("status"), state).copy(asked = true, answered = state.answered + "Health") } }
        runCatching { screens?.exact(scope, SERVER_UPDATE_KEY)?.value?.copy()?.let { state = state.copy(update = it, asked = true, answered = state.answered + "Server updates") } }
        runCatching { screens?.exact(scope, SERVER_BACKUPS_KEY)?.value?.copy()?.let { state = parseCheckpoints(it, state).copy(asked = true, answered = state.answered + "Session checkpoints") } }
        screens?.exact(scope, "settings:logs:${state.logFile}")?.value?.let {
            state = state.copy(savedLogCount = it.optInt("count"), answered = state.answered + "Logs")
        }
        return state
    }
    private fun keepServer(scope: Scope, key: String, value: JSONObject) { jobs.launch(Dispatchers.Default) { cacheAttempt { screens?.open(scope); screens?.set(scope, key, value) } } }

    /** [optimistic] shows the expected result at once; [undo] puts the old state back if the request fails (the error text is shown either way). */
    private fun skillsRequest(write: Boolean = false, optimistic: ((NativeSkillsState) -> NativeSkillsState)? = null, undo: ((NativeSkillsState) -> NativeSkillsState)? = null, toggling: String? = null, seed: (suspend () -> Unit)? = null, block: suspend (NativeSkills) -> NativeSkillsState) {
        val active = client() ?: return
        bind()
        val revision = bindingRevision
        if (_skills.value.saving || _skills.value.toggling != null) return
        skillJob?.cancel()
        // A switch saves without the screen-wide "Saving" state, so the list neither locks nor shifts.
        _skills.update { it.copy(loading = !write, saving = write && toggling == null, toggling = toggling, error = null, notice = null).let { started -> optimistic?.invoke(started) ?: started } }
        skillJob = jobs.launch {
            val c = active.detached()
            try {
                // The saved list comes first: authorizing asks Hermes for the profile list, and that waits for the network.
                seed?.invoke()
                authorizeProfile(active, c.scope.profile)
                if (!current(active, revision)) return@launch
                val result = block(NativeSkills(c))
                if (current(active, revision)) {
                    result.content?.takeIf { it.name == result.selected?.name && !result.editing }?.let {
                        screens?.set(active.scope, "skill-content:${it.name}", json("name" to it.name, "content" to it.content, "revision" to it.revision))
                    }
                    _skills.value = result.copy(loading = false, saving = false, toggling = null); draftChanged()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(active, revision)) { _skills.update { (undo?.invoke(it) ?: it).copy(loading = false, saving = false, toggling = null, error = appErrorText(e)) }; profileFailure(e) } }
            finally { c.closeNative() }
        }
    }
    fun loadSkills(force: Boolean = true) {
        val active = client() ?: return
        bind()
        val revision = bindingRevision
        if (_skills.value.editing || !force && (skillJob?.isActive == true || startupReadFresh(skillsAt, System.currentTimeMillis()))) return
        skillsRequest(seed = {
            if (!force && _skills.value.skills.isEmpty()) cacheAttempt {
                screens?.open(active.scope)
                val cached = savedSkills(active.scope)
                if (cached.isNotEmpty() && current(active, revision)) _skills.update { it.copy(skills = cached) }
            }
        }) { api ->
            val c = active
            val list = api.list()
            if (current(active, revision)) keepSkills(c.scope, api)
            if (current(active, revision)) skillsAt = System.currentTimeMillis()
            val old = _skills.value
            old.copy(skills = list, selected = list.firstOrNull { it.name == old.selected?.name }, notice = api.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        }
    }
    private fun cachedSkill(name: String): JSONObject? {
        val scope = client()?.scope ?: cachedScope()
        return scope?.takeIf { it.key == scopeKey }?.let { screens?.exact(it, "skill-content:$name")?.value }
            ?.takeIf { it.string("name") == name && it.has("content") && it.has("revision") }
    }
    internal fun hasSavedSkill(skill: NativeSkill) = cachedSkill(skill.name) != null
    fun openSkill(skill: NativeSkill) {
        if (_skills.value.saving || _skills.value.toggling != null) return
        val scope = client()?.scope ?: cachedScope()
        val cached = cachedSkill(skill.name)?.let { NativeSkillContent(skill.name, it.getString("content"), it.getString("revision")) }
        fun show() {
            _skills.update { it.copy(selected = skill, content = cached, editing = false, draft = "") }
            skillsRequest { api -> _skills.value.copy(content = api.read(skill.name)) }
        }
        val body = cached?.let { skillInstructionBody(it.content) }
        if (body == null || MediaCache.markdown.get(body) != null) { show(); return }
        val revision = bindingRevision
        skillJob?.cancel()
        val prepare = jobs.launch(start = CoroutineStart.LAZY) {
            // Prepare the existing renderer's cache off the main thread before exposing this document.
            fun current() = revision == bindingRevision && (client()?.scope ?: cachedScope()) == scope
            prepareMarkdown(listOf(body), ::current)
            if (!current()) return@launch
            skillJob = null
            show()
        }
        skillJob = prepare
        prepare.start()
    }
    fun skillSearch(value: String) { _skills.update { it.copy(search = value) } }
    fun skillDraft(value: String) { _skills.update { it.copy(draft = value) }; draftChanged() }
    fun skillEdit(editing: Boolean) { if (!_skills.value.saving && (!editing || !_skills.value.loading && _skills.value.content != null)) { _skills.update { it.copy(editing = editing, draft = if (editing) it.content?.content.orEmpty() else "", error = null) }; draftChanged() } }
    fun closeSkill() { if (!_skills.value.saving) { skillJob?.cancel(); _skills.update { it.copy(selected = null, content = null, editing = false, draft = "", loading = false, error = null) }; draftChanged() } }
    /** The switch moves at once. Hermes confirms in the background (toggle reads the list back to check it, and that list is the refresh); a failure puts the switch back. */
    fun toggleSkill(skill: NativeSkill, enabled: Boolean) {
      val active = client() ?: return
      bind()
      val revision = bindingRevision
      val was = _skills.value.skills.firstOrNull { it.name == skill.name }?.enabled ?: skill.enabled
      skillsRequest(true, optimistic = { it.withSkillEnabled(skill.name, enabled) }, undo = { it.withSkillEnabled(skill.name, was) }, toggling = skill.name) { api ->
        api.toggle(skill.name, enabled)
        val list = api.snapshot?.let(::parseNativeSkills) ?: api.list()
        if (current(active, revision)) keepSkills(active.scope, api)
        // The switch already shows the change; no notice pushes the list down.
        _skills.value.copy(skills = list, selected = list.firstOrNull { it.name == _skills.value.selected?.name })
      }
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
        val revision = bindingRevision
        if (_memory.value.saving || _memory.value.editing.isNotBlank() || !force && (memoryJob?.isActive == true || startupReadFresh(memoryAt, System.currentTimeMillis()))) return
        memoryJob?.cancel()
        _memory.update { it.copy(loading = it.documents.isEmpty(), error = "", message = "") }
        memoryJob = jobs.launch {
            val c = active.detached()
            try {
                // The saved notes come first: authorizing asks Hermes for the profile list, and that waits for the network.
                if (!force && _memory.value.documents.isEmpty()) cacheAttempt {
                    screens?.open(c.scope)
                    savedMemory(c.scope)?.let { value -> if (current(active, revision) && _memory.value.editing.isBlank()) _memory.value = value }
                }
                authorizeProfile(active, c.scope.profile)
                if (!current(active, revision)) return@launch
                val profile = mapOf("profile" to c.auth.profile)
                val status = c.nativeGet("/api/memory", profile)
                val notes = c.nativeGet("/api/plugins/beacon/memory/documents", profile)
                val result = NativeMemoryState.parse(c.scope.key, status, notes)
                prepareMarkdown(result.documents.flatMap { memoryEntries(it.content) }) { current(active, revision) }
                if (current(active, revision)) {
                    if (_memory.value.editing.isBlank()) { _memory.value = result; memoryAt = System.currentTimeMillis() }
                    else memoryAt = 0L
                }
                if (current(active, revision)) cacheAttempt { screens?.set(c.scope, "settings:memory", json("status" to status, "notes" to notes)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(active, revision)) { _memory.update { it.copy(loading = false, error = appErrorText(e)) }; profileFailure(e) } }
            finally { c.closeNative() }
        }
    }
    fun editMemory(target: String) {
        if (_memory.value.saving || target.isNotBlank() && (_memory.value.loading || client() == null)) return
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
        val revision = bindingRevision
        val state = _memory.value
        if (state.saving || state.scope != active.scope.key) return
        val document = state.documents.firstOrNull { it.target == state.editing } ?: return
        _memory.update { it.copy(saving = true, error = "", message = "") }
        memoryJob = jobs.launch {
            val c = active.detached()
            try {
                authorizeProfile(active, c.scope.profile)
                if (!current(active, revision)) return@launch
                val result = c.nativeGet("/api/plugins/beacon/memory/documents", mapOf("profile" to c.auth.profile), method = "PUT", data = document.saveBody(state.draft))
                require(result.optBoolean("ok")) { "Memory save wasn't confirmed. Your draft is kept." }
                val saved = result.getJSONObject("document")
                val confirmed = NativeMemoryDocument(saved.getString("target"), saved.getString("content"), saved.getString("revision"), saved.getBoolean("enabled"), saved.getInt("limit"))
                require(confirmed.target == document.target) { "Saved memory does not match this document" }
                if (current(active, revision)) cacheAttempt { screens?.remove(c.scope, "settings:memory") }
                if (current(active, revision)) { memoryAt = System.currentTimeMillis(); _memory.update { it.copy(documents = it.documents.map { old -> if (old.target == confirmed.target) confirmed else old }, saving = false, editing = "", draft = "", message = "Notes saved. New chats use this change.") }; draftChanged() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(active, revision)) { _memory.update { it.copy(saving = false, error = appErrorText(e)) }; profileFailure(e) } }
            finally { c.closeNative() }
        }
    }

    /** Warm non-mutating server summaries without replacing or cancelling the page the owner is reading. */
    internal suspend fun warmServer(active: BeaconClient, page: String) {
        val cache = screens ?: return
        if (global() !== active) return
        val key = when (page) {
            "Health" -> SERVER_HEALTH_KEY
            "Server updates" -> SERVER_UPDATE_KEY
            "Session checkpoints" -> SERVER_BACKUPS_KEY
            "Logs" -> "settings:logs:errors"
            else -> return
        }
        if (cache.exact(active.scope, key)?.let { startupReadFresh(it.at, System.currentTimeMillis()) } == true) return
        val c = active.detached()
        try {
            val q = mapOf("profile" to c.scope.profile)
            val data = when (page) {
                "Health" -> {
                    val status = c.nativeGet("/api/status", q)
                    val verdict = parseServerStatus(status, NativeServerState(), recentHermesError(c, q)).overall
                    json("status" to status.copy().put("overall", verdict))
                }
                "Server updates" -> c.nativeGet("/api/hermes/update/check", q + ("force" to "false"))
                "Session checkpoints" -> c.nativeGet("/api/ops/checkpoints", q)
                else -> json("count" to c.nativeGet("/api/logs", q + mapOf("file" to "errors", "lines" to "100")).getJSONArray("lines").length())
            }
            if (global() === active) {
                cache.set(active.scope, key, data)
                if (!_server.value.loading && page !in _server.value.answered) _server.update { savedServer(active.scope, it) }
            }
        } finally { c.closeNative() }
    }

    fun loadServer(page: String, force: Boolean = false) {
        // No client yet (the sign-in is still reconnecting): the page is waiting for its answer, not reporting an empty one.
        val active = global() ?: run { _server.update { it.copy(loading = true, error = "") }; return }
        bind(); serverJob?.cancel()
        _server.update { it.copy(loading = true, error = "", asked = true) }
        if (page == "Server updates") observeServerUpdate(active)
        serverJob = jobs.launch {
            val c = active.detached()
            try {
                val q = mapOf("profile" to c.auth.profile)
                if (page == "Server updates") {
                    val reuse = !force && _server.value.update != null && startupReadFresh(updateCheckedAt, System.currentTimeMillis())
                    val check = if (reuse) _server.value.update else c.nativeGet("/api/hermes/update/check", q + ("force" to force.toString()))
                    if (global() === active) {
                        _server.update { it.copy(update = check, loading = false, answered = it.answered + page) }
                        if (!reuse && check != null) { updateCheckedAt = System.currentTimeMillis(); keepServer(c.scope, SERVER_UPDATE_KEY, check) }
                    }
                    return@launch
                }
                val result = when (page) {
                    "Health" -> {
                        val status = c.nativeGet("/api/status", q)
                        val recent = recentHermesError(c, q)
                        // The saved copy keeps the verdict (Needs attention when the log said so), never the log's sentence: log lines are not saved.
                        parseServerStatus(status, _server.value, recent).also { if (global() === active) keepServer(c.scope, SERVER_HEALTH_KEY, json("status" to status.copy().put("overall", it.overall))) }
                    }
                    "Logs" -> {
                        val source = _server.value
                        val lines = c.nativeGet("/api/logs", q + mapOf("file" to source.logFile, "search" to source.logSearch, "lines" to "100")).getJSONArray("lines").strings()
                        if (global() === active && source.logSearch.isBlank()) keepServer(c.scope, "settings:logs:${source.logFile}", json("count" to lines.size))
                        source.copy(logs = lines, savedLogCount = null)
                    }
                    "Session checkpoints" -> {
                        val backups = c.nativeGet("/api/ops/checkpoints", q)
                        parseCheckpoints(backups, _server.value).also { if (global() === active) keepServer(c.scope, SERVER_BACKUPS_KEY, backups) }
                    }
                    else -> error("Unknown server page")
                }
                if (global() === active) _server.value = result.copy(loading = false, answered = result.answered + page)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (global() === active) _server.update { it.copy(loading = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
    }
    /** What Hermes' error log says just now (see [recentServerError]). Hermes' own health verdict is kept when the logs cannot be read: this only adds a warning. */
    private suspend fun recentHermesError(c: BeaconClient, q: Map<String, String>): String = try {
        readRecentServerError { query -> c.nativeGet("/api/logs", q + query).optJSONArray("lines")?.strings().orEmpty() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
    fun logFile(value: String) {
        val scope = global()?.scope ?: cachedScope()
        val count = scope?.let { screens?.exact(it, "settings:logs:$value")?.value?.optInt("count") }
        _server.update { it.copy(logFile = value, logs = emptyList(), savedLogCount = count, answered = if (count == null) it.answered - "Logs" else it.answered + "Logs") }
        loadServer("Logs")
    }
    fun logSearch(value: String) { _server.update { it.copy(logSearch = value) } }
    fun clearReviewedUpdate() {
        val c = global() ?: return
        if (!_server.value.canReviewUnknown) return
        updateJob?.cancel(); ++updateObservationRevision
        val host = updateKey(c)
        saved["server-update:$host:at"] = ""; saved["server-update:$host:id"] = ""
        _server.update { it.copy(requestedAt = "", requestedActionId = "", applying = false, updateStatusFresh = false, update = null, notice = "Checking the Hermes version…") }
        loadServer("Server updates", force = true)
    }
    fun applyServerUpdate() {
        val active = global() ?: return
        bind()
        if (!_server.value.canApply) return
        observeServerUpdate(active, apply = true)
    }

    /** The page/reconnect may resume observation, but never repeats the update POST. */
    fun resumeServerUpdate() {
        val active = global() ?: return
        bind()
        if (_server.value.requestedAt.isNotBlank() || _server.value.hostBusy || !_server.value.updateStatusFresh) observeServerUpdate(active)
    }

    private fun observeServerUpdate(active: BeaconClient, apply: Boolean = false) {
        if (updateJob?.isActive == true) return
        val revision = ++updateObservationRevision
        val host = updateKey(active)
        fun observing() = global() === active && updateObservationRevision == revision
        fun lost() { if (observing()) _server.update { it.copy(applying = false, updateStatusFresh = false, notice = "Waiting for Hermes to restart. Refresh to see if it finished.") } }
        _server.update { if (apply) it.copy(applying = true, updateStatusFresh = false, action = null, receipt = null, error = "")
            else it.copy(applying = false, updateStatusFresh = false) }
        updateJob = jobs.launch {
            val c = active.detached()
            try {
                val settled = withTimeoutOrNull(900_000L) {
                    if (apply) {
                        val before = c.nativeUpdateStatus()
                        if (!observing()) return@withTimeoutOrNull true
                        if (before.optBoolean("host_busy")) {
                            _server.update { it.withUpdateStatus(before) }
                        } else {
                            // This phone's own marker that it asked: the update is told apart by the action id Hermes answers with, not by a clock.
                            val baseline = java.time.Instant.now().toString()
                            saved["server-update:$host:at"] = baseline
                            saved["server-update:$host:id"] = ""
                            _server.update { it.copy(requestedAt = baseline, requestedActionId = "") }
                            if (!observing()) return@withTimeoutOrNull true
                            val response = c.nativeGet("/api/hermes/update", method = "POST", data = JSONObject())
                            if (!response.optBoolean("ok") || response.optBoolean("already_running")) {
                                saved["server-update:$host:at"] = ""; saved["server-update:$host:id"] = ""
                                if (!observing()) return@withTimeoutOrNull true
                                _server.update { it.copy(applying = false, updateStatusFresh = false, requestedAt = "", requestedActionId = "",
                                    error = if (response.optBoolean("already_running")) "" else response.string("message", response.string("error", "Hermes refused this update."))) }
                                if (!response.optBoolean("already_running")) return@withTimeoutOrNull true
                            } else {
                                val id = response.string("action_id")
                                require(id.isNotBlank()) { "Update action identity is missing" }
                                saved["server-update:$host:id"] = id
                                if (!observing()) return@withTimeoutOrNull true
                                _server.update { it.copy(requestedActionId = id) }
                            }
                        }
                    }
                    var failures = 0
                    repeat(450) {
                        if (!observing()) return@withTimeoutOrNull true
                        try {
                            val state = _server.value
                            val result = c.nativeUpdateStatus()
                            if (!observing()) return@withTimeoutOrNull true
                            _server.update { it.withUpdateStatus(result) }
                            failures = 0
                            if (!_server.value.applying && !_server.value.hostBusy) return@withTimeoutOrNull true
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { lost(); if (++failures >= 3) return@withTimeoutOrNull true }
                        delay(2000)
                    }
                    false
                }
                if (settled != true) lost()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { lost() }
            finally { c.closeNative() }
        }
    }

    private fun botSummaries(response: JSONObject) = parseBotSummaries(response)
    fun loadBots(): Job? {
        val editor = client() ?: return null
        val active = global() ?: return null
        bind(); if (_bots.value.saving || _bots.value.editing) return null
        val revision = bindingRevision
        botJob?.cancel(); _bots.update { it.copy(loading = true, error = "") }
        botJob = jobs.launch {
            val c = active.detached()
            try {
                if (_bots.value.profiles.isEmpty()) cacheAttempt {
                    screens?.get(c.scope, "settings:bots")?.let { stored ->
                        val cached = botSummaries(stored.copy())
                        if (current(editor, revision) && global() === active && _bots.value.profiles.isEmpty()) _bots.update { it.copy(profiles = cached) }
                    }
                }
                val response = c.nativeGet("/api/plugins/beacon/admin/bots")
                val profiles = botSummaries(response)
                if (current(editor, revision) && global() === active) cacheAttempt { screens?.set(c.scope, "settings:bots", response) }
                if (current(editor, revision) && global() === active) _bots.update { it.copy(profiles = profiles, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(editor, revision) && global() === active) _bots.update { it.copy(loading = false, error = appErrorText(e)) } }
            finally { c.closeNative() }
        }
        return botJob
    }
    /** A bot from Hermes' own routes: its row in the profile list and its instructions. */
    private suspend fun nativeBot(c: BeaconClient, profile: String): NativeBot {
        val row = c.nativeGet("/api/profiles").rows("profiles").firstOrNull { it.string("name") == profile } ?: throw ApiFailure(404, "Bot not found")
        return NativeBot.fromNative(row, c.nativeGet("/api/profiles/$profile/soul").string("content"))
    }
    fun openBot(profile: String): Job? {
        val active = client() ?: return null
        val revision = bindingRevision
        if (_bots.value.saving || _bots.value.profiles.none { it.profile == profile }) return null
        botJob?.cancel(); _bots.update { it.copy(loading = true, selected = null, editing = false, error = "", notice = "", unconfirmedModel = null) }; _botModels.value = emptyList()
        botJob = jobs.launch {
            val c = active.detached()
            try {
                authorizeProfile(active, profile)
                if (!current(active, revision)) return@launch
                c.bindProfile(profile)
                val bot = nativeBot(c, profile)
                if (current(active, revision)) _bots.update { it.copy(selected = bot) }
                val options = c.nativeGet("/api/model/options", mapOf("profile" to profile, "include_unconfigured" to "false"))
                val choices = options.rows("providers").flatMap { row -> row.optJSONArray("models")?.strings().orEmpty().map { row.string("slug") to it } }.distinct()
                if (current(active, revision) && _bots.value.selected?.profile == profile) { _botModels.value = choices; _bots.update { it.copy(loading = false) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(active, revision)) { _bots.update { it.copy(loading = false, error = appErrorText(e)) }; profileFailure(e) } }
            finally { c.closeNative() }
        }
        return botJob
    }
    /** Hermes confirmed a new [description] or instructions ([soul]) for [profile]: the open profile shows it without a reload. */
    fun botEdited(profile: String, description: String? = null, soul: String? = null) {
        _bots.update { state -> state.selected?.takeIf { it.profile == profile }?.let { bot ->
            state.copy(selected = bot.copy(description = description ?: bot.description, soul = soul ?: bot.soul)) } ?: state }
    }
    fun closeBot() { if (!_bots.value.saving) { botJob?.cancel(); _bots.update { it.copy(selected = null, editing = false, loading = false, unconfirmedModel = null) }; draftChanged() } }
    fun editBot(editing: Boolean) { if (!_bots.value.saving && (!editing || !_bots.value.loading && _bots.value.selected != null)) { _bots.update { it.copy(editing = editing, draftProvider = it.selected?.provider.orEmpty(), draftModel = it.selected?.model.orEmpty(), error = "") }; draftChanged() } }
    fun botDraft(provider: String, model: String) { _bots.update { it.copy(draftProvider = provider, draftModel = model) }; draftChanged() }
    fun saveBot() {
        val active = client() ?: return
        val revision = bindingRevision
        val state = _bots.value; val bot = state.selected ?: return
        if (state.saving) return
        _bots.update { it.copy(saving = true, error = "") }
        botJob = jobs.launch {
            val c = active.detached()
            try {
                authorizeProfile(active, bot.profile)
                if (!current(active, revision)) return@launch
                c.bindProfile(bot.profile)
                // Hermes' PUT is last writer wins, so the phone looks first: a draft made from a model that changed since (on the computer, or before a restart) is not saved over it.
                val now = storedModel(c, bot.profile)
                // The base is a pair this phone wrote and could not read back: Hermes may have stored it normalised, so what it holds now is the base (the owner's own change, not another's).
                val base = modelSaveBase(state.unconfirmedModel, bot, now)
                if (now != base) {
                    if (current(active, revision)) { _bots.update { withModelChangedElsewhere(it, bot, now) }; draftChanged() }
                    return@launch
                }
                // Hermes' own PUT /api/profiles/{name}/model. Its answer echoes the strings it was sent, but what it stores went through its own normalisation (alias, vendor
                // prefix, dot or dash form), so the stored pair is read back the way the check before the write reads it and that is what is shown: the next Save then
                // compares against what Hermes holds and meets no false "changed elsewhere". A blank stored model is not a confirmed save.
                val response = c.nativeGet("/api/profiles/${bot.profile}/model", method = "PUT", data = bot.saveModel(state.draftProvider, state.draftModel))
                require(response.optBoolean("ok") && response.string("model").isNotBlank()) { "Bot save was not confirmed" }
                // The write worked. If only the read-back fails (a network blip) that is no error: the typed pair is shown as saved, said honestly as not confirmed yet.
                val stored = try { storedModel(c, bot.profile) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                require(stored == null || stored.second.isNotBlank()) { "Bot save was not confirmed" }
                val confirmed = if (stored != null) bot.copy(provider = stored.first, model = stored.second) else bot.copy(provider = state.draftProvider, model = state.draftModel)
                if (current(active, revision)) {
                    _bots.update { it.copy(selected = confirmed, saving = false, editing = false, unconfirmedModel = if (stored == null) Triple(confirmed.profile, confirmed.provider, confirmed.model) else null,
                        notice = if (stored == null) MODEL_SAVED_UNCONFIRMED else "Default model saved for new chats.") }
                    draftChanged()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(active, revision)) { _bots.update { it.copy(saving = false, error = appErrorText(e)) }; profileFailure(e) } }
            finally { c.closeNative() }
        }
    }
}
