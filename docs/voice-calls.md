# Voice calls: your Hermes agent

Voice requests go to the same Hermes conversation as text chat. Hermes uses that conversation's configured model, profile, instructions, memory, tools and approvals. Your chosen realtime provider handles speech.

## Tools and memory

- Ask naturally, as you would in the chat. Hermes chooses the tools and skills enabled in your installation, including its connected MCP servers and their normal discovery workflows.
- Questions, searches, files, messages and memory requests all use the attached Hermes agent. Changing the profile's setup changes what that agent can do; no customer-specific voice configuration or training is needed.
- Hermes owns memory recall and saving, including any configured memory provider. Its normal retention rules apply to native conversation turns.
- Approvals and clarification use the attached Hermes conversation. Open the chat when an answer or approval is needed. Voice never approves an action for you.
- A queued request is still pending. Beacon speaks the verified Hermes result when it arrives and keeps the conversation attached to its original profile.

## Call controls

Interrupting speech stops playback and leaves submitted Hermes work running. To cancel that work, open the attached conversation and use Stop. Callback requests require a verified Beacon job link; Hermes servers that return only a native admission receipt currently cannot register these callbacks. Camera and screen sharing remain opt-in; a current visual claim requires a fresh frame that was actually sent.

Provider keys stay encrypted on the phone. Microphone audio and text read aloud travel directly to the selected voice provider. Hermes receives native text requests and authorized shared camera or screen images. Native replies read aloud can contain private information, so choose a voice provider you trust.

Beacon preserves the recognized request text. Its speech checks allow capitalization differences and a numeric addition written as `+` or spoken as “plus”; they do not rewrite the request. Changed words, values and literal identifiers remain unconfirmed. Unverified speech is withheld and reported, rather than automatically repeated.

Speech is generated in an isolated session from the native reply or a fixed call prompt. Gemini can complete speech without a separate output transcript, which is optional in [Google's Live API](https://ai.google.dev/api/live). Supplied transcripts are checked before playback. Captions follow the native text submitted for speech and appear after playback is acknowledged.

Speech comparisons accept apostrophe and prose-quote typography and omit Markdown emphasis markers. Literal code, operators and measurement marks remain checked; the saved native reply is unchanged.

## Saving and ending a call

Beacon keeps text transcripts and call metadata, with no retained call audio. Text uploads retry with the same event identity after a temporary disconnect. Diagnostic uploads cannot turn a saved transcript into a missing-text warning.

Ending a call stops local capture and playback first. Beacon then finalizes the text and confirms the PC-side close receipt, including reading back a close whose response was lost. If text or cleanup really remains unconfirmed, the app reports that limit.

Older released clients keep their supported quick-lookup API behavior while they update. Current voice calls leave tool selection and memory processing to the native Hermes agent.
