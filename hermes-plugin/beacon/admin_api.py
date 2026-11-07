"""Authorized bot reading and revision-protected native model selection."""
import asyncio
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.background import BackgroundTask


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


try:  # loaded now: mid-update the checkout under this process is being replaced
    from hermes_cli.update_lock import update_in_progress as _native_update_in_progress
except Exception:
    _native_update_in_progress = None
UPDATING = 'Hermes is updating'


def update_in_progress():
    """A live update, even one a restarted dashboard no longer remembers, holds Hermes' update marker or checkout lock."""
    if _native_update_in_progress is None:
        return False
    try:
        return bool(_native_update_in_progress())  # Hermes defaults to the checkout this process runs from
    except Exception:
        return False


async def native_update():
    from hermes_cli.web_routers.actions import update_hermes
    return await update_hermes()


async def native_update_status(action_id=''):
    from hermes_cli.web_routers.actions import get_action_status, get_update_receipt
    action = await get_action_status('hermes-update', lines=100)
    receipt = None
    reader = None
    if action_id:
        try:
            from hermes_cli.update_receipt import read_receipt_for_action
            reader = read_receipt_for_action
        except ImportError:  # Older native versions expose only the latest-receipt endpoint.
            pass
    if reader is not None:
        row = await asyncio.to_thread(reader, action_id)
        if isinstance(row, dict) and row.get('action_id') == action_id:
            receipt = {'receipt': row}
    else:
        try:
            receipt = await get_update_receipt()
        except HTTPException as error:
            if error.status_code != 404:
                raise
        if action_id and (not isinstance(receipt, dict) or
                          not isinstance(receipt.get('receipt'), dict) or receipt['receipt'].get('action_id') != action_id):
            receipt = None
    busy = bool(isinstance(action, dict) and action.get('running')) or await asyncio.to_thread(update_in_progress)
    return {'action': action, 'receipt': receipt, 'host_busy': busy}


def restart_error_code(result):
    """Why Hermes did not restart: `live_sessions` is its refusal while chats are running, anything else is `failed`."""
    return 'live_sessions' if 'live session' in str(result.get('restart_error') or '').casefold() else 'failed'


def native_plugin_restart(profile):
    """The owner asked for this restart from the phone, so it is Hermes' own forced one (`gateway restart --interrupt`, as
    POST /api/gateway/restart?interrupt=true): a busy Hermes never holds a plugin update back. A Hermes without it restarts as before."""
    import inspect
    from hermes_cli import web_server
    spawn = getattr(web_server, '_spawn_gateway_restart', None)
    if spawn is None or 'interrupt' not in inspect.signature(spawn).parameters:
        from hermes_cli.web_server_gateway import _restart_gateway_after
        return _restart_gateway_after(profile, what='Beacon plugin update', label='Beacon plugin update')
    try:
        proc, _ = spawn(profile, interrupt=True)
    except Exception as error:  # Hermes says why, the same way its own restart does
        return {'restart_started': False, 'restart_error': str(error)}
    return {'restart_started': True, 'restart_action': 'gateway-restart', 'restart_pid': proc.pid}


async def native_plugin_restart_status():
    from hermes_cli.web_routers.actions import get_action_status
    return await get_action_status('gateway-restart', lines=1)


def dashboard_restarter():
    """The dashboard-restart module, loaded like the dashboard loads its siblings (this file's own directory, under a stable name)."""
    module = sys.modules.get('beacon_dashboard_restart')
    if module is None:
        spec = importlib.util.spec_from_file_location('beacon_dashboard_restart', Path(__file__).resolve().parent / 'dashboard_restart.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules['beacon_dashboard_restart'] = module
        spec.loader.exec_module(module)
    return module


def current_receipt(state, requested_at, action_id):
    from datetime import datetime
    if not requested_at or not action_id:
        return False
    try:
        requested = datetime.fromisoformat(requested_at.replace('Z', '+00:00'))
        response, action = state.get('receipt'), state.get('action')
        candidates = [response.get('receipt') if isinstance(response, dict) else None,
                      action.get('receipt') if isinstance(action, dict) else None]
        for receipt in candidates:
            if isinstance(receipt, dict) and receipt.get('action_id') == action_id:
                try:
                    started = datetime.fromisoformat(str(receipt.get('started_at') or '').replace('Z', '+00:00'))
                    if started >= requested:
                        return True
                except (ValueError, TypeError):
                    continue
        return False
    except (ValueError, TypeError, KeyError):
        return False

# Two taps at once clear and start in turn, so the second one sees the first update running.
_update_gate = asyncio.Lock()


def build_router(identity, read_body):
    router = APIRouter()

    @router.post('/admin/plugin/restart')
    async def restart_plugin(request: Request):
        import sys
        mobile = sys.modules['beacon_mobile']
        identity(request, owner_only=True)
        owner, _, profile, _ = identity(request)
        with mobile.LOCK, mobile.connect() as conn:
            if mobile.active_calls(conn, owner):
                raise HTTPException(409, 'End your call before restarting Hermes')
        try:
            before = await native_plugin_restart_status()
            result = await asyncio.to_thread(native_plugin_restart, profile)
            # Hermes coalesces recent restarts; an action predating this update cannot certify it.
            if before.get('pid') and result.get('restart_pid') == before['pid']:
                return {**result, 'restart_started': False, 'restart_error_code': 'stale'}
            if result.get('restart_started') is False:
                return {**result, 'restart_error_code': restart_error_code(result)}
            return result
        except Exception:
            raise HTTPException(503, 'Hermes could not start the gateway restart') from None

    @router.get('/admin/plugin/restart/status')
    async def plugin_restart_status(request: Request, pid: int):
        identity(request, owner_only=True)
        if pid <= 0:
            raise HTTPException(400, 'Invalid restart identity')
        action = await native_plugin_restart_status()
        matched = action.get('pid') == pid
        ended = matched and action.get('running') is False
        return {'pid': pid, 'running': matched and action.get('running') is True,
                'completed': ended and action.get('exit_code') == 0,
                'failed': ended and isinstance(action.get('exit_code'), int) and action['exit_code'] != 0}

    @router.post('/admin/dashboard/restart')
    async def restart_dashboard(request: Request):
        """Restart the dashboard serving this request, from a helper that outlives it. The answer goes out first; the helper stops nothing before that."""
        mobile = sys.modules['beacon_mobile']
        owner, _, _, cfg = identity(request, owner_only=True)
        with mobile.LOCK, mobile.connect() as conn:
            if mobile.active_calls(conn, owner):
                raise HTTPException(409, 'End your call before restarting Hermes')
        # Hermes' own update replaces its code and restarts what it must; stopping the dashboard under it would only get in the way.
        if await asyncio.to_thread(update_in_progress):
            return {'restart_started': False, 'restart_error_code': 'live_sessions'}
        restarter = dashboard_restarter()
        server = request.scope.get('server')
        payload, start = await asyncio.to_thread(restarter.begin, mobile.home(), server[1] if server else None, cfg['profiles'][0])
        return JSONResponse(payload, background=BackgroundTask(start) if start else None)

    @router.get('/admin/dashboard/restart/status')
    async def dashboard_restart_status(request: Request, id: str):
        mobile = sys.modules['beacon_mobile']
        identity(request, owner_only=True)
        if not re.fullmatch(r'[0-9a-f]{32}', id):
            raise HTTPException(400, 'Invalid restart identity')
        restarter = dashboard_restarter()
        try:
            host = restarter.Host()
        except ImportError:
            host = None
        return restarter.public_status(restarter.read_status(restarter.status_path(mobile.home())), id, host)

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
        async with _update_gate:
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
        state = await native_update_status(action_id)
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
