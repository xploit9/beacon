"""Guarded, local-only access to the pinned native Hermes encrypted vault."""
from contextlib import contextmanager
import base64
import binascii
import hashlib
import importlib
import importlib.util
import json
import re
from pathlib import Path
import secrets
import sys
import threading
from urllib.parse import urlsplit

PINNED_SOURCE_SHA256 = 'f0d5b89056c597627281f649d87476a08310866abd2c770592975a4a18314fc6'
MAX_VAULT_BYTES = 16 * 1024 * 1024
_gateway_attestor = None
_gateway_attestor_lock = threading.Lock()

class VaultFailure(Exception):
    pass

class InvalidInput(VaultFailure):
    pass

class MissingItem(VaultFailure):
    pass

class Unavailable(VaultFailure):
    pass


def _source_path():
    try:
        spec = importlib.util.find_spec('agent')
        paths = list(spec.submodule_search_locations or ()) if spec else []
        return Path(paths[0]) / 'vault_store.py' if len(paths) == 1 else None
    except Exception:
        return None


def native_compatible():
    path = _source_path()
    try:
        return path is not None and hashlib.sha256(path.read_bytes()).hexdigest() == PINNED_SOURCE_SHA256
    except OSError:
        return False


def configure_gateway_attestor(reader):
    """Set one trusted process-level gateway readiness reader; it must validate freshness itself."""
    global _gateway_attestor
    if not callable(reader):
        return False
    with _gateway_attestor_lock:
        if _gateway_attestor is not None and _gateway_attestor is not reader:
            return False
        _gateway_attestor = reader
        return True


def _browser_toolset_enabled():
    try:
        from hermes_cli.config import load_config_readonly
        from hermes_cli.tools_config import _get_platform_tools, _toolset_configuration_platform
        return 'browser' in _get_platform_tools(load_config_readonly(), _toolset_configuration_platform('browser'),
                                                include_default_mcp_servers=False)
    except Exception:
        return False


def capabilities():
    supported = native_compatible()
    if not supported or not _browser_toolset_enabled():
        return {'supported':supported, 'local':supported, 'signup':False, 'otp':False, 'secure_prompts':False}
    bridge = sys.modules.get('beacon_vault_bridge')
    try:
        ready = bool(supported and bridge and bridge.attested())
        signup = bool(ready and bridge.signup_ready())
    except Exception:
        ready = signup = False
    otp = ready
    try:
        remote = _gateway_attestor() if supported and _gateway_attestor is not None else None
        if isinstance(remote, dict) and remote.get('secure_prompts') is True:
            ready = True
            otp = otp or remote.get('otp') is True
            signup = signup or remote.get('signup') is True
    except Exception:
        pass
    return {'supported': supported, 'local': supported, 'signup': signup, 'otp': otp, 'secure_prompts': ready}


def _native():
    if not native_compatible():
        raise Unavailable('Vault unavailable')
    try:
        module = importlib.import_module('agent.vault_store')
        if Path(module.__file__).resolve() != _source_path().resolve():
            raise Unavailable('Vault unavailable')
        return module
    except Exception:
        raise Unavailable('Vault unavailable') from None


def _text(value, limit, *, required=True):
    if not isinstance(value, str) or len(value) > limit or (required and not value.strip()):
        raise InvalidInput('Invalid vault input')
    try:
        if any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError()
        value.encode('utf-8')
    except (ValueError, UnicodeError):
        raise InvalidInput('Invalid vault input') from None
    return value


def _origin(value):
    value = _text(value, 2048).strip()
    try:
        parts = urlsplit(value)
        if parts.scheme.lower() != 'https' or not parts.hostname or parts.username is not None or parts.password is not None:
            raise ValueError()
        if parts.path not in ('', '/') or parts.query or parts.fragment:
            raise ValueError()
        if not parts.hostname.isascii():
            raise ValueError()
        host = parts.hostname.lower()
        if (len(host) > 253 or ':' in host or parts.netloc.endswith(':') or
                any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', label) for label in host.split('.'))):
            raise ValueError()
        port = parts.port
        if port == 0:
            raise ValueError()
        return 'https://' + host + (f':{port}' if port and port != 443 else '')
    except (ValueError, UnicodeError):
        raise InvalidInput('Invalid vault input') from None


def _fields(kind):
    return {
        'login': {'identifier_type', 'identifier', 'password', 'otp_secret'},
        'payment': {'card_number', 'cardholder_name', 'exp_month', 'exp_year', 'cvc', 'billing_postal_code'},
        'address': {'address_line1', 'address_line2', 'city', 'state', 'postal_code', 'country'},
    }[kind]


def _input(payload, *, kind=None):
    if not isinstance(payload, dict):
        raise InvalidInput('Invalid vault input')
    allowed = {'label', 'origin', 'secret'} if kind else {'kind', 'label', 'origin', 'secret'}
    if set(payload) != allowed:
        raise InvalidInput('Invalid vault input')
    kind = kind or payload.get('kind')
    if kind not in ('login', 'payment', 'address'):
        raise InvalidInput('Invalid vault input')
    label = _text(payload.get('label'), 120).strip()
    origin = _origin(payload.get('origin'))
    secret = payload.get('secret')
    if not isinstance(secret, dict) or not secret:
        raise InvalidInput('Invalid vault input')
    fields = _fields(kind)
    if not set(secret) <= fields or any(not isinstance(k, str) for k in secret):
        raise InvalidInput('Invalid vault input')
    clean = {key: _text(value, 4096, required=False) for key, value in secret.items()}
    if kind == 'login':
        if clean.get('identifier_type') not in ('email', 'phone', 'username'):
            raise InvalidInput('Invalid vault input')
        _text(clean.get('identifier'), 320)
        _text(clean.get('password'), 4096)
        if clean.get('otp_secret', '').strip():
            if not capabilities()['otp']:
                raise InvalidInput('Authenticator keys are not available yet')
            try:
                normalized = _native().normalize_otp_secret(clean['otp_secret'])
                seed = normalized.split('|', 1)[0]
                if not base64.b32decode(seed + '=' * (-len(seed) % 8), casefold=True):
                    raise ValueError()
            except Exception:
                raise InvalidInput('Invalid vault input') from None
        else:
            clean.pop('otp_secret', None)
    else:
        required = ('card_number', 'exp_month', 'exp_year', 'cvc') if kind == 'payment' else ('address_line1', 'city', 'postal_code', 'country')
        if any(not clean.get(key, '').strip() for key in required):
            raise InvalidInput('Invalid vault input')
    return kind, label, origin, clean


def _safe_path(path):
    if path.is_symlink() or getattr(path, 'is_junction', lambda: False)():
        raise Unavailable('Vault unavailable')


def _strict_read(store):
    path, key = store._vault_path, store._key_path
    for candidate in (store._base, path, key):
        _safe_path(candidate)
    if not path.exists():
        return []
    if not path.is_file() or not key.is_file() or path.stat().st_size == 0 or path.stat().st_size > MAX_VAULT_BYTES:
        raise Unavailable('Vault unavailable')
    try:
        raw = store._fernet().decrypt(path.read_bytes())
        document = json.loads(raw.decode('utf-8'))
    except Exception:
        raise Unavailable('Vault unavailable') from None
    return _validated_document(document)


def _validated_document(document):
    if (not isinstance(document, dict) or set(document) != {'version', 'items'} or
            type(document['version']) is not int or document['version'] != 1 or not isinstance(document['items'], list)):
        raise Unavailable('Vault unavailable')
    ids = set()
    for record in document['items']:
        if (not isinstance(record, dict) or not isinstance(record.get('id'), str) or
                not record['id'].startswith('vault_') or record['id'] in ids or
                record.get('kind') not in ('login', 'payment', 'address') or
                not isinstance(record.get('label'), str) or not isinstance(record.get('secret'), dict) or
                any(not isinstance(k, str) or not isinstance(v, str) for k, v in record['secret'].items()) or
                not isinstance(record.get('created_at'), str) or
                record.get('origin') is not None and not isinstance(record.get('origin'), str) or
                record.get('identifier') is not None and not isinstance(record.get('identifier'), str) or
                record.get('identifier_type') is not None and not isinstance(record.get('identifier_type'), str)):
            raise Unavailable('Vault unavailable')
        if not re.fullmatch(r'vault_[0-9a-f]{12}', record['id']) or not record['label'].strip():
            raise Unavailable('Vault unavailable')
        secret = record['secret']
        if record['kind'] == 'login':
            if (not record.get('origin') or not record.get('identifier') or
                    record.get('identifier_type') not in ('email', 'phone', 'username') or
                    not secret.get('password') or not set(secret) <= {'password', 'otp_secret'}):
                raise Unavailable('Vault unavailable')
            if secret.get('otp_secret'):
                parts = secret['otp_secret'].split('|')
                try:
                    if len(parts) not in (1, 4):
                        raise ValueError()
                    seed = parts[0]
                    if not base64.b32decode(seed + '=' * (-len(seed) % 8), casefold=True):
                        raise ValueError()
                    if len(parts) == 4 and (int(parts[1]) not in (6, 7, 8) or int(parts[2]) <= 0 or parts[3] not in ('SHA1', 'SHA256', 'SHA512')):
                        raise ValueError()
                except (ValueError, binascii.Error):
                    raise Unavailable('Vault unavailable') from None
        else:
            required = ('card_number', 'exp_month', 'exp_year', 'cvc') if record['kind'] == 'payment' else ('address_line1', 'city', 'postal_code', 'country')
            if any(not secret.get(field, '').strip() for field in required):
                raise Unavailable('Vault unavailable')
        ids.add(record['id'])
    return document['items']


@contextmanager
def _store(root):
    module = _native()
    base = Path(root) / 'vault'
    _safe_path(base)
    _safe_path(base / '.vault.lock')
    store = module.VaultStore(base_dir=base)
    try:
        with store._locked():
            yield module, store, _strict_read(store)
    except VaultFailure:
        raise
    except Exception:
        raise Unavailable('Vault unavailable') from None


def _candidate(module, kind, label, origin, secret):
    """Use the pinned native validator and record shape without a second disk write."""
    class Candidate(module.VaultStore):
        @contextmanager
        def _locked(self):
            yield
        def _read_all(self):
            return []
        def _write_all(self, records):
            self.record = records[0]
    candidate = Candidate()
    try:
        candidate.add_item(kind, label, secret, origin)
        return candidate.record
    except Exception:
        raise InvalidInput('Invalid vault input') from None


def _write(store, rows):
    # The native format is Fernet over JSON; reject a write that our guarded reader could not reopen.
    _validated_document({'version': 1, 'items': rows})
    length = len(json.dumps({'version': 1, 'items': rows}).encode('utf-8'))
    token_bytes = 1 + 8 + 16 + (length // 16 + 1) * 16 + 32
    if ((token_bytes + 2) // 3) * 4 > MAX_VAULT_BYTES:
        raise Unavailable('Vault unavailable')
    store._write_all(rows)


def _metadata(store, record):
    return {**store._meta(record).to_dict(), 'backend': 'local'}


def items(root):
    base = Path(root) / 'vault'
    _safe_path(base)
    _safe_path(base / 'vault.json.enc')
    if not (base / 'vault.json.enc').exists():
        _native()
        return []
    with _store(root) as (_, store, rows):
        return [_metadata(store, row) for row in rows]


def add(root, payload):
    kind, label, origin, secret = _input(payload)
    module = _native()
    record = _candidate(module, kind, label, origin, secret)
    with _store(root) as (_, store, rows):
        rows.append(record)
        _write(store, rows)
        return _metadata(store, record)


def replace(root, item_id, payload):
    if not isinstance(payload, dict) or set(payload) not in ({'label', 'origin'}, {'label', 'origin', 'secret'}):
        raise InvalidInput('Invalid vault input')
    with _store(root) as (module, store, rows):
        index = next((i for i, row in enumerate(rows) if row['id'] == item_id), None)
        if index is None:
            raise MissingItem('Vault item unavailable')
        old = rows[index]
        if 'secret' in payload:
            patch = payload['secret']
            if not isinstance(patch, dict) or not set(patch) <= _fields(old['kind']):
                raise InvalidInput('Invalid vault input')
            if any(not isinstance(value, str) for value in patch.values()):
                raise InvalidInput('Invalid vault input')
            merged = dict(old['secret'])
            retained_otp = merged.pop('otp_secret', None) if old['kind'] == 'login' else None
            if old['kind'] == 'login':
                merged.update(identifier_type=old['identifier_type'], identifier=old['identifier'])
            for key, value in patch.items():
                if value.strip(): merged[key] = value
                else: merged.pop(key, None)
            _, label, origin, secret = _input({'label': payload['label'], 'origin': payload['origin'], 'secret': merged}, kind=old['kind'])
            candidate = _candidate(module, old['kind'], label, origin, secret)
            if retained_otp and 'otp_secret' not in patch:
                candidate['secret']['otp_secret'] = retained_otp
            candidate['id'], candidate['created_at'] = old['id'], old['created_at']
            rows[index] = {**old, **candidate}
        else:
            rows[index] = {**old, 'label': _text(payload.get('label'), 120).strip(), 'origin': _origin(payload.get('origin'))}
        _write(store, rows)
        return _metadata(store, rows[index])


def remove(root, item_id):
    with _store(root) as (_, store, rows):
        remaining = [row for row in rows if row['id'] != item_id]
        if len(remaining) == len(rows):
            return False
        store._write_all(remaining)
        return True


def generate_login(root, payload):
    if not isinstance(payload, dict) or set(payload) != {'label', 'origin', 'identifier_type', 'identifier'}:
        raise InvalidInput('Invalid vault input')
    secret = {'identifier_type': payload['identifier_type'], 'identifier': payload['identifier'], 'password': secrets.token_urlsafe(32)}
    return add(root, {'kind': 'login', 'label': payload['label'], 'origin': payload['origin'], 'secret': secret})
