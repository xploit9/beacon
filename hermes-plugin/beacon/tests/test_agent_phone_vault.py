"""Hermes' vault on the agent phone (EMU-DESIGN §6, spikes S8, S11, S13, S14): binding, arm/release, cards, saves, sign-ups,
resets, codes, failed logins and profiles.

Hermes' own VaultStore runs for real in a temporary folder; the helper, the dropdown tap and the owner's answers are fakes.
All values are synthetic. A canary check at the end of the flows looks for every secret in every result, record and log line.
"""
import importlib.util
import json
import logging
import os
import sys
import threading
import time
from itertools import product
from pathlib import Path
from types import FunctionType, MethodType, SimpleNamespace

import pytest

PLUGIN = Path(__file__).resolve().parents[1]
HERMES_SOURCE = os.environ.get('HERMES_AGENT_SOURCE', '')
SHOP, PROBE, CHROME, MAIL = 'dev.beacon.fixture.shop', 'dev.beacon.fixture.probe', 'com.android.chrome', 'dev.beacon.fixture.mail'
SHOP_CERT, OTHER_CERT, CHROME_CERT = 'ab' * 32, 'cd' * 32, 'ef' * 32
PASSWORD, CARD, CVC, NEW_PASSWORD, CODE_SEED = 'Test-Pass-4711', '4242424242424242', '737', 'Test-Pass-0815', 'JBSWY3DPEHPK3PXP'
IDENTIFIER = 'agent.kanto'
SECRETS = (PASSWORD, CARD, CVC, NEW_PASSWORD)


def _load(name):
    spec = importlib.util.spec_from_file_location('fixture_' + name, PLUGIN / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


V = _load('agent_phone_vault')
R = _load('agent_phone_redact')


def ctx(package=SHOP, certs=(SHOP_CERT,), roles=('username', 'password'), focus='password', web=None, scheme=None, source='play'):
    return {'package': package, 'certs': list(certs), 'web_domain': web, 'web_scheme': scheme,
            'fields': [{'id': 'af-' + r, 'role': r, 'focused': r == focus} for r in roles], 'at_ms': 1, 'source': source}


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class Session:
    """The channel session as the vault sees it: request() replies, answer() records, events go to the vault's handler."""

    def __init__(self, vault, context, owner=False):
        self.context, self.requests, self.answers = context, [], []
        self.code_found, self.note_value, self.link = False, None, 'https://kanto.example/magic?t=abc'
        self.on_event = vault.handler(self, owner=owner)

    def request(self, t, timeout=None, **fields):
        self.requests.append(dict(fields, t=t))
        if t == 'context':
            return {'t': 'context_result', 're': 1, 'context': self.context}
        if t == 'arm_code':
            return {'t': 'code_result', 're': 1, 'found': self.code_found}
        if t == 'note_link':
            return {'t': 'note_link_result', 're': 1, 'found': self.link is not None, **({'url': self.link} if self.link else {})}
        if t == 'note_password':
            found = self.note_value is not None
            return {'t': 'note_password_result', 're': 1, 'found': found, **({'value': self.note_value} if found else {})}
        return {'t': 'ack', 're': 1}

    def answer(self, t, **fields):
        self.answers.append(json.loads(json.dumps(dict(fields, t=t))))

    def last_arm(self):
        return [r for r in self.requests if r['t'] in ('arm', 'arm_code')][-1]


class Driver:
    """The tool's dropdown tap (S9): picking Beacon's entry makes the helper ask for the armed values."""

    def __init__(self, session, release_context=None, roles=None, tap=True, before_release=None, pick=0):
        self.session, self.release_context, self.roles, self.tap, self.before_release = session, release_context, roles, tap, before_release
        self.pick = pick  # which of the arm's entries the person taps
        self.labels = []

    def pick_entry(self, context, label, roles=()):
        self.labels.append(label)
        if not self.tap:
            return False
        arm = self.session.last_arm()
        if arm['t'] == 'arm_code':
            self.session.on_event({'t': 'code_filled', 'arm_id': arm['arm_id'], 'fields': 1})
            return True
        if self.before_release:
            self.before_release()
        req = 'req_' + 'R' * 16
        item = arm['items'][self.pick]
        self.session.on_event({'t': 'release_request', 'req': req, 'arm_id': arm['arm_id'], 'item_id': item['item_id'],
                               'roles': self.roles or item['roles'],
                               'context': self.release_context or context})
        answer = self.session.answers[-1]
        if answer['t'] == 'release':
            self.session.on_event({'t': 'filled', 'req': req, 'arm_id': arm['arm_id'], 'fields': len(answer['values'])})
        else:
            self.session.on_event({'t': 'fill_failed', 'req': req, 'arm_id': arm['arm_id'], 'reason': 'denied'})
        return True


@pytest.fixture
def hermes(monkeypatch):
    if HERMES_SOURCE and HERMES_SOURCE not in sys.path:
        monkeypatch.syspath_prepend(HERMES_SOURCE)
    try:
        import agent.vault_store  # noqa: F401
    except Exception as exc:  # pragma: no cover - no Hermes to run against
        pytest.skip('Set HERMES_AGENT_SOURCE (and PYTHONPATH) to run against a Hermes source tree: %s' % type(exc).__name__)


@pytest.fixture
def world(tmp_path, hermes, monkeypatch):
    notes, registered, clock = [], [], Clock()
    statements = {}
    vault = V.PhoneVault.for_dashboard('default', R.MaskSet(), home=tmp_path / 'owner-home',
                                      notify=lambda kind, payload: notes.append((kind, payload)),
                                      app_label=lambda package: 'Kanto Foods' if package == SHOP else package,
                                      dal_fetch=lambda domain: statements.get(domain, []), clock=clock)
    real_register = vault.h.register
    vault.h.register = lambda value: (registered.append(value), real_register(value))
    store = vault.source.store
    # Rebind the native method only for this fixture store. Its unique hex IDs cannot contain the numeric canary.
    ids = product('abcdef', repeat=12)

    def fixture_uuid():
        return SimpleNamespace(hex=''.join(next(ids)) + '0' * 20)

    add_item = store.add_item.__func__
    local = dict(add_item.__globals__, uuid=SimpleNamespace(uuid4=fixture_uuid))
    add_fixture_item = FunctionType(add_item.__code__, local, argdefs=add_item.__defaults__)
    monkeypatch.setattr(store, 'add_item', MethodType(add_fixture_item, store))

    # Pending decisions share the same canary in notifications and pending().
    held_ids = product('abcdef', repeat=16)
    hold = vault._hold

    def hold_fixture(held):
        held.id = 'held_' + ''.join(next(held_ids))
        hold(held)

    monkeypatch.setattr(vault, '_hold', hold_fixture)
    return SimpleNamespace(vault=vault, store=store, notes=notes, registered=registered, clock=clock, statements=statements,
                           home=tmp_path / 'owner-home')


def app_login(w, identifier=IDENTIFIER, password=PASSWORD, label='Kanto Foods', package=SHOP, pinned=True):
    """An app login; pinned=True as if it was saved on the phone (its app's certificate pinned), False as if saved elsewhere."""
    meta = w.store.add_item('login', label, {'identifier_type': 'username', 'identifier': identifier, 'password': password},
                            origin='android://' + package)
    if pinned:
        w.vault.sidecar.update(meta.id, package=package, certs=[SHOP_CERT])
    return meta


def site_login(w, origin='https://www.kanto.example', otp=None):
    secret = {'identifier_type': 'username', 'identifier': IDENTIFIER, 'password': PASSWORD}
    if otp:
        secret['otp_secret'] = otp
    return w.store.add_item('login', 'Kanto web', secret, origin=origin)


def card(w, origin=None):
    return w.store.add_item('payment', 'Visa', {'card_number': CARD, 'exp_month': '12', 'exp_year': '2099', 'cvc': CVC,
                                                'cardholder_name': 'Test Owner'}, origin=origin)


def fill(w, handle, context, owner=False, **kw):
    session = Session(w.vault, context, owner=owner)
    driver = Driver(session, **{k: kw.pop(k) for k in list(kw) if k in ('release_context', 'roles', 'tap', 'before_release')})
    return w.vault.fill(handle, session, driver, owner=owner, **kw), session, driver


def assert_no_secret(*things):
    text = json.dumps(things, ensure_ascii=False, default=str)
    for secret in SECRETS:
        assert secret not in text


# -- listing and binding ---------------------------------------------------------------------------------------------

def test_the_list_shows_handles_labels_and_identifiers_never_a_secret(world):
    login, payment = app_login(world), card(world)
    listed = world.vault.vault_list()
    entries = {e['handle']: e for e in listed['items']}
    assert entries[login.id]['identifier'] == IDENTIFIER and entries[login.id]['app'] == SHOP
    assert entries[payment.id]['kind'] == 'payment' and entries[payment.id]['available'] is True
    assert_no_secret(listed)


def test_an_app_login_binds_its_package_and_the_certificates_of_its_first_use(world):
    login = app_login(world, pinned=False)
    assert world.vault.bind(login, ctx())[0]
    # In an agent turn the first pin is never silent: the owner says yes once, on the action's own card (Root, 17 Nov).
    result, session, _ = fill(world, login.id, ctx())
    assert result['error_type'] == 'pin_approval_needed' and result['question'] == 'Use the Kanto Foods login in Kanto Foods?'
    assert not [r for r in session.requests if r['t'] == 'arm'] and not V.Sidecar(V.sidecar_path(world.home)).item(login.id)
    result, _, _ = fill(world, login.id, ctx(), pin_approved=True)
    assert result['success'] and V.Sidecar(V.sidecar_path(world.home)).item(login.id)['certs'] == [SHOP_CERT]
    assert world.vault.bind(login, ctx(certs=(OTHER_CERT,))) == (False, "This app isn't the one the login was saved in.")
    assert world.vault.bind(login, ctx(package=PROBE))[0] is False
    assert world.vault.bind(login, ctx(certs=()))[0] is False


def test_a_site_login_fills_only_in_the_pinned_chrome_on_the_exact_origin(world):
    login = site_login(world)
    good = ctx(package=CHROME, certs=(CHROME_CERT,), web='www.kanto.example', scheme='https', source='system')
    assert world.vault.bind(login, dict(good, source='other')) == (False, "This isn't the phone's own Chrome.")
    assert world.vault.sidecar.browser(CHROME) is None  # a sideloaded Chrome is never pinned (Root, 17 Nov)
    assert world.vault.bind(login, good) == (True, 'site')  # first sight of the system Chrome pins its certificate
    assert world.vault.bind(login, dict(good, certs=[OTHER_CERT]))[0] is False
    for web, scheme in (('kanto.example', 'https'), ('evil.www.kanto.example', 'https'), ('www.kanto.example', 'http'),
                        ('www.kanto.example', None)):
        assert world.vault.bind(login, dict(good, web_domain=web, web_scheme=scheme))[0] is False, (web, scheme)


def test_a_site_login_in_another_app_needs_digital_asset_links(world):
    login = site_login(world)
    in_app = ctx(package=SHOP, web='www.kanto.example', scheme='https')
    assert world.vault.bind(login, in_app) == (False, "This app isn't linked to that site.")
    world.vault._dal.clear()
    fingerprint = ':'.join(SHOP_CERT[i:i + 2] for i in range(0, 64, 2)).upper()
    world.statements['www.kanto.example'] = [{'relation': ['delegate_permission/common.get_login_creds'],
                                              'target': {'namespace': 'android_app', 'package_name': SHOP,
                                                         'sha256_cert_fingerprints': [fingerprint]}}]
    assert world.vault.bind(login, in_app) == (True, 'site-app')
    assert world.vault.bind(login, ctx(package=SHOP)) == (True, 'app-site')  # the site's login in its own linked app
    world.vault._dal.clear()
    assert world.vault.bind(login, dict(in_app, certs=[OTHER_CERT]))[0] is False  # same package, another signer


def test_cards_without_a_site_bind_anywhere_and_addresses_without_one_bind_nowhere(world):
    payment = card(world)
    address = world.store.add_item('address', 'Home', {'address_line1': '1 Test Road', 'city': 'Testville', 'postal_code': 'T1 1TT',
                                                       'country': 'Testland'})
    assert world.vault.bind(payment, ctx(package=PROBE)) == (True, 'card')
    assert world.vault.bind(address, ctx())[0] is False


# -- fills and the release ---------------------------------------------------------------------------------------------

def test_a_login_fill_releases_both_fields_masks_them_and_returns_counts_only(world):
    login = app_login(world)
    result, session, driver = fill(world, login.id, ctx())
    assert result == {'success': True, 'filled_fields': 2, 'kind': 'login', 'app': 'Kanto Foods'}
    assert session.answers[-1] == {'req': 'req_' + 'R' * 16, 'values': {'username': IDENTIFIER, 'password': PASSWORD}, 't': 'release'}
    assert driver.labels == ['Kanto Foods · ' + IDENTIFIER]
    arm = session.last_arm()
    assert arm['kind'] == 'login' and arm['ttl_ms'] == 20000 and len(arm['items']) == 1
    assert arm['items'][0]['roles'] == ['username', 'password'] and arm['items'][0]['label'] == 'Kanto Foods · ' + IDENTIFIER
    assert login.id not in json.dumps(arm)  # the helper gets item ids, never vault handles
    assert world.vault.masks.found_in('x' + PASSWORD) and PASSWORD in world.registered and IDENTIFIER not in world.registered
    assert_no_secret(result, world.notes)


def test_the_same_field_in_a_new_autofill_session_still_releases_and_another_field_does_not(world):
    login = app_login(world)
    before = ctx()
    for f in before['fields']:
        f['id'] = f['id'] + '@111'
    renumbered = json.loads(json.dumps(before))
    for f in renumbered['fields']:
        f['id'] = f['id'].replace('@111', '@222')  # the refresh ended the session: same views, new session part
    result, session, _ = fill(world, login.id, before, release_context=renumbered)
    assert result['success'] and session.answers[-1]['t'] == 'release'
    sibling = json.loads(json.dumps(renumbered))
    for f in sibling['fields']:
        f['focused'] = f['role'] == 'username'  # another field of the same form, which this login fills
    result, session, _ = fill(world, login.id, before, release_context=sibling)
    assert session.answers[-1]['t'] == 'release'
    elsewhere = json.loads(json.dumps(renumbered))
    elsewhere['fields'] = [{'id': '9999@222', 'role': 'password', 'focused': True}]  # a field the arm's form never had
    result, session, _ = fill(world, login.id, before, release_context=elsewhere)
    assert session.answers[-1]['t'] == 'deny'
    card_form = json.loads(json.dumps(renumbered))
    card_form['fields'].append({'id': 'af-cvc@222', 'role': 'cvc', 'focused': True})
    for f in card_form['fields'][:-1]:
        f['focused'] = False
    result, session, _ = fill(world, login.id, before, release_context=card_form)
    assert session.answers[-1]['t'] == 'deny'  # focus on a field the login doesn't fill


@pytest.mark.parametrize('change', ['package', 'certs', 'focus', 'role', 'web'])
def test_a_release_whose_own_context_changed_is_denied(world, change):
    login = app_login(world)
    before = ctx()
    outside = dict(before, fields=[{'id': 'af-elsewhere', 'role': 'password', 'focused': True}])  # focus outside the arm's form
    after = {'package': dict(before, package=PROBE), 'certs': dict(before, certs=[OTHER_CERT]),
             'focus': outside, 'role': before, 'web': dict(before, web_domain='kanto.example', web_scheme='https')}[change]
    roles = ['password', 'otp'] if change == 'role' else None
    result, session, _ = fill(world, login.id, before, release_context=after, roles=roles)
    assert session.answers[-1]['t'] == 'deny' and result['success'] is False
    assert PASSWORD not in world.registered


def test_an_expired_arm_is_denied_and_an_arm_releases_once(world):
    login = app_login(world)
    result, session, _ = fill(world, login.id, ctx(), before_release=lambda: setattr(world.clock, 'now', world.clock.now + 21))
    assert result['error_type'] == 'fill_refused' and session.answers[-1]['t'] == 'deny'
    result, session, _ = fill(world, login.id, ctx())
    assert result['success']
    arm_id = session.last_arm()['arm_id']
    session.on_event({'t': 'release_request', 'req': 'req_' + 'S' * 16, 'arm_id': arm_id, 'roles': ['password'], 'context': ctx()})
    assert session.answers[-1]['t'] == 'deny'  # the arm was spent by the first release


def test_no_dropdown_entry_disarms_and_says_so(world):
    login = app_login(world)
    result, session, _ = fill(world, login.id, ctx(), tap=False)
    assert result['error_type'] == 'no_entry' and session.requests[-1]['t'] == 'disarm'


def test_a_failure_while_resolving_denies_and_leaks_nothing(world, caplog, monkeypatch):
    caplog.set_level(logging.DEBUG)
    login = app_login(world)
    backend = world.vault.source.local

    def boom(handle):
        raise RuntimeError('cannot read ' + PASSWORD)
    monkeypatch.setattr(backend, 'resolve_password', boom)
    result, session, _ = fill(world, login.id, ctx())
    assert session.answers[-1]['t'] == 'deny' and not result['success']
    assert PASSWORD not in caplog.text and 'release refused' in caplog.text


def test_unfocused_or_unknown_items_are_refused_before_anything_is_armed(world):
    login = app_login(world)
    result, session, _ = fill(world, login.id, ctx(focus=None))
    assert result['error_type'] == 'no_field' and not [r for r in session.requests if r['t'] == 'arm']
    result, _, _ = fill(world, 'vault_000000000000', ctx())
    assert result['error_type'] == 'not_found'


# -- cards -------------------------------------------------------------------------------------------------------------

def test_a_card_asks_one_combined_question_and_records_the_grant(world):
    payment = card(world)
    asked, granted = [], []
    world.vault.h.consent = lambda message, description, **kw: (asked.append((message, kw)), 'accept')[1]
    world.vault.on_card_approved = lambda **grant: granted.append(grant)
    roles = ('cardholder_name', 'card_number', 'exp_date', 'cvc')
    result, session, _ = fill(world, payment.id, ctx(roles=roles, focus='card_number'), shop='Kanto Foods', amount='£12.40')
    assert asked == [('Pay £12.40 at Kanto Foods with Visa?', {'surface': 'vault-payment', 'title': 'Confirm payment card fill?'})]
    assert granted == [{'package': SHOP, 'handle': payment.id, 'amount': '£12.40', 'certs': [SHOP_CERT], 'task': 'default', 'session': None,
                        'web_domain': None}]
    assert result['filled_fields'] == 4
    assert session.answers[-1]['values'] == {'cardholder_name': 'Test Owner', 'card_number': CARD, 'exp_date': '12/99', 'cvc': CVC}
    assert {CARD, CVC} <= set(world.registered)
    assert_no_secret(result, world.notes)


def test_a_declined_card_fills_nothing_and_the_decline_stands_a_minute(world):
    payment = card(world)
    asked = []
    world.vault.h.consent = lambda message, description, **kw: (asked.append(message), 'decline')[1]
    roles = ('card_number', 'cvc')
    result, session, _ = fill(world, payment.id, ctx(roles=roles, focus='card_number'), task_id='t1', amount='£5')
    assert result['error_type'] == 'payment_declined' and not [r for r in session.requests if r['t'] == 'arm']
    result, _, _ = fill(world, payment.id, ctx(roles=roles, focus='card_number'), task_id='t1', amount='£5')
    assert result['error_type'] == 'payment_declined' and len(asked) == 1  # not asked again inside the minute
    world.clock.now += 61
    fill(world, payment.id, ctx(roles=roles, focus='card_number'), task_id='t1', amount='£5')
    assert len(asked) == 2


def test_a_card_release_needs_a_live_grant_for_that_app(world):
    payment = card(world)
    world.vault.h.consent = lambda *a, **kw: 'accept'
    roles = ('card_number', 'cvc')
    result, session, _ = fill(world, payment.id, ctx(roles=roles, focus='card_number'), amount='£5',
                              before_release=lambda: setattr(world.clock, 'now', world.clock.now + 301))
    assert session.answers[-1]['t'] == 'deny' and not result['success']


def test_the_owner_in_setup_fills_a_card_without_a_question(world):
    payment = card(world)
    world.vault.h.consent = lambda *a, **kw: pytest.fail('the owner holds the phone: no question')
    result, session, _ = fill(world, payment.id, ctx(roles=('card_number', 'cvc'), focus='card_number'), owner=True)
    assert result['success'] and session.answers[-1]['values'] == {'card_number': CARD, 'cvc': CVC}


def _offered(session):
    deadline = time.monotonic() + 5
    while not [r for r in session.requests if r['t'] == 'arm'] and time.monotonic() < deadline:
        time.sleep(0.01)
    return session.last_arm()


def test_setup_offers_every_matching_login_and_the_owner_picks_the_account(world):
    first = app_login(world)
    second = app_login(world, identifier='second.account', password=NEW_PASSWORD)
    app_login(world, identifier='other.app', package=PROBE)  # another app's login is not offered here
    session = Session(world.vault, ctx(), owner=True)
    session.on_event({'t': 'fill_context', 'context': ctx()})
    arm = _offered(session)
    assert [i['label'] for i in arm['items']] == ['Kanto Foods · second.account', 'Kanto Foods · ' + IDENTIFIER]  # newest first
    assert arm['package'] == SHOP and first.id not in json.dumps(arm) and second.id not in json.dumps(arm)
    session.on_event({'t': 'release_request', 'req': 'req_' + 'R' * 16, 'arm_id': arm['arm_id'], 'item_id': arm['items'][1]['item_id'],
                      'roles': ['username', 'password'], 'context': ctx()})
    assert session.answers[-1]['values'] == {'username': IDENTIFIER, 'password': PASSWORD}  # the account the owner tapped


def test_setup_pins_an_unpinned_app_login_at_first_sight(world):
    login = app_login(world, pinned=False)
    session = Session(world.vault, ctx(), owner=True)
    session.on_event({'t': 'fill_context', 'context': ctx()})
    arm = _offered(session)
    assert not world.vault.sidecar.item(login.id)
    session.on_event({'t': 'release_request', 'req': 'req_' + 'R' * 16, 'arm_id': arm['arm_id'], 'item_id': arm['items'][0]['item_id'],
                      'roles': ['username', 'password'], 'context': ctx()})
    assert session.answers[-1]['t'] == 'release' and world.vault.sidecar.item(login.id)['certs'] == [SHOP_CERT]


def test_an_unknown_item_id_or_an_unapproved_first_pin_is_denied(world):
    login = app_login(world)
    result, session, _ = fill(world, login.id, ctx())
    arm_id = session.last_arm()['arm_id']
    world.vault._armed[arm_id] = {'package': SHOP, 'certs': [SHOP_CERT], 'web': None, 'focus': None, 'until': world.clock.now + 20,
                                  'items': {'item_' + 'X' * 16: {'handle': login.id, 'kind': 'login', 'item_kind': 'login',
                                                                'roles': ['password'], 'held': None, 'code': None, 'pin': False}}}
    session.on_event({'t': 'release_request', 'req': 'req_' + 'S' * 16, 'arm_id': arm_id, 'item_id': 'item_' + 'Y' * 16,
                      'roles': ['password'], 'context': ctx()})
    assert session.answers[-1]['t'] == 'deny'  # an item this arm never offered
    unpinned = app_login(world, identifier='new.one', pinned=False)
    world.vault._armed[arm_id] = {'package': SHOP, 'certs': [SHOP_CERT], 'web': None, 'focus': None, 'until': world.clock.now + 20,
                                  'items': {'item_' + 'X' * 16: {'handle': unpinned.id, 'kind': 'login', 'item_kind': 'login',
                                                                'roles': ['password'], 'held': None, 'code': None, 'pin': False}}}
    session.on_event({'t': 'release_request', 'req': 'req_' + 'S' * 16, 'arm_id': arm_id, 'item_id': 'item_' + 'X' * 16,
                      'roles': ['password'], 'context': ctx()})
    assert session.answers[-1]['t'] == 'deny' and not world.vault.sidecar.item(unpinned.id)  # no silent first pin


# -- saves ---------------------------------------------------------------------------------------------------------------

def save_event(values=None, context=None):
    return {'t': 'save', 'save_id': 'save_' + 'C' * 16, 'context': context or ctx(),
            'values': values or {'username': IDENTIFIER, 'password': PASSWORD}}


def test_the_owners_own_save_in_setup_is_written_at_once_with_its_certificates(world):
    session = Session(world.vault, ctx(), owner=True)
    session.on_event(save_event())
    items = world.store.list_items()
    assert [(m.origin, m.identifier, m.label) for m in items] == [('android://' + SHOP, IDENTIFIER, 'Kanto Foods')]
    assert world.store.resolve_secret(items[0].id)['password'] == PASSWORD
    assert world.vault.sidecar.item(items[0].id)['certs'] == [SHOP_CERT]
    assert world.notes[-1] == ('saved', {'label': 'Kanto Foods', 'identifier': IDENTIFIER, 'line': 'Saved: Kanto Foods · ' + IDENTIFIER,
                                      'reason': 'vault.saved'})


def test_a_save_in_the_agents_turn_waits_for_the_owners_yes(world):
    session = Session(world.vault, ctx())
    session.on_event(save_event())
    assert world.store.list_items() == [] and world.notes[-1][0] == 'vault_save'
    held = world.notes[-1][1]['held']
    assert world.vault.decide_save(held, True)['saved'] and len(world.store.list_items()) == 1
    session.on_event(save_event({'username': 'other.user', 'password': NEW_PASSWORD}))
    no = world.notes[-1][1]['held']
    assert world.vault.decide_save(no, False) == {'success': True, 'saved': False}
    assert len(world.store.list_items()) == 1 and world.vault.pending() == []
    assert world.vault.decide_save(no, True)['error_type'] == 'not_pending'  # gone after the no
    assert_no_secret(world.notes)


def test_a_held_save_is_gone_after_its_ten_minutes(world, monkeypatch):
    monkeypatch.setattr(V, 'HOLD_SECONDS', 0.2)
    Session(world.vault, ctx()).on_event(save_event())
    held = world.notes[-1][1]['held']
    deadline = time.monotonic() + 5
    while world.vault.pending() and time.monotonic() < deadline:
        time.sleep(0.02)
    assert world.vault.pending() == [] and world.vault.decide_save(held, True)['error_type'] == 'not_pending'
    assert world.store.list_items() == []


def test_a_new_password_for_a_saved_login_replaces_it_after_the_yes(world):
    old = app_login(world)
    Session(world.vault, ctx(), owner=True).on_event(save_event({'username': IDENTIFIER, 'password': NEW_PASSWORD}))
    assert world.notes[-1][0] == 'vault_update' and world.notes[-1][1]['line'] == 'Replace the saved Kanto Foods login?'
    result = world.vault.decide_save(world.notes[-1][1]['held'], True)
    items = world.store.list_items()
    assert len(items) == 1 and items[0].id == result['handle'] != old.id
    assert world.store.resolve_secret(items[0].id)['password'] == NEW_PASSWORD


def test_saves_without_a_username_or_with_a_page_without_scheme_are_dropped(world):
    session = Session(world.vault, ctx(), owner=True)
    session.on_event(save_event({'password': PASSWORD}))
    session.on_event(save_event(context=ctx(package=CHROME, certs=(CHROME_CERT,), web='kanto.example', scheme=None)))
    assert world.store.list_items() == [] and world.vault.pending() == []


def test_a_temporary_password_from_the_sites_mail_is_held_for_the_owner(world):
    login = site_login(world)
    session = Session(world.vault, ctx())
    session.note_value = NEW_PASSWORD
    asked_at = world.clock.now - 30  # the agent's submit that asked the site for a new password (L3's since), 30 s ago
    out = world.vault.hold_temporary_password(session, handle=login.id, mail_packages=[MAIL], since=asked_at)
    assert out == {'success': True, 'asked_owner': True}
    asked = session.requests[-1]
    assert asked['senders'] == {'packages': [], 'hosts': ['www.kanto.example', 'kanto.example'], 'mail_packages': [MAIL]}
    assert asked['max_age_ms'] == 30000  # only what came after that submit
    world.vault.decide_save(world.notes[-1][1]['held'], True)
    items = world.store.list_items()
    assert len(items) == 1 and world.store.resolve_secret(items[0].id)['password'] == NEW_PASSWORD  # replaced the old item
    assert_no_secret(out, world.notes)


# -- sign-ups, resets and failed logins ---------------------------------------------------------------------------------

SIGNUP = ctx(roles=('username', 'new_password'), focus='new_password')


def test_a_signup_fills_a_generated_password_and_commits_on_success(world):
    session = Session(world.vault, SIGNUP)
    result = world.vault.new_login(session, Driver(session), identifier=IDENTIFIER, label='Kanto Foods')
    assert result['success'] and result['filled_fields'] == 2
    released = session.answers[-1]['values']
    assert released['username'] == IDENTIFIER and len(released['new_password']) == 20
    assert world.store.list_items() == []  # nothing written before the result
    saved = world.vault.settle_signup(result['pending'], 'success')
    meta = world.store.get_meta(saved['handle'])
    assert meta.origin == 'android://' + SHOP and world.store.resolve_secret(meta.id)['password'] == released['new_password']
    assert world.vault.sidecar.item(meta.id)['status'] == 'ok' and 'not_confirmed' not in saved
    assert released['new_password'] not in json.dumps([result, saved, world.notes])


def test_a_signup_whose_result_cannot_be_read_commits_marked_not_confirmed(world):
    session = Session(world.vault, SIGNUP)
    result = world.vault.new_login(session, Driver(session), identifier=IDENTIFIER, label='Kanto Foods')
    saved = world.vault.settle_signup(result['pending'], 'unknown')
    assert saved['not_confirmed'] is True
    entry = next(e for e in world.vault.vault_list()['items'] if e['handle'] == saved['handle'])
    assert entry['status'] == 'not_confirmed'
    assert world.vault.login_result(saved['handle'], 'success') == {'success': True, 'status': 'ok'}  # its first good sign-in
    assert 'status' not in next(e for e in world.vault.vault_list()['items'] if e['handle'] == saved['handle'])


def test_a_clear_signup_failure_keeps_it_for_one_retry_then_drops_it(world):
    session = Session(world.vault, SIGNUP)
    result = world.vault.new_login(session, Driver(session), identifier=IDENTIFIER, label='Kanto Foods')
    assert world.vault.settle_signup(result['pending'], 'failure') == {'success': False, 'saved': False, 'retry': True}
    assert world.vault.settle_signup(result['pending'], 'failure') == {'success': False, 'saved': False, 'retry': False}
    assert world.vault.settle_signup(result['pending'], 'success')['error_type'] == 'not_pending'
    assert world.store.list_items() == []


def test_a_pending_signup_is_gone_after_ten_minutes(world, monkeypatch):
    monkeypatch.setattr(V, 'HOLD_SECONDS', 0.2)
    session = Session(world.vault, SIGNUP)
    result = world.vault.new_login(session, Driver(session), identifier=IDENTIFIER, label='Kanto Foods')
    time.sleep(0.5)
    assert world.vault.settle_signup(result['pending'], 'success')['error_type'] == 'not_pending'
    assert world.store.list_items() == []


def test_a_reset_adds_the_new_login_and_removes_the_old_after_its_first_sign_in(world):
    old = app_login(world)
    session = Session(world.vault, SIGNUP)
    result = world.vault.new_login(session, Driver(session), identifier=IDENTIFIER, label='Kanto Foods', replaces=old.id)
    saved = world.vault.settle_signup(result['pending'], 'success')
    assert {m.id for m in world.store.list_items()} == {old.id, saved['handle']}
    world.vault.login_result(saved['handle'], 'success')
    assert [m.id for m in world.store.list_items()] == [saved['handle']]


def test_a_failed_login_marks_the_item_and_tells_the_owner(world):
    login = app_login(world)
    assert world.vault.login_result(login.id, 'failure') == {'success': False, 'status': 'login_failed'}
    assert world.notes[-1] == ('needs_you', {'label': 'Kanto Foods', 'line': "The Kanto Foods login didn't work.",
                                             'reason': 'vault.login_failed'})
    assert next(e for e in world.vault.vault_list()['items'] if e['handle'] == login.id)['status'] == 'didnt_work'


def test_what_the_screen_says_after_a_submit():
    def screen(*texts, password=None):
        nodes = ''.join(f'<node text="{t}" content-desc="" password="false" bounds="[0,0][1,1]" />' for t in texts)
        if password is not None:
            nodes += f'<node text="{password}" hint="Password" content-desc="" password="true" bounds="[0,0][1,1]" />'
        return f'<hierarchy>{nodes}</hierarchy>'
    assert V.submit_outcome([screen('Kanto Foods', 'Welcome')]) == 'success'
    assert V.submit_outcome([screen('Kanto Foods', 'Wrong password', password='')]) == 'failure'
    assert V.submit_outcome([screen('كانتو', 'كلمة المرور غير صحيحة', password='')]) == 'failure'
    assert V.submit_outcome([screen('Sign up', 'That name is already taken', password='')]) == 'failure'
    assert V.submit_outcome(['', '<not xml']) == 'unknown'
    assert V.submit_outcome([screen('Kanto', 'Sign in', password='••••'), screen('Kanto', 'Sign in', password='Password')]) == 'failure'
    assert V.submit_outcome([screen('Kanto', 'Sign in', password='••••')]) == 'unknown'
    # A failure word on a screen without the form is not a failure: landing pages say such things too.
    assert V.submit_outcome([screen('Welcome to Kanto', 'Payment failed? Try again from your basket')]) == 'unknown'
    assert V.submit_outcome([screen('Kanto', 'Sign in', password=''), screen('Welcome', 'Your orders')]) == 'success'


def test_generated_passwords_are_long_mixed_and_new_each_time():
    made = {V.generate_password() for _ in range(200)}
    assert len(made) == 200
    for value in made:
        assert len(value) == 20 and any(c.isupper() for c in value) and any(c.islower() for c in value)
        assert any(c.isdigit() for c in value) and any(c in '-_.!#%*+' for c in value)


# -- codes ---------------------------------------------------------------------------------------------------------------

OTP = ctx(package=CHROME, certs=(CHROME_CERT,), roles=('otp',), focus='otp', web='www.kanto.example', scheme='https')


def test_a_code_comes_from_the_items_authenticator_key_first(world):
    from agent.vault_store import totp_now
    login = site_login(world, otp=CODE_SEED)
    session = Session(world.vault, OTP)
    result = world.vault.code(session, Driver(session), handle=login.id)
    assert result['source'] == 'authenticator' and result['filled_fields'] == 1
    assert session.answers[-1]['values']['otp'] in {totp_now(CODE_SEED), totp_now(CODE_SEED, at=time.time() - 30)}


def test_a_code_from_the_sites_notification_is_filled_by_the_helper_itself(world):
    login = site_login(world)
    session = Session(world.vault, OTP)
    session.code_found = True
    refreshed = []
    world.vault.refresh = lambda: refreshed.append(True)
    submitted = world.clock.now - 30  # the agent's submit that led to the code screen, 30 s ago
    result = world.vault.code(session, Driver(session), handle=login.id, mail_packages=[MAIL], since=submitted)
    assert refreshed == [True]  # B8: refresh after arm_code too
    assert result['source'] == 'notification' and result['filled_fields'] == 1
    asked = session.last_arm()
    assert asked['senders'] == {'packages': [], 'hosts': ['www.kanto.example', 'kanto.example'], 'mail_packages': [MAIL]}
    assert asked['max_age_ms'] == 30000 and not session.answers  # only codes since that submit; the code never came to the host


class Phone(Session):
    """A helper that keeps notifications as contract §6.4 does: arm_code takes the newest code from the site's app posted at
    most max_age_ms ago. Ages are durations, so the vault's clock stands in for the phone's."""

    def __init__(self, vault, context, clock):
        super().__init__(vault, context)
        self.clock, self.notes, self.armed_code = clock, [], None

    def post(self, package, code):
        self.notes.append({'posted': self.clock(), 'package': package, 'code': code})

    def request(self, t, timeout=None, **fields):
        if t != 'arm_code':
            return super().request(t, timeout, **fields)
        self.requests.append(dict(fields, t=t))
        fresh = [n for n in self.notes if n['package'] in fields['senders']['packages']
                 and self.clock() - n['posted'] <= fields['max_age_ms'] / 1000]
        newest = max(fresh, key=lambda n: n['posted'], default=None)
        self.armed_code = newest['code'] if newest else None
        return {'t': 'code_result', 're': 1, 'found': newest is not None}


APP_OTP = ctx(roles=('otp',), focus='otp')


def test_a_code_from_before_the_sign_in_step_never_fills_and_the_newest_after_it_does(world):
    # L3's live run 5: a code from an earlier sign-in (same app, under 10 minutes old) filled a new code screen and failed.
    login = app_login(world)
    world.vault.h.can_prompt_here = lambda: False
    phone = Phone(world.vault, ctx(), world.clock)
    phone.post(SHOP, '111111')  # the earlier sign-in's code
    world.clock.now += 120
    assert world.vault.fill(login.id, phone, Driver(phone))['success']  # the sign-in step: the login's arm
    world.clock.now += 5  # submitted; the code screen opens before the site's new code arrives
    phone.context = APP_OTP
    result = world.vault.code(phone, Driver(phone), handle=login.id)
    assert phone.armed_code is None and result['error_type'] == 'needs_owner'  # the old code never fills; the owner is asked
    assert phone.last_arm()['max_age_ms'] == 5000
    world.clock.now += 3
    phone.post(SHOP, '222222')
    world.clock.now += 1
    phone.post(SHOP, '333333')
    world.clock.now += 1
    result = world.vault.code(phone, Driver(phone), handle=login.id)
    assert result['source'] == 'notification' and phone.armed_code == '333333'  # the newest one after the step
    world.clock.now += 20  # a second code screen: the code just used is not new for it
    result = world.vault.code(phone, Driver(phone), handle=login.id)
    assert phone.armed_code is None and result['error_type'] == 'needs_owner'


def test_with_no_sign_in_step_the_owner_is_asked_and_the_later_of_arm_and_submit_counts(world):
    login = app_login(world)
    world.vault.h.can_prompt_here = lambda: False
    phone = Phone(world.vault, APP_OTP, world.clock)
    phone.post(SHOP, '111111')
    world.clock.now += 5
    result = world.vault.code(phone, Driver(phone), handle=login.id)  # a sign-in Beacon never saw: no step to count from
    assert result['error_type'] == 'needs_owner' and not [r for r in phone.requests if r['t'] == 'arm_code']
    submitted = world.clock.now  # the agent's own submit (L3: the time taken right before its tap)
    world.clock.now += 2
    phone.post(SHOP, '222222')
    world.clock.now += 1
    result = world.vault.code(phone, Driver(phone), handle=login.id, since=submitted)
    assert result['source'] == 'notification' and phone.armed_code == '222222' and phone.last_arm()['max_age_ms'] == 3000
    phone.context = ctx()
    assert world.vault.fill(login.id, phone, Driver(phone))['success']  # a new sign-in: the arm...
    world.clock.now += 3
    phone.post(SHOP, '333333')  # ...a code before its submit is not this sign-in's...
    world.clock.now += 1
    submitted = world.clock.now  # ...the submit is the later step
    world.clock.now += 2
    phone.context = APP_OTP
    result = world.vault.code(phone, Driver(phone), handle=login.id, since=submitted)
    assert phone.armed_code is None and result['error_type'] == 'needs_owner' and phone.last_arm()['max_age_ms'] == 2000


def test_a_sign_in_on_one_site_is_no_step_for_another_sites_code_in_the_same_browser(world):
    kanto, other = site_login(world), site_login(world, origin='https://www.other.example')
    world.vault.h.can_prompt_here = lambda: False
    browser = ctx(package=CHROME, certs=(CHROME_CERT,), web='www.other.example', scheme='https', source='system')
    phone = Phone(world.vault, browser, world.clock)
    assert world.vault.fill(other.id, phone, Driver(phone))['success']  # a sign-in on www.other.example
    world.clock.now += 5
    phone.context = OTP  # www.kanto.example's code screen in the same Chrome
    result = world.vault.code(phone, Driver(phone), handle=kanto.id)
    assert result['error_type'] == 'needs_owner' and not [r for r in phone.requests if r['t'] == 'arm_code']


def test_without_a_matching_notification_the_owner_types_the_code_or_is_asked(world):
    login = site_login(world)
    session = Session(world.vault, OTP)
    world.vault.h.code_prompt = lambda: (lambda site, hint: '48 29 13')
    world.vault.h.can_prompt_here = lambda: True
    result = world.vault.code(session, Driver(session), handle=login.id)
    assert result['source'] == 'owner' and session.answers[-1]['values'] == {'otp': '482913'}
    world.vault.h.can_prompt_here = lambda: False
    result = world.vault.code(Session(world.vault, OTP), Driver(session), handle=login.id)
    assert result['error_type'] == 'needs_owner'
    assert world.notes[-1] == ('vault_code', {'app': 'www.kanto.example', 'label': 'Kanto web', 'line': 'Enter the code for Kanto web.',
                                              'reason': 'vault.code'})  # no note: the card's title and line say enough (Root, 17 Nov)


def test_refresh_runs_after_the_arm_and_before_the_tap_for_fills_and_setup_offers(world):
    login = app_login(world)
    order = []
    world.vault.refresh = lambda: order.append('refresh')
    session = Session(world.vault, ctx())
    real_request = session.request

    def request(t, timeout=None, **fields):
        if t == 'arm':
            order.append('arm')
        return real_request(t, timeout, **fields)
    session.request = request

    class Watching(Driver):
        def pick_entry(self, context, label, roles=()):
            order.append('tap')
            return super().pick_entry(context, label, roles)
    assert world.vault.fill(login.id, session, Watching(session))['success']
    assert order == ['arm', 'refresh', 'tap']  # B5: Android asks again only after its session ends
    order.clear()
    owner = Session(world.vault, ctx(), owner=True)  # the owner's setup session: the offer arms, then refreshes
    owner_request = owner.request
    owner.request = lambda t, timeout=None, **fields: (order.append('arm') if t == 'arm' else None, owner_request(t, timeout, **fields))[1]
    owner.on_event({'t': 'fill_context', 'context': ctx()})
    deadline = time.monotonic() + 5
    while order != ['arm', 'refresh'] and time.monotonic() < deadline:
        time.sleep(0.01)
    assert order == ['arm', 'refresh']
    world.vault.refresh = lambda: (_ for _ in ()).throw(RuntimeError('adb gone'))
    assert world.vault.fill(login.id, Session(world.vault, ctx()), Driver(session))['success'] is not None  # a failed refresh never raises


def test_ask_owner_fills_the_code_the_owner_typed_through_the_held_code_path(world):
    login = site_login(world)
    asked = []
    world.vault.h.can_prompt_here = lambda: False  # no Hermes viewer: Beacon's own record (L3) asks the owner
    session = Session(world.vault, OTP)
    result = world.vault.code(session, Driver(session), handle=login.id, ask_owner=lambda app, label: asked.append((app, label)) or '48 29-13')
    assert asked == [('www.kanto.example', 'Kanto web')]
    assert result['source'] == 'owner' and result['filled_fields'] == 1
    assert session.answers[-1]['values'] == {'otp': '482913'}  # released only for the armed code field, as any held code
    assert not [n for n in world.notes if n[0] == 'vault_code']  # the record replaced the notice
    declined = world.vault.code(Session(world.vault, OTP), Driver(session), handle=login.id, ask_owner=lambda app, label: None)
    assert declined['error_type'] == 'code_declined'
    def broken(app, label):
        raise RuntimeError('record failed 482913')
    failed = world.vault.code(Session(world.vault, OTP), Driver(session), handle=login.id, ask_owner=broken)
    assert failed['error_type'] == 'code_declined' and '482913' not in json.dumps(failed)


def test_code_hosts_follow_the_contract():
    assert V.code_hosts('https://www.kanto.example') == ['www.kanto.example', 'kanto.example']
    assert V.code_hosts('https://shop.kanto.example:8443') == ['shop.kanto.example']
    assert V.code_hosts('https://localhost') == [] and V.code_hosts('android://dev.beacon.fixture.shop') == []
    assert V.code_hosts('https://www.example') == ['www.example']  # never a single-label name


# -- logins the agent has no item for --------------------------------------------------------------------------------------

def test_save_login_without_a_viewer_or_in_setup_gives_the_needs_you_path(world):
    session = Session(world.vault, ctx())
    result = world.vault.save_login(session, Driver(session))
    assert result['error_type'] == 'prompt_unavailable'
    assert world.notes[-1] == ('needs_you', {'app': 'Kanto Foods', 'site': 'Kanto Foods', 'line': 'Sign in to Kanto Foods on the phone.',
                                         'reason': 'vault.sign_in'})


def test_save_login_with_a_viewer_stores_through_hermes_prompt_and_fills(world, monkeypatch):
    monkeypatch.setattr(world.vault.source, 'owner_only', False)
    world.vault.h.can_prompt_here = lambda: True
    world.vault.h.save_prompt = lambda: (lambda origin, site: {'identifier': IDENTIFIER, 'password': PASSWORD})
    session = Session(world.vault, ctx())
    result = world.vault.save_login(session, Driver(session))
    assert result['success'] and result['fill']['filled_fields'] == 2
    meta = world.store.get_meta(result['handle'])
    assert meta.origin == 'android://' + SHOP and world.vault.sidecar.item(meta.id)['certs'] == [SHOP_CERT]
    assert_no_secret(result)


# -- profiles and managers ------------------------------------------------------------------------------------------------

def test_the_dashboard_binds_the_owning_profiles_vault_not_the_requests(world, tmp_path, monkeypatch, hermes):
    from agent.vault_store import VaultStore
    request_home = tmp_path / 'request-profile'
    VaultStore(base_dir=request_home / 'vault').add_item('login', 'Other profile', {'identifier_type': 'username', 'identifier': 'x',
                                                                                    'password': NEW_PASSWORD}, origin='android://x.y')
    monkeypatch.setenv('HERMES_HOME', str(request_home))
    from agent.vault_store import get_vault_store
    assert [m.label for m in get_vault_store().list_items()] == ['Other profile']  # control: a bare store follows the request
    app_login(world)
    assert [e['label'] for e in world.vault.vault_list()['items']] == ['Kanto Foods']


def test_another_profiles_agent_session_is_refused(hermes):
    with pytest.raises(PermissionError):
        V.PhoneVault.for_agent('work', 'default', R.MaskSet(), home='unused')


class Manager:
    name, display_name, prefix = 'onepassword', '1Password', 'op:'

    def __init__(self, unlocked=True):
        self.needs_unlock, self.unlocked = True, unlocked

    def owns(self, handle):
        return handle.startswith('op:')

    def is_unlocked(self):
        return self.unlocked

    def get_meta(self, handle):
        from agent.vault_store import VaultItemMeta
        return VaultItemMeta(id=handle, kind='login', label='Kanto (1P)', origin='android://' + SHOP, created_at='', identifier=IDENTIFIER,
                             identifier_type='username')


def test_a_locked_manager_item_asks_for_the_unlock_and_a_manager_item_is_never_written(hermes, tmp_path):
    vault = V.PhoneVault.for_agent('default', 'default', R.MaskSet(), home=tmp_path)
    manager = Manager(unlocked=False)
    vault.h.backend_for_handle = lambda handle: manager if handle.startswith('op:') else None
    vault.h.enabled_backends = lambda: [manager]
    session = Session(vault, ctx())
    assert vault.fill('op:abc', session, Driver(session)) ['error'] == 'Unlock 1Password in Beacon first.'
    assert vault.vault_list()['locked'][0]['unlock'] == 'Unlock 1Password in Beacon first.'
    manager.unlocked = True
    out = vault.new_login(Session(vault, SIGNUP), Driver(session), identifier=IDENTIFIER, label='Kanto', replaces='op:abc')
    assert out['error_type'] == 'manager_item' and out['error'].startswith('Update it in 1Password.')


# -- over the real sealed channel -----------------------------------------------------------------------------------------

def test_a_fill_over_the_real_channel_session(world):
    sys.path.insert(0, str(PLUGIN / 'tests'))
    from test_agent_phone_channel import FakeHelper, load_channel
    c = load_channel()
    login = app_login(world)
    session = c.Session()
    session.on_event = world.vault.handler(session)
    session.open()
    helper = FakeHelper(c, session.port, session._token, context=ctx()).dial()
    assert session.ready.wait(5)

    class RealDriver:
        def pick_entry(self, context, label, roles=()):
            arm = helper.wait_for('arm')
            item = arm['items'][0]
            def ask():
                helper.send({'t': 'release_request', 'req': 'req_' + 'R' * 16, 'arm_id': arm['arm_id'], 'item_id': item['item_id'],
                             'roles': item['roles'],
                             'context': ctx()})
                release = helper.wait_for('release')
                helper.send({'t': 'filled', 'req': release['req'], 'arm_id': arm['arm_id'], 'fields': len(release['values'])})
            threading.Thread(target=ask, daemon=True).start()
            return True
    try:
        result = world.vault.fill(login.id, session, RealDriver())
        assert result == {'success': True, 'filled_fields': 2, 'kind': 'login', 'app': 'Kanto Foods'}
        assert helper.wait_for('release')['values'] == {'username': IDENTIFIER, 'password': PASSWORD}
    finally:
        session.close()


# -- whole-module rules -----------------------------------------------------------------------------------------------------

def test_the_item_roles_are_the_channels_kinds():
    sys.path.insert(0, str(PLUGIN / 'tests'))
    from test_agent_phone_channel import load_channel
    c = load_channel()
    for kind, roles in V.ITEM_ROLES.items():
        assert set(roles) <= set(c.KINDS[kind]), kind


def test_no_value_path_goes_through_hermes_tool_dispatch_and_the_owner_line_is_plain():
    source = (PLUGIN / 'agent_phone_vault.py').read_text(encoding='utf-8')
    assert 'handle_function_call' not in source and 'pre_tool_call' not in source
    assert not hasattr(V, 'CODE_SENDER_LINE')  # no rule text on the code card (Root, 17 Nov: no mechanics on screen)


def test_the_canary_finds_no_secret_in_any_record_or_log_after_every_flow(world, caplog):
    caplog.set_level(logging.DEBUG)
    login, payment = app_login(world), card(world)
    world.vault.h.consent = lambda *a, **kw: 'accept'
    results = [fill(world, login.id, ctx())[0],
               fill(world, payment.id, ctx(roles=('card_number', 'cvc'), focus='card_number'), amount='£1')[0]]
    session = Session(world.vault, ctx())
    session.on_event(save_event({'username': IDENTIFIER, 'password': NEW_PASSWORD}))
    results.append(world.vault.pending())
    results.append(world.vault.vault_list())
    assert PASSWORD in world.registered and CARD in world.registered  # positive control: the values did flow
    assert_no_secret(results, world.notes)
    for secret in SECRETS:
        assert secret not in caplog.text
    world.vault.forget()
    assert world.vault.pending() == []


# -- review fixes ---------------------------------------------------------------------------------------------------------

def test_a_signup_retry_reuses_the_same_held_password(world):
    session = Session(world.vault, SIGNUP)
    first = world.vault.new_login(session, Driver(session), identifier=IDENTIFIER, label='Kanto Foods')
    password = session.answers[-1]['values']['new_password']
    assert world.vault.settle_signup(first['pending'], 'failure')['retry'] is True
    again = world.vault.new_login(session, Driver(session), identifier=IDENTIFIER, label='Kanto Foods', pending=first['pending'])
    assert again['pending'] == first['pending'] and session.answers[-1]['values']['new_password'] == password
    saved = world.vault.settle_signup(first['pending'], 'success')
    assert world.store.resolve_secret(saved['handle'])['password'] == password


def test_one_yes_covers_one_card_fill(world):
    payment = card(world)
    world.vault.h.consent = lambda *a, **kw: 'accept'
    result, _, _ = fill(world, payment.id, ctx(roles=('card_number', 'cvc'), focus='card_number'), amount='£5')
    assert result['success'] and (SHOP, payment.id) not in world.vault._grants


class ChannelError(Exception):
    pass


def test_a_dropped_channel_is_a_plain_refusal(world):
    login = app_login(world)
    session = Session(world.vault, ctx())

    def dropped(t, timeout=None, **fields):
        raise ChannelError("The phone's helper is not connected.")
    session.request = dropped
    result = world.vault.fill(login.id, session, Driver(session))
    assert result == {'success': False, 'error_type': 'not_connected', 'error': V.NOT_CONNECTED}


def test_a_locked_manager_is_unlocked_through_hermes_prompt_when_a_viewer_is_there(hermes, tmp_path):
    vault = V.PhoneVault.for_agent('default', 'default', R.MaskSet(), home=tmp_path)
    manager = Manager(unlocked=False)
    vault.h.backend_for_handle = lambda handle: manager if handle.startswith('op:') else None
    vault.h.can_prompt_here = lambda: True
    asked = []

    def unlock(name):
        asked.append(name)
        manager.unlocked = True
        return json.dumps({'success': True, 'backend': name})
    vault.h.unlock = unlock
    session = Session(vault, ctx(focus=None))
    assert vault.fill('op:abc', session, Driver(session))['error_type'] == 'no_field'  # got past the lock
    assert asked == ['onepassword']


def test_open_link_takes_the_sites_link_from_the_helper_and_the_host_opens_it(world):
    login = site_login(world)
    session, opened = Session(world.vault, ctx()), []
    opener = lambda url: opened.append(url) or True  # noqa: E731  (L3's adb `am start -a VIEW -d <url>`)
    asked_at = world.clock.now - 30  # the agent's submit that asked the site for the link (L3's since), 30 s ago
    result = world.vault.open_link(session, opener, handle=login.id, mail_packages=[MAIL], since=asked_at)
    assert result == {'success': True, 'opened': 'link'} and opened == ['https://kanto.example/magic?t=abc']
    assert 'magic' not in json.dumps(result)  # the address never comes back to the model
    assert session.requests[-1] == {'senders': {'packages': [], 'hosts': ['www.kanto.example', 'kanto.example'],
                                                'mail_packages': [MAIL]}, 'max_age_ms': 30000, 't': 'note_link'}
    for bad in ('https://kanto.example.evil.example/x', 'http://kanto.example/magic'):  # the host checks it again
        session.link = bad
        assert world.vault.open_link(session, opener, handle=login.id, since=asked_at)['error_type'] == 'link_mismatch'
    assert opened == ['https://kanto.example/magic?t=abc']
    session.link = None
    assert world.vault.open_link(session, opener, handle=login.id, since=asked_at)['error_type'] == 'not_found'
    assert world.vault.open_link(session, opener, handle=app_login(world).id, since=asked_at)['error_type'] == 'no_site'


def test_setup_offers_never_fetch_asset_links(world):
    site_login(world)
    fetched = []
    world.vault.dal_fetch = lambda domain: fetched.append(domain) or []
    session = Session(world.vault, ctx(), owner=True)
    session.on_event({'t': 'fill_context', 'context': ctx(web='www.kanto.example', scheme='https')})
    deadline = time.monotonic() + 2
    while world.vault._offering.locked() and time.monotonic() < deadline:
        time.sleep(0.01)
    assert fetched == [] and not [r for r in session.requests if r['t'] == 'arm']


# -- Remove (L1a's remove_phone) ----------------------------------------------------------------------------------------

def test_forget_phone_deletes_only_the_logins_saved_from_the_phone_and_always_the_sidecar(world):
    outside = app_login(world, identifier='saved.elsewhere')  # the owner's item from Hermes; the phone only pinned it
    Session(world.vault, ctx(), owner=True).on_event(save_event())  # the owner's save on the phone
    session = Session(world.vault, SIGNUP)
    signup = world.vault.new_login(session, Driver(session), identifier='new.account', label='Kanto Foods')
    world.vault.settle_signup(signup['pending'], 'success')  # the agent's sign-up on the phone
    assert len(world.store.list_items()) == 3 and V.sidecar_path(world.home).exists()
    assert world.vault.sidecar.item(outside.id)['certs'] == [SHOP_CERT]  # control: pinned, so pins alone can't tell
    assert V.forget_phone(world.home, delete_logins=True) == 2
    assert [m.id for m in world.store.list_items()] == [outside.id]
    assert not V.sidecar_path(world.home).exists()
    assert V.forget_phone(world.home, delete_logins=True) == 0  # idempotent: nothing left to forget, no error


def test_forget_phone_keeps_every_login_when_not_asked_and_skips_ones_already_gone(world):
    Session(world.vault, ctx(), owner=True).on_event(save_event())
    Session(world.vault, ctx(), owner=True).on_event(save_event({'username': 'second.user', 'password': NEW_PASSWORD}))
    first, second = [m.id for m in world.store.list_items()]
    assert V.forget_phone(world.home, delete_logins=False) == 0
    assert len(world.store.list_items()) == 2 and not V.sidecar_path(world.home).exists()
    # With the sidecar back and one item already removed by the owner, only the one still there counts.
    world.vault.sidecar.update(first, saved_on_phone=True)
    world.vault.sidecar.update(second, saved_on_phone=True)
    world.store.remove_item(first)
    assert V.forget_phone(world.home, delete_logins=True) == 1 and world.store.list_items() == []


def test_forget_phone_resolves_the_owning_profiles_home_when_given_the_hermes_home(world, tmp_path, monkeypatch):
    Session(world.vault, ctx(), owner=True).on_event(save_event())
    monkeypatch.setattr(V, 'owning_home', lambda profile: world.home if profile == 'work' else tmp_path / 'nowhere')
    elsewhere = tmp_path / 'hermes-home'  # the process's Hermes home: no sidecar of this phone there
    assert V.forget_phone(elsewhere, 'work', delete_logins=True) == 1
    assert world.store.list_items() == [] and not V.sidecar_path(world.home).exists()


def test_a_second_setup_request_for_the_same_form_keeps_the_live_arm(world):
    first = app_login(world)
    refreshed = []
    world.vault.refresh = lambda: refreshed.append(True)
    session = Session(world.vault, ctx(), owner=True)
    session.on_event({'t': 'fill_context', 'context': ctx()})
    arm = _offered(session)
    deadline = time.monotonic() + 5
    while not refreshed and time.monotonic() < deadline:
        time.sleep(0.01)
    renumbered = ctx()
    for f in renumbered['fields']:
        f['id'] = f['id'] + '@2'  # the request after the refresh: same form, new autofill session
    session.on_event({'t': 'fill_context', 'context': renumbered})
    time.sleep(0.3)
    arms = [r for r in session.requests if r['t'] == 'arm']
    assert len(arms) == 1 and refreshed == [True]  # no second arm, no second refresh: no loop
    session.on_event({'t': 'release_request', 'req': 'req_' + 'R' * 16, 'arm_id': arm['arm_id'], 'item_id': arm['items'][0]['item_id'],
                      'roles': ['username', 'password'], 'context': renumbered})
    assert session.answers[-1]['values'] == {'username': IDENTIFIER, 'password': PASSWORD}  # the entry on screen still works
    assert first.id


# -- links and temporary passwords: the codes' step rule (EMU-JOIN wiring of L9 follow-up 1, Root 17 Nov) -------------------

class Mail(Session):
    """A helper that keeps the site's mails as contract §6.5/§6.6 do: note_link / note_password take the newest one posted at
    most max_age_ms ago (ages are durations, so the vault's clock stands in for the phone's)."""

    def __init__(self, vault, clock):
        super().__init__(vault, ctx())
        self.clock, self.mails = clock, []

    def post(self, kind, value):
        self.mails.append({'posted': self.clock(), 'kind': kind, 'value': value})

    def request(self, t, timeout=None, **fields):
        if t not in ('note_link', 'note_password'):
            return super().request(t, timeout, **fields)
        self.requests.append(dict(fields, t=t))
        kind = 'link' if t == 'note_link' else 'password'
        fresh = [m for m in self.mails if m['kind'] == kind and self.clock() - m['posted'] <= fields['max_age_ms'] / 1000]
        newest = max(fresh, key=lambda m: m['posted'], default=None)
        if newest is None:
            return {'t': t + '_result', 're': 1, 'found': False}
        return {'t': t + '_result', 're': 1, 'found': True, ('url' if kind == 'link' else 'value'): newest['value']}


def test_a_sign_in_link_counts_only_after_the_step_the_newest_and_once(world):
    login = site_login(world)
    mail, opened = Mail(world.vault, world.clock), []
    opener = lambda url: opened.append(url) or True  # noqa: E731
    mail.post('link', 'https://kanto.example/magic?t=old')  # an earlier sign-in's link
    world.clock.now += 60
    no_step = world.vault.open_link(mail, opener, handle=login.id, mail_packages=[MAIL])
    assert no_step['error_type'] == 'no_step' and not mail.requests and opened == []  # no step: nothing counts, nothing asked
    asked = world.clock.now  # the agent's submit: "email me a sign-in link"
    world.clock.now += 5
    assert world.vault.open_link(mail, opener, handle=login.id, since=asked)['error_type'] == 'not_found'  # the old one is too old
    mail.post('link', 'https://kanto.example/magic?t=new1')
    world.clock.now += 1
    mail.post('link', 'https://kanto.example/magic?t=new2')
    world.clock.now += 1
    assert world.vault.open_link(mail, opener, handle=login.id, since=asked)['success']
    assert opened == ['https://kanto.example/magic?t=new2']  # the newest after the step
    world.clock.now += 10
    assert world.vault.open_link(mail, opener, handle=login.id, since=asked)['error_type'] == 'not_found'  # used once
    assert opened == ['https://kanto.example/magic?t=new2']


def test_a_temporary_password_counts_only_after_the_step_the_newest_and_once(world):
    login = site_login(world)
    mail = Mail(world.vault, world.clock)
    mail.post('password', 'Old-Temporary-1')
    world.clock.now += 60
    assert world.vault.hold_temporary_password(mail, handle=login.id)['error_type'] == 'no_step' and not mail.requests
    asked = world.clock.now
    world.clock.now += 3
    mail.post('password', 'New-Temporary-2')
    world.clock.now += 1
    assert world.vault.hold_temporary_password(mail, handle=login.id, since=asked) == {'success': True, 'asked_owner': True}
    world.vault.decide_save(world.notes[-1][1]['held'], True)
    assert world.store.resolve_secret(world.store.list_items()[0].id)['password'] == 'New-Temporary-2'
    world.clock.now += 10
    assert world.vault.hold_temporary_password(mail, handle=login.id, since=asked)['error_type'] == 'not_found'  # used once


def test_a_link_the_phone_failed_to_open_is_not_used_up(world):
    login = site_login(world)
    mail = Mail(world.vault, world.clock)
    asked = world.clock.now
    world.clock.now += 2
    mail.post('link', 'https://kanto.example/magic?t=only')
    world.clock.now += 1
    assert world.vault.open_link(mail, lambda url: False, handle=login.id, since=asked)['error_type'] == 'open_failed'
    opened = []
    assert world.vault.open_link(mail, lambda url: opened.append(url) or True, handle=login.id, since=asked)['success']
    assert opened == ['https://kanto.example/magic?t=only']
    assert world.vault.open_link(mail, lambda url: True, handle=login.id, since=asked)['error_type'] == 'not_found'  # now used


def test_the_field_the_helper_put_a_code_in_is_masked_though_the_host_never_saw_the_code(world):
    """EMU-JOIN fu1: code_filled names the field (targets, contract revision 4), and the read guard masks it in captures."""
    login = site_login(world)
    session = Session(world.vault, OTP)
    session.code_found = True
    target = {'id': '1073741832', 'view_id': None, 'bounds': [40, 600, 1040, 720]}

    class Reporting(Driver):
        def pick_entry(self, context, label, roles=()):
            arm = self.session.last_arm()
            self.session.on_event({'t': 'code_filled', 'arm_id': arm['arm_id'], 'fields': 1, 'targets': [target]})
            return True

    result = world.vault.code(session, Reporting(session), handle=login.id, mail_packages=[MAIL], since=world.clock.now - 30)
    assert result['source'] == 'notification' and 'targets' not in result and not session.answers
    xml = ('<hierarchy rotation="0"><node index="0" text="Enter the code" class="android.widget.TextView" package="%s" '
           'bounds="[40,400][1040,500]" /><node index="1" text="731904" class="android.widget.EditText" package="%s" '
           'bounds="[40,600][1040,720]" /><node index="2" text="Verify" class="android.widget.Button" package="%s" '
           'bounds="[40,800][1040,900]" /></hierarchy>') % (CHROME, CHROME, CHROME)
    tree, _, counts = R.mask_tree(xml, world.vault.masks, CHROME)
    assert '731904' not in tree and counts == {'filled_code': 1}


# -- PHONEFIX-121 (REVIEW-PHONE-121 P2-3): the order pass exists only for a card that went in ------------------------------------

def test_p2_3_a_card_fill_that_filled_nothing_records_no_purchase_grant(world):
    payment = card(world)
    granted = []
    world.vault.h.consent = lambda message, description, **kw: 'accept'
    world.vault.on_card_approved = lambda **grant: granted.append(grant)
    roles = ('cardholder_name', 'card_number', 'exp_date', 'cvc')
    result, session, _ = fill(world, payment.id, ctx(roles=roles, focus='card_number'), shop='Kanto Foods', amount='£12.40',
                              tap=False)  # Beacon's entry never showed: nothing was filled
    assert result['success'] is False and result['error_type'] == 'no_entry'
    assert granted == [], 'the order input at £12.40 in this app is now covered for 5 minutes, whatever card the shop uses'
    assert world.vault._grants == {}  # and the vault's own release pass is gone too


def test_p2_3_the_grant_is_recorded_once_the_card_is_in_and_names_the_task(world):
    payment = card(world)
    granted = []
    world.vault.h.consent = lambda message, description, **kw: 'accept'
    world.vault.on_card_approved = lambda **grant: granted.append(dict(grant, released=bool(session_holder and session_holder[0].answers)))
    session_holder = []
    roles = ('cardholder_name', 'card_number', 'exp_date', 'cvc')
    session = Session(world.vault, ctx(roles=roles, focus='card_number'))
    session_holder.append(session)
    result = world.vault.fill(payment.id, session, Driver(session), task_id='task-9', shop='Kanto Foods', amount='£12.40')
    assert result['success'] is True and result['filled_fields'] == 4
    assert granted == [{'package': SHOP, 'handle': payment.id, 'amount': '£12.40', 'certs': [SHOP_CERT], 'task': 'task-9',
                        'session': None, 'web_domain': None, 'released': True}]  # recorded after the helper released the card, never at the yes


# -- PHONEFIX-121 follow-up 1: a question the owner declined or missed leaves no grant -------------------------------------------

@pytest.mark.parametrize('answer', ['decline', 'cancel', 'timeout', None])
def test_fu1_a_declined_or_missed_card_question_records_no_grant(world, answer):
    payment = card(world)
    granted = []
    world.vault.h.consent = lambda message, description, **kw: answer
    world.vault.on_card_approved = lambda **grant: granted.append(grant)
    roles = ('card_number', 'cvc')
    result, session, _ = fill(world, payment.id, ctx(roles=roles, focus='card_number'), task_id='t1', shop='Kanto Foods', amount='£5')
    assert result['error_type'] == 'payment_declined' and granted == [] and world.vault._grants == {}
    assert not [r for r in session.requests if r['t'] == 'arm']  # nothing was armed, nothing filled


# -- PHONEFIX-121 follow-up 4: the grant carries the call's session and the site, and the question names the site ---------------

def test_fu4_a_web_fill_names_the_site_in_the_question_and_the_grant(world):
    payment = card(world)
    messages, granted = [], []
    world.vault.h.consent = lambda message, description, **kw: messages.append(message) or 'accept'
    world.vault.on_card_approved = lambda **grant: granted.append(grant)
    roles = ('cardholder_name', 'card_number', 'exp_date', 'cvc')
    context = ctx(package=CHROME, certs=(CHROME_CERT,), roles=roles, focus='card_number', web='shop-a.example', scheme='https', source='system')
    result, session, _ = fill(world, payment.id, context, task_id='task-9', session_id='chat-1', shop='Google Chrome', amount='£12.40')
    assert result['success'] is True, result
    assert messages == ['Pay £12.40 at shop-a.example with Visa?'], messages  # the site, not the browser's name
    assert granted == [{'package': CHROME, 'handle': payment.id, 'amount': '£12.40', 'certs': [CHROME_CERT], 'task': 'task-9',
                        'session': 'chat-1', 'web_domain': 'shop-a.example'}]


def test_fu4_an_app_fill_keeps_the_shops_name_and_no_site(world):
    payment = card(world)
    messages, granted = [], []
    world.vault.h.consent = lambda message, description, **kw: messages.append(message) or 'accept'
    world.vault.on_card_approved = lambda **grant: granted.append(grant)
    roles = ('cardholder_name', 'card_number', 'exp_date', 'cvc')
    result, session, _ = fill(world, payment.id, ctx(roles=roles, focus='card_number'), task_id='t1', session_id='s1', shop='Kanto Foods', amount='£5')
    assert result['success'] is True and messages == ['Pay £5 at Kanto Foods with Visa?']
    assert granted[0]['web_domain'] is None and granted[0]['session'] == 's1'


def test_r2_7_a_page_in_a_shops_own_app_carries_its_site_too(world):
    """Every fill that has a site carries it (R2-7). A browser's grant is covered only while that browser's own address bar shows the
    host; a WebView in a shop's app has none, so its order asks once more."""
    payment = card(world)
    messages, granted = [], []
    world.vault.h.consent = lambda message, description, **kw: messages.append(message) or 'accept'
    world.vault.on_card_approved = lambda **grant: granted.append(grant)
    roles = ('cardholder_name', 'card_number', 'exp_date', 'cvc')
    context = ctx(package=SHOP, roles=roles, focus='card_number', web='pay.kanto.example', scheme='https')
    result, session, _ = fill(world, payment.id, context, task_id='t1', session_id='s1', shop='Kanto Foods', amount='£5')
    assert result['success'] is True, result
    assert messages == ['Pay £5 at pay.kanto.example with Visa?'] and granted[0]['web_domain'] == 'pay.kanto.example' and granted[0]['package'] == SHOP


def test_r2_7_another_browsers_fill_carries_its_site(world):
    payment = card(world)
    granted = []
    world.vault.h.consent = lambda message, description, **kw: 'accept'
    world.vault.on_card_approved = lambda **grant: granted.append(grant)
    roles = ('cardholder_name', 'card_number', 'exp_date', 'cvc')
    context = ctx(package='org.mozilla.firefox', certs=(CHROME_CERT,), roles=roles, focus='card_number', web='shop-a.example', scheme='https')
    result, session, _ = fill(world, payment.id, context, task_id='t1', session_id='s1', shop='Firefox', amount='£5')
    assert result['success'] is True and granted[0]['web_domain'] == 'shop-a.example' and granted[0]['package'] == 'org.mozilla.firefox'


def test_fu6_a_fill_whose_record_cannot_be_written_is_not_released(world):
    """I1: the stop reads the masks file to know a fill happened. A record that can't be written is a fill the stop would not see, so
    nothing is released."""
    login = app_login(world)
    world.vault.masks.register = lambda value, package=None: False  # run/ is missing, or the disk is full
    result, session, driver = fill(world, login.id, ctx())
    assert result['success'] is False
    assert not [a for a in session.answers if a.get('t') == 'release'] and PASSWORD not in world.registered
    assert_no_secret(result, world.notes)


def _notification_code_fill(world, session):
    login = site_login(world)
    session.code_found = True
    return world.vault.code(session, Driver(session), handle=login.id, mail_packages=[MAIL], since=world.clock.now - 30)


def test_fu7_the_apps_fill_record_is_written_before_the_helper_arms_a_code(world, tmp_path):
    """R4-4 (I1): the helper puts the code in itself, so the host never sees it. The app's record (what the stop reads to know a filled
    screen may be on the phone) is on disk before arm_code, not only after the helper reports the field."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    world.vault.masks = R.MaskSet(store=store)
    session = Session(world.vault, OTP)
    on_disk, real = [], session.request

    def request(t, timeout=None, **fields):
        if t == 'arm_code':
            on_disk.append(store.exists())
        return real(t, timeout=timeout, **fields)

    session.request = request
    result = _notification_code_fill(world, session)
    assert result['success'] is True and result['source'] == 'notification'
    assert on_disk == [True]


def test_fu7_a_code_fill_whose_record_cannot_be_written_is_not_armed(world):
    session = Session(world.vault, OTP)
    world.vault.masks.mark_filled = lambda package: False  # run/ is missing, or the disk is full
    result = _notification_code_fill(world, session)
    assert result['success'] is False and not [r for r in session.requests if r['t'] == 'arm_code']
    assert not session.answers and not world.notes


def test_fu11_a_code_app_whose_record_cannot_be_written_is_logged_not_refused(world, caplog):
    """R6-4: the code is already in when the vault asks the mask set to keep the app, so there is nothing to refuse; but a restart would lose
    the code app, and the log says so."""
    import logging
    session = Session(world.vault, OTP)
    world.vault.masks.watch_fields = lambda package, targets: False  # run/ is missing, or the disk is full
    with caplog.at_level(logging.WARNING, logger='beacon.agent_phone.vault'):
        result = _notification_code_fill(world, session)
    assert result['success'] is True and result['source'] == 'notification'
    assert 'code app could not be recorded' in caplog.text
