# Cypher Preview (private)

The preview is a private test build of this migration branch. It is **not** a production release and does not replace the installed Cypher app.

## What the preview is
- It is the existing `debug` build type. Nothing else is added (no product flavors, no new dependencies).
- Application ID `dev.hermes.android.debug`, so it installs **next to** production `dev.hermes.android` and never upgrades it.
- Version name `0.4.15-preview`. The launcher label is "Cypher Preview" (set only in `app/src/debug/AndroidManifest.xml`); the release label stays "Cypher".
- It is signed with the local Android debug key, not the production signer. Android will never accept it as an update to production, and production cannot update it.
- It is a debuggable build. As in every debug build of this project, `src/debug/res/xml/fixture_network_security.xml` keeps cleartext disabled and also trusts the bundled test-fixture CA (`cypher_fixture_ca.pem`) through `debug-overrides`. Use the preview only on your own test devices.

## Build
From `cypher-android`, with JDK 21 and Android SDK 36:

```
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

The APK is written to `cypher-android/app/build/outputs/apk/debug/app-debug.apk`.

You do not need `owner.local.properties` or any signing material. Without that file:
- the server address starts blank, so the first screen asks for your Hermes HTTPS address;
- sign-in uses the Hermes OAuth setup wizard;
- Firebase is not configured, so background push shows "Notifications unavailable in this build".

Never commit `owner.local.properties`, `local.properties`, `.signing/` or a keystore.

## Install
```
adb install -r cypher-android/app/build/outputs/apk/debug/app-debug.apk
```

- **Do not uninstall production Cypher**, and do not install the preview over it. Each app keeps its own data. Uninstalling production deletes its encrypted sign-in, drafts and cache.
- **Sign in separately.** The preview cannot read production's saved sign-in or cache. Use its setup wizard and complete one native Hermes OAuth sign-in for this app.
- To remove only the preview: `adb uninstall dev.hermes.android.debug`.

## What works and what is incomplete
Standard sign-in, chat and history use unmodified native Hermes (`/api/auth/me`, `/api/ws`). The optional Cypher plugin is probed only after sign-in, in the background with a bounded timeout. If it exists, it adds voice and capability information. If it is missing or fails, chat and history keep working and you stay signed in.

History bounds (stock Hermes routes, no plugin needed):
- The conversation list shows the newest 200 conversations of the signed-in profile (archived ones separately). Search matches only titles and conversation IDs within those 200, not message text.
- Opening a conversation loads its newest 500 messages, including messages from before a context compression. Older messages are not loaded. Browsing never starts or resumes a Hermes agent.
- Stock read-only history does not report live status. Opening a conversation therefore cannot detect a reply running on another device; it polls for saved changes, which may not appear until Hermes writes messages.
- After an in-app send, a dropped stream replays from its last event cursor. Partial output and queued prompts stay visible while the outcome is unknown. The queue advances only after a terminal event or a native `idle` status with matching runtime and stored session identities; missing, malformed or transitional status keeps the turn uncertain and reconnecting.
- This is bounded native recovery behavior, not full live-backend or physical-device acceptance.

Session deletion and project reservation still require the optional Cypher plugin. Standard sign-in, chat and bounded history do not.

Not complete in this preview:
- **Phone callbacks.** The Cypher plugin callback flow on Hermes's `proactive` channel (invite, answer, decline, expiry) is not implemented end to end.
- **Live-device acceptance.** No physical-phone or live-backend acceptance has run. Notifications, background actions, the updater and native cron callbacks are unverified on a real device.
- Production replacement is on hold until physical-phone acceptance passes and the original signer and data preservation are verified.

The coordinator's acceptance checks are serialized `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug`, an independent review, an emulator smoke test and secret scans. Source edits alone do not make a preview ready. Do not treat a preview as accepted until those results are recorded.
