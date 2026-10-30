# Beacon native dashboard plugin

Supported native Hermes user plugin, manifest name `beacon`. Install or update it with Hermes' native CLI as described below. It ships no Hermes core files and no live secrets. `dashboard/plugin_api.py` is mounted by Hermes at `/api/plugins/beacon`. The API requires secure native auth middleware and a verified session on every request. Native Hermes itself only logs cross-login session attachment: this plugin additionally verifies persisted `sessions.user_id` and runtime `auth_user_id` against `provider:owner_id`.

Beacon is the app's default assistant name; the owner can rename it in Settings > Assistant.

## Install

On the Hermes host, use the native installer with the profile that runs the dashboard:

```sh
hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable
```

For an existing Beacon install from this repository, use `hermes -p <profile> plugins update beacon`. If you used Hermes Relay, follow [Coming from Hermes Relay?](../../docs/install-with-agent.md#coming-from-hermes-relay) before enabling Beacon. The repository is public; no GitHub login is required to install it. Never paste or print a token. Ask before restarting an active dashboard or changing network settings. The phone needs an HTTPS dashboard URL; Tailscale Serve is the recommended route. Then install the latest `Beacon-*.apk` from the public repository's [Releases page](https://github.com/xploit9/beacon/releases), open the dashboard's **Beacon** tab and copy the Hermes link. Paste it into Beacon: it takes you to the Hermes dashboard sign-in. Linking a phone needs that sign-in. Manage linked phones in Settings › Connection. See the full [install-with-agent procedure](../../docs/install-with-agent.md).

Releases use `Beacon-<version>.apk` and `Beacon-<version>.json` assets.

Runtime settings go under native config.yaml `plugins.entries.beacon.settings`; the CLI above installs and enables the plugin. Settings are read from the native process launch home, consistently across bound tool/profile calls:

```yaml
plugins:
  entries:
    beacon:
      settings:
        owner_id: hermes
        owner_provider: basic
        profiles: [default]
        model: gemini-3.8-live
        voice: Aoede
        firebase_credentials: /path/to/hermes-fcm.json
        callback_tool_enabled: true
        scheduled_callbacks_enabled: true
        scheduled_callback_conversation_id: OWNER_STAMPED_NATIVE_STORED_ID
```

On a fresh install, leave `owner_id`, `owner_provider`, `profiles`, and `scheduled_callback_conversation_id` unset: the first verified Hermes dashboard account binds the plugin automatically, and the Beacon tab shows the link to paste into Beacon. The app registers its assistant chat for that profile when it opens. If a scheduled result for the phone arrives first, the plugin saves an empty owned chat for that profile to receive it; the app's own Beacon chat replaces it as soon as that chat is saved. Explicit YAML settings always take precedence, so `scheduled_callback_conversation_id` can remain an operator-managed override and either callback flag can explicitly turn calls off. Voice provider keys are configured and kept on the phone; the plugin requires no Gemini, Grok, or OpenAI credentials on the Hermes host. Required Python packages are in requirements.txt; test dependencies in requirements-test.txt. Native Hermes must already supply its own dashboard/gateway packages on Python's import path.

The plugin does not accept or retain call audio. It stores call transcripts and metadata as text; live audio travels from the phone directly to the selected provider. Provider keys stay encrypted on the phone and are not sent to Hermes.

## Wire additions and proof

Plugin routes follow the native Hermes owner/profile contract. GET bootstrap requires explicit allowed `?profile=` when multiple profiles exist; no global profile change. Call-specific paths infer the stored call profile and authorize it against the allowlist.

Clients select the profile with `?beacon_profile=` on plugin routes; `?profile=` is still accepted, but dashboards that scope plugins per profile treat `profile` as a plugin-set selector, so Beacon Android sends `beacon_profile`. GET/PUT `/mobile/history-retention?beacon_profile=` reads or updates the owner-only `history_retention_days` setting in `mobile.sqlite` metadata; it never writes `config.yaml`. `0` (the default) keeps all Beacon call/task history; `30`, `90`, and `365` select a cutoff in days. Shortening prunes only expired closed Beacon calls, their timelines, and old terminal linked-job rows, in bounded batches immediately and from notification polling at most hourly. Active calls, ringing callbacks, running/recent jobs, other owners, and Hermes native sessions/messages/memories/files are preserved. The current plugin writes no transcript archive files.

Bot Mode shared spaces: before creating a space, the plugin looks for an existing matching room in at most 10 pages of 100 native group rooms (1,000). If that ceiling is reached without a match it answers 409 and creates nothing, so a retry can never produce a duplicate; archive unused rooms in Hermes and try again.

Legacy linked-job rows have no last-change timestamp, so migration dates them conservatively at upgrade time and retains them until they pass the selected window.

POST `/mobile/calls/{id}/jobs` additionally requires integer `user_row_id` from the actual native `prompt.submit` ACK. This is necessary because the RPC request id is not a native turn identity. Native history must contain that owned user row. One pending prompt per runtime is supported; duplicate request IDs and already-linked user rows cannot create new results/callbacks. Return `{job_id,request_id,stored_session_id,runtime_session_id,user_row_id,status:"working"}`. GET call returns `{call_id,status:"active"|"closed",recording_status,...,jobs:[{job_id,request_id,stored_session_id,runtime_session_id,user_row_id,status:"working"|"done"|"failed"|"needs_input"|"cancelled",result?,error?}]}`.

Completion requires a native replay `message.complete` persisted_turn receipt whose user_row_id matches, and a verified final native assistant row. Old completions never satisfy a newer job. Unknown/missing native evidence remains working; no result is invented. Native process restart/replay eviction can make completion evidence unavailable; automatic cold-restart completion inference is intentionally absent. Client native approval/reconnect remains the native RPC channel's responsibility. A server background watcher and bounded status/event polling reconcile jobs. Completion callbacks wait until all owned active calls end. FCM only wakes the phone; authenticated durable invitations remain authoritative. FCM failure retains pending state for retry; token/credential data never appears in target/event reads.

Notification target POST uses `{id|device_id, token|fcm_token, profile}`; GET returns sanitized targets; DELETE body `{id|device_id}`. Events GET `?profile=&after=<numeric cursor>` returns `{events,next_cursor}` with opaque event_id, native conversation_id, expiry and generic preview. Target credentials expire after 30 days unless refreshed, invitations 120 seconds after admission; these expirations never touch archives.

`beacon_call_user` is a namespaced native tool. It does not accept a user-selected owner, profile or session; the bound session and native profile authorize each invitation. Scheduled callbacks require an existing owner-stamped conversation configured for that profile. Invitations wait while another call is active, then ring the linked phone. Calls that cannot be tied to the configured owner and conversation are rejected.

## Fixture checks

Run `python -m pytest hermes-plugin/beacon/tests -q -p no:cacheprovider` from the repository root. Tests import the real plugin module and use temporary HERMES_HOME/private fixture state. Authentication injection and native/Google/FCM substitutes are test-only. No live provider, network or owner history/recordings is accessed. Parent must still validate installed native plugin/auth, real owner-stamped RPC sessions/jobs, actual Google constrained-token connection/audio, FCM, APK signer/upgrade and physical phone/Bluetooth behavior.

Compatibility notification fields: target registration returns target.id/target_id/source_ref, and event list/detail provides owner_id/profile/event_id/kind/source_ref/conversation_ref/callback_id/state/expiry. Refs are stable HMAC opaque values backed by a private durable random key. GET `/mobile/notifications/events/{event_id}?profile=` is owner-scoped; FCM includes only opaque refs and generic preview plus expiry.

Target revocation also supports DELETE `/mobile/notifications/targets/{target_id}?profile=` for existing Android revoke flows. A target must name a linked phone with `X-Beacon-Device-ID`; a missing or unknown ID gets 403 `device_required` and a removed phone gets 403 `device_removed`. Pushes go only to targets of phones that are still linked. Removing a phone does not end its Hermes sign-in. With Hermes' built-in login, set a new `dashboard.basic_auth.secret` (or `HERMES_DASHBOARD_BASIC_AUTH_SECRET`, which wins over the config when it is set) and restart the dashboard; that signs every phone out, so sign in again on the ones you keep.

## Native Hermes voice backend

Current Beacon voice calls submit ordinary requests into the attached native Hermes conversation. Its configured model, tools, MCP workflow, skills, memory provider and approvals remain authoritative. The realtime provider handles speech. GET `/mobile/calls/{id}/context?backend=hermes` supplies the bound profile, greeting, language and callback reference data without loading a parallel tool catalogue or copying native memory/history into the speech interface. The call records this mode so recall, retention and abandoned-call recovery stay with the native Hermes agent.

The quick-lookup routes below remain compatible with older released clients. Current voice requests use native `prompt.submit`, owner-bound job linkage and native results. See [voice calls](../../docs/voice-calls.md).

GET `/mobile/calls/{id}/context` returns the selected profile, system instruction, bounded conversation and voice history, personality, memory, callback context, greeting, tool catalogue and capability status. History, memory and callback results are reference data, not authorization. Native secret redaction runs before context leaves the host; the route does not start a voice-provider session.

POST `/mobile/calls/{id}/search` `{query}` returns `{summary,sources:[{title,url}],observed_at}`. It invokes the configured native Hermes web_search_tool in the call's native profile runtime/secret scope; no forced Gemini grounding. Query is capped at 1024 characters, native response 256 KiB, at most 3 credential-free HTTP(S) sources, and 6k summary characters. POST `/mobile/calls/{id}/memory` uses the same owner/profile guards and returns `{summary,sources:[{title,ref}],observed_at}`. It first reuses the configured native memory provider from the matching owner/profile/session's live manager or the manager the call already holds; otherwise it temporarily initializes the same provider for recall-only bounded native prefetch and shuts the temporary instance down afterward. Built-in memory uses read-only curated-memory matching. Writes go through Hermes' own `memory` tool and the provider's tools during the call (like Hermes, without a phone prompt; `approved_by: memory`), and after a call ends its transcript is handed to the memory provider once, like a chat turn (see docs/voice-calls.md). Missing native capability/evidence is unavailable, never a fabricated answer.

Lookup workers have a separate four-worker pool and live-slot cap so slow lookups cannot consume call HTTP/disk workers. Context deadline 8 seconds; search/recall 12 seconds; provider work that outlives a deadline retains its live slot until it actually ends. Each call permits 60 lookups per kind. Closing/finalizing clears counter bookkeeping; call audio is not stored. Callback answer returns call metadata; Beacon Android fetches `/mobile/calls/{id}/context` when the call starts. Callback results bind exact invitation job_id; scheduled beacon_call_user accepts optional bounded 240-character reason and never treats another same-chat job as its result.

Realtime provider connections go directly from Beacon to Gemini Live, Grok, or OpenAI Realtime. Provider keys stay encrypted on the phone; call admission sends only the provider id and `client_key: true` to Hermes. The plugin neither holds provider keys nor mints server-side voice tokens.

Authenticated notifications include preview.title/body; FCM preview remains generic and carries opaque references. No WebUI presence-check capability is advertised or simulated.

C6 wire: GET `/mobile/sessions/{stored_id}/voice-history?profile=` requires current native owner/profile verification and returns `{session_id,profile,events:[{call_id,event_id:"<call>:<role>:<turn>",session_id,role,text,final,interrupted,at_ms,created_at}],truncated}`. Versions group by call/role/turn with stable IDs; actual final text replaces earlier partials, while unfinished dialogue remains final=false with an explicit marker. Latest 200 grouped events and at most 1 MiB UTF-8 text return chronologically. created_at is the server reservation timestamp plus the call-relative at_ms offset. Authenticated response is no-store; unowned legacy sessions fail without relabelling. New-call context includes a bounded prior voice-dialogue excerpt marked historical reference data, never instructions. Nothing is inserted into native core history and no prompt is auto-run.

The mobile API has no audio route and retains no call audio; finalization reports `audio_available: false`. Transcript, call, and job metadata remain separate from audio.


## Native Tasks and category notification contracts

The plugin provides owned GET `/mobile/notifications/status` and POST `/mobile/notifications/test` (a synthetic FCM event only, never a call/model/mail operation). Status reports sanitized targets and queued/sender-accepted counts; device_delivery_confirmed stays false. A configured sender file/SDK is capability, not proof of successful FCM delivery. Generic preview text is authenticated; private mailbox content is not placed in the FCM envelope.

Native on_session_end hooks emit one completion event per owned native session/turn when completed and neither failed nor interrupted. Cron sessions must already be owner-stamped to produce task result notifications; old unowned sessions are not relabeled. Calls and approvals retain their original authoritative flows.

GET `/mobile/tasks/capabilities` reports the actually resolved profile timezone and preset prerequisites. POST `/mobile/tasks/checkups` creates an ordinary native no_agent script task (never a second timer or model loop). It requires scheduled_callbacks_enabled and an existing owner-stamped scheduled_callback_conversation_id. Three or four distinct per-day random opportunities fall between 10:00 inclusive and 22:00 exclusive in that profile's local time; only the exact scheduled minute is considered. Busy/pending-call opportunities are skipped permanently; missed opportunities do not catch up. The task can be paused/run/deleted with native task controls. Run now executes the gate, not an unconditional immediate ring.



## Scheduled posts in threads

Beacon's `phone` cron platform delivers to the Beacon chat (`deliver: phone`, the home channel) or to one of the owner's threads (`deliver: phone:<thread session id>`, the explicit `platform:chat_id` form Hermes cron supports, with other targets after a comma). For a thread, delivery verifies that the owner holds that conversation in the job's profile (any other id is refused with "That thread is no longer available"), records the result in the thread's Hermes conversation (its live continuation if Hermes compressed it), and then queues the usual task-result notice for that conversation, so the phone notifies and opens the thread. The result is stored with Hermes' own `SessionDB.append_completion_note`: a user turn labelled `[Cron delivery: <job name>]` (`display_kind` `cron_delivery`), keyed by the run so a retry adds no second row. Being a user turn keeps message alternation intact (consecutive user turns merge), and the thread's next turn adopts it whether the thread is open or closed (`tui_gateway.prompt_turn._adopt_out_of_band_turns`). Hermes refuses the write while a turn is running in that thread or its context is being compressed (a row added mid-turn would never reach that turn's history); the post is then held in `mobile.sqlite` (`held_posts`, at most 200, kept for a day) and the dashboard watcher adds it and queues its notice once the thread is free. Any other refusal fails the delivery as retryable and queues no notice. A repeat of the same delivery posts once. An older Hermes without `append_completion_note` fails the delivery with a clear error rather than queueing a notice for a post that is not there. Nothing else in Hermes is patched; `deliver: phone` behaves as before. Do not set `attach_to_session` on a job that targets a thread.

GET `/mobile/threads` returns the owner's threads for the request's profile as `{threads:[{id,title}]}` (Beacon threads and Relay threads, newest first, no-store). It is owner/profile checked like the other mobile routes; the app finds a thread's scheduled posts in the cron list it already holds.

## Linking phones and client-owned realtime keys

There is no separate pairing step. Pasting the Hermes link into Beacon signs the phone in to Hermes, and that sign-in is the link. `POST /devices/link` takes `{device_name}` and optionally the phone's `device_id`. It needs the owner's Hermes session, like the other owner routes. It returns `{device_id, name, profiles}` (`Cache-Control: no-store`). The phone's own `device_id` is reused while it is yours and not removed; otherwise a new one is made. A phone you removed is never reused and stays blocked. Up to 32 phones can be linked at a time (409 beyond that), and unknown fields are rejected with 400. Settings › Connection and the dashboard's Beacon tab list linked phones. Removing one stops Beacon notifications and calls to it; it is not signed out of Hermes.

`POST /mobile/calls` and `POST /mobile/callbacks/{invitation_id}/answer` require `voice_provider` plus `client_key: true` for phone-held Gemini, Grok, or OpenAI Realtime keys. Requests without phone-key mode are rejected, and provider keys are never accepted or stored by Hermes. `GET /mobile/notifications/events` keeps its existing `{events,next_cursor}` response and accepts optional `wait` from 0 to 25 seconds (default 0). These additions do not enable or retain call audio.
