"""Isolated tests of Beacon's guarded wrapper around the real native vault."""
import importlib.util
import json
import os
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
NATIVE = Path(os.environ['HERMES_AGENT_SOURCE']) if 'HERMES_AGENT_SOURCE' in os.environ else Path.home() / 'AppData/Local/hermes/hermes-agent'
TEST_EMAIL = 'qa' + chr(64) + 'example.test'
TEST_PASSWORD = 'synthetic-' + 'pass-' + '123'


def load_runtime():
    path = ROOT / 'vault_runtime.py'
    assert path.exists(), 'Guarded vault runtime has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_vault_runtime_test', path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def vault(tmp_path, monkeypatch):
    assert (NATIVE / 'agent/vault_store.py').is_file(), 'Pinned native source is required'
    monkeypatch.syspath_prepend(str(NATIVE))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    return load_runtime, tmp_path


def login(**overrides):
    data = {'kind': 'login', 'label': 'Example', 'origin': 'https://example.test',
            'secret': {'identifier_type': 'email', 'identifier': TEST_EMAIL, 'password': TEST_PASSWORD}}
    data.update(overrides)
    return data


def test_real_native_encryption_and_metadata_only_result(vault):
    load, home = vault; runtime = load()
    result = runtime.add(home, login())
    assert result['kind'] == 'login' and result['identifier'] == TEST_EMAIL
    assert 'synthetic-pass-123' not in json.dumps(result)
    assert 'synthetic-pass-123' not in json.dumps(runtime.items(home))
    assert b'synthetic-pass-123' not in (home / 'vault' / 'vault.json.enc').read_bytes()
    from agent.vault_store import VaultStore
    assert VaultStore(home / 'vault').resolve_secret(result['id'])['password'] == 'synthetic-pass-123'


def test_unicode_origins_cannot_collapse_into_another_registered_host(vault):
    load, home = vault; runtime = load()
    with pytest.raises(runtime.InvalidInput): runtime.add(home, login(origin='https://stra\u00dfe.de'))
    assert not (home / 'vault' / 'vault.json.enc').exists()
    assert runtime.add(home, login(origin='https://xn--strae-oqa.de'))['origin'] == 'https://xn--strae-oqa.de'


def test_a_native_id_collision_cannot_commit_a_guard_unreadable_document(vault, monkeypatch):
    from types import SimpleNamespace
    load, home = vault; runtime = load()
    item = runtime.add(home, login()); path = home / 'vault' / 'vault.json.enc'; before = path.read_bytes()
    module = runtime._native()
    monkeypatch.setattr(module.uuid, 'uuid4', lambda: SimpleNamespace(hex=item['id'][6:] + '0' * 20))
    with pytest.raises(runtime.Unavailable): runtime.add(home, login(label='Second synthetic item'))
    assert path.read_bytes() == before
    assert len(runtime.items(home)) == 1


def test_native_normalizer_rejects_bad_otp_parameters_before_persistence(vault, monkeypatch):
    load, home = vault; runtime = load()
    monkeypatch.setattr(runtime, 'capabilities', lambda: {'otp': True})
    item = runtime.add(home, login()); path = home / 'vault' / 'vault.json.enc'; before = path.read_bytes()
    for value in ('JBSWY3DPEHPK3PXP|9|0|MD5', 'otpauth://totp/Fixture?secret=JBSWY3DPEHPK3PXP&digits=9&period=0&algorithm=MD5'):
        data = login(); data['secret']['otp_secret'] = value
        with pytest.raises(runtime.InvalidInput): runtime.add(home, data)
        assert path.read_bytes() == before
    valid = login(label='Nondefault OTP'); valid['secret']['otp_secret'] = 'otpauth://totp/Fixture?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA256'
    runtime.add(home, valid)
    assert len(runtime.items(home)) == 2


def test_capabilities_are_cheap_and_do_not_create_vault_or_key(vault):
    load, home = vault; runtime = load()
    caps = runtime.capabilities()
    assert caps['signup'] is False and caps['otp'] is False and caps['secure_prompts'] is False
    assert not (home / 'vault').exists()


def test_gateway_readiness_callback_controls_dashboard_capabilities_without_creating_vault(vault, monkeypatch):
    load, home = vault; runtime = load()
    monkeypatch.delitem(sys.modules, 'beacon_vault_bridge', raising=False)
    ready = {'secure_prompts': True, 'otp': True, 'signup': True}
    def read_gateway(): return dict(ready)
    assert runtime.configure_gateway_attestor(read_gateway)
    assert runtime.configure_gateway_attestor(read_gateway)
    assert not runtime.configure_gateway_attestor(lambda: ready)
    assert runtime.capabilities() == {'supported': True, 'local': True, **ready}
    ready['secure_prompts'] = False
    assert runtime.capabilities() == {'supported': True, 'local': True, 'secure_prompts': False, 'otp': False, 'signup': False}
    assert not (home / 'vault').exists()


def test_gateway_readiness_fails_closed_on_invalid_or_failing_callback(vault, monkeypatch):
    load, home = vault; runtime = load()
    monkeypatch.delitem(sys.modules, 'beacon_vault_bridge', raising=False)
    assert runtime.configure_gateway_attestor(lambda: {'secure_prompts': 'true', 'otp': True, 'signup': True})
    assert not runtime.capabilities()['secure_prompts'] and not runtime.capabilities()['otp']
    assert not (home / 'vault').exists()


@pytest.mark.parametrize('bad_secret', ['', None, 3, True, ['synthetic-password']])
def test_nonstring_or_empty_password_never_leaks_or_creates_file(vault, bad_secret):
    load, home = vault; runtime = load()
    data = login(secret={'identifier_type': 'email', 'identifier': TEST_EMAIL, 'password': bad_secret})
    with pytest.raises(Exception) as exc:
        runtime.add(home, data)
    assert 'synthetic-password' not in str(exc.value)
    assert not (home / 'vault' / 'vault.json.enc').exists()


def test_existing_zero_length_vault_is_not_overwritten(vault):
    load, home = vault; runtime = load()
    directory = home / 'vault'; directory.mkdir()
    path = directory / 'vault.json.enc'; path.write_bytes(b'')
    with pytest.raises(Exception): runtime.add(home, login())
    assert path.read_bytes() == b''
    assert not (directory / 'vault.key').exists()


def test_nonlist_native_items_are_not_silently_replaced(vault):
    load, home = vault; runtime = load()
    from agent.vault_store import VaultStore
    store = VaultStore(home / 'vault')
    with store._locked():
        blob = store._fernet().encrypt(json.dumps({'version': 1, 'items': {'bad': 'shape'}}).encode())
        store._vault_path.write_bytes(blob)
    before = store._vault_path.read_bytes()
    with pytest.raises(Exception): runtime.add(home, login())
    assert store._vault_path.read_bytes() == before


def test_missing_key_for_existing_ciphertext_is_never_regenerated(vault):
    load, home = vault; runtime = load()
    directory = home / 'vault'; directory.mkdir()
    ciphertext = directory / 'vault.json.enc'; ciphertext.write_bytes(b'existing-corrupt-ciphertext')
    with pytest.raises(Exception): runtime.add(home, login())
    assert ciphertext.read_bytes() == b'existing-corrupt-ciphertext'
    assert not (directory / 'vault.key').exists()


def test_metadata_edit_retains_native_handle_and_password(vault):
    load, home = vault; runtime = load()
    old = runtime.add(home, login())
    new = runtime.replace(home, old['id'], {'label': 'Renamed', 'origin': 'https://example.test'})
    assert new['id'] == old['id'] and new['label'] == 'Renamed'
    from agent.vault_store import VaultStore
    assert VaultStore(home / 'vault').resolve_secret(old['id'])['password'] == 'synthetic-pass-123'


def test_replace_failure_keeps_old_ciphertext(vault, monkeypatch):
    load, home = vault; runtime = load()
    old = runtime.add(home, login())
    path = home / 'vault' / 'vault.json.enc'; before = path.read_bytes()
    from agent.vault_store import VaultStore
    def refuse(*args, **kwargs): raise OSError('synthetic private error')
    monkeypatch.setattr(VaultStore, '_write_all', refuse)
    with pytest.raises(Exception) as exc:
        runtime.replace(home, old['id'], {'label': 'Changed', 'origin': 'https://example.test'})
    assert 'synthetic private error' not in str(exc.value)
    assert path.read_bytes() == before


def test_malformed_totp_seed_rejected_before_native_store(vault):
    load, home = vault; runtime = load()
    secret = dict(login()['secret'], otp_secret='A')
    with pytest.raises(Exception): runtime.add(home, login(secret=secret))
    assert not (home / 'vault' / 'vault.json.enc').exists()


def test_generated_password_is_stored_only_server_side(vault):
    load, home = vault; runtime = load()
    result = runtime.generate_login(home, {'label': 'Generated', 'origin': 'https://example.test',
                                           'identifier_type': 'email', 'identifier': TEST_EMAIL})
    from agent.vault_store import VaultStore
    password = VaultStore(home / 'vault').resolve_secret(result['id'])['password']
    assert len(password) >= 24
    assert password not in json.dumps(result)
    assert password.encode() not in (home / 'vault' / 'vault.json.enc').read_bytes()


def test_unknown_native_source_fails_closed(vault, monkeypatch):
    load, home = vault; runtime = load()
    monkeypatch.setattr(runtime, 'PINNED_SOURCE_SHA256', '0' * 64)
    assert runtime.capabilities()['supported'] is False
    with pytest.raises(Exception): runtime.add(home, login())
    assert not (home / 'vault').exists()

@pytest.mark.parametrize('origin', [
    'http://example.test', 'https://example.test/private?secret=synthetic',
    ('https://user:synthetic' + chr(64) + 'example.test'), 'https://example .test',
    'https://-bad.example', 'https://example.test:0', 'https://example.test:',
])
def test_unsafe_or_malformed_origins_cannot_create_a_record(vault, origin):
    load, home = vault; runtime = load()
    with pytest.raises(Exception) as exc: runtime.add(home, login(origin=origin))
    assert 'synthetic' not in str(exc.value)
    assert not (home / 'vault' / 'vault.json.enc').exists()


def test_valid_authenticator_is_refused_until_otp_guard_is_wired(vault):
    load, home = vault; runtime = load()
    secret = dict(login()['secret'], otp_secret='JBSWY3DPEHPK3PXP')
    with pytest.raises(Exception): runtime.add(home, login(secret=secret))
    assert runtime.capabilities()['otp'] is False
    assert not (home / 'vault' / 'vault.json.enc').exists()


def test_encrypted_malformed_record_is_never_overwritten(vault):
    load, home = vault; runtime = load()
    from agent.vault_store import VaultStore
    store = VaultStore(home / 'vault')
    with store._locked():
        store._write_all([{'id': 'vault_111111111111', 'kind': 'login', 'label': 'Broken',
                           'origin': 'https://example.test', 'identifier_type': 'email',
                           'identifier': TEST_EMAIL, 'created_at': '2026-01-01', 'secret': {}}])
    before = store._vault_path.read_bytes()
    with pytest.raises(Exception): runtime.add(home, login())
    assert store._vault_path.read_bytes() == before


def test_distinct_payment_and_address_values_remain_encrypted(vault):
    load, home = vault; runtime = load()
    card = {'card_number': '4111111111111111', 'exp_month': '7', 'exp_year': '2029', 'cvc': '987'}
    address = {'address_line1': 'Synthetic Lane 123', 'city': 'Example', 'postal_code': '99999', 'country': 'US'}
    runtime.add(home, {'kind': 'payment', 'label': 'Card', 'origin': 'https://shop.test', 'secret': card})
    runtime.add(home, {'kind': 'address', 'label': 'Address', 'origin': 'https://shop.test', 'secret': address})
    visible = json.dumps(runtime.items(home))
    assert '4111111111111111' not in visible and 'Synthetic Lane 123' not in visible and '987' not in visible
    ciphertext = (home / 'vault' / 'vault.json.enc').read_bytes()
    assert b'4111111111111111' not in ciphertext and b'Synthetic Lane 123' not in ciphertext


def test_secret_patch_keeps_existing_fields_and_clears_only_explicit_optional(vault):
    load, home = vault; runtime = load()
    card = {'card_number': '4111111111111111', 'exp_month': '7', 'exp_year': '2029', 'cvc': '987', 'cardholder_name': 'QA'}
    item = runtime.add(home, {'kind': 'payment', 'label': 'Card', 'origin': 'https://shop.test', 'secret': card})
    result = runtime.replace(home, item['id'], {'label': 'Card 2', 'origin': 'https://shop.test', 'secret': {'cvc': '654', 'cardholder_name': ''}})
    from agent.vault_store import VaultStore
    saved = VaultStore(home / 'vault').resolve_secret(item['id'])
    assert result['id'] == item['id'] and saved['card_number'] == card['card_number']
    assert saved['cvc'] == '654' and 'cardholder_name' not in saved
    assert '654' not in json.dumps(result)


def test_secret_patch_rejects_clearing_required_field_without_losing_old_item(vault):
    load, home = vault; runtime = load()
    item = runtime.add(home, login())
    before = (home / 'vault' / 'vault.json.enc').read_bytes()
    with pytest.raises(Exception):
        runtime.replace(home, item['id'], {'label': 'Example', 'origin': 'https://example.test', 'secret': {'password': ''}})
    assert (home / 'vault' / 'vault.json.enc').read_bytes() == before


def test_secret_patch_keeps_existing_otp_but_cannot_set_a_new_one(vault):
    load, home = vault; runtime = load()
    from agent.vault_store import VaultStore
    store = VaultStore(home / 'vault')
    old = store.add_item('login', 'Example', dict(login()['secret'], otp_secret='JBSWY3DPEHPK3PXP'), origin='https://example.test')
    result = runtime.replace(home, old.id, {'label': 'Example', 'origin': 'https://example.test', 'secret': {'password': 'new-pass-456'}})
    saved = store.resolve_secret(old.id)
    assert result['has_otp'] is True and saved['otp_secret'] == 'JBSWY3DPEHPK3PXP'
    before = store._vault_path.read_bytes()
    with pytest.raises(Exception):
        runtime.replace(home, old.id, {'label': 'Example', 'origin': 'https://example.test', 'secret': {'otp_secret': 'JBSWY3DPEHPK3PXP'}})
    assert store._vault_path.read_bytes() == before
    runtime.replace(home, old.id, {'label': 'Example', 'origin': 'https://example.test', 'secret': {'otp_secret': ''}})
    assert 'otp_secret' not in store.resolve_secret(old.id)


def test_malformed_otp_already_accepted_by_native_is_not_overwritten(vault):
    load, home = vault; runtime = load()
    from agent.vault_store import VaultStore
    store = VaultStore(home / 'vault')
    store.add_item('login', 'Example', dict(login()['secret'], otp_secret='A'), origin='https://example.test')
    before = store._vault_path.read_bytes()
    with pytest.raises(Exception): runtime.add(home, login())
    assert store._vault_path.read_bytes() == before


@pytest.mark.parametrize('password', ['', 123, {'nested': 'value'}])
def test_existing_malformed_password_shape_is_never_overwritten(vault, password):
    load, home = vault; runtime = load()
    from agent.vault_store import VaultStore
    store = VaultStore(home / 'vault')
    with store._locked():
        store._write_all([{'id': 'vault_111111111111', 'kind': 'login', 'label': 'Broken',
                           'origin': 'https://example.test', 'identifier_type': 'email',
                           'identifier': TEST_EMAIL, 'created_at': '2026-01-01', 'secret': {'password': password}}])
    before = store._vault_path.read_bytes()
    with pytest.raises(Exception): runtime.add(home, login())
    assert store._vault_path.read_bytes() == before


def test_growth_past_read_limit_is_refused_before_overwriting_valid_vault(vault, monkeypatch):
    load, home = vault; runtime = load()
    first = runtime.add(home, login())
    path = home / 'vault' / 'vault.json.enc'; before = path.read_bytes()
    monkeypatch.setattr(runtime, 'MAX_VAULT_BYTES', len(before) + 8)
    with pytest.raises(Exception): runtime.add(home, login(label='another login'))
    assert path.read_bytes() == before
    assert runtime.items(home)[0]['id'] == first['id']


def test_native_error_containing_a_one_character_secret_is_never_echoed(vault, monkeypatch):
    load, home = vault; runtime = load()
    from agent.vault_store import VaultStore
    def fail(*args, **kwargs): raise ValueError('7')
    monkeypatch.setattr(VaultStore, 'add_item', fail)
    secret = dict(login()['secret'], password='7')
    with pytest.raises(Exception) as exc: runtime.add(home, login(secret=secret))
    assert str(exc.value) == 'Invalid vault input'
    assert not (home / 'vault' / 'vault.json.enc').exists()
