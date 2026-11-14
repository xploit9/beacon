"""A saved card with no site, filled on the checkout page that is open, after the owner approves that payment on the phone.

Hermes' browser_vault fills a card only on the exact site it was saved for and refuses a card saved with no site. The owner's rule
(10 Nov 2026): a card is saved once, with no site, usable on any checkout page, and every single payment asks first, naming the site.
This tool adds only that missing site binding. Everything else is Hermes' own code: the approval (_confirm_payment_fill, which the
phone shows as "Confirm card payment" with the site), the in-page origin check right before the write (the page cannot move
between the approval and the write), the secret resolution, the redaction and the result shape. The card number, expiry and CVC never
enter the conversation, a log, a result or anything Beacon stores. Hermes is never patched.

These are private Hermes names, so compat.py reads them from Hermes' source at start, the contract test checks each one, and a Hermes
that moved one gets one clear line from the tool instead of a half-working fill.

shortcut: the page is the one the browser holds for this task; a checkout inside a cross-origin frame is what Hermes' fill can reach, no more.
"""
import json
import math
import os
import re
import threading
import time
from pathlib import Path
from types import SimpleNamespace

NAME = 'beacon_pay_with_card'
# The browser toolset: wherever the agent has Hermes' browser it has this, next to browser_vault. A plugin tool in a built-in toolset
# joins it (toolsets.get_toolset merges registry tools) and, not being a core tool, is deferred behind tool_search like browser_vault.
TOOLSET = 'browser'
UPDATE_LINE = 'Beacon needs an update to fill a saved card on this Hermes. Nothing was approved or filled.'
DESCRIPTION = ('Fill a saved card that has no saved site into the checkout page open in the browser. Use it for a card that browser_vault '
               'action=list shows with origin null and available false; browser_vault fills logins, addresses and cards saved for a site. '
               "Open the checkout page first, then pass the card's handle. The owner approves each payment on their phone, which names the "
               'site; if they decline, do not retry or ask again this turn. The card number, expiry and CVC never enter the conversation: '
               'never ask for, type or repeat them. This only fills the fields; it does not submit the order.')
SCHEMA = {'name': NAME, 'description': DESCRIPTION,
          'parameters': {'type': 'object', 'additionalProperties': False, 'required': ['handle'],
                         'properties': {'handle': {'type': 'string', 'minLength': 1, 'maxLength': 300,
                                                   'description': "The card's handle from browser_vault action=list."}}}}


DECLINED_LINE = ('The user did not confirm filling this payment card (declined, or nobody could answer). Nothing was filled. '
                 'Do not retry; ask them instead.')
# A decline stands for the task that got it, so an agent that ignores "do not retry" cannot keep asking the owner's phone. It is a minute,
# not longer: the same refusal also covers a prompt nobody answered, and an owner who missed it and then says "go ahead" must be askable again.
DECLINE_SECONDS = 60
WAIT_LINE = ('The user declined or missed this payment a moment ago. Nothing was filled. Ask them first; '
             'the phone can be asked again in {seconds} {unit}.')
_declined = {}  # task id -> the time its decline stops standing
_now = time.monotonic


# Which approval is a card payment. On the canonical session route Hermes publishes the consent as a prompt (gateway/session_pending_controls.py)
# that carries no `surface`; Hermes tells a plugin the surface only through the pre_approval_request hook, which fires after the prompt is queued
# and before it is published. The hook records Hermes' own card surface against the prompt's id, and canonical_transport.py adds it to that prompt
# on its way to the phone. Only Hermes' surface counts: text that merely reads like a card fill is an ordinary approval.
# The agent that announces the prompt (the gateway process) and the relay that forwards it (the dashboard process) are different processes, so the
# record is one small file per prompt in the plugin's own data folder, which both see; nothing else is shared and no secret is in it.
SURFACE = 'vault-payment'
SURFACE_SECONDS = 330  # a little past Hermes' own 300 s approval wait; the marker ends sooner when the prompt is answered
MAX_SURFACES = 64
_PROMPT_ID = re.compile(r'[0-9a-f]{32}')  # Hermes' request ids (uuid4 hex); nothing else may name a file here
_wall = time.time  # not _now: the record is read by another process, so it needs a clock they share
_surfaces_lock = threading.Lock()


def _store_dir():
    try:
        from hermes_constants import get_process_hermes_home
        return Path(get_process_hermes_home()) / 'plugin-data' / 'beacon' / 'card-surfaces'
    except Exception:
        return None


def _marked(folder):
    """Every marker in the folder: prompt id -> (surface, session key, time it stops standing)."""
    found = {}
    try:
        paths = list(folder.iterdir())
    except (OSError, AttributeError):
        return found
    for path in paths:
        if path.suffix != '.json' or not _PROMPT_ID.fullmatch(path.stem):
            continue
        try:
            entry = json.loads(path.read_text(encoding='utf-8'))
            found[path.stem] = (entry['surface'], entry['key'], float(entry['until']))
        except (OSError, ValueError, KeyError, TypeError):
            continue
    return found


def _drop(folder, prompt_id):
    try:
        (folder / f'{prompt_id}.json').unlink()
    except OSError:
        pass


def _prune(folder, now):
    live = _marked(folder)
    for prompt_id in [prompt_id for prompt_id, (_, _, until) in live.items() if until <= now]:
        _drop(folder, prompt_id)
        live.pop(prompt_id)
    for prompt_id in sorted(live, key=lambda prompt_id: live[prompt_id][2])[:max(0, len(live) - MAX_SURFACES)]:
        _drop(folder, prompt_id)


def surface_for(prompt_id):
    """The surface Hermes announced for this prompt id, or None. Read by the relay for every approval prompt it forwards."""
    if not isinstance(prompt_id, str) or not _PROMPT_ID.fullmatch(prompt_id):
        return None
    folder = _store_dir()
    marked = _marked(folder).get(prompt_id)
    if marked is None:
        return None
    if marked[2] <= _wall():
        _drop(folder, prompt_id)  # it ended with Hermes' wait: gone as soon as it is seen
        return None
    return marked[0]


def has_surfaces():
    folder, now, live = _store_dir(), _wall(), False
    for prompt_id, (_, _, until) in _marked(folder).items():
        if until > now:
            live = True
        else:
            _drop(folder, prompt_id)
    return live


def mark_payment_prompt(**announced):
    """pre_approval_request: remember which queued prompt is Hermes' card consent. Never raises: an approval must not depend on this."""
    try:
        key, command, folder = announced.get('session_key'), announced.get('command'), _store_dir()
        if announced.get('surface') != SURFACE or not isinstance(key, str) or not key or not isinstance(command, str) or folder is None:
            return
        from tools.approval import list_gateway_approvals
        queued = list_gateway_approvals(key)
        with _surfaces_lock:
            folder.mkdir(parents=True, exist_ok=True)
            now, taken = _wall(), _marked(folder)
            # The prompt just queued is the newest one with this text that no marker claims yet (Hermes folds an identical concurrent prompt into the first).
            for waiting in reversed(queued):
                prompt_id = waiting.get('request_id')
                if waiting.get('command') != command or not isinstance(prompt_id, str) or not _PROMPT_ID.fullmatch(prompt_id):
                    continue
                if prompt_id in taken and taken[prompt_id][2] > now:
                    continue
                part = folder / f'{prompt_id}.part-{os.getpid()}'
                part.write_text(json.dumps({'surface': SURFACE, 'key': key, 'until': now + SURFACE_SECONDS}), encoding='utf-8')
                os.replace(part, folder / f'{prompt_id}.json')
                _prune(folder, now)
                return
    except Exception:
        return


def forget_answered_prompts(**answered):
    """post_approval_response: drop the markers of this session whose prompt Hermes no longer holds."""
    try:
        key, folder = answered.get('session_key'), _store_dir()
        if not isinstance(key, str) or not key or folder is None:
            return
        from tools.approval import list_gateway_approvals
        waiting = {entry.get('request_id') for entry in list_gateway_approvals(key)}
        with _surfaces_lock:
            for prompt_id, (_, owner, _) in _marked(folder).items():
                if owner == key and prompt_id not in waiting:
                    _drop(folder, prompt_id)
    except Exception:
        return


def _say(error, kind):
    return json.dumps({'success': False, 'error_type': kind, 'error': error})


def _hermes():
    """Hermes' own pieces as they are now, or None when this Hermes no longer has one of them. Imported by name at each call so a
    Hermes that moves one is seen at once; compat.py watches these same imports."""
    try:
        from agent.vault_backends import UnlockRequired, backend_for_handle
        from tools.browser_vault_context import plugin_errors_as_refusal, saved_origins
        from tools.browser_vault_tool import (_confirm_payment_fill, _current_page_origin, _fenced_page_op, _fill_bound_page,
                                              _focus_bound_origin, browser_vault_unlock)
    except Exception:
        return None
    return SimpleNamespace(UnlockRequired=UnlockRequired, backend_for_handle=backend_for_handle, plugin_errors=plugin_errors_as_refusal,
                           saved_origins=saved_origins, confirm=_confirm_payment_fill, page_origin=_current_page_origin,
                           fenced=_fenced_page_op, fill=_fill_bound_page, focus=_focus_bound_origin, unlock=browser_vault_unlock)


def _pay(hermes, handle, task_id):
    """The browser_vault fill, with the site binding replaced by the owner's approval of this page. Returns the JSON text."""
    backend = hermes.backend_for_handle(handle)
    if backend is not None and backend.needs_unlock and not backend.is_unlocked():
        unlocked = json.loads(hermes.unlock(backend.name))
        if not unlocked.get('success'):
            return json.dumps(unlocked)
    try:
        meta = backend.get_meta(handle) if backend is not None else None
    except hermes.UnlockRequired:
        return _say(f'{backend.display_name} locked again; call browser_vault action=unlock.', 'unlock_required')
    if meta is None:
        return _say(f'No vault item with handle {handle[:80]!r}. Use browser_vault action=list.', 'not_found')
    if meta.kind != 'payment':
        return _say(f'That vault item is a {str(meta.kind)[:20]}, not a card. Use browser_vault for logins and addresses.', 'not_a_card')
    sites = hermes.saved_origins(meta)
    if sites:
        return _say(f"This card is saved for {', '.join(sites)}. Use browser_vault action=fill for it; this tool is only for a card saved with no site.",
                    'site_bound')
    # The tab that holds a card form becomes the page (as browser_vault save_login focuses its login tab), then its origin is read.
    hermes.focus(task_id, '', 'payment')
    origin = hermes.page_origin(task_id)
    if not origin:
        return _say('Could not determine the current page origin. Open the checkout page first.', 'no_page')
    left = _declined.get(task_id, 0) - _now()
    if left > 0:  # the owner already said no (or missed it) in this task a moment ago: the phone is not asked again yet
        seconds = max(1, math.ceil(left))
        return _say(WAIT_LINE.format(seconds=seconds, unit='second' if seconds == 1 else 'seconds'), 'payment_declined')
    if not hermes.confirm(meta.label, origin):
        now = _now()
        for task in [task for task, until in _declined.items() if until <= now]:
            _declined.pop(task, None)
        _declined[task_id] = now + DECLINE_SECONDS
        return _say(DECLINED_LINE, 'payment_declined')
    with backend.fill_context():
        # No saved site to re-check, so the item is read again here: it must still be this card, still without a site.
        fresh = backend.get_meta(handle)
        if fresh is None or fresh.kind != 'payment' or hermes.saved_origins(fresh):
            return _say('Refused: the saved card changed before the fill. Nothing was resolved or written.', 'item_changed')
        return hermes.fill(backend, handle, fresh, origin, task_id)


def pay_with_card(args, **kwargs):
    handle = str(args.get('handle') or '').strip() if isinstance(args, dict) else ''
    if not handle:
        return _say('Give the handle of a saved card: browser_vault action=list shows it.', 'no_handle')
    hermes = _hermes()
    if hermes is None:
        return _say(UPDATE_LINE, 'hermes_update_needed')
    task_id = kwargs.get('task_id') or 'default'
    try:
        return hermes.fenced(task_id, lambda: hermes.plugin_errors(_pay)(hermes, handle, task_id))
    except Exception as exc:  # health: allow BLE001 -- any failure becomes one fixed line; its text may quote the card and is never passed on
        return _say(f'The card could not be filled ({type(exc).__name__}). Check the page before trying again.', 'card_failed')


def available():
    """Listed exactly where Hermes lists browser_vault (a browser is there). A Hermes that lost the check still lists the tool, which then says why it cannot run."""
    try:
        from tools.browser_vault_tool import _check_vault_available
    except Exception:
        return True
    try:
        return bool(_check_vault_available())
    except Exception:
        return False


def register(ctx):
    try:
        from tools.registry import no_cache_check_fn
        no_cache_check_fn(available)  # a browser can appear or go away between turns, as for browser_vault
    except Exception:
        pass
    ctx.register_tool(name=NAME, toolset=TOOLSET, description=DESCRIPTION, schema=SCHEMA, handler=pay_with_card, check_fn=available)
    register_hook = getattr(ctx, 'register_hook', None)
    if callable(register_hook):
        try:
            register_hook('pre_approval_request', mark_payment_prompt)
            register_hook('post_approval_response', forget_answered_prompts)
        except Exception:
            pass  # without the markers a card payment is shown as an ordinary approval, never dropped
