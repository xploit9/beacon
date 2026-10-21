# Proactive assistant

Proactive watches important email for one Hermes profile. It uses whatever tools that profile already has, including MCP servers, skills, and configured integrations. It does not discover or select sources. Hermes memory holds what the owner has taught the assistant about important and unimportant email.

## API

The authenticated, owner/profile-checked `GET /api/plugins/cypher/mobile/proactive` returns `enabled`, `setup_complete`, the profile timezone, default delivery (`notify` or `call`), check cadence (`5`, `15`, or `30` minutes), quiet hours, email-check health, enabled and disabled rules, up to 20 learned email notes, and managed job status. Defaults are delivery `notify`, checks every 15 minutes, and quiet hours 22:00–07:00. The route does not scan for profile tools.

`PUT /api/plugins/cypher/mobile/proactive` accepts either `{"enabled": true|false}` to toggle the saved setup, or the complete setup with `enabled`, `delivery`, `check_minutes`, `quiet_start`, `quiet_end`, and optional `model` and `provider`. Unknown fields, non-boolean flags, invalid cadence, and invalid 24-hour times are rejected. Blank model/provider values use the profile default.

`POST /api/plugins/cypher/mobile/proactive/requests` creates an enabled email rule with plain-language `text` (up to 300 characters), `action` (`notify` or `call`), optional `signal` (`email` or null), and optional `night`. `PATCH .../requests/{id}` changes `enabled`, `action`, or `night`; `DELETE .../requests/{id}` removes a rule. Rules are owner/profile-scoped and capped at 20. The `cypher_create_proactive_request` chat tool is available only in interactive owner chats and confirms a saved rule.

`POST /api/plugins/cypher/mobile/proactive/feedback` accepts an email `notice_id` and a verdict of `important` or `not_important`. It saves a short sender/subject summary in Hermes's profile-scoped user memory; it never saves the email body. Duplicate notes are ignored and the plugin keeps at most 20 learned notes. `DELETE /api/plugins/cypher/mobile/proactive/learned` with `{"note": "..."}` removes one learned note.

## Jobs and safety

Hermes cron manages two jobs per enabled profile. **Cypher: important emails** checks at the configured cadence inside the non-quiet window. It also runs during quiet hours when an enabled call rule has `night: true`. The job uses the profile's own default toolsets, with no toolset or skill restrictions. **Nightly memory upkeep** runs at 02:00, uses only Hermes's `memory` toolset, and delivers locally. It keeps the owner's important-email notes few, specific, current, and free of contradicted entries.

The email prompt starts at the watch job's cron notepad `checked_until`; the first run checks the previous 60 minutes. It uses Hermes memory and enabled rules to judge importance. Newsletters, promotions, and automated notifications are not important unless the owner taught otherwise. After checking, it calls `cypher_email_checked` with the cursor, success state, and a short note. A failed check does not advance the cursor. The tool writes the job's Hermes cron notepad and a bounded health result; stale health is reported as `unknown`.

The `pre_tool_call` read-only guard runs only in an owned Proactive email-watch session. It blocks MCP tools unless Hermes marks them read-only or their raw names clearly describe a read and no write action. It also blocks write-like `action` and `operation` arguments, cron job management, and message delivery. `cypher_email_notice` and `cypher_email_checked` are allowed only by the plugin's owned-watch checks. Memory reads and web search are allowed; `web_extract` and `web_fetch` are blocked, so an email cannot send other mail out through a URL. Delegation, code execution, computer use, Kanban, browser actions and helper or compressed sessions of the watch run get the same checks or are blocked. Any other tool is denied by default unless its name or requested action clearly reads. In an owned watch run, terminal allows only `gws gmail … list` / `get` and `himalaya envelope list` / `search`, `folder list`, or `message read <id> --preview` (the subcommand words before the first option decide, and a read never marks mail as seen); anything else, and any shell control or comment character, is blocked. This is a command filter, not a sandbox, and it does not change behavior in ordinary chats or nightly memory upkeep. Turn Proactive off before disabling the plugin so it can remove its managed jobs and profile instructions.

The `post_tool_call` observer stores only short-lived hashes of item identifiers from successful tool results in the same owned watch session. Failed reads are rejected. `cypher_email_notice` requires fresh same-session evidence containing the email's exact returned item id, then applies a matching enabled rule or the saved default delivery. Model claims alone cannot notify or ring. It stores no source body or audio.

Call rules use Cypher's existing callback invitation path and appear in Call history. Quiet hours block calls unless the matching rule has `night: true`. Missing callback capability or the per-owner/profile limit of three proactive calls per hour falls back to a task-result notification. A successful notice is also the source for later owner feedback.

Email content is untrusted data and never grants permission to follow instructions or change external services. The read-only prompt and tool guard protect email checks; Hermes cron's approval policy still governs dangerous terminal commands. Enabling Proactive updates only its owned persona section in `SOUL.md`. Disabling removes only jobs recorded as owned by Cypher and removes only that managed persona section; unrelated jobs and owner-written profile text are preserved.

The older vetted-mail monitor route and preset, `mailbox_ready`, and `cypher_mail_read` are retired. Check-up calls remain unchanged.

The managed persona invites the owner to set up a recurring check when a need emerges, and creates a rule only after the owner agrees. When the owner says an email was or was not important, it saves one short note to Hermes user memory.

## Long conversations

Hermes already compacts older turns when the active context engine reaches its configured threshold. The base `ContextEngine` default is 75% of the model context window, though profile and model settings may change it; compaction also preserves the system prompt and a protected head and tail. A separately selected context engine can replace the request-only message list through `select_context()`. Cypher's supported `pre_llm_call` hook attaches a bounded capability note with Proactive health and the number of learned email notes. Hermes persists the sent context on that turn for replay, so Cypher suppresses an unchanged note for 30 minutes per session.

The next step for very long chats is to let one Cypher-visible thread map to a sequence of Hermes sessions. Before starting a new session, carry over only a short verified summary of open owner requests, confirmed preferences, and recent tool-backed results, while Cypher continues showing the full thread history. Keep the previous Hermes sessions intact.
