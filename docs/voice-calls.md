# Voice calls: tools and memory

A call talks to your own Hermes profile. It uses what that profile has, and it follows the same rules.

## Tools

- The assistant can use the tools your profile has: connected servers, files, web search and skills.
- Looking something up runs at once. Anything that changes something asks you on the phone first, unless your chat or profile approvals say otherwise. Hermes' own tools ask every time; a connected server's tool can be allowed for the rest of the call. Memory is the exception, as in Hermes (below).
- A few tools stay out of calls, because they do not fit a call. Longer work goes to Hermes with `hermes_work`.
  - `clarify` asks in a chat prompt. On a call the assistant asks out loud.
  - `delegate_task` starts helpers inside a running Hermes agent. `hermes_work` hands the task to a full run instead.
  - `todo_list` keeps its list inside one running agent turn, so it would be lost when the call ends.
  - The browser would need your approval for every click. Ask for it through `hermes_work`.

## Memory during a call

- The call starts with your profile's saved notes, the recent chat and earlier voice dialogue. If your notes in Hermes say your name, the assistant uses it, unless you set a name in Beacon.
- "Remember that..." uses Hermes' own memory tool, with your Hermes limits and rules. Like in a Hermes chat, saving a memory doesn't ask on the phone.
- If your profile uses a memory provider, its tools are available too, without asking, and a note you save is also passed to the provider, as in a chat.
- Looking something up in memory (`hermes_memory`) stays quick and never changes anything. When you first speak, your memory provider starts looking in the background, so the lookup is often ready.
- The assistant can look back at your earlier chats in this profile, the same ones your History shows.

## After a call

- The finished exchanges are given to your memory provider like chat turns, once per call and never twice. A reply you interrupted or that never finished is left out. No audio is kept.
- Nothing is passed on when the call failed, when your profile has no memory provider, or when you turned off Hermes' memory tools for the API platform.
- A call the phone never ended (for example the app was closed by force) is passed on a few minutes later, once Beacon closes it.
