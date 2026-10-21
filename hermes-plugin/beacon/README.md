# Beacon native dashboard plugin

Supported native Hermes user plugin, manifest name `beacon`. Install or update it with Hermes' native CLI as described below. It ships no Hermes core files and no live secrets. `dashboard/plugin_api.py` is mounted by Hermes at `/api/plugins/beacon`. The API requires secure native auth middleware and a verified session on every request, including APK bytes. Native Hermes itself only logs cross-login session attachment: this plugin additionally verifies persisted `sessions.user_id` and runtime `auth_user_id` against `provider:owner_id`.

## Install

On the Hermes host, use the native installer with the profile that runs the dashboard:

```sh
hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable
```

For an existing Beacon install from this repository, use `hermes -p <profile> plugins update beacon`. If `hermes-relay` is installed in the selected profile, back up its folder outside `plugins`, verify the copy, then disable it before enabling Beacon. The repository is public; no GitHub login is required to install it. Never paste or print a token. Ask before restarting an active dashboard or changing network settings. The phone needs an HTTPS dashboard URL; Tailscale Serve is the recommended route. Then install the latest `Beacon-*.apk` from the public repository's [Releases page](https://github.com/xploit9/beacon/releases), open the dashboard's **Beacon** tab, and scan its QR with the Beacon app. See the full [install-with-agent procedure](../../docs/install-with-agent.md).

Pair with `hermes -p <profile> beacon pair` or `/beacon-pair` from an owned chat. Releases use only `Beacon-<version>.apk` and `Beacon-<version>.json` assets.

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
        release_root: PRIVATE_RELEASE_DIRECTORY
        android_certificate_sha256: EXPECTED_CERTIFICATE_SHA256
        aapt: ABSOLUTE_AAPT_PATH
        apksigner: ABSOLUTE_APKSIGNER_PATH
```

On a fresh install, leave `owner_id`, `owner_provider`, `profiles`, and `scheduled_callback_conversation_id` unset: claim Beacon once from its authenticated Hermes dashboard tab, then the app registers its assistant chat for that profile after opening it. Explicit YAML settings always take precedence, so `scheduled_callback_conversation_id` can remain an operator-managed override and either callback flag can explicitly turn calls off. Voice provider keys are configured and kept on the phone; the plugin requires no Gemini, Grok, or OpenAI credentials on the Hermes host. Required Python packages are in requirements.txt; test dependencies in requirements-test.txt. Native Hermes must already supply its own dashboard/gateway packages on Python's import path.

The Beacon plugin does not accept or retain call audio: the legacy `/mobile/calls/{call_id}/audio` storage route rejects uploads, and finalized call records report `recording_status: "disabled"` and `audio_available: false`. Transcript text and call metadata are stored separately. Other limits include 64 events per batch, 20k timeline events per call, a 4h call clock, eight active calls per owner, 64 native job links per call, 32 device targets, 10k notification invitations, and 256 deferred callbacks.

Finalization stores transcript/event metadata and reports `transcript_available: true`; it creates no audio file or recording archive. The legacy audio endpoint rejects old and new clients. The live call transport remains direct to the provider.

## Wire additions and proof

Plugin routes follow the native Hermes owner/profile contract. GET bootstrap requires explicit allowed `?profile=` when multiple profiles exist; no global profile change. Call-specific paths infer the stored call profile and authorize it against the allowlist.

Clients select the profile with `?beacon_profile=` on plugin routes; `?profile=` is still accepted, but dashboards that scope plugins per profile treat `profile` as a plugin-set selector, so Beacon Android sends `beacon_profile`. GET/PUT `/mobile/history-retention?beacon_profile=` reads or updates the owner-only `history_retention_days` setting in `mobile.sqlite` metadata; it never writes `config.yaml`. `0` (the default) keeps all Beacon call/task history; `30`, `90`, and `365` select a cutoff in days. Shortening prunes only expired closed Beacon calls, their timelines, and old terminal linked-job rows, in bounded batches immediately and from notification polling at most hourly. Active calls, ringing callbacks, running/recent jobs, other owners, and Hermes native sessions/messages/memories/files are preserved. The current plugin writes no transcript archive files.

Bot Mode shared spaces: before creating a space, the plugin looks for an existing matching room in at most 10 pages of 100 native group rooms (1,000). If that ceiling is reached without a match it answers 409 and creates nothing, so a retry can never produce a duplicate; archive unused rooms in Hermes and try again.

Legacy linked-job rows have no last-change timestamp, so migration dates them conservatively at upgrade time and retains them until they pass the selected window.

POST `/mobile/calls/{id}/jobs` additionally requires integer `user_row_id` from the actual native `prompt.submit` ACK. This is necessary because the RPC request id is not a native turn identity. Native history must contain that owned user row. One pending prompt per runtime is supported; duplicate request IDs and already-linked user rows cannot create new results/callbacks. Return `{job_id,request_id,stored_session_id,runtime_session_id,user_row_id,status:"working"}`. GET call returns `{call_id,status:"active"|"closed",recording_status,...,jobs:[{job_id,request_id,stored_session_id,runtime_session_id,user_row_id,status:"working"|"done"|"failed"|"needs_input"|"cancelled",result?,error?}]}`.

Completion requires a native replay `message.complete` persisted_turn receipt whose user_row_id matches, and a verified final native assistant row. Old completions never satisfy a newer job. Unknown/missing native evidence remains working; no result is invented. Native process restart/replay eviction can make completion evidence unavailable; automatic cold-restart completion inference is intentionally absent. Client native approval/reconnect remains the native RPC channel's responsibility. A server background watcher and bounded status/event polling reconcile jobs. Completion callbacks wait until all owned active calls end. FCM only wakes the phone; authenticated durable invitations remain authoritative. FCM failure retains pending state for retry; token/credential data never appears in target/event reads.

Notification target POST uses `{id|device_id, token|fcm_token, profile}`; GET returns sanitized targets; DELETE body `{id|device_id}`. Events GET `?profile=&after=<numeric cursor>` returns `{events,next_cursor}` with opaque event_id, native conversation_id, expiry and generic preview. Target credentials expire after 30 days unless refreshed, invitations 120 seconds after admission; these expirations never touch archives.

`beacon_call_user` is a namespaced native tool, with no xara_call alias or override. It takes no user-selected owner/profile/session. Bound session identity and native profile home authorize the invitation. An explicitly enabled native cron callback uses configured `scheduled_callback_conversation_id`, which must be an existing owner-stamped native chat; unowned cron/legacy history is never relabelled or granted ownership. Invitations defer while any call is active, then receive 120 seconds admission. Scheduled callback markers expire after that due-time window plus seven days, and expired callback replays are rejected whether a delivered marker remains or has been pruned. The tool reports pending invitation, never that the phone answered. When the bound chat exists but is not owned by the configured owner (for example a chat Hermes saved without its sign-in), the tool answers `reason: "conversation_not_owned"` and the call and callback-answer routes answer 404, both with a plain instruction to start a new chat in Beacon; every other lookup failure stays generic. Parent must migrate old scheduled prompts/tool references from xara_call to beacon_call_user and provision the owner-bound callback conversation. Legacy plugin/services remain parent-controlled for rollback.

APK root contains immutable APK filenames, `latest.json` and `<sha256>.json` for every published immutable download. Manifest keys: artifact_filename, package_name=`dev.hermes.android`, version_code>20, version_name, certificate_sha256, sha256, size_bytes. Every read independently checks hash/size/package/version plus actual configured aapt badging and apksigner verification against the expected signer. Download verifies an immutable byte snapshot before response; older hash manifests remain downloadable after latest changes. No source APK or release manifest is provided here; parent publishes only after real validation.

## Fixture checks

Run `python -m pytest hermes-plugin/beacon/tests -q -p no:cacheprovider` from the repository root. Tests import the real plugin module and use temporary HERMES_HOME/private fixture state. Authentication injection and native/Google/FCM substitutes are test-only. No live provider, network or owner history/recordings is accessed. Parent must still validate installed native plugin/auth, real owner-stamped RPC sessions/jobs, actual Google constrained-token connection/audio, FCM, APK signer/upgrade and physical phone/Bluetooth behavior.

Compatibility notification fields: target registration returns target.id/target_id/source_ref, and event list/detail provides owner_id/profile/event_id/kind/source_ref/conversation_ref/callback_id/state/expiry. Refs are stable HMAC opaque values backed by a private durable random key. GET `/mobile/notifications/events/{event_id}?profile=` is owner-scoped; FCM includes only opaque refs and generic preview plus expiry.

Target revocation also supports DELETE `/mobile/notifications/targets/{target_id}?profile=` for existing Android revoke flows.

## Native voice context and quick lookups

GET `/mobile/calls/{id}/context` returns the frozen Android shape: `{profile,owner_display_name?,system_instruction,context_summary,greeting,callback:{reason?,result?,job_id?},selected_session_id,capabilities:{web_search:bool,memory_recall:bool}}`. Its additional context object carries bounded role/content history, personality, memory, optional language, and `callback_reason:{untrusted_text:string}`; the greeting stays fixed and callback reason remains reference data. `capability_details` explains unavailable providers. Native SOUL scanning and curated-memory sanitization are preserved. Read at most 32 selected native rows, 16k history characters, 12k persona/memory characters, 24 KiB summary and 6k verified callback result. Native secret redaction runs before context leaves the host. History, memory and callback results are explicitly reference data, never fresh authorization. No model loop/provider session is initialized for context.

POST `/mobile/calls/{id}/search` `{query}` returns `{summary,sources:[{title,url}],observed_at}`. It invokes the configured native Hermes web_search_tool in the call's native profile runtime/secret scope; no forced Gemini grounding. Query is capped at 1024 characters, native response 256 KiB, at most 3 credential-free HTTP(S) sources, and 6k summary characters. POST `/mobile/calls/{id}/memory` uses the same owner/profile guards and returns `{summary,sources:[{title,ref}],observed_at}`. It uses only an already-initialized matching native memory provider's bounded native prefetch path, or read-only curated-memory matching; no sync/write hooks or new memory-provider initialization. Missing native capability/evidence is unavailable, never a fabricated answer.

Lookup workers have a separate four-worker pool and live-slot cap so slow lookups cannot consume call HTTP/disk workers. Context deadline 8 seconds; search/recall 12 seconds; provider work that outlives a deadline retains its live slot until it actually ends. Each call permits 60 lookups per kind. Closing/finalizing clears counter bookkeeping; call audio is not stored. Callback answer returns call metadata; Beacon Android fetches `/mobile/calls/{id}/context` when the call starts. Callback results bind exact invitation job_id; scheduled beacon_call_user accepts optional bounded 240-character reason and never treats another same-chat job as its result.

Realtime provider connections go directly from Beacon to Gemini Live, Grok, or OpenAI Realtime. Provider keys stay encrypted on the phone; call admission sends only the provider id and `client_key: true` to Hermes. The plugin neither holds provider keys nor mints server-side voice tokens.

Release metadata includes artifact_path and min_contract_version=max_contract_version=2 for the current Android native contract. Authenticated notifications include preview.title/body; FCM preview remains generic and carries opaque references. No WebUI presence-check capability is advertised or simulated.

C6 wire: GET `/mobile/sessions/{stored_id}/voice-history?profile=` requires current native owner/profile verification and returns `{session_id,profile,events:[{call_id,event_id:"<call>:<role>:<turn>",session_id,role,text,final,interrupted,at_ms,created_at}],truncated}`. Versions group by call/role/turn with stable IDs; actual final text replaces earlier partials, while unfinished dialogue remains final=false with an explicit marker. Latest 200 grouped events and at most 1 MiB UTF-8 text return chronologically. created_at is the server reservation timestamp plus the call-relative at_ms offset. Authenticated response is no-store; unowned legacy sessions fail without relabelling. New-call context includes a bounded prior voice-dialogue excerpt marked historical reference data, never instructions. Nothing is inserted into native core history and no prompt is auto-run.

The mobile API does not retain call audio; audio-storage requests are rejected and finalization reports `audio_available: false`. Transcript, call, and job metadata remain separate from audio.


## Native Tasks and category notification contracts

The candidate adds owned GET `/mobile/notifications/status` and POST `/mobile/notifications/test` (synthetic FCM event only, never a call/model/mail operation). Status reports sanitized targets and queued/sender-accepted counts; device_delivery_confirmed stays false. A configured sender file/SDK is capability, not proof of successful FCM delivery. Generic preview text is authenticated; private mailbox content is not placed in the FCM envelope.

Native on_session_end hooks emit one completion event per owned native session/turn when completed and neither failed nor interrupted. Cron sessions must already be owner-stamped to produce task result notifications; old unowned sessions are not relabeled. Calls and approvals retain their original authoritative flows.

GET `/mobile/tasks/capabilities` reports the actually resolved profile timezone and preset prerequisites. POST `/mobile/tasks/checkups` creates an ordinary native no_agent script task (never a second timer or model loop). It requires scheduled_callbacks_enabled and an existing owner-stamped scheduled_callback_conversation_id. Three or four distinct per-day random opportunities fall between 10:00 inclusive and 22:00 exclusive in that profile's local time; only the exact scheduled minute is considered. Busy/pending-call opportunities are skipped permanently; missed opportunities do not catch up. The task can be paused/run/deleted with native task controls. Run now executes the gate, not an unconditional immediate ring.



## Phone connection and client-owned realtime keys

The visible Beacon dashboard tab includes a **Connect phone** QR and copyable `beacon://connect?url=<encoded-url>&name=<encoded-name>` link. A verified dashboard session can claim the plugin once; after that the QR appears. The public HTTPS QR carries the URL and name. In loopback mode, the loopback QR includes the Hermes sign-in value in `session` for the local setup flow. On the phone: install Beacon, tap Scan QR, then sign in through Hermes OAuth.

`POST /mobile/calls` and `POST /mobile/callbacks/{invitation_id}/answer` require `voice_provider` plus `client_key: true` for phone-held Gemini, Grok, or OpenAI Realtime keys. Requests without phone-key mode are rejected, and provider keys are never accepted or stored by Hermes. `GET /mobile/notifications/events` keeps its existing `{events,next_cursor}` response and accepts optional `wait` from 0 to 25 seconds (default 0). These additions do not enable or retain call audio.
