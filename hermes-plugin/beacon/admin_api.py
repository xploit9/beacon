"""Authorized bot reading and revision-protected native model selection."""
import asyncio
import hashlib
import json
import re
from fastapi import APIRouter, HTTPException, Request


def revision(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode('utf-8')).hexdigest()


def reasoning_options(row):
    capabilities = row.get('capabilities')
    capabilities = capabilities if isinstance(capabilities, dict) else {}
    levels = row.get('reasoning_efforts', capabilities.get('reasoning_effort'))
    if row.get('supports_reasoning_effort') is False or capabilities.get('supports_reasoning') is False:
        return []
    if not isinstance(levels, (list, tuple)):
        return None
    vocabulary = ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra')
    values = [item.get('value') if isinstance(item, dict) else item for item in levels]
    return [level for level in vocabulary if level in values]


def model_capabilities(profile, provider, model):
    from hermes_cli.web_server_profiles import _config_profile_scope
    from hermes_cli.runtime_provider import resolve_runtime_provider
    from providers import get_provider_profile
    import httpx
    with _config_profile_scope(profile):
        runtime = resolve_runtime_provider(requested=provider or None, target_model=model)
        provider = provider or str(runtime.get('provider') or '')
        options = None
        base = str(runtime.get('base_url') or '').rstrip('/')
        if base:
            try:
                headers = {'Authorization': 'Bearer ' + str(runtime.get('api_key') or 'local')}
                with httpx.Client(timeout=6, follow_redirects=False) as client:
                    with client.stream('GET', base + '/models', headers=headers) as response:
                        response.raise_for_status()
                        raw = bytearray()
                        for chunk in response.iter_bytes():
                            raw.extend(chunk)
                            if len(raw) > 2 * 1024 * 1024:
                                raise ValueError('Model catalogue too large')
                        rows = json.loads(raw).get('data', [])
                row = next((item for item in rows if item.get('id') == model), None)
                if row is not None:
                    options = reasoning_options(row)
            except (httpx.HTTPError, ValueError, TypeError, AttributeError):
                pass
        if options is None:
            definition = get_provider_profile(provider)
            declared = definition.supported_reasoning_efforts(model) if definition else None
            if declared is not None:
                options = reasoning_options({'reasoning_efforts': declared})
        return {'model': model, 'provider': provider, 'reasoning_options': options or [], 'reasoning_known': options is not None}


def snapshot(profile):
    from hermes_cli.web_server_profiles import _config_profile_scope
    from hermes_cli.web_routers._common import _CONFIG_MUTATION_LOCK
    with _config_profile_scope(profile), _CONFIG_MUTATION_LOCK:
        return _snapshot_locked(profile)


def _snapshot_locked(profile):
    from hermes_cli.config import load_config
    from hermes_cli.web_server_profiles import _resolve_profile_dir
    from hermes_cli.web_routers.profiles import _profile_to_dict
    from hermes_cli import profiles
    model = load_config().get('model', {})
    root = _resolve_profile_dir(profile)
    soul = root / 'SOUL.md'
    if soul.is_symlink() or getattr(soul, 'is_junction', lambda: False)():
        raise HTTPException(409, 'Linked bot instructions cannot be read here')
    try:
        if soul.exists() and soul.stat().st_size > 128 * 1024:
            raise HTTPException(413, 'Bot instructions are too large for the phone')
        content = soul.read_text(encoding='utf-8-sig') if soul.exists() else ''
    except UnicodeDecodeError:
        raise HTTPException(409, 'Bot instructions have invalid text encoding; repair them on the computer') from None
    except OSError:
        raise HTTPException(503, 'Bot instructions could not be read; check them on the computer') from None
    info = next((p for p in profiles.list_profiles(lazy_skill_count=True) if p.name == profile), None)
    if info is None:
        raise HTTPException(404, 'Authorized bot profile not found')
    row = _profile_to_dict(info)
    return {'profile': profile, 'title': row.get('bot_title') or row.get('display_name') or profile,
            'description': row['description'], 'soul': content, 'provider': row['provider'] or '',
            'model': row['model'] or '', 'revision': revision(model)}


def save_model(profile, payload):
    from hermes_cli.config import load_config, require_readable_config_before_write
    from hermes_cli.web_server_profiles import _config_profile_scope, _resolve_profile_dir
    from hermes_cli.web_routers._common import _CONFIG_MUTATION_LOCK
    from hermes_cli.web_routers.profiles import _write_profile_model
    provider, model, expected = (payload.get(key) for key in ('provider', 'model', 'revision'))
    if any(not isinstance(value, str) or not value.strip() for value in (provider, model, expected)):
        raise HTTPException(400, 'Provider, model and original revision are required')
    if len(provider) > 200 or len(model) > 500 or len(expected) != 64:
        raise HTTPException(400, 'Invalid model selection')
    with _config_profile_scope(profile), _CONFIG_MUTATION_LOCK:
        try:
            require_readable_config_before_write()
        except Exception:
            raise HTTPException(409, 'Native configuration cannot be read safely. Repair it on the computer before saving.') from None
        if revision(load_config().get('model', {})) != expected:
            raise HTTPException(409, 'Bot model changed elsewhere. Reload and review before saving.')
        _write_profile_model(_resolve_profile_dir(profile), provider.strip(), model.strip())
        return {'ok': True, 'bot': _snapshot_locked(profile)}



def summaries(allowed):
    from hermes_cli import profiles
    from hermes_cli.web_routers.profiles import _profile_to_dict
    wanted = set(allowed)
    rows = [_profile_to_dict(info) for info in profiles.list_profiles(lazy_skill_count=True) if info.name in wanted]
    return {'bots': [{'profile': row['name'], 'title': row.get('bot_title') or row.get('display_name') or row['name'],
                      'purpose': row['description']} for row in rows]}


def set_aside_stale_gateway_pids():
    """Hermes' updater stops at "Could not inspect gateway PID" when a profile's gateway.pid names a process that is gone
    (or whose PID another process reuses now) while another gateway holds that profile's lock: one gateway serving several
    profiles writes their locks, not their PID files, so every later update stops there too. Such a file is stale and is set
    aside; it goes back if Hermes' own strict check still refuses, and a newer file written meanwhile always wins."""
    import logging, os, time
    from pathlib import Path
    try:
        from gateway import status
        from hermes_cli.profiles import list_profiles
        strict, read_record, live_pid = status.get_running_pid_identity_strict, status._read_pid_record, status._live_pid_from_record
    except Exception as error:
        logging.getLogger(__name__).warning('Beacon cannot check gateway files before an update: %s', type(error).__name__)
        return []
    moved = []
    for profile in list_profiles(lazy_skill_count=True):
        pid_path = Path(profile.path) / 'gateway.pid'
        try:
            if not pid_path.is_file():
                continue
            try:
                strict(pid_path)
                continue  # Hermes accepts it
            except Exception:
                pass
            try:
                record = read_record(pid_path)
            except Exception:
                record = None
            # Hermes' own liveness rule (PID alive and its start time matches): a live gateway's file is never touched.
            if record and live_pid(record) is not None:
                continue
            aside = pid_path.with_name(f'gateway.pid.stale-{time.time_ns()}')
            os.replace(pid_path, aside)
            try:
                strict(pid_path)
            except Exception:
                try:
                    os.rename(aside, pid_path)  # not the case this repair understands; never over a newer file
                except OSError:
                    pass
                continue
            moved.append(profile.name)
        except Exception:
            continue
    if moved:
        logging.getLogger(__name__).info('Beacon set aside stale gateway files before an update: %s', ', '.join(moved))
    return moved


def update_in_progress():
    """A live update, even one a restarted dashboard no longer remembers, holds Hermes' update marker or checkout lock."""
    try:
        from hermes_cli.update_lock import read_live_update
        from hermes_cli.web_server import PROJECT_ROOT
        return read_live_update(install_root=PROJECT_ROOT) is not None
    except Exception:
        return False


async def native_update():
    from hermes_cli.web_routers.actions import update_hermes
    return await update_hermes()


async def native_update_status():
    from hermes_cli.web_routers.actions import get_action_status, get_update_receipt
    action = await get_action_status('hermes-update', lines=100)
    try:
        receipt = await get_update_receipt()
    except HTTPException as error:
        if error.status_code != 404:
            raise
        receipt = None
    return {'action': action, 'receipt': receipt}


def current_receipt(state, requested_at, action_id):
    from datetime import datetime
    if not requested_at:
        return False
    try:
        requested = datetime.fromisoformat(requested_at.replace('Z', '+00:00'))
        receipt = (state.get('receipt') or {}).get('receipt') or {}
        started = datetime.fromisoformat(str(receipt.get('started_at') or '').replace('Z', '+00:00'))
        action = state.get('action')
        if not isinstance(action, dict):
            return False
        reported = action.get('action_id') or ''
        return started >= requested and not (action_id and reported and action_id != reported)
    except (ValueError, TypeError, KeyError):
        return False

# Two taps at once clear and start in turn, so the second one sees the first update running.
_update_gate = asyncio.Lock()


def build_router(identity, read_body):
    router = APIRouter()

    @router.get('/admin/model-capabilities')
    async def capabilities(request: Request, model: str, provider: str = ''):
        _, _, profile, _ = identity(request)
        if not model or len(model) > 500 or provider and not re.fullmatch(r'[A-Za-z0-9_.:@-]{1,200}', provider):
            raise HTTPException(400, 'Choose a configured provider and model')
        try:
            return await asyncio.to_thread(model_capabilities, profile, provider, model)
        except Exception:
            raise HTTPException(503, 'Model capabilities could not be read. Refresh the model selection.') from None

    @router.post('/admin/update')
    async def start_update(request: Request):
        from datetime import datetime, timezone
        identity(request, owner_only=True)
        # Clear the stale gateway files Hermes' updater would stop on, unless an update already runs; the native action then
        # owns admission, spawning and already-running de-duplication.
        async with _update_gate:
            if not await asyncio.to_thread(update_in_progress):
                await asyncio.to_thread(set_aside_stale_gateway_pids)
            requested_at = datetime.now(timezone.utc).isoformat()
            result = await native_update()
        if result.get('already_running'):
            return {**result, 'ok': False, 'accepted': False, 'requested_at': None,
                    'error': 'update_already_running',
                    'message': 'A server update is already running. Refresh its status; no new update was started.'}
        return {**result, 'accepted': bool(result.get('ok')), 'requested_at': requested_at if result.get('ok') else None}

    @router.get('/admin/update/status')
    async def update_status(request: Request):
        from datetime import datetime, timezone
        identity(request, owner_only=True)
        requested_at = request.query_params.get('requested_at', '')
        action_id = request.query_params.get('action_id', '')
        if len(requested_at) > 80 or len(action_id) > 128:
            raise HTTPException(400, 'Invalid update correlation')
        if requested_at:
            try:
                parsed = datetime.fromisoformat(requested_at.replace('Z', '+00:00'))
                if parsed.tzinfo is None:
                    raise ValueError()
            except ValueError:
                raise HTTPException(400, 'Update timestamp must include its time zone') from None
        state = await native_update_status()
        return {**state, 'server_now': datetime.now(timezone.utc).isoformat(),
                'receipt_current': current_receipt(state, requested_at, action_id)}

    @router.get('/admin/bots')
    async def list_bots(request: Request):
        _, _, _, cfg = identity(request, owner_only=True)
        return await asyncio.to_thread(summaries, cfg['profiles'])

    @router.get('/admin/bot')
    async def read(request: Request):
        _, _, profile, _ = identity(request)
        return await asyncio.to_thread(snapshot, profile)

    @router.put('/admin/bot')
    async def write(request: Request):
        payload = await read_body(request, limit=4096)
        _, _, profile, _ = identity(request)
        return await asyncio.to_thread(save_model, profile, payload)

    return router
