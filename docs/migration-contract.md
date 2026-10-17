# Migration contract — revision 1

## Stable boundaries
- Keep `dev.hermes.android` production application ID, storage formats, scoped cache/draft keys and signer. The coordinator adds a separate candidate variant; no production deployment.
- UI continues to consume existing `CypherViewModel`, `ChatState`, `Scope` and `org.json` projections. Workers do not change those shared types. Method additions needed by UI are reported to the integrator, not written concurrently.
- `CypherClient.bootstrap(): JSONObject` authenticates via native `/api/auth/me`, resolves authorized profile with supported upstream routes, persists scoped cookies, and returns a compatible local projection. Standard auth/chat/history cannot call or depend on `/api/plugins/cypher/mobile/bootstrap`. Optional-extension failure is distinct from authentication failure and never changes conversation transport.
- Native chat remains `/api/ws` with supported ticket/RPC/session protocol. Preserve canonical session identity, typed interactive requests, terminal tool outcomes and reconnect replay. No availability-driven fallback into another session store.
- Optional Relay is separately paired over HTTPS/WSS. Its token is never a dashboard cookie or API bearer; bind it to native origin/owner/profile plus its connection/device identity. Endpoint changes invalidate authority. Opt-in messages and voice calls are independent and off when unbound.
- Callback wire stays Relay `proactive` channel: `phone.call.invite/action/state/error`, `call_id`, trusted `device_id`, profile, original session_id, epoch-ms created/expires, max 20 seconds ringing. Explicit Answer only; no mic on invite/decline/timeout. Reject stale/foreign/replayed terminal actions. Reuse existing foreground voice teardown; no audio persistence.
- Native cron is the only scheduling mechanism. No second scheduler or Firebase prerequisite. WSS cannot wake a force-stopped Android app.

## First implementation milestone
Native saved sign-in + chat/history with absent/failing plugin; screenshot-based chat presentation preserving shell; fail-closed reusable callback protocol and connection-bound consent. Optional pairing adapters are introduced without declaring live integration complete.

## Verification
Tests must cover absent/error/older optional plugin, unauthorized account/profile, reconnect/session affinity, tool outcomes, stable scrolling, attachments, callback expiry/decline and teardown. The coordinator owns serialized Gradle/lint/device checks, independent OCR and final secret scan. Build success alone is not acceptance. Later milestones must exercise real backend/UI, web/email timing, native cron callbacks, notification/background actions, updater and physical phone before production.
