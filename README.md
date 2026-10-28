# Beacon

**Your own Hermes, in your pocket.** Beacon is the Android app for a self-hosted
[Hermes Agent](https://github.com/NousResearch/hermes-agent): one always-on assistant chat, topic threads that take
scheduled posts, voice calls that use your Hermes tools and memory, and a heads-up when something needs you.
The assistant is named Beacon by default; rename it in Settings › Assistant.

## Install

**1. On the PC that runs Hermes**, give your coding agent (Codex, Claude Code, or Hermes itself) this prompt:

> Install Beacon on my Hermes: download https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py and run it with Python. Whenever it stops to ask something, it names a flag — ask me, then rerun it with that flag. Show me the link it prints at the end. Never print or store passwords, tokens or API keys, and never modify Hermes core.

Or download [`beacon_install.py`](https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py) and run `python beacon_install.py` yourself. It picks your Hermes home and profile, installs Beacon, restarts the dashboard if you say yes, and prints the link for your phone. The link needs an HTTPS address for your dashboard; if you have none, the installer can set one up with Tailscale. Manual steps: [docs/install-with-agent.md](docs/install-with-agent.md).

**2. On your phone**, download the latest `Beacon-*.apk` from [Releases](https://github.com/xploit9/beacon/releases/latest) and install
it. Open it, paste your Hermes link (also shown on the dashboard's **Beacon** tab), sign in with Hermes, and follow the setup wizard.

<p align="center">
  <img src="https://github.com/user-attachments/assets/05bc3b64-e9d7-48b2-94e7-0661e322d950" width="31%" alt="Beacon chat">
  <img src="https://github.com/user-attachments/assets/20e84c96-cba7-481a-b593-edc0aa3d7b06" width="31%" alt="A scheduled post in a thread">
  <img src="https://github.com/user-attachments/assets/e9954d6f-7348-4751-a7e2-bf845ae2b757" width="31%" alt="A voice call">
</p>

## See it work

**You call Beacon.** It checks the calendar and saves a note to your Hermes memory, all by voice:

https://github.com/user-attachments/assets/14f70977-0855-4e36-a583-0262a7718bf5

**Beacon calls you.** Ask it to ring you about an email. It calls, says why, and drafts the reply:

https://github.com/user-attachments/assets/25658228-96aa-4c44-ba31-dae53892b5ab

The voice is Grok; the caller's side is synthetic speech. Screens and videos come from a demo Hermes with sample data.

## What Beacon does

- **A chat that feels like a messenger.** Grouped bubbles, date chips, long-press to copy, quote, share or read aloud,
  and `/` for Hermes commands. Longer runs of steps fold into one line.
- **Threads as channels.** Give a thread a name and an emoji, point a scheduled task at it, and each result arrives
  there as a post. Reply and the answer builds on it ([how threads work](docs/threads.md)).
- **Projects.** Add a chat to a project from its menu. It works in the project's folder and shows the project at the top.
- **Tools at hand.** Tools, under More in a chat's menu, opens your tools and MCP servers, the same controls as Settings.
- **Approvals per chat.** Each chat follows your profile's approval mode, or skips approvals for that chat only.
- **Voice calls.** Talk with Gemini Live, Grok or OpenAI Realtime. Calls use the tools and memory of your Hermes profile,
  and can save new memories like a chat ([how calls use tools and memory](docs/voice-calls.md)). A ball of drifting
  dust shows the call: blue while Beacon listens, amber while it thinks, green while it speaks, and it swirls and
  scatters while someone talks. Call text is saved; audio is not kept.
- **What needs you.** Beacon watches what your Hermes can reach and tells you, or calls you, when something matters.
  Write rules like "Call me if a client moves a meeting" and teach it with Important / Not important.
- **All of Hermes.** Profiles, skills, memory, schedules, files, Kanban and Bot Mode, with screens that open ready.
- **Updates from GitHub.** Beacon tells you when a release is out; tap Update for the app and the Hermes plugin.
- **Foldables.** On a wide screen, the chat list and the chat sit side by side.
- **Your language.** The app follows your phone's language in 16 languages, including right-to-left Arabic.

## The journey

Every screen below is Beacon on Android, signed in to a demo Hermes.

**1. On your PC.** After the install, the dashboard's **Beacon** tab shows the link for your phone and the phones
you have linked.

<p align="center">
  <img src="https://github.com/user-attachments/assets/20933d6d-9318-410d-a100-6fa1fff6b3f4" width="80%" alt="The Beacon tab on the Hermes dashboard">
</p>

**2. Connect.** Paste the link and sign in on Hermes' own page. That links the phone; there is no code or QR step.

<p align="center">
  <img src="https://github.com/user-attachments/assets/71d1f2ea-c9ec-466a-8781-6fff07e95e01" width="23%" alt="Paste the Hermes link">
  <img src="https://github.com/user-attachments/assets/a314c721-b9ad-492e-98e2-c6a284390080" width="23%" alt="Sign in with Hermes">
  <img src="https://github.com/user-attachments/assets/7b8146c2-a291-4957-b094-daaeec8c0ace" width="23%" alt="The Hermes sign-in page">
  <img src="https://github.com/user-attachments/assets/0b03eed4-1af2-47b2-b71d-60ceaa1743ba" width="23%" alt="Connected">
</p>

**3. Voice.** Pick a provider, add your key and choose how calls sound. You can skip this and add it later.

<p align="center">
  <img src="https://github.com/user-attachments/assets/bd391502-b138-45ba-ad4a-a19ec986f60b" width="31%" alt="Choose a voice provider">
  <img src="https://github.com/user-attachments/assets/cec4327f-53e9-4e5a-9f82-5f00a726b122" width="31%" alt="Pick a voice">
  <img src="https://github.com/user-attachments/assets/a453b7e7-d705-4a1e-b1b2-f68a05f16b2d" width="31%" alt="Beacon answers after checking the calendar">
</p>

**4. Talk to Beacon.** Ask about your day, have it draft a reply, use `/` commands, and set approvals for the chat.

<p align="center">
  <img src="https://github.com/user-attachments/assets/05bc3b64-e9d7-48b2-94e7-0661e322d950" width="23%" alt="Plan the day from calendar and inbox">
  <img src="https://github.com/user-attachments/assets/2831e1c4-279d-42b8-b487-8d934bb2b62b" width="23%" alt="A drafted reply">
  <img src="https://github.com/user-attachments/assets/d7686bee-d635-4754-bb6b-852be5b24640" width="23%" alt="Slash commands">
  <img src="https://github.com/user-attachments/assets/d6f2cd69-67c4-48ca-9a1a-491b517863e0" width="23%" alt="Approvals in this chat">
</p>

**5. Threads as channels.** Start a thread, give it a scheduled task, and read the posts like a channel.

<p align="center">
  <img src="https://github.com/user-attachments/assets/d675c9e6-83d0-4a4d-a167-6d72928eefb7" width="23%" alt="New thread">
  <img src="https://github.com/user-attachments/assets/b1a2624d-8fb7-46b1-a7ed-1ffdaa5436ee" width="23%" alt="A scheduled post">
  <img src="https://github.com/user-attachments/assets/20e84c96-cba7-481a-b593-edc0aa3d7b06" width="23%" alt="The post arrives">
  <img src="https://github.com/user-attachments/assets/5164349d-9526-41ca-8b8a-8f188411feed" width="23%" alt="A reply builds on it">
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/fc43815d-bf7a-4ca8-8287-2e0a1bae20f2" width="31%" alt="Scheduled posts for the thread">
  <img src="https://github.com/user-attachments/assets/3dc7d52a-6394-4c50-9127-60d91484fc5e" width="31%" alt="Threads">
</p>

**6. Projects and tools.** Add a chat to a project and it works with the project's files. Tools and MCP servers are one
tap away.

<p align="center">
  <img src="https://github.com/user-attachments/assets/332087d1-59a0-43d9-a6d4-55529513e0f3" width="23%" alt="Add to project">
  <img src="https://github.com/user-attachments/assets/902c558c-47aa-4c37-b6b3-ab11fb19a597" width="23%" alt="A chat in a project">
  <img src="https://github.com/user-attachments/assets/945f17f3-4a9f-4530-bc79-7c5b846331e9" width="23%" alt="Tools">
  <img src="https://github.com/user-attachments/assets/de0b51cc-2e72-411b-8570-10c564535d08" width="23%" alt="MCP servers">
</p>

**7. What needs you.** Turn it on, choose Notify or Call, and add your own rules.

<p align="center">
  <img src="https://github.com/user-attachments/assets/5e51e140-22a9-4790-ae53-fa8bca8fa78c" width="31%" alt="What needs you">
  <img src="https://github.com/user-attachments/assets/8d67816a-d605-48c9-931f-11ec4bc4a47d" width="31%" alt="Add a rule">
</p>

**8. The rest of Hermes.** Chats, settings, updates and a light theme.

<p align="center">
  <img src="https://github.com/user-attachments/assets/cb6ba6a1-7936-4671-b631-7a7ff255bfb5" width="23%" alt="Chats">
  <img src="https://github.com/user-attachments/assets/536125e4-f175-4b24-95f8-ab15210d7d58" width="23%" alt="Settings">
  <img src="https://github.com/user-attachments/assets/c1e5fe50-210b-46ab-b117-b02d181cb5b0" width="23%" alt="App updates">
  <img src="https://github.com/user-attachments/assets/56123012-1185-498d-a95a-ee0aa63553b0" width="23%" alt="Light theme">
</p>

**9. Unfolded.** On a wide screen, such as an unfolded foldable or a tablet held sideways, the list and the chat share the screen.

<p align="center">
  <img src="https://github.com/user-attachments/assets/fef40144-4888-40db-bf96-6bd03cfa2cde" width="80%" alt="Two panes on an unfolded phone">
</p>

## Manage phones

See and remove linked phones in Settings › Connection, or on the dashboard's **Beacon** tab.

## Requirements

- Android 10 or later.
- A Hermes Agent dashboard reachable from the phone over HTTPS.
- The Beacon plugin enabled for the device list and Beacon features. Native chat and conversation history work without it;
  voice calls, scheduled posts in threads, projects and What needs you need it.
- A Gemini, xAI (Grok) or OpenAI key if you want voice calls.

## Privacy and security

- Beacon requires an HTTPS dashboard and uses secure WebSockets for live sessions.
- The plugin checks your signed-in Hermes owner and profile before acting.
- Voice provider keys are encrypted on the phone and sent only to the selected provider.
- During a call, Beacon sends your assistant's instructions, memory notes, recent chat, tool results and any camera or screen you share to the voice provider you choose.
- Call audio is not retained. Call history is saved as text, and a finished call's text is passed to your Hermes memory provider like a chat.
- Beacon's call and task retention setting does not remove Hermes conversations, memories or files.
- Beacon checks the public GitHub release for updates on its own, without signing in, so GitHub sees the phone's address.

## Building from source

See [docs/preview.md](docs/preview.md) for the debug preview, stable build and signing information.

## Licensing

Beacon keeps its GPLv3 license (`LICENSE`). Adapted Hermes Relay code keeps its MIT notice
(`licenses/Hermes-Relay-MIT.txt`). Android library notices are in `beacon-android/THIRD-PARTY-NOTICES.md`.
