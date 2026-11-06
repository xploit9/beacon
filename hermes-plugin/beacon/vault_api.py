"""Owner/profile-bound metadata and write-only access to Hermes' local vault."""
import asyncio
import hashlib
import os
import importlib.util
import re
import sys
import time
import uuid
from pathlib import Path
from fastapi import APIRouter, HTTPException, Request

_KEY = 'beacon_vault_runtime'
if _KEY not in sys.modules:
    spec = importlib.util.spec_from_file_location(_KEY, Path(__file__).with_name('vault_runtime.py'))
    runtime = importlib.util.module_from_spec(spec)
    sys.modules[_KEY] = runtime
    spec.loader.exec_module(runtime)
else:
    runtime = sys.modules[_KEY]

_ATTESTATION = 'beacon_vault_attestation'
if _ATTESTATION not in sys.modules:
    spec = importlib.util.spec_from_file_location(_ATTESTATION, Path(__file__).with_name('vault_attestation.py'))
    attestation = importlib.util.module_from_spec(spec)
    sys.modules[_ATTESTATION] = attestation
    spec.loader.exec_module(attestation)
else:
    attestation = sys.modules[_ATTESTATION]

_ITEM = re.compile(r'vault_[0-9a-f]{12}\Z')
_MAX_RECEIPTS = 8192


def _request_id(value):
    if not isinstance(value, str) or len(value) != 36:
        raise HTTPException(400, 'Invalid vault request')
    try:
        canonical = str(uuid.UUID(value))
    except ValueError:
        raise HTTPException(400, 'Invalid vault request') from None
    if canonical != value.lower():
        raise HTTPException(400, 'Invalid vault request')
    return canonical


def _item_id(value):
    if not isinstance(value, str) or not _ITEM.fullmatch(value):
        raise HTTPException(400, 'Invalid vault request')
    return value


def _identity_input(payload):
    if set(payload) != {'request_id', 'name', 'email'}:
        raise HTTPException(400, 'Invalid vault request')
    name, email = payload['name'], payload['email']
    if not isinstance(name, str) or not isinstance(email, str) or len(name) > 120 or len(email) > 320:
        raise HTTPException(400, 'Invalid vault request')
    name, email = name.strip(), email.strip()
    if any(ord(c) < 32 or ord(c) == 127 for c in name + email):
        raise HTTPException(400, 'Invalid vault request')
    if email and (email.count('@') != 1 or any(c.isspace() for c in email) or not all(email.split('@'))):
        raise HTTPException(400, 'Invalid vault request')
    try:
        (name + email).encode('utf-8')
    except UnicodeError:
        raise HTTPException(400, 'Invalid vault request') from None
    return {'name': name, 'email': email, 'status': 'unverified' if email else 'not_configured'}


def _schema(conn):
    conn.execute('''CREATE TABLE IF NOT EXISTS vault_requests (
        owner TEXT NOT NULL, provider TEXT NOT NULL, profile TEXT NOT NULL,
        request_id TEXT NOT NULL, created REAL NOT NULL,
        PRIMARY KEY (owner, provider, profile, request_id))''')
    conn.execute('''CREATE TABLE IF NOT EXISTS vault_identities (
        owner TEXT NOT NULL, provider TEXT NOT NULL, profile TEXT NOT NULL,
        name TEXT NOT NULL, email TEXT NOT NULL,
        PRIMARY KEY (owner, provider, profile))''')
    conn.execute('''CREATE TABLE IF NOT EXISTS vault_profile_owners (
        home_hash TEXT PRIMARY KEY, owner TEXT NOT NULL, provider TEXT NOT NULL)''')
    conn.execute('''CREATE TABLE IF NOT EXISTS vault_profile_issuers (
        home_hash TEXT PRIMARY KEY, owner TEXT NOT NULL, provider TEXT NOT NULL, issuer TEXT NOT NULL)''')


def _admit(mobile, scope, request_id):
    try:
        with mobile.LOCK, mobile.connect() as conn:
            conn.execute('PRAGMA synchronous=FULL')
            _schema(conn)
            conn.execute('BEGIN IMMEDIATE')
            if conn.execute('SELECT 1 FROM vault_requests WHERE owner=? AND provider=? AND profile=? AND request_id=?', (*scope, request_id)).fetchone():
                raise HTTPException(409, 'Request already used; refresh before continuing')
            count = conn.execute('SELECT count(*) FROM vault_requests WHERE owner=? AND provider=? AND profile=?', scope).fetchone()[0]
            if count >= _MAX_RECEIPTS:
                raise HTTPException(429, 'Vault request limit reached')
            conn.execute('INSERT INTO vault_requests VALUES (?,?,?,?,?)', (*scope, request_id, time.time()))
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, 'Vault unavailable') from None


def _identity(mobile, scope):
    try:
        with mobile.LOCK, mobile.connect() as conn:
            _schema(conn)
            row = conn.execute('SELECT name,email FROM vault_identities WHERE owner=? AND provider=? AND profile=?', scope).fetchone()
            name, email = (row[0], row[1]) if row else ('', '')
            return {'name': name, 'email': email, 'status': 'unverified' if email else 'not_configured'}
    except Exception:
        raise HTTPException(503, 'Vault unavailable') from None


def _set_identity(mobile, scope, value):
    try:
        with mobile.LOCK, mobile.connect() as conn:
            _schema(conn)
            conn.execute('INSERT OR REPLACE INTO vault_identities VALUES (?,?,?,?,?)', (*scope, value['name'], value['email']))
        return value
    except Exception:
        raise HTTPException(503, 'Vault unavailable') from None


def _bound(scoped, mobile, scope, action):
    def run(root):
        try:
            home = Path(root).resolve(strict=True)
            if not home.is_dir():
                raise ValueError()
            home_key = hashlib.sha256(os.path.normcase(str(home)).encode('utf-8')).hexdigest()
            with mobile.LOCK, mobile.connect() as conn:
                conn.execute('PRAGMA synchronous=FULL')
                _schema(conn)
                conn.execute('BEGIN IMMEDIATE')
                row = conn.execute('SELECT owner,provider FROM vault_profile_owners WHERE home_hash=?', (home_key,)).fetchone()
                if row and (row[0], row[1]) != scope[:2]:
                    raise HTTPException(403, 'Vault needs owner migration')
                if row is None:
                    conn.execute('INSERT INTO vault_profile_owners VALUES (?,?,?)', (home_key, scope[0], scope[1]))
                issuer_row = conn.execute('SELECT owner,provider,issuer FROM vault_profile_issuers WHERE home_hash=?', (home_key,)).fetchone()
                if issuer_row and tuple(issuer_row) != (scope[0], scope[1], scope[3]):
                    raise HTTPException(403, 'Vault needs owner migration')
                if issuer_row is None:
                    conn.execute('INSERT INTO vault_profile_issuers VALUES (?,?,?,?)', (home_key, scope[0], scope[1], scope[3]))
            with attestation.scope(scope[0], scope[1], scope[3], home_key):
                return action(home, (scope[0], scope[1], home_key))
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, 'Vault unavailable') from None
    try:
        return scoped(scope[2], run, hydrate_secrets=False)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, 'Vault unavailable') from None


def _mutation(mobile, scope, request_id, action):
    if not runtime.native_compatible():
        raise HTTPException(503, 'Vault unavailable')
    _admit(mobile, scope, request_id)
    try:
        return action()
    except runtime.InvalidInput:
        raise HTTPException(400, 'Invalid vault input') from None
    except runtime.MissingItem:
        raise HTTPException(404, 'Vault item unavailable') from None
    except Exception:
        raise HTTPException(503, 'Vault unavailable') from None


def _capabilities(mobile):
    result = runtime.capabilities()
    coordinator = attestation._DASHBOARD
    try:
        receipt = coordinator.readiness() if coordinator is not None and coordinator.mobile is mobile else {}
    except Exception:
        receipt = {}
    ready = result.get('secure_prompts') is True and receipt.get('secure_prompts') is True
    return {**result, 'secure_prompts':ready, 'otp':ready and result.get('otp') is True and receipt.get('otp') is True,
            'signup':ready and result.get('signup') is True and receipt.get('signup') is True}


def _check_otp(mobile, data):
    secret = data.get('secret') if isinstance(data, dict) else None
    value = secret.get('otp_secret') if isinstance(secret, dict) else None
    if isinstance(value, str) and value.strip() and not _capabilities(mobile)['otp']:
        raise runtime.InvalidInput('Authenticator keys are not available yet')


def build_router(identity_async, body, scoped, mobile):
    router = APIRouter()

    async def scope(request):
        owner, provider, profile, _ = await identity_async(request)
        session = getattr(request.state, 'session', None)
        issuer = getattr(session, 'issuer', None)
        if (getattr(session, 'user_id', None) != owner or getattr(session, 'provider', None) != provider or
                not isinstance(issuer, str) or len(issuer) > 2048 or any(ord(c) < 32 or ord(c) == 127 for c in issuer)):
            raise HTTPException(401, 'Verified native session required')
        return owner, provider, profile, issuer

    async def payload(request, keys):
        value = await body(request, limit=32768)
        if not isinstance(value, dict) or not keys[0] <= set(value) or not set(value) <= keys[1]:
            raise HTTPException(400, 'Invalid vault request')
        request_id = _request_id(value['request_id'])
        return request_id, {key: item for key, item in value.items() if key != 'request_id'}

    @router.get('/mobile/vault/capabilities')
    async def capabilities(request: Request):
        current = await scope(request)
        result = await asyncio.to_thread(_bound, scoped, mobile, current, lambda root, bound: _capabilities(mobile))
        return {'capabilities': result}

    @router.get('/mobile/vault')
    async def read(request: Request):
        current = await scope(request)
        def read_scope(root, bound):
            return {'items': runtime.items(root), 'capabilities': _capabilities(mobile), 'identity': _identity(mobile, bound)}
        return await asyncio.to_thread(_bound, scoped, mobile, current, read_scope)

    @router.get('/mobile/vault/identity')
    async def read_identity(request: Request):
        current = await scope(request)
        return await asyncio.to_thread(_bound, scoped, mobile, current, lambda root, bound: _identity(mobile, bound))

    @router.put('/mobile/vault/identity')
    async def write_identity(request: Request):
        current = await scope(request)
        request_id, data = await payload(request, ({'request_id', 'name', 'email'}, {'request_id', 'name', 'email'}))
        value = _identity_input({'request_id': request_id, **data})
        result = await asyncio.to_thread(_bound, scoped, mobile, current,
            lambda root, bound: _mutation(mobile, bound, request_id, lambda: _set_identity(mobile, bound, value)))
        return {**result, 'request_id': request_id}

    @router.post('/mobile/vault/items')
    async def add_item(request: Request):
        current = await scope(request)
        fields = {'request_id', 'kind', 'label', 'origin', 'secret'}
        request_id, data = await payload(request, (fields, fields))
        def add(root, bound):
            def write():
                _check_otp(mobile, data)
                return runtime.add(root, data)
            return _mutation(mobile, bound, request_id, write)
        item = await asyncio.to_thread(_bound, scoped, mobile, current, add)
        return {'item': item, 'request_id': request_id}

    @router.put('/mobile/vault/items/{item_id}')
    async def replace_item(item_id: str, request: Request):
        current = await scope(request)
        _item_id(item_id)
        request_id, data = await payload(request, ({'request_id', 'label', 'origin'}, {'request_id', 'label', 'origin', 'secret'}))
        def replace(root, bound):
            def write():
                _check_otp(mobile, data)
                attestation.invalidate_intent(mobile, (bound[0], bound[1], current[3], bound[2]), item_id)
                return runtime.replace(root, item_id, data)
            return _mutation(mobile, bound, request_id, write)
        item = await asyncio.to_thread(_bound, scoped, mobile, current, replace)
        return {'item': item, 'request_id': request_id}

    @router.delete('/mobile/vault/items/{item_id}')
    async def remove_item(item_id: str, request: Request):
        current = await scope(request)
        _item_id(item_id)
        request_id, _ = await payload(request, ({'request_id'}, {'request_id'}))
        def remove(root, bound):
            def write():
                attestation.invalidate_intent(mobile, (bound[0], bound[1], current[3], bound[2]), item_id)
                return runtime.remove(root, item_id)
            return _mutation(mobile, bound, request_id, write)
        removed = await asyncio.to_thread(_bound, scoped, mobile, current, remove)
        return {'removed': removed, 'request_id': request_id}

    @router.post('/mobile/vault/generate-login')
    async def generate_login(request: Request):
        current = await scope(request)
        fields = {'request_id', 'label', 'origin', 'identifier_type', 'identifier'}
        request_id, data = await payload(request, (fields, fields))
        def generate(root, bound):
            def write():
                item = runtime.generate_login(root, data)
                attestation.record_intent(mobile, (bound[0], bound[1], current[3], bound[2]), item['id'], item['origin'])
                return item
            return _mutation(mobile, bound, request_id, write)
        item = await asyncio.to_thread(_bound, scoped, mobile, current, generate)
        return {'item': item, 'request_id': request_id}

    return router
