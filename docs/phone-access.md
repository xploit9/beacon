# Phone access for Hermes

Hermes can use your phone, and you approve what it does on the phone.

## What it can do

- **Read:** your calendars, the events in a time range, and contacts matching a name (names and phone numbers only).
- **Change:** add, edit or delete calendar events, add reminders (a short calendar event with an alert), set an alarm or a timer, start a call, open a text-message draft.
- Android has no way to list, change or delete alarms or timers. Hermes can set new ones only.
- A text message is only a draft. You press send in your messages app; Beacon never sends it.

## What you control

- Settings › Phone access has one switch per capability: Calendar & reminders, Alarms & timers, Contacts, Phone calls, Messages. Everything is off until you turn it on, and Android asks for its permission then.
- "Let Hermes use phone access" is separate and off by default. Voice calls and the assistant overlay do not depend on it.
- **Approvals** (Settings > Assistant, also on this page) decides whether Hermes asks first, in chats, scheduled jobs, live calls and the assistant overlay alike: **Manual** (everything asks, reads of your calendar and contacts too; the default), **Auto** (only changes ask), or **Full access** (nothing asks). A card shows what a change will do, with the notes or message text whole. Your phone must be unlocked to allow, unless Personal info when locked is on.
- With **Full access**, what Hermes asks for runs without a card, not even for a moment (events, reminders, alarms, timers, drafts, and Hermes' own tools on a call or from the side button), and is still listed in Activity. A phone call still asks first, nothing changes while the phone is locked unless Personal info when locked is on, and an alarm, timer or message draft runs on its own only while Beacon is on screen, because it opens another app.
- A call you ask for in a live call or the assistant overlay to a contact saved on your phone starts after a 3-second countdown on the screen (Cancel or Call now). A call to any other number waits for your tap. A call Hermes asks for waits for your tap too, unless Ask for approval is Never and the number is a saved contact: then it gets the same 3-second countdown. A saved contact means the same number is saved exactly; a similar number does not count. A card that waited more than a few seconds before you saw it never counts down.
- If you take a permission away in Android, its switch goes off and stays off until you turn it on again.
- A request lasts two minutes. If you do not answer, nothing happens, and it is never run later.
- On a locked phone the side button still starts the assistant at once and it talks, but anything personal (your memory, mail, calendar, contacts, chats) waits: it asks you to unlock, and the same conversation carries on once you do. Turn on Settings > Assistant > "Personal info when locked" to give it full control on the lock screen too: your memory and tools, the call's words on screen, and changes you allow (a phone call still asks first). A call you started while unlocked keeps its tools when the screen turns off.
- The countdown stops (and nothing is dialled) if you press power, lock the phone, leave the screen or end the call. After you cancel or deny a call, further calls in that conversation need your tap for five minutes.
- While the card is on your screen there is no notification for it; if you leave the screen with a request waiting, the notification appears. With notifications off, Phone access shows a line saying Hermes requests need Beacon open.

## How a request travels

1. Hermes calls a `phone_*` tool in a chat or a scheduled job.
2. The Hermes plugin checks the arguments and what your phone allows. If access is off, it answers "off" at once and your phone is not contacted.
3. Otherwise it queues a request and notifies your phone like any other Beacon notice.
4. Beacon reads the request, shows the card, and, after your tap, claims the request, runs it and sends the result back to Hermes.
5. Hermes gets the result, or a short error: `off`, `denied`, `expired`, `unavailable`.

## Safeguards

- A request the phone would refuse (an unknown event, a number it does not accept, text that is too long) gets no card. Hermes is told why, and nothing about your calendar or contacts is revealed before you tap.

- Only your phone, signed in as you and linked to Hermes, can see, claim or answer a request. A request is claimed once and runs once; a result can only come from the phone that claimed it.
- Hermes only reaches the phone through the existing Beacon plugin channel for your account. The phone opens no new network port.
- What Hermes asks for is bounded: a few events, ten contacts, text of limited length. Results come back as data. Event titles, notes and contact names are never treated as instructions.
- A call to an emergency or short service number only opens the dialer.
- Text from events and contacts is cleaned before it is shown on a card: invisible and right-to-left control characters and line breaks are removed, and long names are clipped.
- A request's text (event notes, message bodies, search terms) and the phone's answer leave the plugin's queue as soon as the request ends: when you deny it, when Hermes stops waiting or fails, and in any case five minutes after it was made. The dashboard watcher sweeps the queue, so this does not depend on the tool that asked still running.
- Beacon keeps no copy of your calendar or contacts. The phone keeps an activity list (last 200 entries) in Settings › Phone access › Activity, which you can clear.
- Beacon's read-only mail watch cannot use these tools, so an email cannot make Hermes read or change your phone.

## Things to know

- What a live call or the assistant overlay reads (event titles, notes, contact names and numbers) goes to the voice provider you chose for that call, as part of the conversation. A call can read at most 30 contacts in all.
- With **Auto** or **Full access**, any Hermes chat or scheduled job can read the calendars and contacts you switched on without asking you each time. Choose **Manual** to approve every read; on a call, Manual also asks before Hermes' tools read anything.
- Every request, including the ones you denied, that expired or that were switched off, appears in Settings › Phone access › Activity: grouped by day, newest first, with search and filters for the type, the result and who asked, 20 at a time.
