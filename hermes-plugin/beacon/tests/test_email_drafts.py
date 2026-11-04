"""Email drafts: Hermes shows an email as a card, the owner revises it in place and sends exactly what the card shows.

Fake Hermes everywhere: a fake session store, a fake auxiliary model, a fake mail tool. No network, no real email."""
import json
import sys
import threading
import time
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from test_mobile import harness, load_private  # noqa: F401  (fixtures)

BASE = '/api/plugins/beacon/mobile/email-drafts'
CHAT = {'user_id': 'basic:owner', 'source': 'tui'}


def addr(name):
    # Built at run time: the public source keeps no address-shaped text (release privacy scan).
    return name + '@' + 'example.com'


MAYA, DAN, EVE = addr('maya'), addr('dan'), addr('eve')
DRAFT = {'to': [MAYA], 'cc': [DAN], 'subject': 'Friday review', 'body': 'Hi Maya,\n\nCan we move the review to Friday?\n\nThanks'}


@pytest.fixture
def drafts(harness, monkeypatch):
    """The drafts module on the harness's private home and a chat the owner signed in to."""
    api, client, cfg, _, _ = harness
    module = sys.modules['beacon_email_drafts']
    module._SENDERS.clear()
    module._IDLE['until'] = 0.0
    monkeypatch.setattr(api.proactive, 'current_profile', lambda cfg: 'default')
    monkeypatch.setattr(module, '_session_row', lambda profile, session: CHAT if session == 'chat' else {'user_id': 'basic:owner', 'source': 'cron'} if session == 'cron' else {'user_id': 'basic:other', 'source': 'tui'} if session == 'other' else None)
    return api, client, cfg, module


def make(module, args=None, session='chat'):
    reply = json.loads(module.tool_draft(DRAFT if args is None else args, session_id=session))
    assert 'error' not in reply, reply
    return reply['draft_id']


def get(client, draft_id, profile='default'):
    return client.get(f'{BASE}/{draft_id}?profile={profile}')


# ---- the tool -----------------------------------------------------------------------------------------------------

def test_the_tool_stores_a_draft_and_tells_hermes_to_wait(drafts):
    api, client, cfg, module = drafts
    reply = json.loads(module.tool_draft(DRAFT, session_id='chat'))
    assert set(reply) == {'draft_id', 'version', 'status', 'note'}
    assert (reply['version'], reply['status']) == (1, 'shown')
    assert 'Do not send it' in reply['note']
    card = get(client, reply['draft_id']).json()
    assert (card['to'], card['cc'], card['subject'], card['body']) == (DRAFT['to'], DRAFT['cc'], DRAFT['subject'], DRAFT['body'])
    assert (card['version'], card['versions'], card['status']) == (1, 1, 'draft')


@pytest.mark.parametrize('change,why', [
    ({'to': []}, 'to'), ({'to': 'x'}, 'to'), ({'to': ['not an address']}, 'to'), ({'to': [MAYA + '\nbcc: ' + EVE]}, 'to'),
    ({'to': ['Maya <' + MAYA + '>']}, 'to'), ({'to': [MAYA] * 21}, 'to'), ({'cc': [MAYA, 5]}, 'cc'),
    ({'subject': ''}, 'subject'), ({'subject': 'Hi\nBcc: ' + EVE}, 'subject'), ({'subject': 'x' * 201}, 'subject'),
    ({'body': ''}, 'body'), ({'body': 'x' * 10001}, 'body'), ({'body': 'a\x00b'}, 'body'),
    ({'bcc': [EVE]}, 'bcc'), ({'extra': 1}, 'extra'),
])
def test_the_tool_refuses_fields_that_are_not_sendable(drafts, change, why):
    api, client, cfg, module = drafts
    reply = json.loads(module.tool_draft({**DRAFT, **change}, session_id='chat'))
    assert 'draft_id' not in reply and why in reply['error']


def test_missing_fields_are_refused(drafts):
    api, client, cfg, module = drafts
    for missing in ('to', 'subject', 'body'):
        reply = json.loads(module.tool_draft({k: v for k, v in DRAFT.items() if k != missing}, session_id='chat'))
        assert missing in reply['error']
    assert 'error' in json.loads(module.tool_draft(None, session_id='chat'))


def test_addresses_are_trimmed_deduplicated_and_cc_cannot_repeat_to(drafts):
    api, client, cfg, module = drafts
    draft_id = make(module, {**DRAFT, 'to': ['  ' + MAYA + ' ', MAYA.upper(), DAN], 'cc': [DAN.upper(), EVE]})
    card = get(client, draft_id).json()
    assert card['to'] == [MAYA, DAN] and card['cc'] == [EVE]


def test_line_endings_and_bidi_overrides_are_cleaned(drafts):
    api, client, cfg, module = drafts
    draft_id = make(module, {**DRAFT, 'subject': 'Plan ‮exe.txt', 'body': 'one\r\ntwo\rthree'})
    card = get(client, draft_id).json()
    assert card['subject'] == 'Plan exe.txt' and card['body'] == 'one\ntwo\nthree'


def test_only_an_owner_signed_in_interactive_chat_can_make_a_draft(drafts):
    api, client, cfg, module = drafts
    for session in ('cron', 'other', 'missing', None):
        reply = json.loads(module.tool_draft(DRAFT, session_id=session))
        assert 'draft_id' not in reply and reply['error']
    cfg_owner = dict(cfg)
    cfg.pop('owner_id')
    assert 'error' in json.loads(module.tool_draft(DRAFT, session_id='chat'))
    cfg.update(cfg_owner)


def test_the_tool_is_hidden_until_beacon_has_an_owner(drafts):
    api, client, cfg, module = drafts
    assert module.available() is True
    cfg.pop('owner_id')
    assert module.available() is False


# ---- read, ownership and profiles ---------------------------------------------------------------------------------

def test_a_draft_is_only_readable_by_its_owner_and_profile(drafts):
    api, client, cfg, module = drafts
    draft_id = make(module)
    assert get(client, draft_id).status_code == 200
    assert get(client, 'f' * 32).status_code == 404
    assert get(client, 'not-an-id').status_code in (400, 404)
    client.headers['x-fixture-owner'] = 'foreign'
    assert get(client, draft_id).status_code == 403
    client.headers['x-fixture-owner'] = 'owner'
    cfg['profiles'] = ['default', 'work']
    # Another profile of the same owner cannot read it, revise it or send it.
    assert get(client, draft_id, 'work').status_code == 404
    assert client.post(f'{BASE}/{draft_id}/revise?profile=work', json={'instruction': 'shorter', 'version': 1}).status_code == 404
    assert client.post(f'{BASE}/{draft_id}/send?profile=work', json={'version': 1}).status_code == 404
    assert client.get(f'{BASE}/{draft_id}/history?profile=work').status_code == 404
    assert get(client, draft_id, 'stranger').status_code == 403


def test_old_drafts_and_the_oldest_beyond_the_cap_are_dropped(drafts, monkeypatch):
    api, client, cfg, module = drafts
    monkeypatch.setattr(module, 'MAX_DRAFTS', 3)
    first = make(module)
    with api.mobile.connect() as conn:
        conn.execute('UPDATE email_drafts SET updated=?, created=?', (time.time() - module.KEEP_SECONDS - 5, time.time() - module.KEEP_SECONDS - 5))
    ids = [make(module) for _ in range(4)]
    assert get(client, first).status_code == 404
    assert get(client, ids[0]).status_code == 404
    assert all(get(client, draft_id).status_code == 200 for draft_id in ids[1:])
