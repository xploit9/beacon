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
- Firebase is not configured, so the build has no Firebase push. Incoming Hermes calls reach the phone through **Keep connected** instead (see below).

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
- The conversation list shows the newest 200 conversations of the signed-in profile (archived ones separately). Scheduled-task (cron) runs and conversations without messages are left out, as in Hermes's desktop app, so a frequent schedule cannot push your chats out of the list. Search matches only titles and conversation IDs within those 200, not message text.
- Opening a conversation loads its newest 500 messages, including messages from before a context compression. Older messages are not loaded. Browsing never starts or resumes a Hermes agent.
- Stock read-only history does not report live status. Opening a conversation therefore cannot detect a reply running on another device; it polls for saved changes, which may not appear until Hermes writes messages.
- After an in-app send, a dropped stream replays from its last event cursor. Partial output and queued prompts stay visible while the outcome is unknown. The queue advances only after a terminal event or a native `idle` status with matching runtime and stored session identities; missing, malformed or transitional status keeps the turn uncertain and reconnecting.
- This is bounded native recovery behavior, not full live-backend or physical-device acceptance.

Session deletion and project reservation still require the optional Cypher plugin. Standard sign-in, chat and bounded history do not.

Threads and filters (drawer, under Search; native Hermes, no plugin needed):
- **Threads** are named conversations you come back to, as in Hermes Relay. "New thread" asks for a name; the thread is an ordinary Hermes chat created with its own source (`android_thread`) and your title, which Hermes keeps instead of its automatic title. A repeated name gets a number, because Hermes does not allow two conversations with the same title. The Threads lane also lists your Hermes Relay threads.
- **Filters**: All, Pinned and Archived, plus a Filter menu to sort by recent activity, newest first or A–Z and to show or hide conversations by where they came from (Web, Terminal, Telegram and so on). Hermes applies the source filters, so the 200-conversation window holds only what you choose to see. Choices are remembered on the phone.

Display: Cypher follows the phone for text size, language, text direction and animations (Android's font size, app language and "Remove animations" settings). Settings → Appearance keeps only Theme and Color style.

Approvals (composer shield, native Hermes): Manual, Smart and Off set the profile's approval mode. **YOLO for this chat** is a switch: turning it on asks for confirmation, and it stays on for that chat until you turn it off (Hermes saves it with the conversation). While the profile's approvals are Off, the switch shows on and is locked.

Conversation actions: long-press any conversation in History (or use the chat's ⋮ menu) to pin or unpin, rename, add it to a project or remove it from its project, archive or delete it. Removing a chat from a project moves it to the profile's own folder; its messages stay.

Assistant (Settings › Assistant): set the assistant's name, profile and model. Its chat always opens in that profile on that model; your other chats keep the profile you picked, and leaving its tab returns you to the chat you had open. The name shows in the app and its calls; the app itself stays Cypher.

Settings are grouped: Assistant (name, profile and model; voice and calls; notifications), Hermes (chat, profiles and models, skills, memory, tools, server) and App (appearance, updates, about).

Chat extras from Relay: quote a message into your reply, attach from the camera or paste an image, open the command list from the attach menu, start a new chat from a suggested prompt, share the whole conversation or copy its ID from the ⋮ menu. Messages are grouped under Today, Yesterday or their date, and in-chat search shows how many messages match.

With the Cypher plugin installed:
- **Voice & calls.** One-tap realtime calls with Gemini Live, Grok or OpenAI Realtime. The provider key is entered in Cypher, stored encrypted on the phone and sent only to that provider, never to Hermes. Call audio is never stored.
- **Keep connected** (Settings → Notifications & Keep connected). A low-importance foreground service keeps a connection to Hermes for each signed-in profile, so Hermes can ring the phone while Cypher is closed. The page shows the connection state and, when it fails, the reason (for example "Hermes refused profile coder (403)").
- **Keep call and task history** (same page): Forever (default), 1 year, 90 days or 30 days. This applies only to Cypher's own call transcripts and task history on Hermes. Shortening it asks for confirmation, then deletes older closed calls and finished tasks. Hermes conversations, messages, memories and files are never touched.

Verified on an Android 16 emulator against a live Hermes (19 October 2026):
- One sign-in, chat, history, Bot Mode and settings; new phone chats are saved under the signed-in owner.
- Incoming calls: with Keep connected on and Cypher closed, Hermes's `cypher_call_user` rings the phone for 20 seconds as a pop-up call card (full screen when the screen is off), and the caller opens by saying why it is calling; Answer starts the call in that conversation, Decline settles it on Hermes, and an unanswered ring leaves a missed-call notification with Call back.
- One-tap calls with Gemini Live and Grok, with the key kept on the phone. The call transcript appears in the conversation afterwards.

Not complete in this preview:
- **Physical phone.** Nothing has run on a real phone yet: lock-screen ringing, battery behaviour, real microphone input and voice-dispatched work (the emulator has no audio input).
- **OpenAI Realtime** calls are implemented but were not tested (no key was supplied).
- Production replacement is on hold until physical-phone acceptance passes and the original signer and data preservation are verified.

The coordinator's acceptance checks are serialized `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug`, an independent review, an emulator smoke test and secret scans. Source edits alone do not make a preview ready. Do not treat a preview as accepted until those results are recorded.
