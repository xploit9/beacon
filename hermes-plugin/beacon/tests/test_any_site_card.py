"""A saved card with no site, used on any checkout page after the owner approves that payment on the phone.

Hermes' own vault store, classifier and fill run for real; the page, the approval and the browser supervisor are fakes.
The card numbers here are synthetic test numbers.
"""
import ast
import importlib.util
import json
import logging
import os
import re
import sys
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace

import pytest
from test_mobile import harness, load_private  # noqa: F401  (fixtures)

PLUGIN = Path(__file__).resolve().parents[1]
HERMES_SOURCE = os.environ.get('HERMES_AGENT_SOURCE', '')
NUMBER, CODE, YEAR = '4242424242424242', '737', '2099'
SECRETS = (NUMBER, CODE)
SITE = 'https://shop.example'
CONTROLS = [{'autocomplete': token, 'formIndex': 0, 'index': index, 'label': token, 'name': token, 'type': 'text'}
            for index, token in enumerate(('cc-number', 'cc-exp-month', 'cc-exp-year', 'cc-csc'))]


def load_card():
    spec = importlib.util.spec_from_file_location('fixture_any_site_card', PLUGIN / 'any_site_card.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Page:
    """What the browser shows, who is asked to approve, and everything that was written."""

    def __init__(self):
        self.origin = SITE          # the tab the tool looks at
        self.now = None             # where the tab is when the write runs (None: still there)
        self.approve = True
        self.during_approval = None
        self.asked, self.focused, self.fenced, self.writes, self.reads = [], [], [], [], []
        self.controls = list(CONTROLS)


@pytest.fixture
def hermes(monkeypatch):
    if HERMES_SOURCE and HERMES_SOURCE not in sys.path:
        monkeypatch.syspath_prepend(HERMES_SOURCE)
    try:
        import tools.browser_vault_tool as vault
        import agent.vault_store as store
    except Exception as exc:  # pragma: no cover - no Hermes to run against
        pytest.skip('Set HERMES_AGENT_SOURCE (and PYTHONPATH) to run against a Hermes source tree: %s' % type(exc).__name__)
    return SimpleNamespace(vault=vault, store=store)


@pytest.fixture
def world(hermes, monkeypatch):
    """A Hermes with a real local vault and a fake browser page."""
    page, vault = Page(), hermes.vault
    real_confirm = vault._confirm_payment_fill
    monkeypatch.setattr(vault, '_fenced_page_op', lambda task_id, fn: (page.fenced.append(task_id), fn())[1])
    monkeypatch.setattr(vault, '_focus_bound_origin', lambda task_id, origin, kind: page.focused.append((origin, kind)))
    monkeypatch.setattr(vault, '_current_page_origin', lambda task_id: page.origin)

    def confirm(label, origin):
        page.asked.append((label, origin))
        if page.during_approval:
            page.during_approval()
        return page.approve
    monkeypatch.setattr(vault, '_confirm_payment_fill', confirm)

    def inspect(task_id, expression):
        page.reads.append(expression)
        return {'success': True, 'result': json.dumps(page.controls)}
    monkeypatch.setattr(vault, '_eval_js', inspect)

    def write(task_id, expression):
        expected = json.loads(re.search(r'const expectedOrigin = (".*?");', expression).group(1))
        fills = json.loads(re.search(r'const fills = (\[.*?\]);', expression, re.S).group(1))
        if page.now is not None and page.now != expected:
            return {'success': True, 'result': json.dumps({'refused': 'origin_changed', 'found': page.now})}
        page.writes.append({'origin': expected, 'fills': fills})
        return {'success': True, 'result': json.dumps({'filled': len(fills)})}
    monkeypatch.setattr(vault, '_eval_js_secret', write)

    def hermes_approval(consent):
        """Hermes' own _confirm_payment_fill again, with only the consent prompt (the phone) answering [consent]."""
        import tools.approval_prompt as prompt
        monkeypatch.setattr(vault, '_confirm_payment_fill', real_confirm)
        monkeypatch.setattr(prompt, 'request_elicitation_consent', consent if callable(consent) else (lambda *a, **k: consent))
    return SimpleNamespace(page=page, vault=vault, store=hermes.store, card=load_card(), hermes_approval=hermes_approval)


def add_card(world, origin=None, kind='payment', label='Test card'):
    secret = {'card_number': NUMBER, 'exp_month': '12', 'exp_year': YEAR, 'cvc': CODE}
    if kind == 'address':
        secret = {'address_line1': '1 Example Street', 'city': 'Example', 'postal_code': '00000', 'country': 'Example'}
    if kind == 'login':
        secret = {'identifier_type': 'username', 'identifier': 'tester', 'password': 'synthetic-password'}
        origin = origin or SITE
    return world.store.get_vault_store().add_item(kind, label, secret, origin=origin).id


def run(world, handle, **kwargs):
    return json.loads(world.card.pay_with_card({'handle': handle}, task_id='task-1', **kwargs))


def nothing_was_written(world):
    return world.page.writes == []


# ---- the happy path: Hermes' own fill, on the page the owner approved -----------------------------------------------------------

def test_a_card_with_no_site_is_filled_on_the_page_after_the_owner_approves(world):
    handle = add_card(world)
    result = run(world, handle)
    assert result['success'] is True and result['filled_fields'] == 4 and result['kind'] == 'payment'
    assert result['origin'] == SITE and result['fields'] == ['cc-csc', 'cc-exp-month', 'cc-exp-year', 'cc-number']
    assert world.page.asked == [('Test card', SITE)]
    # the write is Hermes' own, bound to the approved origin, and it is the only place the values appear
    assert [w['origin'] for w in world.page.writes] == [SITE]
    assert {f['value'] for f in world.page.writes[0]['fills']} >= {NUMBER, CODE}
    assert not any(secret in json.dumps(result) for secret in SECRETS)


def test_it_looks_for_the_checkout_tab_and_runs_inside_the_browser_fence(world):
    run(world, add_card(world))
    assert world.page.focused == [('', 'payment')] and world.page.fenced == ['task-1']


def test_the_fill_runs_inside_the_backends_fill_context_and_rereads_the_item(world, monkeypatch):
    handle = add_card(world)
    order = []
    from agent.vault_backends import backend_for_handle
    backend = backend_for_handle(handle)
    real_meta = type(backend).get_meta

    class Context:
        def __enter__(self): order.append('enter')
        def __exit__(self, *exc): order.append('exit')
    monkeypatch.setattr(type(backend), 'fill_context', lambda self: Context())
    monkeypatch.setattr(type(backend), 'get_meta', lambda self, h: (order.append('meta'), real_meta(self, h))[1])
    world.page.during_approval = lambda: order.append('approved')
    assert run(world, handle)['success'] is True
    assert order == ['meta', 'approved', 'enter', 'meta', 'exit'], 'the approval comes first, then the context re-reads the item before anything is resolved'


# ---- the approval names the page's site ----------------------------------------------------------------------------------------

def test_hermes_own_approval_text_names_the_page_s_site(world):
    seen = {}

    def consent(message, description, **kwargs):
        seen.update(message=message, description=description, **kwargs)
        return 'accept'
    world.hermes_approval(consent)
    world.page.origin = 'http://127.0.0.1:8765'
    result = run(world, add_card(world))
    assert result['success'] is True
    assert seen['message'] == "Fill payment card 'Test card' on http://127.0.0.1:8765" and seen['surface'] == 'vault-payment'
    assert not any(secret in json.dumps(seen) for secret in SECRETS)


def test_the_approval_is_asked_for_the_site_the_tab_shows_not_one_the_agent_names(world):
    world.page.origin = 'http://127.0.0.1:8765'
    result = json.loads(world.card.pay_with_card({'handle': add_card(world), 'site': 'https://other.example'}, task_id='t'))
    assert world.page.asked == [('Test card', 'http://127.0.0.1:8765')] and result['origin'] == 'http://127.0.0.1:8765'


# ---- refusals: nothing is approved, resolved or written ------------------------------------------------------------------------------

@pytest.mark.parametrize('kind', ['login', 'address'])
def test_anything_that_is_not_a_card_is_refused_and_points_to_browser_vault(world, kind):
    result = run(world, add_card(world, kind=kind))
    assert result['success'] is False and result['error_type'] == 'not_a_card' and 'browser_vault' in result['error']
    assert world.page.asked == [] and nothing_was_written(world)


def test_a_card_saved_for_a_site_is_refused_and_points_to_browser_vault(world):
    result = run(world, add_card(world, origin='https://pay.example'))
    assert result['success'] is False and result['error_type'] == 'site_bound'
    assert 'https://pay.example' in result['error'] and 'browser_vault' in result['error']
    assert world.page.asked == [] and nothing_was_written(world)


def test_an_unknown_or_empty_handle_is_refused(world):
    assert run(world, 'vault_doesnotexist')['error_type'] == 'not_found'
    assert run(world, 'bw:unknown')['error_type'] == 'not_found'
    empty = json.loads(world.card.pay_with_card({}, task_id='t'))
    assert empty['success'] is False and 'handle' in empty['error']
    assert world.page.asked == [] and nothing_was_written(world)


def test_a_declined_payment_fills_nothing_and_says_not_to_retry(world):
    world.page.approve = False
    result = run(world, add_card(world))
    assert result['success'] is False and result['error_type'] == 'payment_declined'
    assert 'Do not retry' in result['error'] and nothing_was_written(world)
    assert world.page.reads == [], 'the page is not even read before the owner agrees'


def run_in(world, handle, task):
    return json.loads(world.card.pay_with_card({'handle': handle}, task_id=task))


def fake_clock(world, monkeypatch, start=1000.0):
    now = [start]
    monkeypatch.setattr(world.card, '_now', lambda: now[0])
    return now


def test_after_a_decline_the_phone_is_not_asked_again_and_the_model_is_told_how_long_is_left(world, monkeypatch):
    now = fake_clock(world, monkeypatch)
    handle = add_card(world)
    world.page.approve = False
    first = run_in(world, handle, 'task-1')
    # the first refusal keeps its text
    assert first == {'success': False, 'error_type': 'payment_declined', 'error': world.card.DECLINED_LINE}
    assert 'Do not retry' in first['error'] and len(world.page.asked) == 1
    now[0] += 10.4
    again = run_in(world, handle, 'task-1')
    assert again == {'success': False, 'error_type': 'payment_declined', 'error': (
        'The user declined or missed this payment a moment ago. Nothing was filled. Ask them first; '
        'the phone can be asked again in 50 seconds.')}  # 49.6 s left, rounded up
    now[0] += 20
    assert run_in(world, handle, 'task-1')['error'].endswith('the phone can be asked again in 30 seconds.')
    assert len(world.page.asked) == 1, 'the phone is not asked again'
    assert world.page.reads == [] and nothing_was_written(world)
    # even if the owner would now say yes, the task does not ask until the minute is over
    world.page.approve = True
    assert run_in(world, handle, 'task-1')['error_type'] == 'payment_declined' and len(world.page.asked) == 1


def test_a_decline_stands_for_sixty_seconds_then_the_phone_is_asked_again(world, monkeypatch):
    assert world.card.DECLINE_SECONDS == 60
    now = fake_clock(world, monkeypatch)
    handle = add_card(world)
    world.page.approve = False
    run_in(world, handle, 'task-1')
    world.page.approve = True
    now[0] += 59
    last = run_in(world, handle, 'task-1')
    assert last['error_type'] == 'payment_declined' and last['error'].endswith('asked again in 1 second.') and len(world.page.asked) == 1
    now[0] += 1  # 60 s after the decline
    assert run_in(world, handle, 'task-1')['success'] is True
    assert len(world.page.asked) == 2 and len(world.page.writes) == 1


def test_a_prompt_nobody_answered_stands_for_a_minute_only(world, monkeypatch):
    # An owner who missed the prompt and then says "go ahead" must be able to be asked again soon: the wait is one minute, not five.
    now = fake_clock(world, monkeypatch)
    answers, asked = iter([None, 'accept']), []
    world.hermes_approval(lambda *a, **k: asked.append(1) or next(answers))
    handle = add_card(world)
    assert run_in(world, handle, 'task-1')['error_type'] == 'payment_declined' and len(asked) == 1
    now[0] += 30
    still = run_in(world, handle, 'task-1')
    assert still['error'].endswith('the phone can be asked again in 30 seconds.') and len(asked) == 1
    now[0] += 31  # 61 s after the missed prompt
    assert run_in(world, handle, 'task-1')['success'] is True and len(asked) == 2


def test_a_decline_in_one_task_does_not_touch_another_task(world):
    handle = add_card(world)
    world.page.approve = False
    run_in(world, handle, 'task-1')
    world.page.approve = True
    other = run_in(world, handle, 'task-2')
    assert other['success'] is True and len(world.page.asked) == 2


def test_every_approved_payment_still_asks_once(world):
    handle = add_card(world)
    assert run_in(world, handle, 'task-1')['success'] is True
    assert run_in(world, handle, 'task-1')['success'] is True
    assert len(world.page.asked) == 2 and len(world.page.writes) == 2
    # an approval is not a decline: nothing latches until the owner says no
    world.page.approve = False
    assert run_in(world, handle, 'task-1')['error_type'] == 'payment_declined' and len(world.page.asked) == 3
    assert run_in(world, handle, 'task-1')['error_type'] == 'payment_declined' and len(world.page.asked) == 3


def test_other_refusals_are_not_latched(world):
    # Only a payment_declined stands; a card that cannot be used here says why every time.
    world.page.origin = None
    handle = add_card(world)
    assert run_in(world, handle, 'task-1')['error_type'] == 'no_page'
    world.page.origin = SITE
    assert run_in(world, handle, 'task-1')['success'] is True


@pytest.mark.parametrize('consent', ['decline', 'cancel', '', None])
def test_headless_declined_or_unanswered_consent_fills_nothing(world, consent):
    # Hermes' own _confirm_payment_fill: only an explicit accept passes; a headless session or no answer is decline/cancel
    world.hermes_approval(consent)
    result = run(world, add_card(world))
    assert result['success'] is False and result['error_type'] == 'payment_declined'
    assert nothing_was_written(world) and world.page.reads == []


def test_a_consent_prompt_that_raises_fills_nothing(world):
    def broken(*a, **k):
        raise RuntimeError('prompt failed ' + NUMBER)
    world.hermes_approval(broken)
    raw = world.card.pay_with_card({'handle': add_card(world)}, task_id='t')
    assert json.loads(raw)['success'] is False and nothing_was_written(world) and NUMBER not in raw


def test_no_open_page_asks_the_agent_to_open_the_checkout_first(world):
    world.page.origin = None
    result = run(world, add_card(world))
    assert result['success'] is False and result['error_type'] == 'no_page' and 'checkout' in result['error']
    assert world.page.asked == [] and nothing_was_written(world)


def test_a_page_that_moves_between_the_approval_and_the_write_gets_nothing(world):
    world.page.during_approval = lambda: setattr(world.page, 'now', 'https://evil.example')
    result = run(world, add_card(world))
    assert result['success'] is False and result['error_type'] == 'origin_changed' and 'Nothing was written' in result['error']
    assert world.page.asked == [('Test card', SITE)] and nothing_was_written(world)


def test_a_card_that_gained_a_site_during_the_approval_is_refused(world, monkeypatch):
    handle = add_card(world)
    from agent.vault_backends import backend_for_handle
    backend = backend_for_handle(handle)
    real = type(backend).get_meta
    calls = []

    def get_meta(self, h):
        calls.append(h)
        meta = real(self, h)
        if len(calls) == 1:
            return meta
        return world.store.VaultItemMeta(id=h, kind='payment', label='Test card', origin='https://pay.example', created_at='')
    monkeypatch.setattr(type(backend), 'get_meta', get_meta)
    result = run(world, handle)
    assert len(calls) == 2, 'once before the approval, once inside the fill context'
    assert result['success'] is False and result['error_type'] == 'item_changed' and nothing_was_written(world)
    assert world.page.asked == [('Test card', SITE)] and world.page.reads == []


def test_a_card_removed_during_the_approval_is_refused(world):
    handle = add_card(world)
    world.page.during_approval = lambda: world.store.get_vault_store().remove_item(handle)
    result = run(world, handle)
    assert result['success'] is False and result['error_type'] == 'item_changed' and nothing_was_written(world)
    assert world.page.reads == []


def test_the_secret_is_never_resolved_when_a_refusal_comes_first(world, monkeypatch):
    from agent.vault_backends import backend_for_handle
    handle = add_card(world)
    backend = backend_for_handle(handle)
    monkeypatch.setattr(type(backend), 'resolve_secret', lambda self, h: pytest.fail('a refused fill must not resolve the secret'))
    world.page.approve = False
    assert run(world, handle)['error_type'] == 'payment_declined'
    world.page.approve, world.page.origin = True, None
    assert run(world, handle)['error_type'] == 'no_page'


# ---- password managers: the unlock step is Hermes' own --------------------------------------------------------------------------

class Manager:
    name, display_name, needs_unlock = 'bitwarden', 'Bitwarden', True

    def __init__(self, store, unlocked=False):
        self.unlocked, self.store, self.metas = unlocked, store, 0

    def is_unlocked(self): return self.unlocked
    def fill_context(self): return nullcontext()

    def get_meta(self, handle):
        self.metas += 1
        return self.store.VaultItemMeta(id=handle, kind='login', label='Work', origin='https://work.example', created_at='')


def test_a_locked_manager_is_unlocked_first_and_a_declined_unlock_stops(world, monkeypatch):
    manager = Manager(world.store)
    import agent.vault_backends as backends
    monkeypatch.setattr(backends, 'backend_for_handle', lambda handle: manager)
    monkeypatch.setattr(world.vault, 'browser_vault_unlock', lambda name: json.dumps({'success': False, 'error_type': 'unlock_cancelled', 'error': 'The user declined to unlock Bitwarden.'}))
    result = run(world, 'bw:1')
    assert result['error_type'] == 'unlock_cancelled' and manager.metas == 0 and world.page.asked == []


def test_an_unlocked_manager_login_is_refused_as_not_a_card(world, monkeypatch):
    manager = Manager(world.store, unlocked=True)
    import agent.vault_backends as backends
    monkeypatch.setattr(backends, 'backend_for_handle', lambda handle: manager)
    assert run(world, 'bw:1')['error_type'] == 'not_a_card' and world.page.asked == []


def test_a_manager_that_locks_again_asks_for_an_unlock(world, monkeypatch):
    from agent.vault_backends import UnlockRequired
    manager = Manager(world.store, unlocked=True)

    def locked(handle):
        raise UnlockRequired(manager)
    manager.get_meta = locked
    import agent.vault_backends as backends
    monkeypatch.setattr(backends, 'backend_for_handle', lambda handle: manager)
    assert run(world, 'bw:1')['error_type'] == 'unlock_required' and world.page.asked == []


def test_a_plugin_backend_failure_is_a_refusal_with_hermes_fixed_text(world, monkeypatch):
    from agent.vault_backends.plugin import PluginBackendError
    handle = add_card(world)
    from agent.vault_backends import backend_for_handle
    backend = backend_for_handle(handle)

    def broken(self, h):
        raise PluginBackendError('The vault plugin is not available.')
    monkeypatch.setattr(type(backend), 'get_meta', broken)
    result = run(world, handle)
    assert result == {'success': False, 'error_type': 'backend_unavailable', 'error': 'The vault plugin is not available.'}


# ---- nothing secret anywhere -------------------------------------------------------------------------------------------------------

def test_an_unexpected_failure_answers_one_fixed_line_that_carries_no_secret(world, monkeypatch):
    handle = add_card(world)
    monkeypatch.setattr(world.vault, '_fill_bound_page', lambda *a: (_ for _ in ()).throw(RuntimeError('boom ' + NUMBER + ' ' + CODE)))
    raw = world.card.pay_with_card({'handle': handle}, task_id='t')
    result = json.loads(raw)
    assert result['success'] is False and result['error_type'] == 'card_failed' and 'RuntimeError' in result['error']
    assert not any(secret in raw for secret in SECRETS)


def test_no_secret_reaches_a_result_a_log_the_output_or_beacons_files(world, caplog, capsys, tmp_path):
    caplog.set_level(logging.DEBUG)
    handle = add_card(world)
    results = [world.card.pay_with_card({'handle': handle}, task_id='t')]
    world.page.approve = False
    results.append(world.card.pay_with_card({'handle': handle}, task_id='t'))
    results.append(world.card.pay_with_card({'handle': add_card(world, origin='https://pay.example')}, task_id='t'))
    out = capsys.readouterr()
    haystack = '\n'.join(results) + caplog.text + out.out + out.err
    assert not any(secret in haystack for secret in SECRETS)
    home = Path(os.environ['HERMES_HOME'])
    for path in [p for p in home.rglob('*') if p.is_file()]:
        assert not any(secret.encode() in path.read_bytes() for secret in SECRETS), path.name


def test_the_card_values_are_registered_with_hermes_redaction_before_they_touch_the_page(world, monkeypatch):
    registered = []
    import agent.redact as redact
    monkeypatch.setattr(redact, 'register_vault_redaction_value', lambda value: registered.append(value))
    run(world, add_card(world))
    assert {NUMBER, CODE} <= set(registered)


# ---- a Hermes that moved a private name ----------------------------------------------------------------------------------------------

@pytest.mark.parametrize('gone', ['_fill_bound_page', '_confirm_payment_fill', '_current_page_origin', '_fenced_page_op', '_focus_bound_origin', 'browser_vault_unlock'])
def test_a_hermes_that_lost_a_name_gets_one_clear_line_and_nothing_is_approved_or_filled(world, monkeypatch, gone):
    monkeypatch.delattr(world.vault, gone)
    handle = add_card(world)
    result = run(world, handle)
    assert result == {'success': False, 'error_type': 'hermes_update_needed', 'error': world.card.UPDATE_LINE}
    assert 'update' in world.card.UPDATE_LINE.lower() and '\n' not in world.card.UPDATE_LINE
    assert world.page.asked == [] and nothing_was_written(world)


def test_the_tool_stays_listed_when_a_name_is_gone_so_it_can_say_so(world, monkeypatch):
    monkeypatch.delattr(world.vault, '_check_vault_available')
    assert world.card.available() is True


def test_the_tool_is_listed_exactly_where_hermes_lists_browser_vault(world, monkeypatch):
    monkeypatch.setattr(world.vault, '_check_vault_available', lambda: True)
    assert world.card.available() is True
    monkeypatch.setattr(world.vault, '_check_vault_available', lambda: False)
    assert world.card.available() is False


# ---- registration ----------------------------------------------------------------------------------------------------------------------

def test_phone_tools_registers_the_tool_in_the_browser_toolset(harness):
    api = harness[0]
    registered = {}
    api.phone_tools.register(SimpleNamespace(register_tool=lambda **kw: registered.update({kw['name']: kw})))
    tool = registered['beacon_pay_with_card']
    assert tool['toolset'] == 'browser', "the browser toolset: wherever Hermes' browser is, and deferred behind tool_search like browser_vault"
    assert tool['schema']['name'] == 'beacon_pay_with_card' and tool['schema']['parameters']['required'] == ['handle']
    assert tool['schema']['parameters']['additionalProperties'] is False and callable(tool['handler']) and callable(tool['check_fn'])
    text = tool['description']
    assert tool['schema']['description'] == text
    for needle in ('no saved site', 'browser_vault', 'approves', 'phone', 'never enter the conversation'):
        assert needle in text, needle


@pytest.mark.parametrize('broken', ['this is not python (\n', 'def register(ctx):\n    raise RuntimeError("card " + "4242")\n', None],
                         ids=['does not compile', 'register raises', 'file is missing'])
def test_a_card_tool_that_cannot_load_never_costs_the_phone_tools(harness, monkeypatch, tmp_path, caplog, broken):
    phone_tools = harness[0].phone_tools
    # phone_tools loads its sibling from beside its own file: point that at a folder whose any_site_card.py is broken (or absent).
    if broken is not None:
        (tmp_path / 'any_site_card.py').write_text(broken, encoding='utf-8')
    monkeypatch.setattr(phone_tools, '__file__', str(tmp_path / 'phone_tools.py'))
    monkeypatch.setitem(sys.modules, 'beacon_any_site_card', SimpleNamespace(_beacon_mtime=None))  # whatever this test loads is undone afterwards
    registered = {}
    with caplog.at_level(logging.WARNING):
        phone_tools.register(SimpleNamespace(register_tool=lambda **kw: registered.update({kw['name']: kw})))
    assert set(registered) == set(phone_tools.TOOLS), 'every phone tool is registered, and not the card tool'
    said = [record.getMessage() for record in caplog.records if 'saved-card tool' in record.getMessage()]
    assert len(said) == 1 and said[0].startswith('Beacon could not add the saved-card tool: '), said
    assert '4242' not in said[0], 'only the error class is logged'


def test_the_watch_can_never_use_the_tool(harness):
    assert load_private('__init__')._watch_tool_guard('beacon_pay_with_card', {'handle': 'vault_x'}, 'default')['action'] == 'block'


def test_the_manifest_lists_the_tool():
    import yaml
    manifest = yaml.safe_load((PLUGIN / 'plugin.yaml').read_text(encoding='utf-8'))
    assert 'beacon_pay_with_card' in manifest['provides_tools']


# ---- the contract with Hermes' source (read, never imported) -----------------------------------------------------------------

@pytest.fixture(scope='module')
def source():
    if not HERMES_SOURCE:
        pytest.skip('Set HERMES_AGENT_SOURCE to check against a Hermes source tree')
    return Path(HERMES_SOURCE)


def _compat():
    spec = importlib.util.spec_from_file_location('beacon_compat_under_card_test', PLUGIN / 'compat.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _functions(path):
    tree = ast.parse(path.read_text(encoding='utf-8-sig'))
    return {n.name: [a.arg for a in n.args.args] for n in tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}


# module, name, positional parameters the tool passes
CALLS = (
    ('tools/browser_vault_tool.py', '_current_page_origin', ['task_id']),
    ('tools/browser_vault_tool.py', '_confirm_payment_fill', ['label', 'origin']),
    ('tools/browser_vault_tool.py', '_fill_bound_page', ['backend', 'handle', 'meta', 'page_origin', 'effective_task_id']),
    ('tools/browser_vault_tool.py', '_fenced_page_op', ['task_id', 'fn']),
    ('tools/browser_vault_tool.py', '_focus_bound_origin', ['task_id', 'origin', 'kind']),
    ('tools/browser_vault_tool.py', 'browser_vault_unlock', ['backend_name']),
    ('tools/browser_vault_tool.py', '_check_vault_available', []),
    ('tools/browser_vault_context.py', 'saved_origins', ['meta']),
    ('tools/browser_vault_context.py', 'plugin_errors_as_refusal', ['fn']),
    ('agent/vault_backends/base.py', 'backend_for_handle', ['handle']),
)


@pytest.mark.parametrize('path,name,parameters', CALLS)
def test_hermes_still_has_each_name_the_card_tool_calls_with_the_arguments_it_passes(source, path, name, parameters):
    assert _functions(source / path).get(name) == parameters, f'{path}: {name} moved or changed its parameters'


def test_hermes_still_has_the_classes_and_methods_the_card_tool_uses(source):
    base = (source / 'agent/vault_backends/base.py').read_text(encoding='utf-8-sig')
    assert 'class UnlockRequired(Exception)' in base and 'def fill_context(self)' in base and 'def get_meta(self, handle: str)' in base
    assert 'def needs_unlock' not in base and 'needs_unlock: bool = False' in base and 'def is_unlocked(self)' in base
    assert 'class PluginBackendError' in (source / 'agent/vault_backends/plugin.py').read_text(encoding='utf-8-sig')


def test_hermes_still_asks_for_the_card_the_way_the_phone_shows_it(source):
    text = (source / 'tools/browser_vault_tool.py').read_text(encoding='utf-8-sig')
    confirm = re.search(r'def _confirm_payment_fill\(.*?\n\n\n', text, re.S).group(0)
    assert 'f"Fill payment card \'{label}\' on {origin}"' in confirm and 'surface="vault-payment"' in confirm and '== "accept"' in confirm


def test_hermes_still_rechecks_the_origin_in_the_page_and_redacts_the_card_in_its_own_fill(source):
    text = (source / 'tools/browser_vault_tool.py').read_text(encoding='utf-8-sig')
    fill = re.search(r'def _fill_bound_page\(.*?\n\n\ndef _confirm_payment_fill', text, re.S).group(0)
    assert 'build_fill_js(fills, expected_origin=page_origin' in fill and 'register_vault_redaction_value(value)' in fill
    assert 'origin_changed' in fill and 'scrub_secret_from_text' in fill
    assert '_fenced_page_op(tid, page_ops[action])' in text, 'Hermes still fences every vault page operation'


def test_hermes_still_refuses_a_card_without_a_site_which_is_why_the_tool_exists(source):
    text = (source / 'tools/browser_vault_tool.py').read_text(encoding='utf-8-sig')
    assert 'has no bound origin' in text and '"available": meta.kind == "login" or bool(meta.origin)' in text
    assert 'if origin:\n                norm_origin = normalize_origin(origin)' in (source / 'agent/vault_store.py').read_text(encoding='utf-8-sig')


def test_the_startup_check_watches_every_name_the_card_tool_imports(source):
    compat = _compat()
    groups = [g for g in compat.needs() if any(e[3] == 'any_site_card.py' for e in g)]
    watched = {(e[0], e[1]) for g in groups for e in g}
    for module, name in (('tools.browser_vault_tool', '_fill_bound_page'), ('tools.browser_vault_tool', '_confirm_payment_fill'),
                         ('tools.browser_vault_tool', '_current_page_origin'), ('tools.browser_vault_tool', '_fenced_page_op'),
                         ('tools.browser_vault_tool', '_focus_bound_origin'), ('tools.browser_vault_tool', 'browser_vault_unlock'),
                         ('agent.vault_backends', 'backend_for_handle'), ('agent.vault_backends', 'UnlockRequired'),
                         ('tools.browser_vault_context', 'saved_origins')):
        assert (module, name) in watched, (module, name)
    assert compat.missing(groups, source) == []


def test_a_renamed_card_name_is_named_at_start_with_the_feature_it_stops(source):
    compat = _compat()
    renamed = (('tools.browser_vault_tool', '_fill_bound_page_renamed_in_a_later_hermes', None, 'any_site_card.py', False),)
    gone = compat.missing([renamed], source)
    assert gone == ['tools.browser_vault_tool._fill_bound_page_renamed_in_a_later_hermes (any_site_card.py)']
    assert compat.stopped(gone) == ['Using a saved card on any site needs a Beacon update for this Hermes']
    assert compat.stopped(['tui_gateway.server._sessions_renamed (native.py)']) == []
    both = compat.stopped(gone + ['hermes_cli.web_server_cron._mutate_cron_for_profile (proactive.py)'])
    assert both == [compat.PRIVATE_STOPS, 'Using a saved card on any site needs a Beacon update for this Hermes']


# ---- the phone learns which approval is a card payment ---------------------------------------------------------------------------------
# On the canonical session route Hermes publishes the approval as a prompt (gateway/session_pending_controls.py) that carries no
# `surface`; Hermes tells a plugin the surface only through the pre_approval_request hook, which fires after the prompt is queued and
# before it is published. The tool records that against the prompt's own id so the relay can vouch for it to the phone. The agent that
# announces a prompt and the relay that forwards it are different processes (the gateway and the dashboard), so the record is a file
# in the plugin's own data folder, not memory (found on the demo: a memory-only record never reached the relay).

CARD_WARNING = ('The agent wants to enter your saved card details into this checkout page. The card number and CVC never enter the '
                'conversation. Approve only if you intend to pay here.')
KEY = 'local-1234'
CARD_COMMAND = "Fill payment card 'Test card' on http://127.0.0.1:8793"


@pytest.fixture
def approvals(hermes, monkeypatch):
    """Hermes' own approval queue, emptied, with a way to queue a prompt exactly as _await_gateway_decision does."""
    import tools.approval as approval
    from tools.approval_gateway_wait import _ApprovalEntry
    monkeypatch.setattr(approval, '_gateway_queues', {})

    def queue(command, key=KEY, description=CARD_WARNING):
        entry = _ApprovalEntry({'command': command, 'description': description, 'pattern_key': 'mcp_elicitation',
                                'pattern_keys': ['mcp_elicitation'], 'allow_session': False, 'allow_permanent': False})
        approval._gateway_queues.setdefault(key, []).append(entry)
        return entry
    return SimpleNamespace(queue=queue, queues=approval._gateway_queues)


def hook_kwargs(entry, key=KEY, surface='vault-payment'):
    """The keyword arguments Hermes fires pre_approval_request with (tools/approval_gateway_wait.py: **payload, plus turn/tool ids)."""
    return {'command': entry.data['command'], 'description': entry.data['description'], 'pattern_key': 'mcp_elicitation',
            'pattern_keys': ['mcp_elicitation'], 'session_key': key, 'surface': surface, 'turn_id': None, 'tool_call_id': None}


def card_with_hooks():
    card, hooks = load_card(), {}
    card.register(SimpleNamespace(register_tool=lambda **kw: None, register_hook=lambda name, callback: hooks.setdefault(name, []).append(callback)))
    return card, hooks


def markers(card):
    return card._marked(card._store_dir())


def test_the_tool_listens_for_the_approvals_hermes_announces():
    card, hooks = card_with_hooks()
    assert len(hooks['pre_approval_request']) == 1 and len(hooks['post_approval_response']) == 1


def test_a_card_payment_prompt_is_marked_with_the_id_hermes_publishes(approvals):
    card, hooks = card_with_hooks()
    prompt = approvals.queue(CARD_COMMAND)
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))
    assert card.surface_for(prompt.data['request_id']) == 'vault-payment'
    assert card.surface_for('0' * 32) is None and card.surface_for('') is None and card.surface_for(None) is None


def test_the_marker_is_visible_to_another_process(approvals):
    # The gateway announces the prompt; the dashboard's relay is another process with its own copy of the module and only the data folder in common.
    announcing, hooks = card_with_hooks()
    relay = load_card()
    prompt = approvals.queue(CARD_COMMAND)
    assert not relay.has_surfaces()
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))
    assert relay.has_surfaces() and relay.surface_for(prompt.data['request_id']) == 'vault-payment'
    approvals.queues[KEY].remove(prompt)
    hooks['post_approval_response'][0](session_key=KEY, choice='once')
    assert not relay.has_surfaces() and relay.surface_for(prompt.data['request_id']) is None


@pytest.mark.parametrize('surface', ['gateway', 'cli', 'mcp-elicitation/evil', 'mcp-trust/evil', 'vault-payment/extra', '', None])
def test_only_hermes_card_surface_is_marked(approvals, surface):
    card, hooks = card_with_hooks()
    # Text that reads like a card fill, from any other surface, is an ordinary approval.
    prompt = approvals.queue(CARD_COMMAND)
    hooks['pre_approval_request'][0](**hook_kwargs(prompt, surface=surface))
    assert card.surface_for(prompt.data['request_id']) is None and not card.has_surfaces()


def test_a_prompt_that_is_not_waiting_in_hermes_queue_is_not_marked(approvals):
    card, hooks = card_with_hooks()
    prompt = approvals.queue(CARD_COMMAND, key='another-session')
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))  # announced for KEY, queued for another session
    hooks['pre_approval_request'][0](command='x', description='y', session_key=None, surface='vault-payment')
    hooks['pre_approval_request'][0](surface='vault-payment')
    assert not card.has_surfaces()


def test_each_marker_belongs_to_one_prompt(approvals):
    card, hooks = card_with_hooks()
    card_prompt = approvals.queue(CARD_COMMAND)
    other = approvals.queue('rm -rf build')
    hooks['pre_approval_request'][0](**hook_kwargs(card_prompt))
    assert card.surface_for(card_prompt.data['request_id']) == 'vault-payment' and card.surface_for(other.data['request_id']) is None
    # Hermes coalesces an identical concurrent prompt into the first, so a repeated announcement marks nothing new.
    hooks['pre_approval_request'][0](**hook_kwargs(card_prompt))
    assert len(markers(card)) == 1


def test_a_marker_ends_when_its_prompt_is_answered(approvals):
    card, hooks = card_with_hooks()
    prompt = approvals.queue(CARD_COMMAND)
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))
    hooks['post_approval_response'][0](session_key=KEY, choice='once')
    assert card.surface_for(prompt.data['request_id']) == 'vault-payment', 'a prompt Hermes still holds keeps its marker'
    approvals.queues[KEY].remove(prompt)
    hooks['post_approval_response'][0](session_key=KEY, choice='once')
    assert card.surface_for(prompt.data['request_id']) is None and not card.has_surfaces() and markers(card) == {}


def test_a_marker_never_outlives_hermes_wait_and_the_store_stays_small(approvals, monkeypatch):
    card, hooks = card_with_hooks()
    now = [1000.0]
    monkeypatch.setattr(card, '_wall', lambda: now[0])
    prompt = approvals.queue(CARD_COMMAND)
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))
    now[0] += card.SURFACE_SECONDS - 1
    assert card.surface_for(prompt.data['request_id']) == 'vault-payment'
    now[0] += 2
    assert card.surface_for(prompt.data['request_id']) is None and not card.has_surfaces()
    assert markers(card) == {}, 'an expired marker is deleted as soon as it is seen'
    ids = []
    for number in range(card.MAX_SURFACES + 40):
        now[0] += 1
        queued = approvals.queue(f"Fill payment card 'Card {number}' on http://127.0.0.1:8793")
        hooks['pre_approval_request'][0](**hook_kwargs(queued))
        ids.append(queued.data['request_id'])
    assert len(markers(card)) <= card.MAX_SURFACES
    assert card.surface_for(ids[-1]) == 'vault-payment' and card.surface_for(ids[0]) is None


def test_only_a_real_prompt_id_can_name_a_file(approvals):
    card, hooks = card_with_hooks()
    for bad in ('../../escape', 'x' * 32, '', 'A' * 32, '0' * 31):
        assert card.surface_for(bad) is None
    entry = approvals.queue(CARD_COMMAND)
    entry.data['request_id'] = '../../escape'
    hooks['pre_approval_request'][0](**hook_kwargs(entry))
    assert not card.has_surfaces() and list(card._store_dir().parent.glob('*escape*')) == []


def test_a_hermes_that_cannot_list_its_queue_marks_nothing_and_never_breaks_the_approval(approvals, monkeypatch):
    import tools.approval as approval
    card, hooks = card_with_hooks()
    prompt = approvals.queue(CARD_COMMAND)
    monkeypatch.delattr(approval, 'list_gateway_approvals')
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))
    hooks['post_approval_response'][0](session_key=KEY, choice='once')
    assert not card.has_surfaces()


def test_an_unwritable_data_folder_marks_nothing_and_never_breaks_the_approval(approvals, monkeypatch):
    card, hooks = card_with_hooks()
    prompt = approvals.queue(CARD_COMMAND)
    monkeypatch.setattr(card, '_store_dir', lambda: None)
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))
    hooks['post_approval_response'][0](session_key=KEY, choice='once')
    assert card.surface_for(prompt.data['request_id']) is None and not card.has_surfaces()


def test_hermes_still_announces_the_surface_after_it_queues_the_prompt(source):
    wait = (source / 'tools/approval_gateway_wait.py').read_text(encoding='utf-8-sig')
    assert '"session_key": session_key, "surface": surface' in wait
    assert wait.index('_enqueue()') < wait.index('_fire_approval_hook("pre_approval_request", **payload)') < wait.index('notify_cb(dict(entry.data))')
    assert 'self.data.setdefault("request_id", uuid.uuid4().hex)' in wait
    approval = (source / 'tools/approval.py').read_text(encoding='utf-8-sig')
    assert re.search(r'def list_gateway_approvals\(session_key: str\) -> list\[dict\]:\n.*\n    with _lock:\n        return \[dict\(entry.data\)', approval, re.S)
    controls = (source / 'gateway/session_pending_controls.py').read_text(encoding='utf-8-sig')
    assert "prompt_id = data['request_id']" in controls and "'surface'" not in controls, 'Hermes still sends no surface on the canonical route'
    assert "event_type='approval.request'" in controls
    constants = (source / 'hermes_constants.py').read_text(encoding='utf-8-sig')
    assert 'def get_process_hermes_home() -> Path:' in constants


def test_the_relay_in_another_process_vouches_for_the_prompt_the_gateway_marked(approvals, monkeypatch):
    # The dashboard process never registered the tool, so it has no module of its own yet: the relay loads the reader and finds the file.
    card, hooks = card_with_hooks()
    prompt = approvals.queue(CARD_COMMAND)
    hooks['pre_approval_request'][0](**hook_kwargs(prompt))
    monkeypatch.delitem(sys.modules, 'beacon_any_site_card', raising=False)
    spec = importlib.util.spec_from_file_location('relay_in_dashboard_process', PLUGIN / 'canonical_transport.py')
    relay = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(relay)
    frame = json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'approval.request', 'session_id': KEY, 'seq': 3, 'payload': {
        'kind': 'approval', 'prompt_id': prompt.data['request_id'], 'execution_generation': 7, 'command': CARD_COMMAND, 'choices': ['once', 'deny']}}})
    assert json.loads(relay.annotate(frame))['params']['payload']['surface'] == 'vault-payment'
    other = frame.replace(prompt.data['request_id'], '0' * 32)
    assert relay.annotate(other) is other


def test_the_markers_live_in_the_plugin_data_of_this_hermes_home():
    card = load_card()
    assert card._store_dir() == Path(os.environ['HERMES_HOME']) / 'plugin-data' / 'beacon' / 'card-surfaces'
