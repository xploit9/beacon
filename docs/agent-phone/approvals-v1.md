# Agent phone approvals, version 1

Owner: lane L3 (EMU-DESIGN rev 4 §5.3, §5.4, §5.5, §9.2). **Revision 3**, 17 Nov 2026 (revision 1 was announced in
msg_8770f908c354, sha256 06ec4e4d…; revision 2 sha256 8c21f863…).

Changes in revision 3 (follow-up 1; additions only, nothing removed or renamed):
1. §5.5 notice `agent_phone_done` after an approved payment or send (§6b); L2 adds it to its notice lines.
2. `GET /mobile/agent-phone/activity` rows carry `seq`, the number the done notice names.
3. `agent_phone_guard` (§7): Android's USB / wireless debugging prompt is answered No with Back, by the tool and the setup view.
4. The plugin taps Android's own "Save … to Beacon?" right after the agent's own submit of a form with a password field (§2,
   `vault_save` / `vault_update` as before); never for a form the agent didn't submit, never the model.

Changes since revision 1 (no route, table column or payload field changed):
1. `mail_packages` (§7, end): `mailto:` handlers that are system apps or Play-installed; sideloaded never (Root, msg_9a8ed075287e).
2. `needs_you` notices have their own cap of 5 (a sixth ends the oldest) and do not count toward the 10 open decisions; the list
   returns the decision records first, then the notices (§1).
3. The lease seam (§7): always `agent_phone_lease.read(folder)`, never `run/lease.json` directly (L2, msg_6b778f416fbc).
4. L9's `code(..., ask_owner=)` exists (§7).
5. `label` (§4) is the control the input lands on: when an element's centre sits on another control, the record names that one. L2 mounts the routes, L6 shows the records, L9's
vault bridge raises some of them. The plugin side is `hermes-plugin/beacon/agent_phone_approvals.py`; the table is created in
`mobile.py` with the others.

## 1. What a record is

A decide-only question for the owner about the agent's own phone. Nothing runs on the owner's phone: the owner says yes or no
and the plugin acts on the agent phone. A record holds labels only (app, button, amount, purpose, site, identifier): never a
secret, never an image, never a screenshot crop. It is separate from Phone access requests (`phone_requests`) and is shown
to every linked device of the owner, whatever Phone access says.

Limits: at most 10 open (pending) records that need a decision per owner, plus at most 5 open `needs_you` notices (a sixth
ends the oldest, so notices never crowd out a payment question); at most 30 created a minute per owner (denied and
expired count). The list returns the decision records first, then the notices.
The tool waits at most 120 s for an answer, as `phone_tools._wait` does; a record nobody answered ends as `expired` and
nothing runs. A decline stands 60 s for the agent's task: within it the tool refuses the same kind of input without asking.

## 2. Kinds

| kind | Raised by | Decision | Effect of yes | Lives |
|---|---|---|---|---|
| `agent_phone_action` | L3: `tap`, `long_press`, `swipe`, `type` with `submit`, `key enter` that the risk check says ask; a first pin folded in (`pin`) | yes / no | Exactly one input, bound (§5) | 120 s |
| `vault_new_login` | L3: `new_login` | yes / no | L9 fills a generated password into the new-password fields | 120 s |
| `vault_save` | L9 `notify('vault_save')`: a save in the agent's turn (the plugin tapped Android's save prompt after the agent's own sign-in or sign-up), a temporary password | yes / no | The process holding the credential calls `vault.decide_save(held, yes)` | 10 min |
| `vault_update` | L9 `notify('vault_update')`: a save that would replace a login | yes / no | as `vault_save` | 10 min |
| `vault_code` | L9 `code(..., ask_owner=)` when no viewer can answer Hermes' own code prompt (see §7, open) | a code, or no | The code fills the focused code field once | 120 s |
| `needs_you` | L9 `notify('needs_you')`: a login didn't work, a sign-in only the owner can do | none: "Open the phone" or dismiss | none; the app opens the setup view | 24 h |

`notify('saved')` is not a record: it is an activity row (`app` the login's label, `action` `save`, `label` the login's identifier,
`outcome` `saved`; the app words it, channel-v1 revision 5 §11.1), and in a setup session L2's view shows the notice with its code.

Statuses: `pending` → `approved` | `denied` | `expired`. `withdrawn` = the tool stopped waiting, or the process holding a
credential ended (its held value is gone, so the record can no longer do anything). `needs_you` ends `approved` ("Open the
phone"), `denied` (dismissed) or `expired`.

## 3. Table (`mobile.sqlite`)

```
agent_phone_approvals(
  id TEXT PRIMARY KEY,          -- 32 lowercase hex (uuid4)
  owner TEXT NOT NULL,          -- Beacon owner id
  profile TEXT NOT NULL,        -- the phone's owning profile
  session TEXT NOT NULL,        -- the Hermes session that asked ('' for none)
  kind TEXT NOT NULL,           -- §2
  payload TEXT NOT NULL,        -- JSON, labels only (§4)
  bind TEXT NOT NULL,           -- JSON, host-side binding (§5); never sent to a device
  holder TEXT NOT NULL,         -- '<pid>:<process start>' of the process that acts on the answer
  created REAL, expires REAL, decided REAL,
  status TEXT NOT NULL DEFAULT 'pending',
  device TEXT NOT NULL DEFAULT '',   -- the device that decided
  answer TEXT NOT NULL DEFAULT ''    -- vault_code only: the owner's code, scrubbed when read or after 120 s
)
agent_phone_activity(seq INTEGER PRIMARY KEY AUTOINCREMENT, owner, profile, at REAL, app TEXT, action TEXT, label TEXT,
  approved INTEGER, outcome TEXT)   -- "What {name} did on its phone": labels only; newest 500 per owner, 30 days
```

The policy ("Let {name} use its phone", "Ask before") is kept in `meta` under `agent_phone_policy:<owner>` as
`{"enabled": true, "ask": "payments_and_sends" | "every_action"}`; default `enabled` true, `ask` `payments_and_sends`.

Every write runs the expiry pass first (`pending` past `expires` → `expired`; `answer` scrubbed; records of a dead holder →
`withdrawn`; rows older than 1 hour deleted, `needs_you` after 24 h).

## 4. Payload (what L6 shows)

One JSON object per record. Every field but `id`, `kind`, `created_at`, `expires_at`, `expires_in_seconds` may be absent or null.

| Field | Type | Kinds | Meaning |
|---|---|---|---|
| `id` | 32 hex | all | the record |
| `kind` | §2 | all | |
| `risk` | `payment` `unlabeled` `send` `delete` `install` `account` `other` | `agent_phone_action` | why it asks |
| `action` | `tap` `long_press` `swipe` `type` `key` `fill` | `agent_phone_action` | |
| `app` | ≤ 80 chars | all but `needs_you` may omit | the app's label on the agent phone, or the site |
| `label` | ≤ 120 chars | `agent_phone_action`, vault kinds | the text of the control the input lands on ("Place order"), or the login's label |
| `amount` | ≤ 40 chars | `agent_phone_action` | the amount read from the screen ("£12.40"), as shown there |
| `purpose` | ≤ 120 chars | `agent_phone_action` | the agent's stated purpose, plain text |
| `pay` | `true` / `false` | `agent_phone_action` | `true` only when the tapped control's own words (its text, description or hint, never its id) are one of: place order, pay, pay now, buy, purchase, confirm order / payment / purchase, complete order / purchase / payment, subscribe (and the same in the commonest phone languages), or carry an amount ("Place order", "Pay £12.40", "£12.40"); `false` for Enter, a typed submit, Sign in, Continue, an unlabeled tap and checkout navigation ("Checkout", "Proceed to checkout", "Continue to payment"), which still ask as payments. Always present, never text |
| `identifier` | ≤ 120 chars | vault kinds | a login's identifier (metadata, never a password) |
| `pin` | ≤ 160 chars | `agent_phone_action` | a first-pin question folded in: "Use the Kanto Foods login in Kanto Foods?" |
| `line` | ≤ 200 chars | vault kinds, `needs_you` | the whole sentence: "Save the new Amazon login?", "Enter the code for Amazon.", "The Amazon login didn't work." |
| `note` | ≤ 200 chars | optional, any kind | none sent since 17 Nov (Root: the card shows no rule text); an app still shows one if sent |
| `created_at`, `expires_at` | int (unix s) | all | |
| `expires_in_seconds` | int | all | |

Words L6 shows (§8; `{name}` is the assistant's name, which the app knows):

| Record | Title | Body |
|---|---|---|
| `agent_phone_action`, risk `payment` | {name} wants to pay | `<app>: <label> · <amount>` · `For: <purpose>` |
| risk `unlabeled` | {name} wants to tap on a payment screen | `<app>` · `<amount>` when read · `For: <purpose>`. Also raised (with no `amount`) for a tap by coordinate that lands on no labeled control, for Enter with no readable focused node and for a slide that starts on no labeled control (§5a): none of these needs a payment screen, so the app words it by `amount` and `label` being absent |
| risk `send` | {name} wants to send | `<app>: <label>` · `For: <purpose>` |
| risk `delete` | {name} wants to delete | as send |
| risk `install` | {name} wants to install an app | as send |
| risk `account` | {name} wants to change an account | as send |
| risk `other` | {name} wants to use its phone | as send ("Every action" is on) |
| any with `pin` | as its risk; for `fill`: {name} wants to use a saved login | `pin` |
| `vault_new_login` | {name} wants to make a login | "Create a login for `<label>` with `<identifier>`? It's saved to your vault." |
| `vault_save`, `vault_update` | {name} found a login | `line` |
| `vault_code` | {name} needs a code | `line`, a code field |
| `needs_you` | {name} needs you | `line` · Open the phone |

Buttons: Allow / Deny; `vault_code`: Enter / Deny; `needs_you`: Open the phone / Dismiss. The dialog is FLAG_SECURE (L6). An `agent_phone_action` shows "Pay {amount}" only when `pay` is `true`; a Sign in, Continue, Enter or unlabeled tap on a payment screen is the same question with Allow / Deny.

## 5. Binding (one approval = one input)

An `agent_phone_action` record's `bind` holds what the yes covers: the package, the focused window, the element's label, its
bounds and the amount text on the screen. Right before the input the tool captures the tree again; it acts only when the package
and window are the same, an element with the same label sits within 8 px of the same bounds, and the screen's amount text is the
same. Anything else voids the yes: the tool asks once more with what the screen shows now, and if that changes too it stops and
says so. A yes allows exactly one input and ends after 2 min or on any change of the lease (§6). The model's declared `risk`
can only raise the level.

## 5a. The invariants, and the bound purchase grant (a vault card)

These five sentences are what the tests pin. Every rule below follows from them; a change that breaks one is wrong whatever it fixes.

- **I0, secrets within a session.** Everything Beacon filled is masked in the text and the picture of every capture, for as long as its
  fill is live: a value it holds wherever it shows, and a code it read and filled in every text box of its app (§5c item 18). One thing
  outside that app is not promised: a code can still show in the message it came in (§5c item 19).
- **I1, secrets across a restart.** Nothing a Quick Boot snapshot can restore is shown to the model unmasked.
- **I2, money.** One owner yes pays for one purchase. Any answer but yes ends it, and it never covers a tap whose target the tool can't
  read. With the owner's two accepted limits ("Buy again" on a confirmation, a step-word button that really orders) one yes pays for at
  most two orders.
- **I3, reading.** Reading a window other than the active one changes nothing (no watch, no mask, no record). While a fill is live, a
  capture whose window list, or any window's tree, can't be read is refused.
- **I4, asking.** An input whose target or effect the tool can't read asks, except a vertical scroll.

A purchase with a card from the vault is one question, and it is Hermes' own card consent inside L9's `fill` (surface
`vault-payment`): "Pay £12.40 at Kanto Foods with Visa?". The tool passes `shop` and `amount` read from the screen (the app's
label, the order button's amount or else the screen's total), never from the model; for a fill that has a site the question names
the site (the vault's `web_domain`) instead of the app or browser. On a yes, L9 puts the card in, and only once the card went in
calls `on_card_approved(package, handle, amount, certs, task, session, web_domain)`: L9's grant carries the task and the session of
the call that asked (`fill`'s `session_id`), never a slot another chat can change, and the site of every fill that has one. A yes
whose fill never happened (no entry, a timeout, nothing filled, a record of the fill that could not be written), a decline and a
missed question record nothing.

- it is bound to the package, the card handle, its certificates, the normalized amount, the task and session that got the yes,
  and the site when the fill had one; it lives 5 min from the card going in;
- it allows the card fill (L9 consumes its own half) and that purchase's payment taps in that package, each read again right
  before the tap: the amount, from the element's text or else the screen's total, equals the approved amount;
- **only a tap can be a step.** Enter, a typed submit and a slide or hold gesture ("Slide to confirm", "Hold to confirm") may
  place the order whatever the focused field or the button is called ("Email for order confirmation"), so they use the grant up.
  So does a tap whose own words, or the words of the control it lands on (an element's centre can sit on another control), are a
  pay word or carry an amount;
- the **order tap**, whose own words are a pay word or carry an amount ("Place order", "Pay £12.40"), uses the grant up at
  once;
- a **step tap** (commit or submit words among the words a person reads on the control, never its id: Continue, Confirm, Next, OK,
  Sign in, Save) keeps the grant for the next screen, one per grant (a second step asks, so going back to a step screen and tapping it again
  asks), and only while the next screen is a payment screen of the same app that shows a pay-word control, enabled or not (the order button is ahead; a disabled "Place order" that waits for a terms
  box counts). Otherwise the step tap uses the grant up: a "Confirm" that leads to "Order placed, total £12.40" with only
  "Continue shopping" spent it. **A step tap on a screen that already shows a pay-word control uses the yes up:** the order button is
  there, so the "step" may be the order. That, and the one step, is what holds one yes to two orders. The tool looks at the page a step led to up to
  three times, a second apart, until the screen has changed to a payment screen: a spinner or a blank page is no answer yet, and the
  first look does not wait. Each look is a tree read (about 2 s on the agent phone), so a covered step costs about 2 s when the page
  is up, and up to about 8 s when it is slow;
- **a site's grant covers a tap only while the page's host is read and matches.** The host is the text of that app's own address
  bar in the tree (`<package>:id/url_bar`, outside the page's WebView, since a page can draw an element with the same id; a bar
  being typed into is no host), and it must be the site of the fill or one of its subdomains. Measured on the agent phone (Chrome,
  19 Nov): the bar's text is the host, or the host and path ("example.com", "en.wikipedia.org/wiki/Android_(operating_system)"),
  while the toolbar shows. **When no host can be read the order asks once more**, in plain words: Chrome hides its toolbar when a
  page is scrolled down, and then the tree holds neither the bar nor the page; a page inside a shop's own app (a WebView) has no
  address bar at all; any other browser is read only if its bar has the same id, and asks if it does not. That question is the
  owner's, with Allow and Deny like any other payment, and its answer ends the card's yes (below);
- **what the tool can't read asks (I4).** A tap or long press by coordinate that lands on no labeled control asks as an unlabeled
  tap, because the picture can show an order button that the tree does not. So does Enter with no readable focused node, on any
  screen, and a slide that is not up or down and starts on no labeled control (a slide-to-pay drawn in a page). A vertical scroll
  never asks. A hit on a scrollable container (a WebView, a list) counts as no labeled control, even when the container is named
  (the page's title): the label must come from the control under the finger. Chrome's scrolled-down page is read this way: its
  tree holds only a "Web View" placeholder, so a coordinate tap, Enter and a slide there ask, and a named toolbar button does not;
- any other covered input (a button the agent declared a payment that has no commit or submit word) may be the order and uses
  the grant up;
- **an input the owner answers by question ends or keeps the grant by its words, and any answer but yes ends it.** A yes to a plain
  step (a tap with commit or submit words and no pay word or amount) is handled as a covered step is: it goes through the step count
  and the next-screen look, and ends the grant if its screen already shows the order button. A yes to anything else (an order, Enter,
  a typed submit, a tap on an icon or on no control) ends the grant, so the page that follows cannot match it for a retry. A no, no
  answer, or a screen that moved ends it too: the owner's last word about that order was not yes;
- a different amount, another app, card, task or session, a second order after the order tap (a retry of a failed order
  included), or a screen with no readable amount asks again with an `agent_phone_action` record; a grant with no amount covers
  no payment tap;
- a card saved in the shop's own app (no vault card) means the order input asks on its own.

## 5b. What a capture hides, and how long the masks last (I0, I1, I3)

What Beacon filled is masked in every capture (`agent_phone_redact`), in the text and in the picture. The masks are kept as digests,
never values, in `run/masks.json` of the phone's folder (the newest 2048 digests), shared by the gateway and the dashboard's setup
session. That file is also the record that a fill happened.

- **A fill whose record can't be written is not released.** The stop reads the record to know a filled screen may be on the phone; a
  record that is missing is a fill it would not see. A code the helper reads and puts in itself is no different: the app's record is
  written before the code is armed, and nothing is armed if it can't be written.
- **A stop while a fill is on record saves no snapshot.** The stop drops Quick Boot, then deletes the masks; a stop with no fill on
  record saves as before. "On record" is any masks file, even an empty or unreadable one, or its temporary file. The record is read
  again right before the shutdown and after it: a fill that lands while the snapshot is written drops that snapshot too. The next
  start after a session with a fill is a cold boot. Every start that boots cold deletes the masks. A hard kill (the keeper's
  fallback, a crash) keeps the old snapshot, which was saved with no fill on record, and leaves the masks, which over-mask until
  the next stop.
- **While a fill is live a capture reads every window.** The plain dump holds only the active window, so a window that fills the screen
  (a bottom sheet over a filled form) hid the form from the masks while the picture showed it. The capture takes
  `uiautomator dump --windows` (one tree per window: the app, a dialog, the keyboard, the status bar), runs the read guard on each
  and draws every box on the picture; the elements and the text come from the active window only. A window list that can't be
  read, a window with no tree, no active window, or a window of a filled app whose tree is unreadable refuses the capture. A
  window that is merely sparse (the keyboard's has two named nodes) is read, not refused. Outside the active window's tree the
  picture is blacked out when that window does not cover the screen, and a capture is refused when another app's window is in front of
  a filled app.
- **A code Beacon puts in hides the whole app.** A code the helper reads and fills itself makes its app a **code app**, at once,
  whatever the helper reported (a view id, a position, nothing): a sheet of the same app can carry the same view id or lie over the
  code field, and the code can reach the page by a path no field bookkeeping follows. Every text field of a code app is masked, in
  every capture and every window of that app, the boxes the agent types into included, in every process, until the stop. So is every
  view whose whole text, or whole description, is only digits (1 to 8, spaces and dashes ignored), in the text and in the picture: an
  OTP widget can draw one digit per view and keep the code in a hidden box. Outside a code app digits are the app's own. The app is
  kept in the masks file, outside the newest 8 watches' cap, so eight later code fills or a Hermes restart while the phone runs don't
  show a code that is still on its page. The stop and every cold boot delete it with the masks, so those fields stay hidden from Beacon
  until the phone stops (§5c item 18).
- **A window the list leaves out withholds the picture.** `uiautomator dump --windows` leaves out a window that a window above it
  covers entirely. Measured on the agent phone (20 Nov): the activity under a full-screen bottom sheet is not listed, and the picture
  still shows it through the scrim: the save-card sheet over the filled card form showed the card number, expiry and security code
  legibly, while the text list was clean. While a fill is live the capture also reads `dumpsys window windows`, which names every
  window on the screen. A window of an app that holds something Beacon filled that it names and the list does not, or a dumpsys that
  names no window, **withholds the picture**: the capture returns the clean text list of the active window, `picture: withheld` and a
  plain line, and the element numbers still work. A window the list does hold has every text field boxed in the picture, whether it
  matches a value or not.
- **Only a capture that is shown moves a watch.** A watch only registers the code's own text by value (so it stays masked wherever else
  it shows), once a shown capture finds the field the helper reported: by its view id (the watch ends when that field is gone from its
  own app's tree) or, failing that, by position alone (never dropped). Reading another window, reading a second time, or a capture that
  is then refused leaves the watches as they were, and another app's tree (the status bar's) neither masks nor ends one. A sheet of the
  same app with no text field leaves the watch unplaced.
- **A new phone session forgets the last one's sets.** Every start writes a new session id (`run/session`). A process that kept sets
  for the last session (code apps, watches, fill records, what the file named) forgets them at its next read and keeps only the values
  it holds, digested again under the new key: more is hidden, never less. It is how a gateway that missed a stop does not carry the
  last session's code apps into the next.
- **The Recents overview is never captured.** Recents shows a picture of each recent app's last screen, and no mask knows where its
  fields are. It is the launcher's own activity, so the focus names the launcher whether it shows home or the overview; its tree holds
  `overview_panel` (measured on the agent phone), and a capture of that tree is refused with "press back". The home screen and the app
  drawer hold no `overview_panel` (measured 19 Nov: `launcher_home.xml`, `launcher_drawer.xml`; the home screen does hold
  `overview_actions_view`), so they are captured. A force-stop leaves the app's task and picture in Recents (measured), and the masks
  end at the stop, so the refusal does not wait for a fill to be on record.

## 5c. The limits that remain (for the owner)

**Paying**
1. A "Buy again" button on an order confirmation that still shows the same total can be paid with the same yes. (Accepted.)
2. A button that says "Confirm", "Complete", "Finish" or "Order" and really places the order can be tapped on the same yes. A step on a
   screen that already shows an order button uses the yes up, and a grant keeps one step, so one yes pays for **at most two orders**: a
   step that orders, then "Buy again" on the next screen, or a step that orders, then the order button. Going back to the step and
   tapping it again asks. (Accepted.) That rule has a cost. A "Continue" or "Next" on a page that also shows a pay button ("Pay with
   Google Pay", "Buy now") uses the yes up, so the order asks again.
3. A payment page that many shops share (Stripe, PayPal) counts as one site: a yes at one shop covers another shop's page there, at the
   same amount, in the same chat, within 5 minutes. (Accepted.)
4. A web order with Chrome's address bar scrolled away asks once more. (Accepted.)
5. A shop app whose checkout is a web page inside the app can't have a card filled from your vault; the agent asks you to fill it. The
   order there asks too.
6. A step button with no words (only an icon) asks, and then the order asks again.
7. An order button whose words Beacon doesn't know as payment words ("Get it now") on a checkout runs without asking. The payment
   words cover about 15 languages.
8. A failed order that the agent tries again asks again. A step button whose hidden id holds a pay word asks once more.
9. A yes lasts 5 minutes, for that chat, that app or site and that amount. If the total can't be read, the order asks.

**Secrets**
10. After the phone restarts, a filled value that an app kept in its own data isn't hidden: a mail repeating a new password, a code in
    Messages, a browser refilling a form, the filled app itself. Card numbers (by their checksum), security codes and expiry dates next
    to their labels, and passwords in password fields stay hidden by their own rules; a name on the card or a password shown in plain
    text don't.
11. A value the owner filled during setup, or one Beacon filled before Hermes restarted (a password, say), shown inside a longer word
    ("pass=Test-Pass-4711"), isn't hidden from the agent's capture. A code Beacon read and filled is not one of these: its app's text
    boxes are hidden (item 18).
12. Bank and wallet apps refuse to run on this phone. Bank websites work in Chrome, and moving money there asks like a payment.
13. If the phone is stopped abruptly while Beacon has filled something, more of the screen stays hidden until the next normal stop.

**Everyday use**
14. After a session with a fill, the next start is a cold boot: about 15 s instead of about 4 s.
15. While a fill is live: a capture with another app's window in front of the filled app is refused ("press back"); a screen the phone
    can't fully read is refused; a dialog or sheet over a filled form gives the text list and no picture; each capture takes about 2 s
    longer.
16. The Recents overview is never captured.
17. Taps by position on things the phone can't name ask: canvas apps, games, maps, parts of a web page the phone doesn't expose. So
    does Enter with nothing readable selected. A sideways swipe on a list, a carousel or an unnamed area asks, and during a purchase it
    ends the card's yes. On Linux, restarting Hermes turns the phone off, and it starts again the next time it's needed (README).
18. After Beacon puts a code into an app, every text box in that app stays hidden from it until the phone stops, including boxes it
    types into. In Chrome that means every site, and the address bar. Numbers the app shows on their own (a quantity, a count, a size)
    are hidden too.
19. A code Beacon filled can still show in the message it came in, such as Messages or mail, if the agent opens that message.
20. In an app Beacon put a code into, the code can still show if the app draws it in a way the phone can't read as digits: a code with
    letters drawn one character at a time, a code of 9 or more digits shown in one piece with spaces, a code painted as a picture, or
    digits the app only describes in words.

Reading of "one approval allows one input": the combined question yields the card fill and the taps of that one purchase, each
re-checked right before it runs. Window, element and bounds are bound at each payment tap itself (they are not known when the
card is filled).

## 6. Routes (L2 mounts them in `dashboard/plugin_api.py`)

All answer `Cache-Control: no-store`. Identity is `identity_async(request)`; the device is `phone_device(request, owner,
provider, profile)` (a linked device of the owner; `x-beacon-device-id`). Records are listed for the owner across profiles, so a
device that has another profile open still sees them. No Phone access filter (`_phone_allows` is not used).

| Route | Body | Calls | Answers |
|---|---|---|---|
| `GET /mobile/agent-phone/approvals` | | `agent_phone_approvals.open_records(owner)` | 200 `{"approvals": [payload…]}` |
| `POST /mobile/agent-phone/approvals/{id}/decision` | `{"approved": bool}`; `vault_code` with yes: `{"approved": true, "code": "<4-10 letters or digits>"}` | `decide(owner, id, approved, device, code=None)` | 200 `{"approved": bool}`; 404 not found; 409 `expired` or `settled`; 400 invalid |
| `GET /mobile/agent-phone/activity?limit=50` | | `activity(owner, limit)` | 200 `{"activity": [{"seq", "at", "app", "action", "label", "approved", "outcome"}]}` |
| `GET /mobile/agent-phone/policy` | | `policy(owner)` | 200 `{"enabled": bool, "ask": "payments_and_sends" \| "every_action"}` |
| `PUT /mobile/agent-phone/policy` (owner only) | `{"enabled"?: bool, "ask"?: …}` | `set_policy(owner, **body)` | 200 the policy; 400 invalid |

Errors from the module: `LookupError` → 404, `ValueError('expired' | 'settled')` → 409, `ValueError('invalid')` → 400.
The record id is checked as 32 lowercase hex before the call (as `phone_request_id`).

**Notice lines L2 adds in `plugin_api.py`:**
- `_visible_notice`: add `'agent_phone_approval'` to the kinds that skip the conversation check
  (`row.get('kind') in ('test','app_update','phone_request','agent_phone_approval')`).
- `notice_preview`: `if kind=='agent_phone_approval': return 'Approval needed','Open Beacon to review the request.'` (no content
  in a push).
- The watcher's sweep: call `agent_phone_approvals.sweep()` beside `mobile.phone_requests_sweep()`.

Each record raises one notice: `kind='agent_phone_approval'`, the same id, `conversation='agent-phone-'+id`, the owning profile,
expiring with the record (as `phone_request_create` does).

## 6b. The done notice (§5.5)

After an approved payment or send ran (a yes on its record, or the vault card's combined yes for the order input), the plugin
raises one notice: `kind='agent_phone_done'`, a new 32-hex id, `conversation='agent-phone-done-'+id`, the owning profile, lives
1 hour, `reason` = `payment` | `send`, `activity_key` = `agent_phone_activity:<seq>` (the activity row it names). No other content.
Function: `agent_phone_approvals.notify_done(owner, profile, reason, seq)`.

**Lines L2 adds in `plugin_api.py`:** `_visible_notice` lets `'agent_phone_done'` through like `'agent_phone_approval'`;
`notice_preview('agent_phone_done')` returns `('Done on the assistant\'s phone', 'Open Beacon to see what was paid or sent.')`.
The app (L6) shows the activity row named by `activity_key` as the line ("Paid £12.40 at Kanto Foods", "Sent in Messages").

Other activity outcomes the app may show: `done`, `declined`, `blocked`, `said_no` (Android's debugging question answered No:
app `Android`, label "Allow USB debugging?"), `asked_owner` / `not_saved` (the plugin tapped Android's save prompt).

## 6a. The lease

While the owner holds the phone (the setup view is open) the tool answers `busy` ("The owner is setting up your phone. Try again
when they're done.") without capturing anything. An action admitted under one lease epoch is void if the epoch changed before
its input.

## 7. Seams L3 reads from other lanes

| From | What L3 calls or reads | Status |
|---|---|---|
| L1b | `agent_phone_adb.PhoneAdb(home, adb_port)` (`.shell`, `.exec_out`, `.session`), `agent_phone_grpc.EmulatorGrpc` (`screenshot`, `touch`, `tap`, `key`), `agent_phone_host.PhoneHost(folder, …)` (`state`, `running`, `start`, `grpc`, `adb`, `note_activity`), `parse_focus` | in emu-l1b |
| L9 | `agent_phone_channel.connect(adb, events=…)`, `PhoneVault.for_agent(...)` with `notify=`, `on_card_approved=`; `fill`/`code` with `pin_approved=`; `save_login`, `new_login`, `settle_signup`, `login_result`, `submit_outcome`, `open_link(session, opener, handle=, mail_packages=)`, `vault_list`; `agent_phone_redact.MaskSet`, `mask_capture` | in emu-l9 |
| L9 | `code(..., ask_owner=fn)`: when no viewer can answer Hermes' code prompt, `fn(app, label) -> code or None` replaces `notify('vault_code')`; the code goes through the same held path as the owner's typed code. L3's `fn` is `agent_phone_approvals.ask_owner_code` | Root asked L9 |
| L1a | the pointer `<process Hermes home>/plugin-data/beacon/agent-phone.json`: `folder` (the phone folder), `profile` (the owning profile); a start gate `agent_phone_checks.start_gate() -> (ok, line)` (sign-in on, dashboard readable; the 5037 line is a warning, not a stop) | Root: keep the names; L1a confirms |
| L2 | the lease: `agent_phone_lease.read(folder) -> {"holder": "agent" \| "human", "epoch": int}` (timeouts applied first; unreadable reads as human). Always the module, never `run/lease.json` directly (it is replaced under `run/lease.lock`) | L2 confirmed (msg_6b778f416fbc) |
| L8 | the helper's `clip_set` / `clip_clear` for non-ASCII text (channel contract §5.1); a `save` event only while a channel session is live (the plugin opens one before it taps Save) | in the contract |
| L2 (uses L3's) | `agent_phone_guard.debug_prompt(adb) -> 'usb' \| 'wifi' \| None` (the focused window is SystemUI's `.usb.UsbDebuggingActivity`, `.usb.UsbDebuggingSecondaryUserActivity` or `.wifi.WifiDebuggingActivity`, from `dumpsys window` `mCurrentFocus`), `deny(adb) -> bool` (Back while the prompt shows, up to 3 times), `answer(adb) -> kind or None` (RuntimeError when it stays); the setup view calls `answer` while the owner holds the phone | follow-up 1 |

`mail_packages` (L9's `code`, `open_link`, `hold_temporary_password`): the agent phone's `mailto:` handlers that are system apps
(`FLAG_SYSTEM`, updated ones included) or installed by the Play Store (`com.android.vending`), the same source rule as Chrome;
sideloaded apps never count (Root, msg_9a8ed075287e).

## 8. Never logged, never kept

No record, log line, result or error carries a secret, a URL from `open_link`, a token, a port, a path of the phone folder or a
screenshot. `answer` (a `vault_code` code) lives at most until the waiting tool reads it, and never past 120 s.
