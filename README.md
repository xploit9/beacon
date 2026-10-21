# Cypher

**Your own Hermes, in your pocket.** Cypher is the Android app for a self-hosted
[Hermes Agent](https://github.com/NousResearch/hermes-agent): chat and history, real-time voice calls that use your whole
Hermes, and an assistant that watches your email and calls you when something important arrives.

<p align="center">
  <img src="https://github.com/user-attachments/assets/f0197a0c-e6bd-4aaf-b031-067054616c1d" width="31%" alt="Planning tomorrow from the calendar">
  <img src="https://github.com/user-attachments/assets/d60210ff-cf81-42a6-bfe2-cb4f5ac0e148" width="31%" alt="Important emails with a call rule">
  <img src="https://github.com/user-attachments/assets/61e342ed-4c84-4835-81f4-6d540dc816a0" width="31%" alt="Dark mode">
</p>

## What you get

- **One sign-in.** Scan the QR code on your Hermes dashboard (or type its address) and sign in once with Hermes. Chat,
  history, calls and alerts all use that one sign-in.
- **Chat that feels native.** Markdown, maths, tables and code; approvals with a shield you can read at a glance;
  photos, files, camera and paste; quote, share, read aloud and branch any reply. The steps behind an answer fold into
  one line, so the answer sits right under your question.
- **Real voice calls with your whole Hermes.** One tap to talk with Gemini Live, Grok or OpenAI Realtime. The call uses
  your Hermes tools, memory, files, skills and web search, says what it's checking while it works, and greets you by name.
  With Gemini Live it can also see your camera or your screen. Your provider key stays encrypted on the phone and is never
  sent to Hermes. No call audio is kept.
- **Important emails.** Cypher checks your email with whatever your Hermes already reaches — your own mail MCP server,
  Composio, or the `gws` / `himalaya` command line — and tells you, or calls you, when something matters. It learns from your
  **Important / Not important** taps and from what you tell it in chat. Read-only, quiet hours, at most three calls an hour.
- **Call history** in the Cypher chat, with every transcript.
- **Your whole Hermes.** Profiles (with their names), skills, memory, schedules, files, projects, Kanban, Bot Mode and server
  health — all from the phone. History across every profile, with threads one tap away.
- **Updates on their own.** Cypher keeps itself and its Hermes plugin up to date from this repository's releases, on by
  default (Settings › App updates has a switch for each). Every app update is verified before it installs; Android may ask
  for one tap.

## Install

**1. On the PC that runs Hermes**, give your agent this prompt:

> Install or update the Cypher plugin for my selected Hermes profile by following https://github.com/xploit9/cypher/blob/main/docs/install-with-agent.md. Ask before restarting an active dashboard or changing network settings. Never print or store passwords, tokens, or API keys, and never modify Hermes core.

The repository is private, so that PC needs GitHub access first (`gh auth login`). Hermes' own installer checks the plugin
before it installs. Then open the dashboard's **Cypher** tab once and tap **Use Cypher with this Hermes account**.

**2. On your phone**, download `Cypher-1.0.0.apk` from [Releases](https://github.com/xploit9/cypher/releases) and install
it. Scan the QR code on the dashboard's **Cypher** tab with Cypher.

The app is `dev.hermes.android.stable` ("Cypher"). It installs next to any older Cypher build without touching it.

## A first session

<p align="center">
  <img src="https://github.com/user-attachments/assets/5437cd56-ddc1-40a3-ae8c-0f69cbed3094" width="23%" alt="Connect to Hermes">
  <img src="https://github.com/user-attachments/assets/acb3e75a-695c-408e-a3f5-c3602b27e00e" width="23%" alt="Sign in with Hermes">
  <img src="https://github.com/user-attachments/assets/07bb2e0d-9838-4fb9-9c3b-9267441ba793" width="23%" alt="Voice key">
  <img src="https://github.com/user-attachments/assets/578e451c-5d7f-4699-96a4-900794f0e5ca" width="23%" alt="Connected">
</p>

1. **Connect.** Scan the QR code or type your dashboard address. A bare address like `my-pc.tailnet.ts.net:8443` is fine.
2. **Sign in once** on Hermes' own sign-in page. Cypher returns by itself; there is no second login for the plugin.
3. **Voice.** Pick Gemini Live, Grok or OpenAI Realtime, paste your key and tap Test. Or skip and add it later.
4. **Permissions.** Microphone, notifications and background use, one tap each, so calls reach you.
5. **Chat.** Ask anything — plan your day from your calendar, find the emails that need a reply, draft an answer, compare
   two options in a table.
6. **Important emails.** Settings › Assistant › Important emails: switch it on, choose what happens when one arrives (Notify
   or Call) and add a rule like *"call me if a client moves a meeting"*. When it rings, the assistant tells you why.
7. **Teach it.** Tap Important or Not important on an alert, or just tell it in chat. See and remove what it learned under
   *What Cypher learned*.

<p align="center">
  <img src="https://github.com/user-attachments/assets/4bccdb85-4ceb-4019-8746-372dda87db1e" width="23%" alt="Which emails need a reply">
  <img src="https://github.com/user-attachments/assets/799327f1-0cb7-451b-bf0b-fdfe1fcb605c" width="23%" alt="A drafted reply">
  <img src="https://github.com/user-attachments/assets/ea38e3b5-7585-46ef-b480-382e8644ce8d" width="23%" alt="A comparison table">
  <img src="https://github.com/user-attachments/assets/5e9dfda2-c320-4c0f-b79e-91a3da588a67" width="23%" alt="Approvals: Manual, Auto, Off, YOLO">
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/9cc15f65-a568-40b4-bc86-303f505574ae" width="31%" alt="An important email waiting in the Cypher chat">
  <img src="https://github.com/user-attachments/assets/d57b7861-8617-4988-a814-a2affc8bafab" width="31%" alt="What Cypher learned from an Important tap">
</p>

## Voice calls

**You call Cypher** — tomorrow's plan and the inbox, answered from Maya's own calendar and email:

https://github.com/user-attachments/assets/b2a6082b-cdbf-44cb-a4c1-6228603dc029

**Cypher calls you** — a client moved a meeting, the email rule says "call me", and the assistant explains why it's calling:

https://github.com/user-attachments/assets/e79e6055-e9e0-4669-98e2-086739969481

- **You call.** Tap the call button in the Cypher chat. The assistant answers in a second and works from your Hermes:
  calendar, inbox, memory, files, skills, web search and your other tools. Anything that changes something waits for your
  approval in the app.
- **It calls you.** When an important email arrives and you chose Call, your phone rings like a normal call. The assistant
  tells you why it called, and you can ask follow-ups right away.
- **Everything lands in the chat.** Both sides of the call are saved as text in the Cypher chat, under Call history.
  The audio is never stored.
- **Your keys stay on the phone.** Gemini, Grok or OpenAI keys are encrypted on the phone, per provider, so switching
  providers never loses a key.

<p align="center">
  <img src="https://github.com/user-attachments/assets/ae1189a5-c530-491d-8a22-5b5afcf3d12f" width="23%" alt="Call history">
  <img src="https://github.com/user-attachments/assets/24c5929b-c033-452d-90e1-ab59337e36c6" width="23%" alt="Settings">
  <img src="https://github.com/user-attachments/assets/1c421635-f135-4b73-b828-50c5c03f422b" width="23%" alt="Profiles">
  <img src="https://github.com/user-attachments/assets/7a00f960-2732-4536-b5f5-ed1f85b5e195" width="23%" alt="Files on your PC">
</p>

## Requirements

- A Hermes Agent dashboard reachable over HTTPS from the phone. Tailscale Serve works well.
- Android 10 or later.
- For calls: your own Gemini, xAI (Grok) or OpenAI key, entered in the app.
- The Cypher plugin on Hermes for calls, alerts, call history, Important emails and the plugin pages. Chat and history work
  without it.

## Privacy and safety

- Standard sign-in, chat and history use Hermes' own routes; the plugin adds phone features only.
- Voice keys stay on the phone. No call audio is stored.
- In calls, read-only tools run directly; anything that sends, changes or fetches a link asks you first, and your Hermes
  tool rules still apply.
- Important emails only reads. A built-in guard blocks tools that would send, change or schedule something during its runs,
  the terminal may run only read commands of a mail tool (`gws` or `himalaya`), email content is treated as data (never
  instructions), and an alert needs an email it actually read in that run.
- App updates install only when the package, version, SHA-256 and signing certificate all match.

## Building from source

See [docs/preview.md](docs/preview.md) for the Gradle builds (debug preview and stable) and how the stable build is signed.

## Licensing

Cypher keeps its GPLv3 license (`LICENSE`). Adapted Hermes Relay code keeps its MIT notice
(`licenses/Hermes-Relay-MIT.txt`). Android library notices are in `cypher-android/THIRD-PARTY-NOTICES.md`.
