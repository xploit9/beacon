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
- **Updates.** Beacon tells you when a new release is on GitHub. Tap Update to install the
  app and, when needed, the Hermes plugin; both are verified first and Android may ask you to confirm.

## Install

**1. On the PC that runs Hermes**, give your coding agent (Codex, Claude Code, or Hermes itself) this prompt:

> Set up Beacon on my Hermes. First follow the predecessor-removal prerequisite in https://github.com/xploit9/beacon/blob/main/docs/install-with-agent.md: verify an external backup, then use supported native operations to remove only positively identified predecessor-managed cron jobs and the predecessor plugin. Stop and ask me if ownership is uncertain. Then download https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py and run it with Python. Whenever it stops to ask something, it names a flag — ask me, then rerun it with that flag. Show me the link it prints at the end. Never print or store passwords, tokens or API keys, and never modify Hermes core.

Before running it yourself, complete the [predecessor-removal prerequisite](docs/install-with-agent.md#before-installing-beacon). Then run `python beacon_install.py`: it selects your Hermes home/profile, installs or updates Beacon, restarts the dashboard if you say yes, and prints the link to paste into Beacon. The installer does not back up or remove a predecessor plugin or its jobs. Manual steps: [docs/install-with-agent.md](docs/install-with-agent.md).

**2. On your phone**, download `Beacon-1.1.3.apk` from [Releases](https://github.com/xploit9/beacon/releases/latest) and install
it. Open it, paste your Hermes link (also shown on the dashboard's **Beacon** tab), sign in with Hermes, and follow the setup wizard.

## Connect or manage phones

Paste your Hermes link into Beacon and sign in with Hermes; that links the phone. There is no
separate code or QR step. See and remove linked phones in Settings › Connection, or on the
dashboard's **Beacon** tab.

## Requirements

- Android 10 or later.
- A Hermes Agent dashboard reachable from the phone over HTTPS.
- The Beacon plugin enabled for the device list and Beacon features. Native chat and conversation history work without it.
- A Gemini, xAI (Grok) or OpenAI key if you want voice calls.

## Privacy and security

- Beacon requires an HTTPS dashboard and uses secure WebSockets for live sessions.
- The plugin checks your signed-in Hermes owner and profile before acting.
- Voice provider keys are encrypted on the phone and sent only to the selected provider.
- During a call, Beacon sends your assistant's instructions, memory notes, recent chat, tool results and any camera or screen you share to the voice provider you choose.
- Call audio is not retained. Call history is saved as text.
- Beacon's call and task retention setting does not remove Hermes conversations, memories or files.
- Beacon checks the public GitHub release for updates on its own, without signing in, so GitHub sees the phone's address.

## Building from source

See [docs/preview.md](docs/preview.md) for the debug preview, stable build and signing information.

## Licensing

Beacon keeps its GPLv3 license (`LICENSE`). Adapted Hermes Relay code keeps its MIT notice
(`licenses/Hermes-Relay-MIT.txt`). Android library notices are in `beacon-android/THIRD-PARTY-NOTICES.md`.
