# Beacon migration contract

## UI boundary
Preserve Beacon's existing tabs, drawer, profiles, Bot Mode and settings, including their routes and behavior. Apply Hermes Relay's settings section order in Beacon's own visual style and keep its navigation/back stack and search. The supplied Relay chat reference applies only to chat: messages, composer, background, tools, pending activity and real timestamps. Reuse proven implementation behavior with accurate attribution; do not transplant Relay screens.

## Owner decisions — 2026-10-18
- The Beacon plugin replaces the optional Hermes Relay plugin and pairing adapter.
- One native Hermes OAuth sign-in and one setup wizard serve chat, history, plugin pages, calls and Keep connected.
- Voice provider keys for Gemini Live, Grok and OpenAI Realtime are entered in Beacon and encrypted with Android Keystore AES-GCM. They are never sent to Hermes or logged. One tap in chat starts a realtime call.
- Keep connected uses an Android foreground service. Firebase push is optional; incoming calls do not depend on it.
- No audio retention is permitted for Beacon phone calls.
- Public GitHub `xploit9/beacon` is the only repository for this project; Gitea is reserved for Archon.
- The chat composer's Approvals menu offers Ask first (follow the profile's Manual or Auto policy) and YOLO in this chat. Enabling YOLO always asks for confirmation. A chat opened without resuming shows its own saved YOLO choice (the shield shows the chat's own setting), and a live read replaces it once the chat is resumed; a live status Hermes cannot report is shown as unknown, never as off. Manual and Auto are changed only in profile Settings; an existing profile Off mode is reported honestly there and in chat.
- Beacon's own call and task history on Hermes is pruned only by the user's "Keep call and task history" setting (Forever by default). Hermes conversations, messages, memories and files are never pruned by Beacon.
- Duplicate-prevention markers are kept only as long as they can prevent a duplicate. Bot Mode stops its room scan at 1,000 rooms and refuses to create a space rather than risk a duplicate.

## Reference-first implementation
Hermes Relay is the primary implementation reference for Hermes and all native workers. Inspect the corresponding real Relay components before introducing replacement code; reuse proven behavior and verified callback work, adapting only for Beacon compatibility, safety, architecture and product identity. Preserve the existing Beacon shell. The result must feel maintained as Beacon, not like a second application pasted inside it. Do not use gratuitous renaming, obfuscation or inaccurate authorship claims to conceal derivation. Retain required copyright, MIT/GPL licenses and truthful third-party attribution; preserve protocol identifiers. Each run handoff records the Relay sources inspected, reuse choices and concrete reasons for new code. Operational handoffs remain untracked.

## Clean plugin rename

This is a clean break for the Hermes plugin identity. Do not keep compatibility code that consults retired plugin IDs, module prefixes, route names, tool identifiers, commands, or release asset prefixes. The install guide directs agents to back up and remove the previous plugin and its managed cron jobs before installing Beacon.

## Stable boundaries
- Keep `dev.hermes.android` production application ID, storage formats, scoped cache/draft keys and signer. The debug preview uses `dev.hermes.android.debug` with the `-preview` version suffix and "Beacon Preview" launcher label; the stable channel uses `dev.hermes.android.stable`. These installs remain separate. Production replacement is held for physical-phone acceptance and original signer/data-preservation checks; see [preview](preview.md).
- The optional Beacon plugin projection is probed only after native sign-in is established, asynchronously and bounded, and is merged only into the native projection of the same client, owner and profile. Its failure never signs out or gates chat/history.
- UI continues to consume the app's existing ViewModel, `ChatState`, `Scope` and `org.json` projections. Workers do not change those shared types. Method additions needed by UI are reported to the integrator, not written concurrently.
- Native Hermes sign-in authenticates via OAuth and `/api/auth/me`, resolves the authorized profile with supported upstream routes, and stores an encrypted OAuth Bearer plus refresh record. Legacy cookie records remain readable. Refresh is silent on 401 and the original request is retried once. Hermes has no native bearer-revoke route: signing out clears the phone only, and the server refresh token expires on its own TTL. Standard auth/chat/history cannot call or depend on `/api/plugins/beacon/mobile/bootstrap`.
- Use HTTPS for HTTP routes and WSS for sockets. Preserve owner, profile and conversation authority, plus canonical session identity, typed interactive requests, terminal tool outcomes and reconnect replay. No availability-driven fallback into another session store.
- Voice provider credentials remain on the phone, outside Hermes requests, backups, logs and conversation state. Voice transport stays HTTPS/WSS; call teardown must not persist audio.
- Callback wire uses Hermes's `proactive` channel: `phone.call.invite/action/state/error`, `call_id`, trusted `device_id`, profile, original session_id, epoch-ms created/expires. Beacon's local incoming ringtone lasts at most 45 seconds, bounded by the invitation's expiry; the separate Relay callback wire window remains 20 seconds. Explicit Answer only; no mic on invite/decline/timeout. Reject stale/foreign/replayed terminal actions. Reuse existing foreground voice teardown; no audio persistence.
- Native cron is the only scheduling mechanism. Keep connected uses the foreground service; no Firebase prerequisite. WSS cannot wake a force-stopped Android app.

## Current product boundary
Standard sign-in, chat and conversation history use native Hermes. The Beacon plugin adds owner-bound device records and mobile features; it is checked only after sign-in, so a missing or failed plugin does not block native chat or history. A phone is linked by the Hermes sign-in itself (pasting the link and signing in); there is no separate pairing code or QR. Linked phones are managed in Settings > Connection and on the dashboard's Beacon tab.

## Verification
Tests must cover absent/error/older optional plugin, unauthorized account/profile, reconnect/session affinity, tool outcomes, stable scrolling, attachments, callback expiry/decline and teardown. The coordinator owns serialized Gradle/lint/device checks; workers do not run Gradle, ADB, installs, deployment or LLM reviews. Before a push, follow the project rules in `AGENTS.md`: current Ponytail review and audit, Gitleaks on the final tree and outgoing history, and a completed Strix scan of the exact clean export, with validated findings fixed. Build success alone is not acceptance. Production replacement remains held until physical-phone acceptance and original signer/data preservation are verified.
