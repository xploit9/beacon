"""The agent phone's approval records (contract docs/agent-phone/approvals-v1.md): private homes, no network, no emulator."""
import importlib.util
import json
import sys
import threading
import time
from decimal import Decimal
from pathlib import Path

import pytest
from test_mobile import harness, phone  # noqa: F401  (fixtures)

ROOT = Path(__file__).resolve().parents[1]
ADDRESS = 'agent' + '@' + 'example.com'


def load_approvals():
    spec = importlib.util.spec_from_file_location('beacon_agent_phone_approvals', ROOT / 'agent_phone_approvals.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules['beacon_agent_phone_approvals'] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def ap(harness, monkeypatch):
    api, client, cfg, tmp, app = harness
    module = load_approvals()
    monkeypatch.setattr(module, 'POLL_SECONDS', 0.01)
    yield module, api, client
    sys.modules.pop('beacon_agent_phone_approvals', None)


PAY = {'risk': 'payment', 'action': 'tap', 'app': 'Kanto Foods', 'label': 'Pay £12.40', 'amount': '£12.40', 'purpose': 'dinner'}


def rows(api, sql, *args):
    with api.mobile.connect() as conn:
        return [tuple(r) for r in conn.execute(sql, args).fetchall()]


# ---- records ---------------------------------------------------------------------------------------------------------

def test_record_is_listed_decided_once_and_raises_its_notice(ap):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY, {'package': 'dev.shop', 'bounds': [1, 2, 3, 4]})
    listed = module.open_records('owner')
    assert len(listed) == 1 and listed[0]['id'] == record_id and listed[0]['kind'] == 'agent_phone_action'
    assert {k: listed[0][k] for k in PAY} == PAY
    assert 0 < listed[0]['expires_in_seconds'] <= 120
    assert 'bind' not in listed[0] and 'package' not in listed[0]  # the binding never reaches a device
    assert rows(api, 'SELECT kind,state,conversation FROM notices WHERE id=?', record_id) == [('agent_phone_approval', 'pending', 'agent-phone-' + record_id)]
    assert module.decide('owner', record_id, True, 'device-1') is True
    with pytest.raises(ValueError, match='settled'):
        module.decide('owner', record_id, False, 'device-2')
    assert module.status('owner', record_id) == 'approved'
    assert rows(api, 'SELECT state FROM notices WHERE id=?', record_id) == [('answered',)]
    assert module.open_records('owner') == []


def test_foreign_unknown_and_malformed_answers_are_refused(ap):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    with pytest.raises(LookupError):
        module.decide('someone-else', record_id, True, 'd')
    with pytest.raises(LookupError):
        module.decide('owner', '0' * 32, True, 'd')
    for bad in ('../x', record_id.upper(), 7):
        with pytest.raises(ValueError, match='invalid'):
            module.decide('owner', bad, True, 'd')
    with pytest.raises(ValueError, match='invalid'):
        module.decide('owner', record_id, 'yes', 'd')
    with pytest.raises(ValueError, match='invalid'):  # a code only answers a vault_code record
        module.decide('owner', record_id, True, 'd', code='123456')
    assert module.status('owner', record_id) == 'pending'


def test_payload_keeps_labels_only(ap):
    module, api, client = ap
    payload = dict(PAY, password='hunter2', image='data:image/png;base64,AAAA', handle='vault_x', label='Pay\nnow\x00 £12.40' + 'x' * 300)
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', payload)
    stored = json.loads(rows(api, 'SELECT payload FROM agent_phone_approvals WHERE id=?', record_id)[0][0])
    assert set(stored) <= set(module.FIELDS) | set(module.FLAGS) and stored['pay'] is False  # the flag is a boolean, never the model's text
    assert 'hunter2' not in json.dumps(stored) and 'base64' not in json.dumps(stored) and 'vault_x' not in json.dumps(stored)
    assert stored['label'].startswith('Pay now £12.40') and len(stored['label']) == 120
    with pytest.raises(ValueError):
        module.create('owner', 'default', 'chat', 'agent_phone_action', {'app': 'x'})  # an action record names its risk
    with pytest.raises(ValueError):
        module.create('owner', 'default', 'chat', 'agent_phone_action', dict(PAY, risk='nothing'))
    with pytest.raises(ValueError):
        module.create('owner', 'default', 'chat', 'phone_request', PAY)


def test_limits_ten_open_and_thirty_a_minute(ap, monkeypatch):
    module, api, client = ap
    made = [module.create('owner', 'default', 'chat', 'agent_phone_action', PAY) for _ in range(10)]
    with pytest.raises(RuntimeError):
        module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    module.create('other-owner', 'default', 'chat', 'agent_phone_action', PAY)  # per owner
    for record_id in made:
        module.decide('owner', record_id, False, 'd')
    for _ in range(10):
        module.decide('owner', module.create('owner', 'default', 'chat', 'agent_phone_action', PAY), False, 'd')
    for _ in range(10):
        module.decide('owner', module.create('owner', 'default', 'chat', 'agent_phone_action', PAY), False, 'd')
    with pytest.raises(RuntimeError):  # denied ones count: 30 in the minute
        module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    later = time.time() + 61
    monkeypatch.setattr(module, '_now', lambda: later)
    module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)


def test_listed_for_every_linked_device_with_phone_access_off(ap):
    module, api, client = ap
    first, second = phone(client, 'Pixel'), phone(client, 'Fold')
    # No phone reported Phone access at all.
    assert api.mobile.phone_state('owner')['hermes_access'] is False
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    assert [r['id'] for r in module.open_records('owner')] == [record_id]
    module.decide('owner', record_id, True, second['x-beacon-device-id'])
    assert rows(api, 'SELECT device FROM agent_phone_approvals WHERE id=?', record_id) == [(second['x-beacon-device-id'],)]


def test_records_are_listed_across_profiles(ap):
    module, api, client = ap
    record_id = module.create('owner', 'work', 'chat', 'agent_phone_action', PAY)
    assert [r['id'] for r in module.open_records('owner')] == [record_id]


def test_wait_returns_the_answer(ap):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    threading.Timer(0.05, module.decide, args=('owner', record_id, True, 'd')).start()
    assert module.wait('owner', record_id, seconds=2) == 'approved'
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    threading.Timer(0.05, module.decide, args=('owner', record_id, False, 'd')).start()
    assert module.wait('owner', record_id, seconds=2) == 'denied'


def test_unanswered_wait_withdraws_so_a_late_yes_cannot_act(ap):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    assert module.wait('owner', record_id, seconds=0.05) == 'expired'
    assert module.status('owner', record_id) == 'withdrawn'
    with pytest.raises(ValueError, match='expired'):
        module.decide('owner', record_id, True, 'd')
    assert rows(api, 'SELECT state FROM notices WHERE id=?', record_id) == [('expired',)]


def test_wait_never_exceeds_120_seconds(ap):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    slept = []
    clock = [0.0]
    module._mono = lambda: clock[0]

    def sleep(seconds):
        slept.append(seconds)
        clock[0] += seconds
    assert module.wait('owner', record_id, seconds=10_000, sleep=sleep) == 'expired'
    assert sum(slept) == pytest.approx(120, abs=0.6)
    module._mono = time.monotonic


def test_expired_records_end_and_drop_from_the_list(ap, monkeypatch):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    later = time.time() + 121
    monkeypatch.setattr(module, '_now', lambda: later)
    assert module.open_records('owner') == []
    assert module.status('owner', record_id) == 'expired'
    with pytest.raises(ValueError, match='expired'):
        module.decide('owner', record_id, True, 'd')
    monkeypatch.setattr(module, '_now', lambda: later + 3600)
    module.sweep()
    assert rows(api, 'SELECT COUNT(*) FROM agent_phone_approvals') == [(0,)]


def test_vault_code_answer_is_read_once_and_scrubbed(ap):
    module, api, client = ap

    def owner_types():
        deadline = time.time() + 2
        while time.time() < deadline:
            found = [r for r in module.open_records('owner') if r['kind'] == 'vault_code']
            if found:
                assert found[0]['line'] == 'Enter the code for Kanto Foods.'
                module.decide('owner', found[0]['id'], True, 'd', code='482913')
                return
            time.sleep(0.01)
    threading.Thread(target=owner_types).start()
    assert module.ask_owner_code('owner', 'default', 'chat', 'Kanto Foods', 'Kanto Foods', note='x') == '482913'
    assert rows(api, "SELECT answer FROM agent_phone_approvals WHERE kind='vault_code'") == [('',)]
    record_id = module.create('owner', 'default', 'chat', 'vault_code', {'label': 'X', 'line': 'Enter the code for X.'})
    with pytest.raises(ValueError, match='invalid'):  # a yes to a code record carries the code
        module.decide('owner', record_id, True, 'd')
    with pytest.raises(ValueError, match='invalid'):
        module.decide('owner', record_id, True, 'd', code='12; rm')
    module.decide('owner', record_id, False, 'd')
    assert module.take_answer('owner', record_id) is None


def test_vault_code_answer_is_scrubbed_when_nobody_reads_it(ap, monkeypatch):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'vault_code', {'label': 'X', 'line': 'Enter the code for X.'})
    module.decide('owner', record_id, True, 'd', code='998877')
    later = time.time() + 121
    monkeypatch.setattr(module, '_now', lambda: later)
    module.sweep()
    assert rows(api, 'SELECT answer FROM agent_phone_approvals WHERE id=?', record_id) == [('',)]


def test_vault_save_is_applied_in_the_holder_process(ap):
    module, api, client = ap
    decided = []
    threads = []
    vault = type('V', (), {'decide_save': lambda self, held, yes: decided.append((held, yes))})()
    notify = module.vault_notifier('owner', 'default', 'chat', lambda: vault,
                                   spawn=lambda target: threads.append(threading.Thread(target=target)) or threads[-1].start(),
                                   sleep=lambda s: time.sleep(0.01))
    notify('vault_save', {'held': 'held_abc', 'label': 'Kanto Foods', 'identifier': ADDRESS, 'line': 'Save the new Kanto Foods login?',
                          'password': 'never-stored'})
    record = module.open_records('owner')[0]
    assert record['kind'] == 'vault_save' and record['identifier'] == ADDRESS and record['line'] == 'Save the new Kanto Foods login?'
    assert 'never-stored' not in json.dumps(rows(api, 'SELECT * FROM agent_phone_approvals'))
    module.decide('owner', record['id'], True, 'd')
    threads[0].join(3)
    assert decided == [('held_abc', True)]


def test_vault_save_of_a_dead_holder_is_withdrawn(ap):
    module, api, client = ap
    record_id = module.create('owner', 'default', 'chat', 'vault_save', {'label': 'X', 'line': 'Save the new X login?'})
    with api.mobile.connect() as conn:
        conn.execute("UPDATE agent_phone_approvals SET holder='999999:1.0' WHERE id=?", (record_id,))
    assert module.open_records('owner') == []
    assert module.status('owner', record_id) == 'withdrawn'


def test_needs_you_and_saved_notices(ap):
    module, api, client = ap
    notify = module.vault_notifier('owner', 'default', 'chat', lambda: None)
    notify('needs_you', {'label': 'Kanto Foods', 'line': "The Kanto Foods login didn't work."})
    notify('saved', {'label': 'Kanto Foods', 'identifier': ADDRESS, 'line': 'Saved: Kanto Foods · ' + ADDRESS})
    needs = module.open_records('owner')
    assert [(r['kind'], r['line']) for r in needs] == [('needs_you', "The Kanto Foods login didn't work.")]
    assert needs[0]['expires_in_seconds'] > 3600
    assert module.activity('owner')[0]['label'] == ADDRESS  # the identifier as data; the app words 'save' / 'saved' (rev 5)


def test_notify_never_raises(ap, monkeypatch):
    module, api, client = ap
    monkeypatch.setattr(module, 'create', lambda *a, **k: (_ for _ in ()).throw(RuntimeError('full')))
    module.vault_notifier('owner', 'default', 'chat', lambda: None)('needs_you', {'line': 'x'})


def test_declines_stand_sixty_seconds_per_task(ap, monkeypatch):
    module, api, client = ap
    clock = [100.0]
    monkeypatch.setattr(module, '_mono', lambda: clock[0])
    assert module.declined_for('task-a') == 0
    module.note_decline('task-a')
    assert module.declined_for('task-a') == 60 and module.declined_for('task-b') == 0
    clock[0] += 61
    assert module.declined_for('task-a') == 0


# ---- amounts and the bound purchase grant ------------------------------------------------------------------------------

@pytest.mark.parametrize('text,expected', [
    ('Pay £12.40', ('GBP', Decimal('12.40'))),
    ('Total: 12.40 GBP', ('GBP', Decimal('12.40'))),
    ('€1.234,50', ('EUR', Decimal('1234.50'))),
    ('$1,234.50', ('USD', Decimal('1234.50'))),
    ('ادفع ١٢٫٤٠ ر.س', ('SAR', Decimal('12.40'))),
    ('الإجمالي 45 درهم', ('AED', Decimal('45.00'))),
    ('SAR 1,500', ('SAR', Decimal('1500.00'))),
    ('Rs 1,500', ('RS', Decimal('1500.00'))),
])
def test_amounts_are_found_and_normalized(ap, text, expected):
    module, api, client = ap
    assert module.normalize_amount(text) == expected


def test_no_amount_in_plain_text(ap):
    module, api, client = ap
    assert module.normalize_amount('Place order') is None
    assert module.normalize_amount('Order 12 items') is None


def grants(module, clock):
    return module.PurchaseGrants(clock=lambda: clock[0])


def test_one_yes_covers_one_order_input_at_the_same_amount(ap):
    module, api, client = ap
    clock = [0.0]
    g = grants(module, clock)
    g.record('dev.beacon.fixture.shop', 'vault_card', '£12.40', ['ab' * 32])
    screen = [module.normalize_amount('Total £12.40')]
    grant = g.covering('dev.beacon.fixture.shop', screen)
    assert grant and grant['handle'] == 'vault_card'
    assert g.consume(grant) is True
    assert g.covering('dev.beacon.fixture.shop', screen) is None  # a second order asks again
    assert g.consume(grant) is False


def test_another_amount_app_or_no_amount_is_not_covered(ap):
    module, api, client = ap
    clock = [0.0]
    g = grants(module, clock)
    g.record('dev.shop', 'vault_card', '£12.40')
    assert g.covering('dev.shop', [module.normalize_amount('£13.00')]) is None
    assert g.covering('dev.other', [module.normalize_amount('£12.40')]) is None
    assert g.covering('dev.shop', [module.normalize_amount('$12.40')]) is None  # same number, other currency
    assert g.covering('dev.shop', []) is None
    g.record('dev.shop2', 'vault_card', None)  # the screen showed no amount when the card was approved
    assert g.covering('dev.shop2', [module.normalize_amount('£12.40')]) is None


def test_grant_lives_five_minutes(ap):
    module, api, client = ap
    clock = [0.0]
    g = grants(module, clock)
    g.record('dev.shop', 'vault_card', '£12.40')
    clock[0] = 299
    assert g.covering('dev.shop', [module.normalize_amount('£12.40')])
    clock[0] = 301
    assert g.covering('dev.shop', [module.normalize_amount('£12.40')]) is None


def test_on_card_approved_records_the_grant(ap):
    module, api, client = ap
    module.GRANTS.clear()
    module.on_card_approved(package='dev.shop', handle='vault_card', amount='£12.40', certs=['cd' * 32])
    assert module.GRANTS.covering('dev.shop', [module.normalize_amount('Pay £12.40')])
    module.on_card_approved(package=None, handle='x', amount='£1')  # malformed: ignored
    module.GRANTS.clear()


# ---- policy and activity -----------------------------------------------------------------------------------------------

def test_policy_defaults_and_changes(ap):
    module, api, client = ap
    assert module.policy('owner') == {'enabled': True, 'ask': 'payments_and_sends'}
    assert module.set_policy('owner', ask='every_action') == {'enabled': True, 'ask': 'every_action'}
    assert module.set_policy('owner', enabled=False)['enabled'] is False
    assert module.policy('owner') == {'enabled': False, 'ask': 'every_action'}
    assert module.policy('someone-else') == {'enabled': True, 'ask': 'payments_and_sends'}
    with pytest.raises(ValueError):
        module.set_policy('owner', ask='never')
    with pytest.raises(ValueError):
        module.set_policy('owner', enabled='yes')


def test_activity_keeps_labels_and_the_newest_rows(ap, monkeypatch):
    module, api, client = ap
    monkeypatch.setattr(module, 'ACTIVITY_KEEP', 3)
    for i in range(5):
        module.add_activity('owner', 'default', 'Kanto Foods', 'tap', f'Button {i}\n', i % 2 == 0, 'done')
    shown = module.activity('owner')
    assert [r['label'] for r in shown] == ['Button 4', 'Button 3', 'Button 2']
    assert shown[0]['approved'] is True and shown[1]['approved'] is False


def test_needs_you_notices_have_their_own_cap_and_never_block_a_payment_question(ap):
    module, api, client = ap
    notices = [module.create('owner', 'default', 'chat', 'needs_you', {'line': f'The shop {i} login didn\'t work.'}) for i in range(7)]
    shown = module.open_records('owner')
    assert [r['line'] for r in shown] == [f"The shop {i} login didn't work." for i in range(2, 7)]  # the newest five
    assert module.status('owner', notices[0]) == 'expired'
    payments = [module.create('owner', 'default', 'chat', 'agent_phone_action', PAY) for _ in range(10)]
    shown = module.open_records('owner')
    assert [r['kind'] for r in shown] == ['agent_phone_action'] * 10 + ['needs_you'] * 5  # decisions first, then notices
    with pytest.raises(RuntimeError):
        module.create('owner', 'default', 'chat', 'agent_phone_action', PAY)
    assert len(payments) == 10


def test_done_notice_names_the_activity_row(ap):
    module, api, client = ap
    seq = module.add_activity('owner', 'default', 'Kanto Foods', 'tap', 'Pay £12.40', True, 'done')
    assert isinstance(seq, int) and module.activity('owner')[0]['seq'] == seq
    notice_id = module.notify_done('owner', 'default', 'payment', seq)
    with api.mobile.connect() as conn:
        row = dict(conn.execute('SELECT kind,reason,activity_key,state,conversation,expires-created AS life FROM notices WHERE id=?',
                                (notice_id,)).fetchone())
    assert row == {'kind': 'agent_phone_done', 'reason': 'payment', 'activity_key': f'agent_phone_activity:{seq}', 'state': 'pending',
                   'conversation': 'agent-phone-done-' + notice_id, 'life': 3600}
    with pytest.raises(ValueError):
        module.notify_done('owner', 'default', 'tap', seq)


def test_p2_3_a_grant_covers_only_the_task_and_session_that_got_the_yes(ap):
    """PHONEFIX-121: the grant is process-wide state; a scheduled run of the same profile in the same app at the same amount is
    another task, and another chat another session."""
    module, api, client = ap
    clock = [0.0]
    g = grants(module, clock)
    g.record('dev.shop', 'vault_card', '£12.40', task='t1', session='s1')
    amounts = [module.normalize_amount('£12.40')]
    assert g.covering('dev.shop', amounts, task='t2', session='s1') is None
    assert g.covering('dev.shop', amounts, task='t1', session='s2') is None
    assert g.covering('dev.shop', amounts) is None  # a call with no task and session is not the one that got the yes either
    grant = g.covering('dev.shop', amounts, task='t1', session='s1')
    assert grant and g.consume(grant) is True


def test_p2_3_on_card_approved_carries_the_task_and_session(ap):
    module, api, client = ap
    module.GRANTS.clear()
    module.on_card_approved(package='dev.shop', handle='vault_card', amount='£12.40', certs=[], task='t1', session='s1')
    amounts = [module.normalize_amount('Pay £12.40')]
    assert module.GRANTS.covering('dev.shop', amounts, task='t2', session='s1') is None
    assert module.GRANTS.covering('dev.shop', amounts, task='t1', session='s1')
    module.GRANTS.clear()


def test_fu1_a_grant_covers_one_step_tap_then_the_order_tap_uses_it_up(ap):
    module, api, client = ap
    clock = [0.0]
    g = grants(module, clock)
    g.record('dev.shop', 'vault_card', '£12.40', task='t1', session='s1')
    amounts = [module.normalize_amount('£12.40')]
    grant = g.covering('dev.shop', amounts, task='t1', session='s1')
    assert [g.step(grant) for _ in range(3)] == [True, False, False]  # R4-2: one step per grant, so Back to a step screen asks
    assert g.covering('dev.shop', amounts, task='t1', session='s1')  # the order tap can still be
    assert g.consume(grant) is True and g.covering('dev.shop', amounts, task='t1', session='s1') is None
    assert g.step(grant) is False  # used up


# ---- PHONEFIX-121 follow-up 4: the grant names the site of a web fill, and the action question says whether the control pays -----

def test_fu4_a_web_grant_covers_a_tap_only_on_its_own_site(ap):
    module, api, client = ap
    module.GRANTS.clear()
    module.on_card_approved(package='com.android.chrome', handle='vault_card', amount='£12.40', certs=[], task='t1', session='s1',
                            web_domain='shop-a.example')
    amounts = [module.normalize_amount('£12.40')]
    covers = lambda host: module.GRANTS.covering('com.android.chrome', amounts, task='t1', session='s1', host=host)  # noqa: E731
    assert covers('shop-a.example') and covers('Checkout.Shop-A.example') and covers('www.shop-a.example')
    assert covers('shop-b.example') is None and covers('evilshop-a.example') is None and covers('shop-a.example.evil.test') is None
    assert covers(None) is None and covers('') is None  # a host that cannot be read covers no tap
    module.GRANTS.clear()


def test_fu4_a_grant_with_no_site_covers_a_tap_whatever_the_host(ap):
    module, api, client = ap
    module.GRANTS.clear()
    module.on_card_approved(package='dev.shop', handle='vault_card', amount='£12.40', certs=[], task='t1', session='s1')
    amounts = [module.normalize_amount('£12.40')]
    for host in (None, '', 'anything.example'):
        assert module.GRANTS.covering('dev.shop', amounts, task='t1', session='s1', host=host)
    module.GRANTS.clear()


@pytest.mark.parametrize('host, site, same', [('shop.example', 'shop.example', True), ('www.shop.example', 'shop.example', True),
                                              ('shop.example', 'www.shop.example', True), ('a.b.shop.example', 'shop.example', True),
                                              ('SHOP.example:443', 'shop.example', True), ('https://shop.example/cart?x=1', 'shop.example', True),
                                              ('en.wikipedia.org/wiki/Android_(operating_system)', 'wikipedia.org', True),  # as Chrome shows a long address
                                              ('notshop.example', 'shop.example', False), ('shop.example.evil.test', 'shop.example', False),
                                              ('', 'shop.example', False), (None, 'shop.example', False), ('x.example', '', False)])
def test_fu4_same_site(ap, host, site, same):
    module, api, client = ap
    assert module.same_site(host, site) is same


def test_fu4_the_action_payload_keeps_pay_as_a_flag_only(ap):
    module, api, client = ap
    kept = module.clean_payload('agent_phone_action', dict(PAY, pay=True))
    assert kept['pay'] is True
    assert module.clean_payload('agent_phone_action', dict(PAY, pay=False))['pay'] is False
    assert module.clean_payload('agent_phone_action', dict(PAY))['pay'] is False  # absent means false: the app shows Allow and Deny
    assert module.clean_payload('agent_phone_action', dict(PAY, pay='yes'))['pay'] is False  # only a real true counts
    assert 'pay' not in module.clean_payload('needs_you', {'line': 'x', 'pay': True})
    record_id = module.create('owner', 'default', 'chat', 'agent_phone_action', dict(PAY, pay=True), {'package': 'dev.shop'})
    assert [r['pay'] for r in module.open_records('owner') if r['id'] == record_id] == [True]


def test_fu4_retire_ends_the_grants_of_that_package_task_and_session_only(ap):
    module, api, client = ap
    module.GRANTS.clear()
    amounts = [module.normalize_amount('£12.40')]
    module.on_card_approved(package='dev.shop', handle='vault_card', amount='£12.40', certs=[], task='t1', session='s1')
    module.on_card_approved(package='dev.other', handle='vault_card', amount='£12.40', certs=[], task='t1', session='s1')
    assert module.GRANTS.retire('dev.shop', 't2', 's1') is False and module.GRANTS.retire('dev.shop', 't1', 's2') is False
    assert module.GRANTS.retire('dev.shop', 't1', 's1') is True
    assert module.GRANTS.covering('dev.shop', amounts, task='t1', session='s1') is None
    assert module.GRANTS.covering('dev.other', amounts, task='t1', session='s1')
    assert module.GRANTS.retire('dev.shop', 't1', 's1') is False
    module.GRANTS.clear()


def test_fu6_owned_finds_the_live_grant_of_this_package_task_and_session_whatever_its_amount_or_site(ap):
    module, api, client = ap
    clock = [0.0]
    g = grants(module, clock)
    g.record('dev.shop', 'vault_card', '£12.40', task='t1', session='s1', web_domain='shop.example')
    assert g.owned('dev.shop', 't1', 's1')['handle'] == 'vault_card'
    assert g.owned('dev.shop', 't2', 's1') is None and g.owned('dev.shop', 't1', 's2') is None and g.owned('dev.other', 't1', 's1') is None
    clock[0] = 301
    assert g.owned('dev.shop', 't1', 's1') is None  # the 5 minutes are over


# ---- PHONEFIX-121 follow-up 7 (REVIEW-FIX-PHONE-121 R4-3, R4-5): the doc says what the code does --------------------------------------

def _doc_section(start, end):
    from pathlib import Path
    doc = Path(__file__).resolve().parents[3] / 'docs' / 'agent-phone' / 'approvals-v1.md'
    text = doc.read_text(encoding='utf-8').replace('\r\n', '\n').split(start, 1)[1].split(end, 1)[0]
    return ' '.join(text.split())  # one line: where the doc wraps does not matter


def test_fu7_the_doc_says_one_step_per_grant():
    grant = _doc_section('## 5a.', '## 5b.')
    assert 'at most 3 per grant' not in grant and 'one per grant' in grant and 'going back to a step screen' in grant


def test_fu7_5c_item_2_gives_the_real_count_and_the_tightenings_cost():
    limits = _doc_section('## 5c.', '\n## 6.')
    assert 'one yes pays for **at most two orders**' in limits and 'Going back to the step and tapping it again asks.' in limits
    assert ('A "Continue" or "Next" on a page that also shows a pay button ("Pay with Google Pay", "Buy now") uses the yes up, '
            'so the order asks again.') in limits


def test_fu7_5c_items_6_13_15_and_17_are_in_the_words_root_gave():
    limits = _doc_section('## 5c.', '\n## 6.')
    assert 'A step button with no words (only an icon) asks, and then the order asks again.' in limits
    assert ('If the phone is stopped abruptly while Beacon has filled something, more of the screen stays hidden until the next '
            'normal stop.') in limits
    assert "a screen the phone can't fully read" in limits and 'window list' not in limits
    assert ("A sideways swipe on a list, a carousel or an unnamed area asks, and during a purchase it ends the card's yes.") in limits
    assert 'over-mask' not in limits and "keeper's fallback" not in limits


def test_fu8_5c_names_the_cost_of_a_code_fill_the_helper_could_not_place():
    limits = _doc_section('## 5c.', '\n## 6.')
    masks = _doc_section('## 5b.', '## 5c.')
    assert 'newest 8' in masks and 'until the phone stops' in masks
    assert 'text fields stay hidden from Beacon' not in limits  # the fu8 wording of item 18, replaced by the later ones


def test_fu9_5c_item_18_says_in_plain_words_that_every_text_box_of_the_app_stays_hidden():
    limits = _doc_section('## 5c.', '\n## 6.')
    assert "18. If Beacon can't tell which box a code went into" not in limits  # follow-up 9's wording, replaced by follow-up 10's and 11's


def test_fu10_5c_item_18_and_item_11_say_what_the_code_does_and_no_hermes_restart_limit_is_needed():
    limits = _doc_section('## 5c.', '\n## 6.')
    assert "18. If Beacon isn't sure which box a code went into" not in limits  # follow-up 10's wording, replaced by follow-up 11's
    assert 'If Hermes restarts while the phone is on' not in limits  # the code apps are in the masks file: a restart keeps those boxes hidden
    masks = _doc_section('## 5b.', '## 5c.')
    assert 'code app' in masks and 'newest 8' in masks and 'every process' in masks and 'Hermes restart' in masks


def test_fu11_5c_item_18_and_item_11_and_5b_say_what_the_code_does():
    limits = _doc_section('## 5c.', '\n## 6.')
    assert ("18. After Beacon puts a code into an app, every text box in that app stays hidden from it until the phone stops, including boxes "
            "it types into. In Chrome that means every site, and the address bar. Numbers the app shows on their own (a quantity, a count, a size) "
            "are hidden too.") in limits
    assert ('11. A value the owner filled during setup, or one Beacon filled before Hermes restarted (a password, say), shown inside a longer word '
            '("pass=Test-Pass-4711"), isn\'t hidden from the agent\'s capture. A code Beacon read and filled is not one of these: its app\'s text '
            'boxes are hidden (item 18).') in limits
    masks = _doc_section('## 5b.', '## 5c.')
    assert 'makes its app a **code app**' in masks and 'whatever the helper reported' in masks and 'run/session' in masks
    assert 'by digest' not in masks and 'position alone' in masks  # the digest watches are gone: every code fill is a code app


def test_fu12_5c_item_19_and_i0_name_the_message_a_code_came_in():
    limits = _doc_section('## 5c.', '\n## 6.')
    assert "19. A code Beacon filled can still show in the message it came in, such as Messages or mail, if the agent opens that message." in limits
    invariants = _doc_section('## 5a.', 'A purchase with a card from the vault')
    assert 'message it came in' in invariants and '§5c item 19' in invariants and '§5c item 18' in invariants
    masks = _doc_section('## 5b.', '## 5c.')
    assert 'only digits' in masks and 'one digit per view' in masks


def test_fu13_5c_item_18_says_numbers_on_their_own_are_hidden_and_item_20_lists_what_can_still_show():
    """R8-2 and R8-1 (doc only): item 18 ends with the numbers the digit rule hides; item 20 names the code shapes it fails open for."""
    limits = _doc_section('## 5c.', '\n## 6.')
    assert ("In Chrome that means every site, and the address bar. Numbers the app shows on their own (a quantity, a count, a size) are hidden too."
            ) in limits
    assert ("20. In an app Beacon put a code into, the code can still show if the app draws it in a way the phone can't read as digits: a code with "
            "letters drawn one character at a time, a code of 9 or more digits shown in one piece with spaces, a code painted as a picture, or "
            "digits the app only describes in words.") in limits
    assert limits.index('19. A code Beacon filled can still show') < limits.index('20. In an app Beacon put a code into')  # in order: 19 then 20
    assert limits.index('20. In an app Beacon put a code into') < limits.index('Reading of "one approval allows one input"')  # the last item of the list
    # the digit rule's own limits are the ones item 20 names: 1 to 8 digits, text or description, whole
    masks = _doc_section('## 5b.', '## 5c.')
    assert 'only digits (1 to 8, spaces and dashes ignored)' in masks


def test_fu14_the_doc_names_the_withheld_picture_and_the_card_in_a_web_checkout_that_cannot_be_filled():
    masks = _doc_section('## 5b.', '## 5c.')
    assert 'A window the list leaves out withholds the picture' in masks and '`dumpsys window windows`' in masks
    assert '`picture: withheld`' in masks and 'every text field boxed in the picture, whether it matches a value or not' in masks
    limits = _doc_section('## 5c.', '\n## 6.')
    assert ("5. A shop app whose checkout is a web page inside the app can't have a card filled from your vault; the agent asks you to fill it. "
            "The order there asks too.") in limits
    assert "a dialog or sheet over a filled form gives the text list and no picture" in limits
    assert 'window list' not in limits  # section 5c speaks of dialogs and sheets
