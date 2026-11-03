# Voice calls: tools and memory

A call talks to your own Hermes profile. The voice model answers you itself, straight away, and uses what that profile has, following the same rules. Each tool it runs shows in the call, and the whole call appears in the chat as one call card.

## Tools

- The assistant can use the tools your profile has: connected servers, files, web search and skills.
- Looking something up runs at once. Anything that changes something asks you on the phone first, unless your chat or profile approvals say otherwise. Hermes' own tools ask every time; a connected server's tool can be allowed for the rest of the call. Memory is the exception, as in Hermes (below).
- A few tools stay out of calls, because they do not fit a call. Longer work goes to Hermes with `hermes_work`.
- The assistant starts each call knowing every source of tools your profile has, each under its own name (a connected server, or Hermes for its own tools), so it never guesses what you have. It searches for the exact tool when it needs one, and searches before saying it cannot do something.
- The tools are the ones Hermes gives Beacon chats (its API platform tool set). A Hermes tool you turned on for that platform reaches calls too; one it leaves out, such as computer use by default, is not in calls either.
- A search finds every tool Hermes already knows from earlier connections at once, even right after Hermes starts, because it reads the tool lists Hermes keeps on disk (`cache/mcp_schema_cache.json`) and starts no server. A server starts only when one of its tools is used, and only that server. A server Hermes has never connected, or whose settings changed, connects in the background: a search answers at once with what is ready and names that server as still starting, so the assistant asks again instead of waiting.
  - `clarify` asks in a chat prompt. On a call the assistant asks out loud.
  - `delegate_task` starts helpers inside a running Hermes agent. `hermes_work` hands the task to a full run instead.
  - `todo_list` keeps its list inside one running agent turn, so it would be lost when the call ends.
  - The browser would need your approval for every click. Ask for it through `hermes_work`.

## Memory during a call

- The call starts with your profile's saved notes, the latest messages of this chat and earlier voice dialogue, all read before you tap so the call connects at once. If your notes in Hermes say your name, the assistant uses it, unless you set a name in Beacon.
- "Remember that..." uses Hermes' own memory tool, with your Hermes limits and rules. Like in a Hermes chat, saving a memory doesn't ask on the phone.
- If your profile uses a memory provider, its tools are available too, without asking, and a note you save is also passed to the provider, as in a chat.
- Looking something up in memory (`hermes_memory`) stays quick and never changes anything. When you first speak, your memory provider starts looking in the background, so the lookup is often ready.
- The assistant can look back at your earlier chats in this profile, the same ones your History shows.

## During and after a call

- Calls start on the speaker unless a headset or Bluetooth is connected.
- Replies in the chat you are calling from are not sent as notifications while the call is on; you hear and see them in the call.
- The finished exchanges are given to your memory provider like chat turns, once per call and never twice. A reply you interrupted or that never finished is left out. No audio is kept.
- Nothing is passed on when the call failed, when your profile has no memory provider, or when you turned off Hermes' memory tools for the API platform.
- A call the phone never ended (for example the app was closed by force) is passed on a few minutes later, once Beacon closes it.

## Saving and ending a call

Plugin updates from Settings include a gateway restart when Hermes requires it. Beacon waits for native restart completion and a serving gateway before reporting success. Active calls and native work can defer the restart; use the saved **Restart Hermes** action in the app to retry without reinstalling the plugin. A restart that did not start says why in `restart_error_code`: `live_sessions` (Hermes refused while chats are running), `stale` (it was an earlier restart, not a new one) or `failed`; an active call still answers 409.

Beacon keeps text transcripts and call metadata, with no retained call audio. Text uploads retry with the same event identity after a temporary disconnect. Diagnostic uploads cannot turn a saved transcript into a missing-text warning.

Ending a call stops local capture and playback first. Beacon then finalizes the text and confirms the PC-side close receipt, including reading back a close whose response was lost. If text or cleanup really remains unconfirmed, the app reports that limit.

Older released clients keep their supported quick-lookup API behavior while they update.
