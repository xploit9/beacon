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

**1. On the PC that runs Hermes**, give your coding agent (Codex, Claude Code, or Hermes itself) this prompt. It sets
everything up end to end and asks you before anything that needs your say:

> Set up Beacon for my Hermes end to end by following https://github.com/xploit9/beacon/blob/main/docs/install-with-agent.md (if you can't open it, read it with `gh api repos/xploit9/beacon/contents/docs/install-with-agent.md -H "Accept: application/vnd.github.raw"`). Do every step yourself:
> 1. Check that Hermes is installed and find the profile my dashboard uses; ask me if more than one could be it.
> 2. If a plugin named `cypher` is installed, back up its folder and plugin data to a location outside `plugins`, remove its managed cron jobs, then remove it.
> 3. Install the Beacon plugin with Hermes' own installer (`hermes -p <profile> plugins install xploit9/beacon/hermes-plugin/beacon --enable`), or update it with `hermes -p <profile> plugins update beacon` if it's already installed. Let Hermes' security scan run, and ask me before approving anything it asks about.
> 4. Restart only that dashboard (ask me first), then confirm it runs the new plugin and shows a **Beacon** tab.
> 5. Make sure my phone can reach the dashboard over HTTPS. If it can't yet, propose Tailscale Serve and ask before changing any network setting.
> 6. Run `hermes -p <profile> beacon pair` (or `/beacon-pair` in an owned chat) and show me the dashboard address and pairing code. Each phone gets its own code, and a code lasts 10 minutes, so run it again if it runs out.
> 7. Then tell me to install the app from https://github.com/xploit9/beacon/releases/latest and, in its setup, enter that address and code (or scan the QR on the dashboard's **Beacon** tab).
>
> Never print or store passwords, tokens or API keys, and never modify Hermes core.

The public repository needs no GitHub login; if Releases cannot be reached, check network access.

**2. On your phone**, download `Beacon-1.1.0.apk` from [Releases](https://github.com/xploit9/beacon/releases) and install
it. Open it. Scan the QR, choose a screenshot of it, or type the address and code, then follow the setup wizard.

The app is `dev.hermes.android.stable` ("Beacon"). It installs next to any older Beacon build without touching it.

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
