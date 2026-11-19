# Agent phone vault channel, version 1

Contract revision 5 (17 Nov 2026). No change to the messages between the host and the helper. It adds §11: what the app is told.
Every line a phone screen can show comes with a stable `reason` code (and its `args`), so the app can show its own translated words.
The view's `state.keyboard` says `"password"` when the focused field on the agent phone is a password field. The protocol version
stays 1.

Revision 4 (17 Nov 2026). Changes from revision 3: `code_filled` names the fields the code went into in `targets`
(id, view id, screen bounds; never the value), so the host can mask a code it never sees in its own captures (§5.2, §6.4).
`targets` may be absent: a helper built before revision 4 does not send it. The protocol version stays 1.

Revision 3 (17 Nov 2026). Changes from revision 2: Android 16 blocks a background app from starting an activity, so
the helper no longer opens links: `open_link` is answered with `error` `unavailable`, `note_link` hands the site's link to the
host, and the host opens it on the phone through adb after checking it (§5.1, §5.2, §6.6). An out-of-range value closes the
link like any malformed field, as the vectors always did (§6.7). The protocol version stays 1.

Revision 2 (17 Nov 2026). Changes from revision 1: an `arm` carries a list of `items`, one dropdown entry each, and
`release_request` names the `item_id` that was picked (§5.1, §5.2, §6.2); the fill context says where the app came from in
`source` (§6.1). The protocol version stays 1: no helper had shipped revision 1.

The contract between Beacon's plugin (the **host**) and the Beacon helper app on the agent phone (the **helper**,
`dev.hermes.agentphone.helper`). It carries fill contexts, armed fills, released values, saves, codes and paste text.
Design: EMU-DESIGN revision 4, §6.6. The host end is `hermes-plugin/beacon/agent_phone_channel.py`; the helper end is
`beacon-agentphone-helper/`. Both are checked against the test vectors in
`hermes-plugin/beacon/tests/fixtures/agent_phone_channel_v1_vectors.json`.

Words used below: **T** is the session token, **Nc** and **Ns** the two nonces, `||` joins byte strings, `u32`/`u64` are
unsigned big-endian integers, hex is lowercase.

## 1. Session start

1. The host makes **T**: 32 bytes from a cryptographically secure generator, new for every session.
2. The host opens a TCP listener on `127.0.0.1`, port 0 (the system picks a free port **P**).
3. The host runs one command through the phone's private adb server:

   ```
   am start-foreground-service -n dev.hermes.agentphone.helper/.ChannelService --ei port <P> --es token <T as base64url, no padding>
   ```

   `ChannelService` is exported with `android:permission="android.permission.DUMP"`, so only the shell and the system can
   start it. This is the only way **T** reaches the helper; it never crosses the network. The host never logs this command line.
4. The helper checks the intent: `port` in 1024-65535 and `token` decoding to exactly 32 bytes; otherwise it ignores the intent.
   - While the helper has a live link (§3), it ignores every new start intent. A start intent never replaces the token of a
     live link and never closes it.
   - Without a live link, a new valid intent replaces any earlier intent whose dial or handshake has not finished.
   - Each token is used for one link only. When that link ends, the helper forgets the token.
5. The helper dials `10.0.2.2:<P>` and nothing else (the emulator's alias for the host's loopback; the host sees `127.0.0.1`).
   It tries at most 3 times, 500 ms apart, then forgets the token.

## 2. Frames

Every unit on the wire is a **frame**: `u32 length || body` (`length` bytes).

- **Before the proof** only the three handshake frames are allowed, each with a fixed length (CH 37, SH 69, CP 32). A
  receiver reads the 4 length bytes, closes unless the length is the one it expects, then reads exactly that many bytes.
- **After the proof** every frame is sealed (§4): `25 <= length <= 262144`.

## 3. Handshake (token proof, both ways)

| Frame | From | Body | Length |
|---|---|---|---|
| CH | helper | `"BAPC" \|\| 0x01 \|\| Nc` (Nc: 32 random bytes) | 37 |
| SH | host | `"BAPC" \|\| 0x01 \|\| Ns \|\| Ps` (Ns: 32 random bytes) | 69 |
| CP | helper | `Pc` | 32 |

`"BAPC"` is the bytes `42 41 50 43`; `0x01` is the handshake version, fixed for this contract. Let `SH0` be the first 37 bytes
of SH (`"BAPC" || 0x01 || Ns`).

```
Ka    = HKDF-SHA256(IKM = T, salt = (none), info = "beacon-agent-phone-channel/1 auth", L = 32)
Ps    = HMAC-SHA256(Ka, "beacon-agent-phone-channel/1 host proof"   || CH || SH0)
Pc    = HMAC-SHA256(Ka, "beacon-agent-phone-channel/1 helper proof" || CH || SH0 || Ps)
```

HKDF is RFC 5869; "salt = (none)" means 32 zero bytes, as RFC 5869 says. Labels are their ASCII bytes, no terminator.

- The host, on CH: closes unless the magic is `BAPC` and the version byte is `0x01`. A version byte arrives before any proof,
  so the host never acts on it beyond that check; protocol versions are agreed inside the seal (§7).
- The helper, on SH: same magic and version check, then compares Ps in constant time. A mismatch closes the connection and the
  helper forgets the token (something other than Beacon answered on that port).
- The host, on CP: compares Pc in constant time. A mismatch closes that connection only.
- A connection that has not sent a valid CP 5 s after the host accepted it is closed.
- The host handles connections concurrently: a connection that sends nothing, or junk, never delays another connection's
  handshake. At most 64 connections may be in the handshake at once; more are closed on accept.
- The first connection that passes CP becomes the session's **link**. The host then closes its listener (no more connections
  for this session). Only the link can carry sealed frames; an unauthenticated connection can never set, replace or clear it.

## 4. Sealed frames

Per-link keys, one per direction:

```
salt   = Nc || Ns                                   (64 bytes)
K_h2c  = HKDF-SHA256(IKM = T, salt, info = "beacon-agent-phone-channel/1 host->helper", L = 32)
K_c2h  = HKDF-SHA256(IKM = T, salt, info = "beacon-agent-phone-channel/1 helper->host", L = 32)
```

A sealed frame's body is `seq || C`:

```
seq    = u64, 0 for the first sealed frame in each direction, then +1 per frame
nonce  = 00 00 00 00 || seq                          (12 bytes)
aad    = "BAPC" || 0x01 || dir || seq                (14 bytes; dir 0x01 host->helper, 0x02 helper->host)
C      = AES-256-GCM-Encrypt(key = K_h2c or K_c2h, nonce, plaintext, aad)   ciphertext || 16-byte tag
```

The receiver closes the link when the length is out of range, `seq` is not exactly the next one it expects (a replayed,
skipped or reordered frame), the tag does not verify, or the plaintext is not what §5 allows. Nothing is sent back first.

## 5. Messages

A plaintext is one JSON object (RFC 8259) in UTF-8, no byte-order mark, at most 262,000 bytes, with a string `t` (the type).
Fields this contract does not list are ignored. An unknown `t`, a `t` sent in the wrong direction or before `hello`, a reply
whose `re` matches no open request, or a missing or mistyped listed field closes the link.

- **Requests** come from the host and carry `id`: an integer, 1 the first time, +1 per request.
- **Replies** carry `re` = the request's `id`; the helper sends exactly one reply per request, within 10 s.
- **Events** come from the helper unasked and carry neither.

### 5.1 Host to helper

| `t` | Fields | Reply | What the helper does |
|---|---|---|---|
| `hello_ok` | `proto` (int) | none | The version both sides use from now on (§7). Sent once, right after the helper's `hello`. |
| `ping` | `id` | `pong` | Nothing else. The host pings every 30 s during a setup session. |
| `context` | `id` | `context_result` | Returns the latest fill context (§6.1), or null. |
| `arm` | `id`, `arm_id`, `kind`, `package`, `web_domain` (string or null), `items` (list of `{item_id, label, roles}`), `ttl_ms` | `ack` | Arms one fill with one dropdown entry per item (§6.2). Replaces any earlier arm. |
| `disarm` | `id`, `arm_id` (string or null: any) | `ack` | Drops the arm. |
| `release` | `req`, `values` (object: role → string) | none | Answers a `release_request`: fills these values, then forgets them. |
| `deny` | `req` | none | Answers a `release_request`: fills nothing. |
| `arm_code` | `id`, `arm_id`, `package`, `label`, `senders` (§6.4), `max_age_ms`, `ttl_ms` | `code_result` | Looks for a code in recent notifications (§6.4); if found, arms a fill of the `otp` field in `package` with it. The code stays in the helper. |
| `note_password` | `id`, `senders`, `max_age_ms` | `note_password_result` | Looks for a temporary password in recent notifications (§6.5). |
| `note_link` | `id`, `senders`, `max_age_ms` | `note_link_result` | Finds the site's sign-in link in a recent notification and gives it to the host (§6.6). |
| `open_link` | `id`, `senders`, `max_age_ms` | `error` | Always answered with `error` `unavailable`: the helper cannot start an activity from the background on Android 16 (§6.6). |
| `clip_set` | `id`, `text`, `ttl_ms` | `ack` | Puts `text` on the phone's clipboard, marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) and without SystemUI's clipboard chip (`com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY`, honoured on an emulator); clears it after `ttl_ms` if no `clip_clear` came. |
| `clip_clear` | `id` | `ack` | Clears the clipboard if it still holds the helper's clip. |
| `bye` | `reason` | none | Ends the session (§8). |

### 5.2 Helper to host

| `t` | Kind | Fields | Meaning |
|---|---|---|---|
| `hello` | event | `proto_min`, `proto_max` (ints), `helper_version` (string), `version_code` (int) | First sealed frame from the helper, sent at once after CP. |
| `ack` | reply | `re` | Done. |
| `error` | reply | `re`, `code` | Could not do it; `code` is one of `bad_request`, `not_armed`, `busy`, `no_clipboard`, `unavailable`. |
| `pong` | reply | `re` | Alive. |
| `context_result` | reply | `re`, `context` (object or null) | §6.1. |
| `code_result` | reply | `re`, `found` (bool) | Whether a matching code was found and armed. Never the code. |
| `note_password_result` | reply | `re`, `found` (bool), `value` (string, only when found) | §6.5. |
| `note_link_result` | reply | `re`, `found` (bool), `url` (string, only when found) | §6.6. |
| `fill_context` | event | `context` | Sent on every Android fill request, before any dataset is offered. |
| `release_request` | event | `req`, `arm_id`, `item_id`, `roles` (list), `context` | The owner or the agent picked one of Beacon's entries (`item_id`); the host answers with `release` or `deny` within 8 s. `context` is this request's own, read fresh. |
| `filled` | event | `req`, `arm_id`, `fields` (int) | The values were handed to Android; how many fields. |
| `fill_failed` | event | `req`, `arm_id`, `reason` | `denied`, `timeout` or `cancelled`. |
| `code_filled` | event | `arm_id`, `fields` (int), `targets` (list or absent) | A code armed by `arm_code` was filled; `targets` names the fields (§6.4). |
| `save` | event | `save_id`, `context`, `values` (object: `username`, `password`, `new_password`, each optional) | Android's save request (the person tapped Save). Its `context` is read from the save request itself. |
| `bye` | event | `reason` | §8. |

## 6. Rules

### 6.1 Fill context (never field text)

```
{"package": "<app package>", "certs": ["<sha-256 hex of each signing certificate>", ...],
 "web_domain": "<domain Android reports>" | null, "web_scheme": "<scheme Android reports>" | null,
 "fields": [{"id": "<AutofillId as a string>", "role": "<role>", "focused": true|false}, ...], "at_ms": <helper clock>,
 "source": "system" | "play" | "other"}
```

`certs` are the SHA-256 of each `Signature` from `PackageManager.GET_SIGNING_CERTIFICATES` (the APK signers, or the signing
history when there is one signer), lowercase hex, sorted. Fields with no role are left out. A context carries no field text,
no hints and no values.

`source` says where the app in front came from: `system` when its `ApplicationInfo` has `FLAG_SYSTEM` (also an updated
system app), else `play` when `PackageManager.getInstallSourceInfo(package).getInstallingPackageName()` is
`com.android.vending`, else `other` (sideloaded, unknown, or the lookup failed). The host trusts a browser only when it is
`system` or `play`.

### 6.2 Roles and arms

Roles are a closed list. The names of the card and address roles are the field names of Hermes' vault
(`PAYMENT_FIELDS`, `ADDRESS_FIELDS` in `agent/vault_store.py`), so the host maps nothing.

| Role | Value the host sends | Where the helper finds the field (Android `View.AUTOFILL_HINT_*`, then HTML `autocomplete`, then input type) |
|---|---|---|
| `username` | the login's identifier | `username`, `emailAddress`; `username`, `email`; `type=email` |
| `password` | the password | `password`; `current-password`; text/web password input types |
| `new_password` | a password the host generated | androidx `HintConstants` new-password hint (read from the library source); `new-password` |
| `otp` | a one-time code | androidx one-time-code hints; `one-time-code` |
| `card_number` | card number, digits | `creditCardNumber`; `cc-number` |
| `cardholder_name` | name on the card | `cc-name` |
| `exp_date` | `MM/YY` | `creditCardExpirationDate`; `cc-exp` |
| `exp_month` | `MM` | `creditCardExpirationMonth`; `cc-exp-month` |
| `exp_year` | `YYYY`; the helper fills the last two digits when the field takes at most 2 characters | `creditCardExpirationYear`; `cc-exp-year` |
| `cvc` | security code | `creditCardSecurityCode`; `cc-csc` |
| `billing_postal_code` | postcode in a card form | `postalCode` / `postal-code` inside a form that has a card field |
| `address_line1`, `address_line2`, `city`, `state`, `postal_code`, `country` | the address fields | `postalAddress`, `postalCode`; `address-line1`, `address-line2`, `address-level2`, `address-level1`, `postal-code`, `country-name` |

Kinds and the roles an arm may offer: `login` (`username`, `password`, `otp`), `new_login` (`username`, `new_password`),
`payment` (the card roles), `address` (the address roles), `otp` (`otp`).

- `arm_id`, `item_id` and `req`: 16-64 characters from `A-Z a-z 0-9 _ -`. Each item's `label`: 1-120 characters, shown as
  `Beacon · <label>`. `items`: 1-16, `item_id`s distinct within the arm, each item's `roles` within the arm's `kind`.
- `ttl_ms`: 1-20000 for `arm` and `arm_code`; 1-10000 for `clip_set`. `max_age_ms`: 1-600000.
- One arm at a time. An arm matches an Android fill request only while it is unexpired, the request's package equals
  `package`, and `web_domain` is null or equals the request's web domain.
- The helper offers one dataset per item, in the order given, each behind dataset authentication, for the fields whose role
  is in that item's `roles` (an item with no such field on the screen gets no entry). The owner in setup sees every account
  that fits the screen and picks one; the agent arms the one item it chose by handle. When an entry is picked the helper
  sends `release_request` with that `item_id` and the roles it fills (with a new `req`) and waits up to 8 s. One
  `release_request` per arm: the arm, with all its items, is spent when it is sent. A `release` is used once; only the
  roles in the request are filled; the values are dropped right after Android takes them.
- The host decides a release from the `release_request`'s own `context` (package, certificates, web domain and scheme,
  focused field), never from an earlier one. The focused field must be one of the arm's own form fields that the item
  fills, compared by the AutofillId's view part (before `@`): Android renumbers the `@<session>` part when an autofill
  session ends (the host resets autofill after each arm so the next tap asks the helper again), and it answers from the
  fill request of whichever field of the form gained focus first.
### 6.3 Saves

The helper adds `SaveInfo` for forms with a password or new-password field. On Android's save request it sends `save`
with the identifier and password typed into that form and a fresh `context`. The host builds the item's origin from that
context: `android://<package>` for an app, `<web_scheme>://<web_domain>` for a page; a page without a scheme is refused,
never guessed. The host keeps a save in memory until the owner decides (EMU-DESIGN §6.4).

### 6.4 Codes from notifications (`arm_code`)

`senders` is an object: `packages` (list of app packages), `hosts` (list of host names), `mail_packages` (list of mail-app
packages). The helper keeps the title and text of each notification in memory only: at most 20, at most 10 minutes.

A notification matches when all of these hold:
1. It was posted at most `max_age_ms` ago.
2. Its title + `" "` + text contains a code word (case-insensitive): `code`, `otp`, `verification`, `verify`, `passcode`, `رمز`.
3. It holds a code: the first run of 4-8 ASCII digits with no digit right before or after it.
4. Its sender is the site's:
   - (a) it was posted by a package in `packages`; or
   - (b) it was posted by a package in `mail_packages`, and the first e-mail address in its title + `" "` + text has a domain
     (lowercased, one trailing dot removed) that equals a name in `hosts` or ends with `"."` + a name in `hosts`. The address is
     the first match of `[A-Za-z0-9._%+-]+` `@` `([A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+\.?)`. A domain with any other character
     (a look-alike in another script) never matches.

The newest matching notification wins. `hosts` comes from the item's own origin: its host and, when that host starts with
`www.`, the same host without `www.`. The host never sends a name with fewer than two labels. This is stricter than a
registrable-domain match on purpose: it needs no public-suffix list, and a mail from another part of the same company falls
back to asking the owner.

"Posted" is the notification's own post time (`StatusBarNotification.getPostTime()`). The host sets `max_age_ms` to the time
since the sign-in step that led to the code screen (its own last fill in that app or site, the last code it filled there, or
the agent's submit), so a code that came before that step never fills and the newest one after it wins. With no such step
the host sends no `arm_code` and asks the owner.

The code stays in the helper, so the host has no value to mask in what the agent sees. `code_filled` therefore carries
`targets`: at most 16 objects, one per field the code went into, each with exactly `id` (the field's AutofillId as a string,
as in §6.1), `view_id` (its view id as a screen dump shows it: `package:id/name`, or a web field's own id; null when it has
none) and `bounds` (`[left, top, right, bottom]`, integers from -100000 to 100000, the field's place on the screen in pixels;
null when unknown). Any other key, or a value, closes the link. The host masks those fields' text in its screen captures
and screen dumps until the field's text changes or the screen goes. When `targets` is absent or a field can't be found on
the screen, it masks every text field of that app's screen instead.

### 6.5 Temporary passwords (`note_password`)

The same sender rule (6.4, step 4) and age rule apply. In the newest matching notification, after the first password word
(case-insensitive: `temporary password`, `password`, `كلمة المرور`, `كلمة السر`), skip spaces and at most one of `:` or
`is`, then take the run of 6-64 characters up to the next whitespace, without one trailing `.` `,` or `;`. The value goes to
the host, which holds it in memory until the owner decides; it is never kept on the phone.

### 6.6 Links (`note_link`)

The same sender and age rules. In the newest matching notification the helper takes the first `https://` address whose
host equals a name in `hosts` or ends with `"."` + one, and returns it in `note_link_result` (`found: false` when there is
none; the helper never sends another address). The host checks it again (`https`, the same host rule) and opens it on the
phone with `am start -a android.intent.action.VIEW -d <url>` through the private adb server. The address never reaches
the model, a log or a result. `open_link` is answered with `error` `unavailable`: Android 16 blocks a background app from
starting an activity ("Background activity launch blocked").

### 6.7 Limits

`text` 1-10,000 Unicode code points; each released value at most 4,096 characters; `items` and each `roles` at most 16; `senders` lists at
most 16 names each; a `url` at most 2,048 characters. A value out of range closes the link like any malformed field (§5).
`bad_request` is for a well-formed request the helper cannot apply on this phone.

## 7. Versions

The handshake (§2-4) is fixed for this contract. Protocol versions are agreed inside the seal: the helper's `hello` says
`proto_min` and `proto_max`; the host answers `hello_ok` with the highest version both support, or sends `bye` with reason
`version` when there is none, and then reinstalls the helper from its own release. This contract is protocol version **1**.

## 8. End of a session

- `bye` reasons: `done`, `version`, `idle`, `error`. After sending `bye` the sender closes the link.
- When the link closes for any reason, both sides forget T and both keys; the helper drops its arm, any pending release and
  any value it holds, and clears the clipboard if it still holds the helper's clip; the host fails every open request.
- The host sends `bye` (`idle`) after 20 minutes without a frame either way, and closes the link 10 s after a request that got
  no reply.

## 9. Never logged, never kept

Neither side writes T, Ka, the direction keys, a plaintext, a value, a code, an address from a notification or notification
text to a log, a file or a result. Logs carry types, counts and labels only.

## 10. What this does not cover (stated)

- An app on the agent phone can connect to the host's port while a session is open and use up handshake slots (§3); it cannot
  get a value or touch the link, but it can delay a fill. The helper's start command runs through the private adb server,
  which a phone app can reach while that command runs (EMU-DESIGN §11.12).
- A forged start intent can only make the helper send one CH to a loopback port; nothing else leaves it without the host's
  proof (gate B7).

## 11. What the app is told (revision 5): reason codes and the keyboard field

The host's routes (`/api/plugins/beacon/mobile/agent-phone/...`) and the setup view's socket (`beacon-agent-phone-v1`) are the
app's side of this phone. Revision 5 adds two things to them. Nothing is removed: every field an app read before is still sent.

### 11.1 Reason codes

Every line the host sends for a phone screen carries a stable `reason`: a code from the table below, or null when the host has no
code for it (an unexpected error). When the line names something, `args` holds those names as strings. `line` stays the English
fallback, with `{name}` left unfilled as before (the app fills in the assistant's chosen name; `{name}` is never in `args`).

The app shows its own words for a code it knows: its template for that code, translated into the phone's language, with `{name}`
and each `{arg}` filled in. It shows `line` only for an unknown code or a null one. Codes are stable: a change of wording keeps the
code, and a code is never reused for another meaning.

| Where | Line field | Code fields |
|---|---|---|
| `GET /mobile/agent-phone` | `warning` | `warning_reason` |
| | `error` | `error_reason`, `error_args` |
| | `helper_line` | `helper_reason` |
| | `install.line`, `install.fix` | `install.reason`, `install.args`, `install.fix_reason` |
| `POST /mobile/agent-phone/start`, 409 | `line` | `reason` |
| `POST /mobile/agent-phone/checks`, each row | `line`, `fix` | `reason`, `args`, `fix_reason` |
| `GET /mobile/agent-phone/terms`, 502 | `line`, `fix` | `reason`, `fix_reason` |
| `GET /mobile/agent-phone/install` | `line`, `fix` | `reason`, `args`, `fix_reason` |
| `POST /mobile/agent-phone/helper/update`, 409 | `line` | `reason` |
| a 409 that refuses an install step or a Remove | `detail` (as before) and `line` | `reason` |
| any route's 403, 404 or 503 that names the phone's or Hermes' state | `detail` (as before) and `line` | `reason` |
| the view's `{"t":"notice"}` | `line` | `reason`, `args` |
| an approval record's payload (approvals-v1) | `line` | `reason`; the names are the record's own `label`, `identifier`, `site` |

A row's `how` is a link, not a line, and gets no code. A saved login's activity row (approvals-v1, `action` `save`, `outcome`
`saved`) carries the login's identifier as its `label`, not an English line; the app words it from its action and outcome. The view's `bye` and `refused` messages and the `agent_phone_done` push
already carry only codes (`reason`), and are unchanged. A refusal no screen shows as a line (a 400 for an invalid request, a 401,
a 403 for a removed or unknown device, a 404 for an unknown approval, a 409 for an expired or settled one) stays FastAPI's own
`{"detail": ...}`; the app words those by their status.

| Code | English template |
|---|---|
| `check.cannot_run` | This computer can't run a virtual phone. |
| `check.unsupported` | There is no phone software for this kind of computer. |
| `check.container` | Give the container read and write access to /dev/kvm, or run Hermes on a computer instead. |
| `check.virt_windows` | Turn on Windows Hypervisor Platform in Windows Features, then restart the computer. |
| `check.virt_linux` | Turn on virtualisation (KVM) on this computer. |
| `check.virt_mac` | This Mac can't run it. Use another computer for Hermes. |
| `check.virt_unknown` | Checked again with the test download. |
| `check.memory_low` | The computer is low on memory. Close something there and try again. |
| `check.memory_small` | This computer has less than 16 GB memory, so the phone may be slow. |
| `check.memory_unknown` | Couldn't read the computer's memory. |
| `check.disk_low` | Not enough free space. Free up {n} GB on the Hermes computer. |
| `check.disk_tight` | Space is tight: about {n} GB free. |
| `check.network` | The Hermes computer can't reach Google's download site. |
| `check.network_fix` | Check the Hermes computer's internet. |
| `check.ports` | Close other virtual phones on this computer, then try again. |
| `check.sign_in_off` | Turn on Hermes sign-in first. |
| `check.sign_in_fix` | Give the Hermes dashboard a sign-in and the address you open it at, then restart Hermes. |
| `check.sign_in_unread` | Couldn't check Hermes sign-in. |
| `check.sign_in_unread_fix` | Make sure Hermes' dashboard is on, then check again. |
| `check.dashboard_off` | {name}'s phone can't start while Hermes' dashboard is off. |
| `check.developer_tools` | Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to. |
| `install.checks` | Check this computer first. |
| `install.licences` | The phone uses Google's Android software. Read and accept to continue. |
| `install.offline` | Download stopped. Check the Hermes computer's internet. |
| `install.damaged` | A download was damaged. Getting it again. |
| `install.space` | Not enough free space. Free up {n} GB on the Hermes computer. |
| `install.cannot_run` | This computer can't run a virtual phone. |
| `install.update` | Beacon needs an update to get the phone. Update Beacon, then try again. |
| `install.busy` | The phone is already being set up. |
| `install.ports` | Close other virtual phones on this computer, then try again. |
| `install.failed` | Something went wrong. |
| `install.terms_first` | Read and accept the terms first. |
| `install.nothing_to_resume` | Nothing to resume. |
| `fix.windows` | Turn on Windows Hypervisor Platform in Windows Features, then restart the computer. |
| `fix.linux` | Turn on virtualisation (KVM) on this computer. |
| `fix.macosx` | This Mac can't run it. Use another computer for Hermes. |
| `fix.unsupported` | There is no phone software for this kind of computer. |
| `fix.broken` | The phone software doesn't run here. Check the computer's updates, then try again. |
| `helper.no_pin` | Saved logins on {name}'s phone need a Beacon update. |
| `helper.not_fetched` | Saved logins on {name}'s phone need a download. Turn the phone on while online. |
| `helper.none` | This Beacon has no helper to install. |
| `helper.damaged` | Beacon's helper file is damaged. Update Beacon. |
| `helper.failed` | The helper didn't install. Try again. |
| `phone.not_started` | The phone didn't start. |
| `phone.no_keeper` | The phone can't start on this Hermes. Update Beacon. |
| `phone.process` | The phone's process could not start. |
| `phone.process_job` | The phone's process could not start outside Hermes' job. |
| `phone.control` | The phone's control line is not ready. |
| `phone.helper_damaged` | The phone's helper file is damaged. |
| `phone.ports` | The phone's ports are in use. |
| `phone.busy` | The phone is busy. Try again in a minute. |
| `phone.not_set_up` | The phone is not set up yet. |
| `phone.turn_on_first` | Turn the phone on first. |
| `phone.not_its_folder` | That isn't {name}'s phone folder. |
| `phone.needs_update` | {name}'s phone needs a Beacon update. |
| `phone.none` | {name}'s phone isn't set up yet. |
| `phone.other_profile` | {name}'s phone belongs to another profile. |
| `hermes.busy` | Busy, try again. |
| `hermes.updating` | Update in progress |
| `vault.saved` | Saved: {label} · {identifier} |
| `vault.saved_label` | Saved: {label} |
| `vault.saved_no_username` | Couldn't save that login: the app didn't give its username. |
| `vault.sign_in` | Sign in to {site} on the phone. |
| `vault.login_failed` | The {label} login didn't work. |
| `vault.code` | Enter the code for {label}. |
| `vault.replace` | Replace the saved {label} login? |
| `vault.save_new` | Save the new {label} login? |

`args` names: `n` (a whole number), `label` (a saved login's label), `identifier` (its username or address), `site` (a web
address or an app's name). The host's module that makes each line (`agent_phone_checks`, `agent_phone_install`,
`agent_phone_host`, `agent_phone_api`, `agent_phone_vault`) holds its code beside it; a test checks that every line those routes
can send has its code, and that the app's templates are the same table.

### 11.2 The keyboard field

The view's `{"t":"state", ...}` keeps `keyboard`, which now has three values: `false` (no keyboard on the agent phone), `true` (a
text field has focus) and `"password"` (the focused field is a password field). An app treats `true` and `"password"` alike
for showing its text row, and opens the row hidden, offering the owner's password manager, on `"password"`.

The host decides `"password"` from Android's own description of the focused field, read only while the keyboard is up: the input
type Android reports for the field the keyboard serves (`dumpsys input_method`, `curEditorInfo`). A text field with the password
or web password variation (the helper's own rule for a password field) or a number field with the password variation (a PIN)
is `"password"`; a visible password stays `true`, since the app shows that text on purpose. Never from any text on the screen.
When the type can't be read, it says `true`.
