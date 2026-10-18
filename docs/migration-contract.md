# Migration contract — revision 4

## UI boundary
Preserve Cypher's existing tabs, left menu buttons and top-right action, including their routes and behavior. Apply Hermes Relay's settings section order in Cypher's own visual style and keep its navigation/back stack and search. The owner-supplied Relay video/image applies only to chat: messages, composer, background, tools, pending activity and real timestamps. Reuse proven implementation behavior with accurate attribution; do not transplant Relay screens. Claude works task-locally on Opus 5.5 at extra-high (`xhigh`) effort, not max; set this per task and do not change global defaults.

## Owner decisions — 2026-10-18
- The Cypher plugin replaces the optional Hermes Relay plugin and pairing adapter.
- One native Hermes OAuth sign-in and one setup wizard serve chat, history, plugin pages, calls and Keep connected.
- Voice provider keys for Gemini Live, Grok and OpenAI Realtime are entered in Cypher and encrypted with Android Keystore AES-GCM. They are never sent to Hermes or logged. One tap in chat starts a realtime call.
- Keep connected uses an Android foreground service. The preview build does not include Firebase configuration.
- No audio retention is permitted for Cypher phone calls.
- Private GitHub `xploit9/cypher` is the only repository for this project; Gitea is reserved for Archon.
- Per-chat YOLO lives in the chat composer's Approvals menu. Enabling always asks for confirmation, and a status Hermes cannot report is shown as unknown, never as off.
- Cypher's own call and task history on Hermes is pruned only by the user's "Keep call and task history" setting (Forever by default). Hermes conversations, messages, memories and files are never pruned by Cypher.
- Duplicate-prevention markers are kept only as long as they can prevent a duplicate. Bot Mode stops its room scan at 1,000 rooms and refuses to create a space rather than risk a duplicate.

## Reference-first implementation
Hermes Relay is the primary implementation reference for Hermes and all native workers. Inspect the corresponding real Relay components before introducing replacement code; reuse proven behavior and verified callback work, adapting only for Cypher compatibility, safety, architecture and product identity. Preserve the existing Cypher shell. The result must feel maintained as Cypher, not like a second application pasted inside it. Do not use gratuitous renaming, obfuscation or inaccurate authorship claims to conceal derivation. Retain required copyright, MIT/GPL licenses and truthful third-party attribution; preserve protocol identifiers. Each run handoff records the Relay sources inspected, reuse choices and concrete reasons for new code. Operational handoffs remain untracked.

## Stable boundaries
- Keep `dev.hermes.android` production application ID, storage formats, scoped cache/draft keys and signer. The private preview is the existing debug build type (`dev.hermes.android.debug`, version suffix `-preview`, launcher label "Cypher Preview"; see [preview](preview.md)); no production deployment.
- The optional Cypher plugin projection is probed only after native sign-in is established, asynchronously and bounded, and is merged only into the native projection of the same client, owner and profile. Its failure never signs out or gates chat/history.
- UI continues to consume existing `CypherViewModel`, `ChatState`, `Scope` and `org.json` projections. Workers do not change those shared types. Method additions needed by UI are reported to the integrator, not written concurrently.
- `CypherClient.bootstrap(): JSONObject` authenticates via native OAuth and `/api/auth/me`, resolves the authorized profile with supported upstream routes, and stores an encrypted OAuth Bearer plus refresh record. Legacy cookie records remain readable. Refresh is silent on 401 and the original request is retried once. Hermes has no native bearer-revoke route: signing out clears the phone only, and the server refresh token expires on its own TTL. Standard auth/chat/history cannot call or depend on `/api/plugins/cypher/mobile/bootstrap`.
- Use HTTPS for HTTP routes and WSS for sockets. Preserve owner, profile and conversation authority, plus canonical session identity, typed interactive requests, terminal tool outcomes and reconnect replay. No availability-driven fallback into another session store.
- Voice provider credentials remain on the phone, outside Hermes requests, backups, logs and conversation state. Voice transport stays HTTPS/WSS; call teardown must not persist audio.
- Callback wire uses Hermes's `proactive` channel: `phone.call.invite/action/state/error`, `call_id`, trusted `device_id`, profile, original session_id, epoch-ms created/expires, max 20 seconds ringing. Explicit Answer only; no mic on invite/decline/timeout. Reject stale/foreign/replayed terminal actions. Reuse existing foreground voice teardown; no audio persistence.
- Native cron is the only scheduling mechanism. Keep connected uses the foreground service; no Firebase prerequisite. WSS cannot wake a force-stopped Android app.

## First implementation milestone
Single OAuth sign-in + native chat/history with an absent or failing Cypher plugin; a setup wizard; screenshot-based chat presentation preserving the Cypher shell; and a fail-closed reusable callback protocol with connection-bound consent. The Relay pairing adapter is not part of Cypher.

## Verification
Tests must cover absent/error/older optional plugin, unauthorized account/profile, reconnect/session affinity, tool outcomes, stable scrolling, attachments, callback expiry/decline and teardown. The coordinator owns serialized Gradle/lint/device checks, independent OCR and final secret scan. Build success alone is not acceptance. Later milestones must exercise real backend/UI, web/email timing, native cron callbacks, notification/background actions, updater and physical phone before production.
