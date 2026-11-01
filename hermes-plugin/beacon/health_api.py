"""Owner-only plugin health: what this Beacon plugin observes about itself right now.

Cheap by design: the version files, Hermes' own gateway record, two small SQLite counts and one timed read of the profile
config. It never lists plugins, MCP servers, skills or profiles. An answer is reused for CACHE_SECONDS; `fresh=1` skips that.
A value it cannot observe is null or unknown, never a guess.
"""
import asyncio
import importlib
import importlib.util
import os
import sys
import threading
import time
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

ROOT = Path(__file__).resolve().parent
CACHE_SECONDS = 5.0
# The call context loads the profile before anything else; past this the phone would wait too, so calls are not ready.
CONTEXT_BUDGET_SECONDS = 3.0
# A context read slower than this cannot start a call in under a second.
SLOW_CONTEXT_MS = 1000

_monotonic = time.monotonic
_cache = {}
_cache_lock = threading.Lock()
_probe_lock = threading.Lock()


def _sibling(name):
    return sys.modules.get('beacon_' + name)


def _installed_version():
    mobile = _sibling('mobile')
    return mobile.read_plugin_version(ROOT / 'plugin.yaml') if mobile else ''


# What this dashboard process loaded: Hermes imports the plugin once, and an updated install on disk changes `installed`, not this.
RUNNING_VERSION = _installed_version()


def _started_at(pid):
    """When a process started (epoch seconds), None when psutil or the process cannot say. psutil is optional, so it is imported by name."""
    try:
        return int(importlib.import_module('psutil').Process(pid).create_time())
    except Exception:
        return None


def _gateway():
    """Running and busy from Hermes' own liveness record, the same rule its /api/status uses; unknown when this Hermes lacks it."""
    try:
        from gateway import status as native
        runtime = native.read_runtime_status() or {}
        liveness = native.resolve_gateway_liveness(runtime=runtime)
        state = runtime.get('gateway_state')
        state = state if isinstance(state, str) else ''
        running = bool(liveness.running)
        busy = bool(native.derive_gateway_busy(gateway_running=running, gateway_state=state, active_agents=runtime.get('active_agents', 0)))
        return {'known': True, 'running': running, 'busy': busy, 'state': state,
                'started_at': _started_at(liveness.pid) if running and liveness.pid else None}
    except Exception:
        return {'known': False, 'running': False, 'busy': False, 'state': '', 'started_at': None}


def _sender_configured(cfg):
    credentials = cfg.get('firebase_credentials')
    if isinstance(credentials, dict):
        configured = credentials.get('type') == 'service_account' and all(credentials.get(k) for k in ('project_id', 'client_email', 'private_key'))
    elif isinstance(credentials, str):
        try:
            configured = Path(credentials).is_file()
        except OSError:
            configured = False
    else:
        configured = False
    try:
        sdk = importlib.util.find_spec('firebase_admin') is not None
    except (ImportError, ValueError):
        sdk = False
    return bool(configured and sdk)


def _push(cfg, owner, profile):
    """Whether Hermes can push to this owner's phone: a configured sender and a registered phone. Keep connected lives on the phone."""
    mobile = _sibling('mobile')
    sender = _sender_configured(cfg)
    with mobile.connect() as conn:
        targets = conn.execute('SELECT COUNT(*) FROM targets WHERE owner=? AND profile=? AND expires>? AND ' + mobile.ACTIVE_TARGET,
                               (owner, profile, time.time())).fetchone()[0]
    reason = '' if sender and targets else 'no_sender' if not sender else 'no_phone'
    return {'ready': not reason, 'sender': sender, 'targets': targets, 'reason': reason}


def _context_read(profile):
    """The profile load every call context starts with, timed; its answer is not used."""
    context = _sibling('voice_context')
    started = time.perf_counter()
    context.scoped(profile, context.config, hydrate_secrets=False)
    return round((time.perf_counter() - started) * 1000)


async def _calls(cfg, owner, profile):
    """Calls are ready when a voice provider is selected, Hermes is not updating, and the context read answers in time."""
    models = _sibling('voice_models')
    mobile = _sibling('mobile')
    admin = _sibling('admin_api')
    result = {'ready': False, 'provider': '', 'context_ms': None, 'reason': 'unavailable'}
    try:
        result['provider'] = models.selected(mobile, owner, profile, cfg)['provider']
    except Exception:
        return result
    if admin is not None and await asyncio.to_thread(admin.update_in_progress):
        return {**result, 'reason': 'updating'}
    # One read at a time: a hung profile must not pile up threads behind repeated checks.
    def probe():
        if not _probe_lock.acquire(blocking=False):
            return None
        try:
            return _context_read(profile)
        finally:
            _probe_lock.release()
    try:
        ms = await asyncio.wait_for(asyncio.to_thread(probe), CONTEXT_BUDGET_SECONDS)
    except asyncio.TimeoutError:
        return {**result, 'reason': 'timeout'}
    except Exception:
        return {**result, 'reason': 'unavailable'}
    if ms is None:
        return {**result, 'reason': 'busy'}
    return {**result, 'ready': True, 'context_ms': ms, 'reason': 'slow' if ms > SLOW_CONTEXT_MS else ''}


def _observe(cfg, owner, profile):
    """Everything that is read from files and the database, in one pass off the event loop."""
    installed = _installed_version()
    gateway_version = _sibling('mobile').gateway_plugin_version()
    gateway = _gateway()
    dashboard_current = RUNNING_VERSION == installed if RUNNING_VERSION and installed else None
    gateway_current = gateway_version == installed if gateway_version and installed else None
    try:
        installed_at = int((ROOT / 'plugin.yaml').stat().st_mtime)
    except OSError:
        installed_at = None
    # The phone asks for a restart only when something loaded older code; nothing on disk records how an update ended.
    result = 'unknown' if dashboard_current is None else 'restart_needed' if dashboard_current is False or gateway_current is False else 'applied'
    return {
        'observed_at': int(time.time()),
        'plugin': {'running': RUNNING_VERSION, 'installed': installed, 'gateway': gateway_version},
        'dashboard': {'current': dashboard_current, 'started_at': _started_at(os.getpid())},
        'gateway': {**gateway, 'current': gateway_current},
        'update': {'at': installed_at, 'result': result},
        'push': _push(cfg, owner, profile),
    }


async def _snapshot(cfg, owner, profile):
    value = await asyncio.to_thread(_observe, cfg, owner, profile)
    return {**value, 'calls': await _calls(cfg, owner, profile)}


def build_router(identity):
    router = APIRouter()

    @router.get('/mobile/health')
    async def health(request: Request, fresh: int = 0):
        owner, _, _, cfg = identity(request, owner_only=True)
        profiles = cfg['profiles']
        wanted = request.query_params.get('beacon_profile') or request.query_params.get('profile')
        if wanted is not None and wanted not in profiles:
            raise HTTPException(403, 'Profile not authorized')
        profile = wanted or profiles[0]
        key = (owner, profile)
        now = _monotonic()
        with _cache_lock:
            held = _cache.get(key)
        if held and not fresh and 0 <= now - held[0] < CACHE_SECONDS:
            return JSONResponse({**held[1], 'cached': True}, headers={'Cache-Control': 'no-store'})
        value = await _snapshot(cfg, owner, profile)
        with _cache_lock:
            if len(_cache) >= 64:
                _cache.pop(next(iter(_cache)))
            _cache[key] = (now, value)
        return JSONResponse({**value, 'cached': False}, headers={'Cache-Control': 'no-store'})

    return router
