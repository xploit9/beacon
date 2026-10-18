# Cypher native dashboard plugin

Supported native Hermes user plugin, manifest name `cypher`. Install or update it with Hermes' native CLI as described below. No Hermes core files, archived WebUI/Pipecat code or live credentials are imported. `dashboard/plugin_api.py` is mounted by Hermes at `/api/plugins/cypher`. The API requires secure native auth middleware and a verified session on every request, including APK bytes. Native Hermes itself only logs cross-login session attachment: this plugin additionally verifies persisted `sessions.user_id` and runtime `auth_user_id` against `provider:owner_id`.

## Install

On the Hermes host, use the native installer with the profile that runs the dashboard:

```sh
hermes -p <profile> plugins install xploit9/cypher/hermes-plugin/cypher --enable
```

For an existing Cypher install from this repository, use `hermes -p <profile> plugins update cypher`. If `hermes-relay` is installed in the selected profile, back up its folder outside `plugins`, verify the copy, then disable it before enabling Cypher. The private repository requires existing GitHub CLI or Git credential-helper access; never paste or print a token. Ask before restarting an active dashboard or changing network settings. The phone needs an HTTPS dashboard URL; Tailscale Serve is the recommended route. Then install the latest `Cypher-*.apk` from the private repository's [Releases page](https://github.com/xploit9/cypher/releases), open the dashboard's **Cypher** tab, and scan its QR with the Cypher app. See the full [install-with-agent procedure](../../docs/install-with-agent.md).

Runtime settings go under native config.yaml `plugins.entries.cypher.settings`; the CLI above installs and enables the plugin. Settings are read from the native process launch home, consistently across bound tool/profile calls:

```yaml
plugins:
  entries:
    cypher:
      settings:
        owner_id: hermes
        owner_provider: basic
        profiles: [default]
        model: gemini-3.8-live
        voice: Aoede
        google_api_key_env: GOOGLE_API_KEY
        google_credentials_file: C:/Users/XPLoiT999/AppData/Local/hermes/secrets/cipher-gemini.env
        firebase_credentials: C:/Users/XPLoiT999/AppData/Local/hermes/secrets/hermes-fcm.json
        callback_tool_enabled: true
        scheduled_callbacks_enabled: true
        scheduled_callback_conversation_id: OWNER_STAMPED_NATIVE_STORED_ID
        release_root: PRIVATE_RELEASE_DIRECTORY
        android_certificate_sha256: EXPECTED_CERTIFICATE_SHA256
        aapt: ABSOLUTE_AAPT_PATH
        apksigner: ABSOLUTE_APKSIGNER_PATH
```

Paths above describe configuration shape only. No credentials are supplied by this plugin. `google_credentials_file` reads at most 16KiB of an existing private env file server-side. Without it, native `agent.secret_scope.get_secret` resolves the configured env name. Provider/key failures return unavailable, never another provider. Required Python packages are in requirements.txt; test dependencies in requirements-test.txt. Native Hermes must already supply its own dashboard/gateway packages on Python's import path.

The Cypher plugin does not accept or retain call audio: the legacy `/mobile/calls/{call_id}/audio` storage route rejects uploads, and finalized call records report `recording_status: "disabled"` and `audio_available: false`. Transcript text and call metadata are stored separately. Other limits include 64 events per batch, 20k timeline events per call, a 4h call clock, eight active calls per owner, 64 native job links per call, 32 device targets, 10k notification invitations, and 256 deferred callbacks.

Finalization stores transcript/event metadata and reports `transcript_available: true`; it creates no audio file or recording archive. The legacy audio endpoint rejects old and new clients. The live call transport remains direct to the provider.

## Wire additions and proof

All routes follow docs/native-migration/implementation-contract.md. GET bootstrap requires explicit allowed `?profile=` when multiple profiles exist; no global profile change. Call-specific paths infer the stored call profile and authorize it against the allowlist.

POST `/mobile/calls/{id}/jobs` additionally requires integer `user_row_id` from the actual native `prompt.submit` ACK. This is necessary because the RPC request id is not a native turn identity. Native history must contain that owned user row. One pending prompt per runtime is supported; duplicate request IDs and already-linked user rows cannot create new results/callbacks. Return `{job_id,request_id,stored_session_id,runtime_session_id,user_row_id,status:"working"}`. GET call returns `{call_id,status:"active"|"closed",recording_status,...,jobs:[{job_id,request_id,stored_session_id,runtime_session_id,user_row_id,status:"working"|"done"|"failed"|"needs_input"|"cancelled",result?,error?}]}`.

Completion requires a native replay `message.complete` persisted_turn receipt whose user_row_id matches, and a verified final native assistant row. Old completions never satisfy a newer job. Unknown/missing native evidence remains working; no result is invented. Native process restart/replay eviction can make completion evidence unavailable; automatic cold-restart completion inference is intentionally absent. Client native approval/reconnect remains the native RPC channel's responsibility. A server background watcher and bounded status/event polling reconcile jobs. Completion callbacks wait until all owned active calls end. FCM only wakes the phone; authenticated durable invitations remain authoritative. FCM failure retains pending state for retry; token/credential data never appears in target/event reads.

Notification target POST uses `{id|device_id, token|fcm_token, profile}`; GET returns sanitized targets; DELETE body `{id|device_id}`. Events GET `?profile=&after=<numeric cursor>` returns `{events,next_cursor}` with opaque event_id, native conversation_id, expiry and generic preview. Target credentials expire after 30 days unless refreshed, invitations 120 seconds after admission; these expirations never touch archives.

`cypher_call_user` is a namespaced native tool, with no xara_call alias or override. It takes no user-selected owner/profile/session. Bound session identity and native profile home authorize the invitation. An explicitly enabled native cron callback uses configured `scheduled_callback_conversation_id`, which must be an existing owner-stamped native chat; unowned cron/legacy history is never relabelled or granted ownership. Invitations defer while any call is active, then receive 120 seconds admission. The tool reports pending invitation, never that the phone answered. Parent must migrate old scheduled prompts/tool references from xara_call to cypher_call_user and provision the owner-bound callback conversation. Legacy plugin/services remain parent-controlled for rollback.

APK root contains immutable APK filenames, `latest.json` and `<sha256>.json` for every published immutable download. Manifest keys: artifact_filename, package_name=`dev.hermes.android`, version_code>20, version_name, certificate_sha256, sha256, size_bytes. Every read independently checks hash/size/package/version plus actual configured aapt badging and apksigner verification against the expected signer. Download verifies an immutable byte snapshot before response; older hash manifests remain downloadable after latest changes. No source APK or release manifest is provided here; parent publishes only after real validation.

## Fixture checks

Run `python -m pytest cypher-dashboard/tests -q` from the Cypher root. Tests import the real plugin module and use temporary HERMES_HOME/private fixture state. Authentication injection and native/Google/FCM substitutes are test-only. No live provider, network or owner history/recordings is accessed. Parent must still validate installed native plugin/auth, real owner-stamped RPC sessions/jobs, actual Google constrained-token connection/audio, FCM, APK signer/upgrade and physical phone/Bluetooth behavior.

Compatibility notification fields: target registration returns target.id/target_id/source_ref, and event list/detail provides owner_id/profile/event_id/kind/source_ref/conversation_ref/callback_id/state/expiry. Refs are stable HMAC opaque values backed by a private durable random key. GET `/mobile/notifications/events/{event_id}?profile=` is owner-scoped; FCM includes only opaque refs and generic preview plus expiry.

Target revocation also supports DELETE `/mobile/notifications/targets/{target_id}?profile=` for existing Android revoke flows.

## Native voice context and quick lookups

GET `/mobile/calls/{id}/context` returns the frozen Android shape: `{profile,owner_display_name?,system_instruction,context_summary,greeting,callback:{reason?,result?,job_id?},selected_session_id,capabilities:{web_search:bool,memory_recall:bool}}`. An additional context object carries bounded role/content history, personality, memory and optional language; capability_details explains unavailable providers. Native SOUL scanning and curated-memory sanitization are preserved. Read at most32 selected native rows,16k history characters,12k persona/memory characters,24KiB summary and6k verified callback result. Native secret redaction runs before context leaves the host. History, memory and callback results are explicitly reference data, never fresh authorization. No model loop/provider session is initialized for context.

POST `/mobile/calls/{id}/search` `{query}` returns `{summary,sources:[{title,url}],observed_at}`. It invokes the configured native Hermes web_search_tool in the call's native profile runtime/secret scope; no forced Gemini grounding. Query is capped1024 characters, native response256KiB, at most3 credential-free HTTP(S) sources, and6k summary characters. POST `/mobile/calls/{id}/memory` uses the same owner/profile guards and returns `{summary,sources:[{title,ref}],observed_at}`. It uses only an already-initialized matching native memory provider's bounded native prefetch path, or read-only curated-memory matching; no sync/write hooks or new memory-provider initialization. Missing native capability/evidence is unavailable, never a fabricated answer.

Lookup workers have a separate four-worker pool and live-slot cap so slow lookups cannot consume call HTTP/disk workers. Context deadline8seconds; search/recall12seconds; provider work that outlives a deadline retains its live slot until it actually ends. Each call permits60 lookups per kind. Closing/finalizing clears counter bookkeeping; call audio is not stored. Incoming answer embeds owned context when available, with context_error on unavailable context. Callback results bind exact invitation job_id; scheduled cypher_call_user accepts optional bounded240-character reason and never treats another same-chat job as its result.

Google token creation now sets `lock_additional_fields=[]`: tested SDK2.30.1 serializes fieldMask as `model,generationConfig.responseModalities,generationConfig.speechConfig`, so custom Android functions, input/output transcription and context setup remain unlocked. This is verified by a real SDK serialization fixture with intercepted transport and zero network calls; actual Google admission/audio remains a parent integration gate. Native Hermes' deployment environment must install requirements.txt (google-genai>=2.19,<3); a missing SDK yields truthful token-unavailable503. This leaf did not install packages into the live Hermes venv or launch/retry native services.

Release metadata includes artifact_path and min_contract_version=max_contract_version=2 for the current Android native contract. Authenticated notifications include preview.title/body; FCM preview remains generic and carries opaque references. No WebUI presence-check capability is advertised or simulated.

Gate invocation from the Cypher root: `node C:/Users/XPLoiT999/.codex/skills/unlazy/scripts/gate-check.mjs .unlazy/native-migration/gates/leaf-2.3.md --cwd . --approve` (or --reverify with the same default directory). Explicit ledger paths otherwise anchor CWD beside the ledger; --cwd . is required here.

C6 wire: GET `/mobile/sessions/{stored_id}/voice-history?profile=` requires current native owner/profile verification and returns `{session_id,profile,events:[{call_id,event_id:"<call>:<role>:<turn>",session_id,role,text,final,interrupted,at_ms,created_at}],truncated}`. Versions group by call/role/turn with stable IDs; actual final text replaces earlier partials, while unfinished dialogue remains final=false with an explicit marker. Latest200 grouped events and at most1MiB UTF-8 text return chronologically. created_at is the server reservation timestamp plus the call-relative at_ms offset. Authenticated response is no-store; unowned legacy sessions fail without relabelling. New-call context includes a bounded prior voice-dialogue excerpt marked historical reference data, never instructions. Nothing is inserted into native core history and no prompt is auto-run.

The mobile API does not retain call audio; audio-storage requests are rejected and finalization reports `audio_available: false`. Transcript, call, and job metadata remain separate from audio. Detailed disposition of all 15 OCR findings is in REVIEW-DISPOSITIONS.md.


## Native Tasks and category notification contracts

The candidate adds owned GET `/mobile/notifications/status` and POST `/mobile/notifications/test` (synthetic FCM event only, never a call/model/mail operation). Status reports sanitized targets and queued/sender-accepted counts; device_delivery_confirmed stays false. A configured sender file/SDK is capability, not proof of successful FCM delivery. Generic preview text is authenticated; private mailbox content is not placed in the FCM envelope.

Native on_session_end hooks emit one completion event per owned native session/turn when completed and neither failed nor interrupted. Cron sessions must already be owner-stamped to produce task result notifications; old unowned sessions are not relabeled. Calls and approvals retain their original authoritative flows.

GET `/mobile/tasks/capabilities` reports the actually resolved profile timezone and preset prerequisites. POST `/mobile/tasks/checkups` creates an ordinary native no_agent script task (never a second timer or model loop). It requires scheduled_callbacks_enabled and an existing owner-stamped scheduled_callback_conversation_id, plus resolved Asia/Dubai. Three or four distinct per-day random opportunities fall between10:00inclusive and22:00exclusive; only the exact scheduled minute is considered. Busy/pending-call opportunities are skipped permanently; missed opportunities do not catch up. The task can be paused/run/deleted with native task controls. Run now executes the gate, not an unconditional immediate ring.

POST `/mobile/tasks/mail-monitor` creates an ordinary native15minute scan during the Dubai day window only when a strict read-only source contract is available. Optional host settings `mailbox_evidence` must name the exact `server`, prefixed native `tool`, authorized `account`, and canonical `toolset` (`mcp-<server>`). Every tool on that server must have native discovery readOnlyHint exactly true; mixed mutation toolsets are rejected. The selected tool must return a JSON object `{account,message:{id,received_at,unread,action_required,urgent}}` with those facts backed by the source. This is deliberately not assumed from generic Gmail body text. The actual owner's compatible connector/schema has not been established. The monitor uses only its exact read-only MCP set, cypher-mailbox and the native no_mcp sentinel so unrelated MCP or generic call tools cannot be auto-included.

Legacy cypher_email_notice/xara_email_notice are source-evidence compatibility tools in cypher-mailbox. Model-supplied ids/account/urgency are insufficient: a successful exact native read-only tool observation in the same authorized source session/profile must precede the call, evidence must be at most5minutes old and message at most1day old, unread/urgent/action_required must be true, and account/timestamp must match. A durable per-owner/profile/account/message-id record prevents a second callback after restart.10:00–22:00Dubai quiet policy remains in force, with no night override. These paths never send/reply/mark-read/label mail. They do not automatically migrate existing tasks or certify live monitoring.

## Phone connection and client-owned realtime keys (2.1.0)

The visible Cypher dashboard tab includes a **Connect phone** QR and copyable `cypher://connect?url=<encoded-url>&name=<encoded-name>` link. Authenticated `GET /connect-info` supplies the configured public dashboard URL only when it uses HTTPS and is not loopback; the QR contains no sign-in token, cookie, API key or pairing secret. On the phone: install Cypher, tap Scan QR, then sign in through Hermes OAuth.

`POST /mobile/calls` and `POST /mobile/callbacks/{invitation_id}/answer` accept `voice_provider` plus `client_key: true` for phone-held Gemini, Grok or OpenAI Realtime keys. Provider keys are not accepted or stored by Hermes; existing requests without `client_key` keep the server-key admission path. `GET /mobile/notifications/events` keeps its existing `{events,next_cursor}` response and accepts optional `wait` from 0 to 25 seconds (default 0). These additions do not enable or retain call audio.
