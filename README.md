# Beacon

**Your own Hermes, in your pocket.** Beacon is the Android app for a self-hosted
[Hermes Agent](https://github.com/NousResearch/hermes-agent): one always-on assistant chat, topic threads that take
scheduled posts, voice calls that use your Hermes tools and memory, and a heads-up when something needs you.
The assistant is named Beacon by default; rename it in Settings › Assistant.

<p align="center">
  <img src="https://github.com/user-attachments/assets/bee7a28c-e6f1-497c-8dad-b1b13afbc320" width="31%" alt="Beacon chat">
  <img src="https://github.com/user-attachments/assets/20e84c96-cba7-481a-b593-edc0aa3d7b06" width="31%" alt="A scheduled post in a thread">
  <img src="https://github.com/user-attachments/assets/54b85748-656a-493a-a65d-284138a1f724" width="31%" alt="A voice call">
</p>

## See it work

**You call Beacon.** It checks the calendar and saves a note to your Hermes memory, all by voice:

https://github.com/user-attachments/assets/6a723567-d330-4776-a0fd-974f11506226

**Beacon calls you.** Ask it to ring you about an email. It calls, says why, and drafts the reply:

https://github.com/user-attachments/assets/157763a7-d7e0-4181-9f6a-46efbedb6d11

The voice is Grok; the caller's side is synthetic speech. Screens and videos come from a demo Hermes with sample data.

## What Beacon does

- **A chat that feels like a messenger.** Grouped bubbles, date chips, long-press to copy, quote, share or read aloud,
  and `/` for Hermes commands. Longer runs of steps fold into one line.
- **Threads as channels.** Give a thread a name and an emoji, point a scheduled task at it, and each result arrives
  there as a post. Reply and the answer builds on it ([how threads work](docs/threads.md)).
- **Projects.** Add a chat to a project from its menu. It works in the project's folder and shows the project at the top.
- **Tools at hand.** Toolsets in a chat's menu opens your tools and MCP servers, the same controls as Settings.
- **Approvals per chat.** Each chat follows your profile's approval mode, or YOLO for that chat only.
- **Voice calls.** Talk with Gemini Live, Grok or OpenAI Realtime. Calls use the tools and memory of your Hermes profile,
  and can save new memories like a chat ([how calls use tools and memory](docs/voice-calls.md)). Call text is saved;
  audio is not kept.
- **What needs you.** Beacon watches what your Hermes can reach and tells you, or calls you, when something matters.
  Write rules like "Call me if a client moves a meeting" and teach it with Important / Not important.
- **All of Hermes.** Profiles, skills, memory, cron, files, Kanban and Bot Mode, with screens that open ready.
- **Updates from GitHub.** Beacon tells you when a release is out; tap Update for the app and the Hermes plugin.
- **Foldables.** On a wide screen, the chat list and the chat sit side by side.

## Install

**1. On the PC that runs Hermes**, give your coding agent (Codex, Claude Code, or Hermes itself) this prompt:

> Set up Beacon on my Hermes. First follow the predecessor-removal prerequisite in https://github.com/xploit9/beacon/blob/main/docs/install-with-agent.md: verify an external backup, then use supported native operations to remove only positively identified predecessor-managed cron jobs and the predecessor plugin. Stop and ask me if ownership is uncertain. Then download https://raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py and run it with Python. Whenever it stops to ask something, it names a flag — ask me, then rerun it with that flag. Show me the link it prints at the end. Never print or store passwords, tokens or API keys, and never modify Hermes core.

Before running it yourself, complete the [predecessor-removal prerequisite](docs/install-with-agent.md#before-installing-beacon) first: it removes an older plugin such as `hermes-relay`, or confirms you have none. Then run `python beacon_install.py`: it selects your Hermes home/profile, installs or updates Beacon, restarts the dashboard if you say yes, and prints the link to paste into Beacon. The installer does not back up or remove a predecessor plugin or its jobs. Manual steps: [docs/install-with-agent.md](docs/install-with-agent.md).

**2. On your phone**, download the latest `Beacon-*.apk` from [Releases](https://github.com/xploit9/beacon/releases/latest) and install
it. Open it, paste your Hermes link (also shown on the dashboard's **Beacon** tab), sign in with Hermes, and follow the setup wizard.

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
  <img src="https://github.com/user-attachments/assets/54b85748-656a-493a-a65d-284138a1f724" width="31%" alt="A call with Beacon">
</p>

**4. Talk to Beacon.** Ask about your day, have it draft a reply, use `/` commands, and set approvals for the chat.

<p align="center">
  <img src="https://github.com/user-attachments/assets/bee7a28c-e6f1-497c-8dad-b1b13afbc320" width="23%" alt="Plan the day from calendar and inbox">
  <img src="https://github.com/user-attachments/assets/549be056-143a-4c68-9ae1-5d5aae3110fe" width="23%" alt="A drafted reply">
  <img src="https://github.com/user-attachments/assets/d7686bee-d635-4754-bb6b-852be5b24640" width="23%" alt="Slash commands">
  <img src="https://github.com/user-attachments/assets/2be62168-3852-4f60-b4f9-6f02e04438dc" width="23%" alt="Approvals in this chat">
</p>

**5. Threads as channels.** Start a thread, give it a scheduled task, and read the posts like a channel.

<p align="center">
  <img src="https://github.com/user-attachments/assets/d675c9e6-83d0-4a4d-a167-6d72928eefb7" width="23%" alt="New thread">
  <img src="https://github.com/user-attachments/assets/f1945320-66c4-418e-9e74-c77a7eb9ac49" width="23%" alt="A scheduled post">
  <img src="https://github.com/user-attachments/assets/20e84c96-cba7-481a-b593-edc0aa3d7b06" width="23%" alt="The post arrives">
  <img src="https://github.com/user-attachments/assets/5164349d-9526-41ca-8b8a-8f188411feed" width="23%" alt="A reply builds on it">
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/3e968e0e-d75e-4226-a438-01ec7b0974f2" width="31%" alt="Scheduled posts for the thread">
  <img src="https://github.com/user-attachments/assets/3dc7d52a-6394-4c50-9127-60d91484fc5e" width="31%" alt="Threads">
</p>

**6. Projects and tools.** Add a chat to a project and it works with the project's files. Tools and MCP servers are one
tap away.

<p align="center">
  <img src="https://github.com/user-attachments/assets/332087d1-59a0-43d9-a6d4-55529513e0f3" width="23%" alt="Add to project">
  <img src="https://github.com/user-attachments/assets/902c558c-47aa-4c37-b6b3-ab11fb19a597" width="23%" alt="A chat in a project">
  <img src="https://github.com/user-attachments/assets/506a1bd5-0dc4-49fe-b08e-c7fe50356d84" width="23%" alt="Toolsets">
  <img src="https://github.com/user-attachments/assets/013b162f-7408-4566-9f95-1e8d61f32fc0" width="23%" alt="MCP servers">
</p>

**7. What needs you.** Turn it on, choose Notify or Call, and add your own rules.

<p align="center">
  <img src="https://github.com/user-attachments/assets/406aa3ce-1749-4604-b4a9-9d46565fe717" width="31%" alt="What needs you">
  <img src="https://github.com/user-attachments/assets/1fb5a2c2-1453-4d27-8f88-356b41e6b3ce" width="31%" alt="Add a rule">
</p>

**8. The rest of Hermes.** Chats, settings, updates and a light theme.

<p align="center">
  <img src="https://github.com/user-attachments/assets/8da571b3-f6e6-4347-90c3-60bc8ec4c942" width="23%" alt="Chats">
  <img src="https://github.com/user-attachments/assets/120c6066-5458-4428-a67a-f30b932e25b2" width="23%" alt="Settings">
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
