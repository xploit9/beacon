# Proactive assistant

Proactive is profile-scoped and capability-based. It discovers email, calendar, task, and news sources already available to the selected Hermes profile. Sources may be MCP servers (including Composio and self-hosted servers), Hermes skills such as Himalaya, or Hermes web search for news. Each signal can select one discovered source or none.

## API

The authenticated, owner/profile-checked `GET /api/plugins/cypher/mobile/proactive` returns `enabled`, `setup_complete`, the profile timezone, selected and discovered sources for each signal, health, the saved schedule, notification level, urgent-delivery preference, enabled and disabled requests, and managed job status. Defaults are brief 08:00, wrap-up 19:00, watch every 30 minutes, quiet hours 22:00–07:00, level `useful`, and urgent delivery `notify`. Watch intervals are `0`, `5`, `15`, `30`, or `60` minutes. A single read-only source is preselected for its signal; other signals start unselected.

`PUT /api/plugins/cypher/mobile/proactive` accepts either `{"enabled": true|false}` to toggle the saved setup, or the complete setup with `enabled`, `signals`, `schedule`, `level`, optional `urgent`, `model`, and `provider`. `urgent.delivery` is `notify` or `call`. Unknown fields, invalid times, and invalid choices are rejected. Source identifiers must still appear in current profile discovery. A model/provider supplied in setup pins each managed job; blank values use the profile default.

`POST /api/plugins/cypher/mobile/proactive/requests` creates an enabled request with plain-language `text` (up to 300 characters), `action` (`notify` or `call`), optional `signal`, and optional `night`. `PATCH .../requests/{id}` changes `enabled`, `action`, or `night`; `DELETE .../requests/{id}` removes it. Requests are owner/profile-scoped and capped at 20. The `cypher_create_proactive_request` chat tool is available only in interactive owner chats, is excluded from cron toolsets, and confirms the saved request in chat.

`POST /api/plugins/cypher/mobile/proactive/preview` accepts an empty body and queues the enabled brief jobs through Hermes cron. Results use the normal task-result notice; preview requires Proactive to be enabled and set up. `POST /api/plugins/cypher/mobile/proactive/feedback` accepts a verdict of `more` or `less` and a topic up to 200 characters. Feedback is stored in the profile's `PROACTIVE_PREFERENCES.md`, with only the newest 50 lines retained.

## Jobs and safety

Brief, wrap-up, and watch jobs are created separately for each selected source. MCP jobs receive only `mcp-<server>` and `memory` toolsets, never `terminal`. A source is marked read-only when every tool on the server carries Hermes's read-only hint, or when the server uses Hermes's `untrusted` tier and that profile's `approvals.cron_mode` is `deny`. If that evidence is unavailable or cron approvals are allowed, its status says it may change data. Skill jobs receive the selected skill plus `terminal` and `memory`, and are marked as requiring terminal access. Web jobs receive only `web` and `memory`. Nightly memory upkeep receives only `memory`, runs at 02:00 in the profile timezone, and delivers locally.

An enabled request for a selected signal that asks for an immediate check or a call forces that signal's watch interval to five minutes. Five-minute checks use more model runs. Other watches use the saved interval and run only inside the non-quiet window. Their prompts name the enabled matching requests and cannot create or edit requests.

The existing `post_tool_call`/`native_mailbox_observation` path records short-lived, hashed item identifiers from a successful read in the same watch session and selected source. MCP evidence is accepted only for tools Hermes identifies as read-only; a skill uses the terminal result from its selected skill job, where Hermes has no read-only hint. `cypher_email_notice` remains the watch-only alert tool under its historical name: it requires fresh same-session source evidence plus an enabled matching request, and cannot alert from model claims alone. Watch jobs return `[SILENT]` after any alert so Hermes does not also send a second cron report. The observer stores no source body or audio.

Call requests use Cypher's existing native callback invitation path, with the matching request in the reason; calls appear in Call history. Quiet hours block calls unless that request has `night: true`. A call blocked by quiet hours, missing callback capability, or the per-owner/profile limit of three proactive calls per hour falls back to a notification. Notifications and scheduled results use Cypher's existing durable task-result notices. The watch job itself does not read a source during quiet hours.

Source content is untrusted data and never grants permission to follow instructions or change external services. Skill jobs use Hermes terminal and can access whatever that profile's terminal can reach; cron's default deny mode blocks flagged commands but cannot prevent every command. Existing unrelated cron jobs and user-authored profile text are preserved. Disabling removes only jobs recorded as owned by Cypher and keeps the preferences file.

The old vetted mail monitor route and preset, `mailbox_ready`, and `cypher_mail_read` are retired. The generic notice/evidence callbacks remain and now work across the selected Proactive sources. Check-up calls remain unchanged.

The persona addition invites the owner to set up a recurring check when a need emerges, and creates a job only after the owner agrees.
