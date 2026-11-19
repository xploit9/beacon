"""Authorized bot list and model capabilities, plus the restart routes Hermes has no route for."""
import asyncio
import importlib.util
import json
import logging
from pathlib import Path
import re
import sys
import threading
import time
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.background import BackgroundTask


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


# Turning Beacon on for another profile. Hermes gates a plugin per profile (its own plugins.enabled) and has no profile-scoped enable route; its name
# resolver only sees the active profile's plugins, so the key is resolved here, where Beacon is installed (the dashboard's profile), and Hermes' own
# activation (the config part of `hermes plugins enable`, the PM transaction every enable surface goes through) runs inside that one profile's scope.
# The plugin writes no Hermes file and uses no other Hermes call for it (the background pass also takes PM's file lock, below); compat.py and
# the contract test watch these private names.
PROFILE_PLUGIN_KEY = 'beacon'
PROFILE_PLUGIN_LOCK = threading.Lock()
PROFILE_PLUGIN_SECONDS = 120
# How long the route waits for a turn-on already running (the background pass below) before it answers 409.
PROFILE_PLUGIN_WAIT = 20
# How often the dashboard's watcher looks for an authorised profile without Beacon (a profile created since the last pass).
PROFILES_ON_SECONDS = 60
# Profiles whose background turn-on Hermes refused (a dependency conflict, a selection it did not record, Beacon not found here, a worker or lock
# timeout), each with the monotonic time it was parked: a race with another turn-on is not parked (_changed_meanwhile). Each try is a PM admission under PM's install lock, so the pass
# tries a parked profile again at most once every PROFILE_RETRY_SECONDS. A dashboard restart (every install and update) clears them. The phone still asks.
PROFILE_RETRY_SECONDS = 3600
PROFILES_REFUSED = {}
# Consecutive races per profile (_changed_meanwhile): a race is asked again on the next pass, but one that keeps recurring (another writer touching
# that profile's config every minute) is parked like a refusal after PROFILE_RACES_BEFORE_PARK in a row, since each try holds PM's install lock ~18 s.
PROFILE_RACES_BEFORE_PARK = 3
PROFILES_RACED = {}
# Two processes can serve Beacon on one Hermes home: the dashboard and the desktop GUI backend each mount its API and start its watcher, each with its
# own PROFILE_PLUGIN_LOCK (REVIEW-E3 finding 7: both passes ran, 15 s apart). Only the process that holds this file lock, next to the plugin's
# database, runs the background pass, so the second does not repeat PM's sync either. It is Hermes' own PM byte lock (pm.filesystem.lock_path,
# msvcrt on Windows, fcntl on POSIX), taken without waiting and kept for the life of the process; the OS releases it when that process ends, and
# the other process's next pass takes it over. The phone's route still turns a profile on from either process (PM's compare-and-swap decides).
PROFILE_PASS_LOCK = 'profile-pass.lock'
_profile_pass_fd = None
log = logging.getLogger(__name__)
# PM's compare-and-swap answer when the selection it was asked to publish changed under it (another turn-on, from the phone or another process, or
# any write of that profile's config). Hermes has no type for it: the PM worker re-raises a plain ValueError (pm/client.py:83-97) and
# plugins_admission.admit_plugin_set_change wraps it in a plain AdmissionRefused (plugins_admission.py:78-83). Every such PM message names what
# changed and ends in "retry" (pm/publication.py:96, :117, pm/install.py:833, pm/plugin_eviction.py:231); test_hermes_contract.py pins them.
_CHANGED_MEANWHILE = re.compile(r'\bchanged\b.*;\s*retry\.?\s*$', re.IGNORECASE | re.DOTALL)


def _changed_meanwhile(error):
    """True when Hermes refused only because the selection changed while it worked: the next pass simply asks again. A dependency conflict is a refusal."""
    seen = 0
    while error is not None and seen < 6:
        if type(error).__name__ == 'DependencyConflict':
            return False
        if _CHANGED_MEANWHILE.search(str(error)):
            return True
        error, seen = error.__cause__ or error.__context__, seen + 1
    return False


def _runs_profile_pass():
    """True while this process holds the background pass's file lock (taken once, without waiting, and kept). A Hermes without PM's lock, or a lock
    file that cannot be opened, runs the pass as before: PM's compare-and-swap keeps two passes correct, only slower."""
    global _profile_pass_fd
    if _profile_pass_fd is not None:
        return True
    try:
        from pm.filesystem import lock_path
        root = sys.modules['beacon_mobile']._protected_root()
    except Exception as error:  # no PM lock in this Hermes, or Beacon's data folder is unusable: the pass runs as before, never raises
        log.debug('Beacon runs the profile pass without its file lock: %s', type(error).__name__)
        return True
    try:
        fd = lock_path(Path(root) / PROFILE_PASS_LOCK, timeout=0)
    except OSError as error:
        log.debug('Beacon runs the profile pass without its file lock: %s', type(error).__name__)
        return True
    if fd is None:
        return False
    _profile_pass_fd = fd
    return True


def _beacon_on(profile):
    """Look again after a failed turn-on: another turn-on (the phone's, the other process's pass) may have won the race. Never raises."""
    try:
        from hermes_cli.web_server_profiles import _config_profile_scope
        from hermes_cli import plugins_cmd
        with _config_profile_scope(profile):
            return PROFILE_PLUGIN_KEY in plugins_cmd._get_enabled_set() and PROFILE_PLUGIN_KEY not in plugins_cmd._get_disabled_set()
    except Exception:
        return False


class ProfilePluginBusy(Exception):
    pass


class ProfilePluginUnavailable(Exception):
    pass


class ProfilePluginMissing(Exception):
    """Hermes found no `beacon` plugin in the dashboard's profile. Private on purpose: a KeyError or IndexError from inside Hermes is a failure, not this."""


def enable_plugin_for_profile(profile, auto=False, wait=0):
    """Beacon on for `profile` only; True when this call changed the selection, False when it was already on. With `auto` (the phone or the
    background pass, nobody asked) a profile whose own config turned Beacon off (plugins.disabled, which Hermes' loader obeys over plugins.enabled)
    stays off and the answer is None; only the owner's explicit turn-on changes that. Waits up to `wait` seconds for another turn-on. Runs in a
    worker thread."""
    if not (PROFILE_PLUGIN_LOCK.acquire(timeout=wait) if wait > 0 else PROFILE_PLUGIN_LOCK.acquire(blocking=False)):
        raise ProfilePluginBusy()
    try:
        try:
            from hermes_cli.web_server_profiles import _config_profile_scope
            from hermes_cli import plugins_cmd
            resolve, activate, enabled_set = plugins_cmd._resolve_plugin_key, plugins_cmd._activate_key, plugins_cmd._get_enabled_set
            disabled_set = plugins_cmd._get_disabled_set
        except (ImportError, AttributeError):
            raise ProfilePluginUnavailable() from None
        key = resolve(PROFILE_PLUGIN_KEY)
        if key is None:
            raise ProfilePluginMissing()
        with _config_profile_scope(profile):
            if auto and {key, PROFILE_PLUGIN_KEY} & disabled_set():
                return None
            changed = bool(activate(key, enable=True))
            if key not in enabled_set():
                raise RuntimeError('Hermes did not record the selection')
        return changed
    finally:
        PROFILE_PLUGIN_LOCK.release()


def turn_on_for_profiles(profiles):
    """Beacon on for each of the owner's `profiles` that lacks it, so every profile works from the phone as every profile works in Hermes' dashboard.

    The dashboard's watcher runs it at start (so after an install or update too) and every PROFILES_ON_SECONDS (a profile created later), in a worker
    thread; register() never does. Each profile's own selection is read first, so a pass where every profile has Beacon resolves and writes nothing.
    A profile whose own config turned Beacon off stays off. Never raises: one profile's failure is logged once and the next one runs, and that profile is
    parked for PROFILE_RETRY_SECONDS (PROFILES_REFUSED), then tried once more; a busy lock or a profile Hermes no longer has is not a refusal. After a
    failure the profile is looked at again: Beacon on there now (another turn-on won the race) is done, and a selection that changed while PM worked
    ([_changed_meanwhile]) is asked again by the next pass, neither logged as a failure nor parked. Returns the profiles this pass turned on."""
    if update_in_progress() or not _runs_profile_pass():  # another process on this Hermes home runs the pass
        return []
    try:
        from hermes_cli.web_server_profiles import _config_profile_scope
        from hermes_cli import plugins_cmd
        enabled_set, disabled_set = plugins_cmd._get_enabled_set, plugins_cmd._get_disabled_set
    except (ImportError, AttributeError):
        return []
    on_now = _beacon_on
    turned_on = []
    for profile in profiles:
        parked = PROFILES_REFUSED.get(profile)
        if parked is not None and time.monotonic() - parked < PROFILE_RETRY_SECONDS:
            continue
        try:
            with _config_profile_scope(profile):
                if PROFILE_PLUGIN_KEY in enabled_set() or PROFILE_PLUGIN_KEY in disabled_set():
                    continue
            if enable_plugin_for_profile(profile, auto=True, wait=PROFILE_PLUGIN_SECONDS):
                turned_on.append(profile)
            PROFILES_REFUSED.pop(profile, None)
            PROFILES_RACED.pop(profile, None)
        except HTTPException as error:  # Hermes no longer has a profile the owner's list still names: nothing to do, and no warning every pass
            log.debug('Beacon skipped profile %r: Hermes answered HTTP %s', profile, error.status_code)
        except ProfilePluginBusy:  # another turn-on held the lock past the wait: the next pass tries again
            log.debug('Beacon skipped profile %r: another turn-on is running', profile)
        except Exception as error:
            if on_now(profile):  # Beacon is on there now: nothing failed that matters, so no warning and nothing parked
                PROFILES_REFUSED.pop(profile, None)
                PROFILES_RACED.pop(profile, None)
                log.debug('Beacon is on for profile %r: another turn-on finished first', profile)
            elif _changed_meanwhile(error) and PROFILES_RACED.get(profile, 0) + 1 < PROFILE_RACES_BEFORE_PARK:  # a race: the next pass asks again
                PROFILES_REFUSED.pop(profile, None)
                PROFILES_RACED[profile] = PROFILES_RACED.get(profile, 0) + 1
                log.info('Turning Beacon on for profile %r met a concurrent change; the next pass tries again', profile)
            elif _changed_meanwhile(error):  # the same race every pass: parked like a refusal
                PROFILES_RACED.pop(profile, None)
                PROFILES_REFUSED[profile] = time.monotonic()
                log.warning('Turning Beacon on for profile %r met a concurrent change %d times in a row; tried again in an hour', profile,
                            PROFILE_RACES_BEFORE_PARK)
            else:
                PROFILES_RACED.pop(profile, None)
                PROFILES_REFUSED[profile] = time.monotonic()
                log.warning('Turning Beacon on for profile %r failed: %s; tried again in an hour', profile, type(error).__name__)
    if turned_on:
        log.info('Beacon turned on for profiles %r', turned_on)
    return turned_on


async def read_object(request, limit):
    """The JSON object a small request carries; refuses more than `limit` bytes, a broken body and anything that is not an object."""
    from starlette.requests import ClientDisconnect
    raw = bytearray()
    try:
        async for part in request.stream():
            raw.extend(part)
            if len(raw) > limit:
                raise HTTPException(413, 'Request too large')
    except ClientDisconnect:
        raise HTTPException(400, 'Request disconnected before body was received') from None
    try:
        value = json.loads(raw or b'{}')
    except Exception:
        raise HTTPException(400, 'Invalid JSON') from None
    if not isinstance(value, dict):
        raise HTTPException(400, 'Expected object')
    return value


def _refuse_during_call(mobile, owner):
    with mobile.LOCK, mobile.connect() as conn:
        if mobile.active_calls(conn, owner):
            raise HTTPException(409, 'End your call before restarting Hermes')


def build_router(identity):
    router = APIRouter()

    @router.post('/admin/plugin/restart')
    async def restart_plugin(request: Request):
        import sys
        mobile = sys.modules['beacon_mobile']
        await asyncio.to_thread(identity, request, owner_only=True)
        owner, _, profile, _ = await asyncio.to_thread(identity, request)
        await asyncio.to_thread(_refuse_during_call, mobile, owner)
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
        await asyncio.to_thread(identity, request, owner_only=True)
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
        owner, _, _, cfg = await asyncio.to_thread(identity, request, owner_only=True)
        await asyncio.to_thread(_refuse_during_call, mobile, owner)
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
        await asyncio.to_thread(identity, request, owner_only=True)
        if not re.fullmatch(r'[0-9a-f]{32}', id):
            raise HTTPException(400, 'Invalid restart identity')
        restarter = dashboard_restarter()
        try:
            host = restarter.Host()
        except ImportError:
            host = None
        return restarter.public_status(restarter.read_status(restarter.status_path(mobile.home())), id, host)

    @router.post('/admin/profile-plugin/enable')
    async def enable_profile_plugin(request: Request):
        """Beacon on for one profile. The app asks with `auto: true` by itself when Hermes refused the plugin there (the refused request then runs
        again); that leaves a profile whose own config turned Beacon off as it is and answers `enabled: false`. Without `auto` it is the owner's own
        Turn on, which also undoes such an off. Idempotent; changes only that profile's plugin selection.

        One status, one meaning, because the app acts on the status: 400 no single profile chosen; 403 profile not in the owner's list; 404 is never
        answered here (it can only mean this plugin has no such route); 409 another turn-on is running; 422 Hermes does not know the profile; 500 Hermes
        failed or refused; 501 this plugin or Hermes cannot do it (Beacon is not installed in the dashboard profile, or Hermes lacks the enable names);
        503 Hermes is updating; 504 it took longer than the budget (the worker may still finish).
        """
        _, _, _, cfg = await asyncio.to_thread(identity, request, owner_only=True)
        value = await read_object(request, 512)
        profile, auto = value.get('profile'), value.get('auto', False)
        if (not {'profile'} <= set(value) <= {'profile', 'auto'} or type(auto) is not bool or not isinstance(profile, str)
                or not 0 < len(profile) <= 80):
            raise HTTPException(400, 'Choose one profile')
        if profile not in cfg['profiles']:
            raise HTTPException(403, 'Profile not authorized')
        if await asyncio.to_thread(update_in_progress):
            raise HTTPException(503, 'Update in progress')
        try:
            changed = await asyncio.wait_for(asyncio.to_thread(enable_plugin_for_profile, profile, auto, PROFILE_PLUGIN_WAIT), PROFILE_PLUGIN_SECONDS)
        except asyncio.TimeoutError:
            raise HTTPException(504, 'Turning Beacon on is taking longer than expected. Check again in a minute.') from None
        except ProfilePluginBusy:
            raise HTTPException(409, 'Another change is running. Try again in a moment.') from None
        except ProfilePluginUnavailable:
            raise HTTPException(501, 'Turning Beacon on for a profile needs a Beacon update for this Hermes') from None
        except ProfilePluginMissing:
            raise HTTPException(501, 'Beacon is not installed in the dashboard profile') from None
        except HTTPException as error:
            # Hermes' own profile checks (unknown or invalid name); its text names nothing more than the profile.
            if error.status_code in (400, 404):
                raise HTTPException(422, 'Unknown profile') from None
            log.warning('Turning Beacon on for profile %r failed: Hermes answered HTTP %s', profile, error.status_code)
            raise HTTPException(500, 'Hermes could not turn Beacon on for this profile') from None
        except Exception as error:
            # Another turn-on (the other process's pass, a second tap) may have won PM's compare-and-swap: Beacon is on, so this is done.
            if await asyncio.to_thread(_beacon_on, profile):
                log.info('Beacon is on for profile %r: another turn-on finished first', profile)
                return {'ok': True, 'profile': profile, 'enabled': True, 'changed': False}
            log.warning('Turning Beacon on for profile %r failed: %s', profile, type(error).__name__)
            raise HTTPException(500, 'Hermes could not turn Beacon on for this profile') from None
        if changed is None:
            log.info('Beacon stays off for profile %r: that profile turned it off', profile)
            return {'ok': True, 'profile': profile, 'enabled': False, 'changed': False}
        log.info('Beacon turned on for profile %r (changed=%s)', profile, changed)
        return {'ok': True, 'profile': profile, 'enabled': True, 'changed': changed}

    @router.get('/admin/model-capabilities')
    async def capabilities(request: Request, model: str, provider: str = ''):
        _, _, profile, _ = await asyncio.to_thread(identity, request)
        if not model or len(model) > 500 or provider and not re.fullmatch(r'[A-Za-z0-9_.:@-]{1,200}', provider):
            raise HTTPException(400, 'Choose a configured provider and model')
        try:
            return await asyncio.to_thread(model_capabilities, profile, provider, model)
        except Exception:
            raise HTTPException(503, 'Model capabilities could not be read. Refresh the model selection.') from None

    @router.get('/admin/bots')
    async def list_bots(request: Request):
        _, _, _, cfg = await asyncio.to_thread(identity, request, owner_only=True)
        return await asyncio.to_thread(summaries, cfg['profiles'])

    return router
