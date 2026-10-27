# Beacon preview and stable builds

The preview is Beacon's debug build for testing. It installs separately from the stable channel and production app; it does not replace either one.

## Stable channel
- The `stable` build type has application ID `dev.hermes.android.stable` and launcher label "Beacon", so it installs beside production and the preview.
- The current source builds stable version code `119`, version `1.1.9`. It inherits release minification and is not debuggable; this identifies the source configuration, not a newly accepted or published binary.
- A stable APK is unsigned unless `beaconStableSigning` (or `BEACON_STABLE_SIGNING`) points to an external properties file with the four Android signing fields. Never store that file or its keystore in this repository. The stable signer is separate from production.
- Release assets are named `Beacon-<version>.apk` and `Beacon-<version>.json`; Settings **App updates** asks the public GitHub release itself and verifies the APK before installation.

Stable builds check the latest GitHub release themselves and notify you when a new version is out; tapping **Update** downloads it from GitHub, verifies package, version, checksum and signer, and installs it (Android asks you to confirm). The same screen updates the Hermes plugin first through Hermes' own plugin updater; neither updates on its own. The debug preview remains manually installed.
- The app asks GitHub, not the plugin. The plugin still stores stable releases separately from production and can pin them with `android_stable_certificate_sha256`, but that pin applies only to older 1.1.x phones, which update from Hermes. The app always requires the downloaded APK to have the same signer as the installed stable app.
- Installing stable does not replace production. Production replacement remains held until physical-phone acceptance and original signer/data-preservation checks pass.

## What the preview is
- It is the existing `debug` build type. Nothing else is added (no product flavors, no new dependencies).
- Application ID `dev.hermes.android.debug`, so it installs **next to** production `dev.hermes.android` and never upgrades it.
- The current source version name is `1.1.9-preview`. The launcher label is "Beacon Preview" (set only in `app/src/debug/AndroidManifest.xml`); the release label stays "Beacon".
- It is signed with the local Android debug key, not the production signer. Android will never accept it as an update to production, and production cannot update it.
- It is a debuggable build. `src/debug/res/xml/fixture_network_security.xml` allows cleartext only to `10.0.2.2` for the local crash reporter, and trusts the bundled test-fixture CA (`beacon_fixture_ca.pem`) through `debug-overrides`. Use the preview only on your own test devices.

## Build
From `beacon-android`, with JDK 21 and Android SDK 36:

```
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

The APK is written to `beacon-android/app/build/outputs/apk/debug/app-debug.apk`.

You do not need `owner.local.properties` or any signing material. Without that file:
- the server address starts blank, so the first screen asks for your Hermes HTTPS address;
- sign-in uses the Hermes OAuth setup wizard;
- Firebase is not configured, so the build has no Firebase push. Incoming Hermes calls reach the phone through **Keep connected** instead (see below).

Never commit `owner.local.properties`, `local.properties`, `.signing/` or a keystore.

## Install
```
adb install -r beacon-android/app/build/outputs/apk/debug/app-debug.apk
```

- **Do not uninstall production Beacon**, and do not install the preview over it. Each app keeps its own data. Uninstalling production deletes its encrypted sign-in, drafts and cache.
- **Sign in separately.** The preview cannot read production's saved sign-in or cache. Use its setup wizard and complete one native Hermes OAuth sign-in for this app.
- To remove only the preview: `adb uninstall dev.hermes.android.debug`.

## What works and what is incomplete
Standard sign-in, chat and history use unmodified native Hermes (`/api/auth/me`, `/api/ws`). The optional Beacon plugin is probed only after sign-in, in the background with a bounded timeout. If it exists, it adds voice and capability information. If it is missing or fails, chat and history keep working and you stay signed in.

History bounds (stock Hermes routes, no plugin needed):
- The conversation list shows the newest 200 conversations of the signed-in profile (archived ones separately). Scheduled-task (cron) runs and conversations without messages are left out, as in Hermes's desktop app, so a frequent schedule cannot push your chats out of the list. Search matches only titles and conversation IDs within those 200, not message text.
- Opening a conversation loads its newest 500 messages, including messages from before a context compression. Older messages are not loaded. Browsing never starts or resumes a Hermes agent.
- Stock read-only history does not report live status. Opening a conversation therefore cannot detect a reply running on another device; it polls for saved changes, which may not appear until Hermes writes messages.
- After an in-app send, a dropped stream replays from its last event cursor. Partial output and queued prompts stay visible while the outcome is unknown. The queue advances only after a terminal event or a native `idle` status with matching runtime and stored session identities; missing, malformed or transitional status keeps the turn uncertain and reconnecting.
- This is bounded native recovery behavior, not full live-backend or physical-device acceptance.

Deleting a conversation uses native Hermes. Project reservation still requires the optional Beacon plugin. Standard sign-in, chat and bounded history use native Hermes.

Threads and filters (drawer, under Search; native Hermes, no plugin needed):
- **Threads** are named conversations you come back to, as in Hermes Relay. "New thread" asks for a name; the thread is an ordinary Hermes chat created with its own source (`android_thread`) and your title, which Hermes keeps instead of its automatic title. A repeated name gets a number, because Hermes does not allow two conversations with the same title. The Threads lane also lists your Hermes Relay threads.
- **Filters**: All, Pinned and Archived, plus a Filter menu to sort by recent activity, newest first or A–Z and to show or hide conversations by where they came from (Web, Terminal, Telegram and so on). Hermes applies the source filters, so the 200-conversation window holds only what you choose to see. Choices are remembered on the phone.

Display: Beacon follows the phone for text size, language, text direction and animations (Android's font size, app language and "Remove animations" settings). Settings → Appearance keeps only Theme and Color style.

Approvals (composer shield, native Hermes): **Profile default** follows the profile's Manual (Ask first) or Auto policy; **YOLO in this chat** skips prompts only in that conversation. Enabling YOLO always asks for confirmation, and Hermes saves that choice until you turn it off. **Change for all chats** opens profile Settings, where Manual and Auto are changed. An existing Off policy is displayed honestly in Settings and chat, with chat choices disabled; unavailable approval status is shown as unknown with a retry action.

Conversation actions: long-press any conversation in History (or use the chat's ⋮ menu) to pin or unpin, rename, add it to a project or remove it from its project, archive or delete it. Removing a chat from a project moves it to the profile's own folder; its messages stay.

Assistant (Settings › Assistant): set the assistant's name, profile and model. Its chat always opens in that profile on that model; your other chats keep the profile you picked, and leaving its tab returns you to the chat you had open. The name shows in the app and its calls; the app itself stays Beacon. Turn on Proactive for briefings, follow-ups and alerts, then connect the services it needs. Task result notifications open their conversation with the full, selectable result in a dismissible card.

Settings are grouped: Assistant (name, profile and model; voice and calls; notifications), Hermes (chat, profiles and models, skills, memory, tools, server) and App (appearance, updates, about).

Chat extras from Relay: quote a message into your reply, attach from the camera or paste an image, open the command list from the attach menu, start a new chat from a suggested prompt, share the whole conversation or copy its ID from the ⋮ menu. Messages are grouped under Today, Yesterday or their date, and in-chat search shows how many messages match.

With the Beacon plugin installed:
- **Phones.** Paste the Hermes link into Beacon and sign in with Hermes; that links the phone. Linking needs the Hermes dashboard sign-in. Linked phones are listed in Settings > Connection and on the dashboard's **Beacon** tab, where you can remove them.
- **Proactive.** Add plain-language email rules and choose notifications or calls. Checks use the tools already connected to the selected Hermes profile; the app reports results by notification or call.
- **Voice & calls.** One-tap realtime calls with Gemini Live, Grok or OpenAI Realtime. The provider key is entered in Beacon, stored encrypted on the phone and sent only to that provider, never to Hermes. Call audio is never stored.
- **Call history.** Open it from the Beacon chat menu to review call entries and jump to saved transcripts.
- **Keep connected** (Settings → Notifications & Keep connected). A low-importance foreground service keeps a connection to Hermes for each signed-in profile, so Hermes can ring the phone while Beacon is closed (about 20 seconds; builds with push ring for 45). The page shows the connection state and, when it fails, the reason (for example "Hermes refused profile coder (403)").
- **Keep call and task history** (same page): Forever (default), 1 year, 90 days or 30 days. This applies only to Beacon's own call transcripts and task history on Hermes. Shortening it asks for confirmation, then deletes older closed calls and finished tasks. Hermes conversations, messages, memories and files are never touched.

Verification recorded on an Android 16 emulator against a live Hermes (19 October 2026):
- One sign-in, chat, history, Bot Mode and settings; new phone chats are saved under the signed-in owner.
- Incoming calls: with Keep connected on and Beacon closed, Hermes's `beacon_call_user` rings the phone for about 20 seconds as a pop-up call card (full screen when the screen is off), bounded by the invitation's expiry, and the caller opens by saying why it is calling; Answer starts the call in that conversation, Decline settles it on Hermes, and an unanswered ring leaves a missed-call notification with Call back.
- One-tap calls with Gemini Live and Grok, with the key kept on the phone. The call transcript appears in the conversation afterwards.

Remaining acceptance recorded for this preview:
- **Physical phone.** Physical-phone acceptance is still required for lock-screen ringing, battery behaviour, real microphone input and voice-dispatched work.
- **OpenAI Realtime.** It was not tested in the 19 October run because no key was supplied; this note records that run, not a later test.
- Production replacement is on hold until physical-phone acceptance passes and the original signer and data preservation are verified.

The coordinator serializes `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug`, then records the device acceptance. Before any push, follow the current review, audit, Gitleaks and Strix gates in `AGENTS.md`. Source edits alone do not make a preview ready. Do not treat the preview as accepted until those results are recorded.
