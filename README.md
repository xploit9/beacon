# Beacon

**Your own Hermes, in your pocket.** Beacon is the Android companion for a self-hosted
[Hermes Agent](https://github.com/NousResearch/hermes-agent). The assistant is named Beacon by default;
you can rename it in Settings › Assistant.

## What Beacon does

- **Chat, history, threads and files.** Chat with your Hermes profile, return to saved
  conversations, create named threads, and attach or open files.
- **Voice calls.** Talk with Gemini Live, Grok or OpenAI Realtime. Beacon uses the tools and
  memory available to your Hermes profile. Review any action that needs approval in the app.
  Call transcripts are saved as text; audio is not retained.
- **Important-email alerts.** Beacon checks email with tools connected to your Hermes profile.
  Write rules for a notification or call, and teach it with Important / Not important feedback.
- **Approvals.** Beacon follows the approval settings in your Hermes profile and lets you review
  and answer requests in the app.
- **Updates.** Manage automatic app and plugin updates in Settings › App updates. Beacon
  verifies app updates before installation; Android may ask you to confirm.

## Install

**1. On the PC that runs Hermes**, give your coding agent (Codex, Claude Code, or Hermes itself) this prompt:

> Set up Beacon on my Hermes. First follow the predecessor-removal prerequisite in https://github.com/xploit9/beacon/blob/main/docs/install-with-agent.md: verify an external backup, then use supported native operations to remove only positively identified predecessor-managed cron jobs and the predecessor plugin. Stop and ask me if ownership is uncertain. Then download https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py and run it with Python. Whenever it stops to ask something, it names a flag — ask me, then rerun it with that flag. Show me the pairing block it prints at the end. Never print or store passwords, tokens or API keys, and never modify Hermes core.

Before running it yourself, complete the [predecessor-removal prerequisite](docs/install-with-agent.md#before-installing-beacon). Then run `python beacon_install.py`: it selects your Hermes home/profile, installs or updates Beacon, restarts the dashboard if you say yes, and prints your pairing code. The installer does not back up or remove a predecessor plugin or its jobs. Manual steps: [docs/install-with-agent.md](docs/install-with-agent.md).

**2. On your phone**, download `Beacon-1.1.2.apk` from [Releases](https://github.com/xploit9/beacon/releases/latest) and install
it. Open it, enter the address and code (or scan the QR on the dashboard's **Beacon** tab, or choose a screenshot of it), sign in with Hermes, and follow the setup wizard.

## Pair or manage phones

Your AI installer can run `hermes -p <profile> beacon pair` or `/beacon-pair` in an owned chat
to print a single-use pairing code. You can also open the Hermes dashboard's **Beacon** tab and
select **Pair a phone**. In Beacon, scan the QR, choose a screenshot of it, or enter the HTTPS
dashboard address and code. Manage linked phones in Settings › Devices.

## Requirements

- Android 10 or later.
- A Hermes Agent dashboard reachable from the phone over HTTPS.
- The Beacon plugin enabled for phone pairing and Beacon features. Native chat and conversation history work without it.
- A Gemini, xAI (Grok) or OpenAI key if you want voice calls.

## Privacy and security

- Beacon requires an HTTPS dashboard and uses secure WebSockets for live sessions.
- The plugin checks your signed-in Hermes owner and profile before acting.
- Voice provider keys are encrypted on the phone and sent only to the selected provider.
- Call audio is not retained. Call history is saved as text.
- Beacon's call and task retention setting does not remove Hermes conversations, memories or files.

## Building from source

See [docs/preview.md](docs/preview.md) for the debug preview, stable build and signing information.

## Licensing

Beacon keeps its GPLv3 license (`LICENSE`). Adapted Hermes Relay code keeps its MIT notice
(`licenses/Hermes-Relay-MIT.txt`). Android library notices are in `beacon-android/THIRD-PARTY-NOTICES.md`.
