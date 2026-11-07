"""Authenticated, profile-confined access to the existing gateway transport."""
import asyncio
import contextvars
from contextlib import asynccontextmanager
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import ipaddress
import json
import logging
import os
from pathlib import Path
import secrets
import socket
import sys
import threading
import time
from urllib.parse import quote, urlsplit
import anyio
from fastapi import APIRouter, HTTPException, Request, WebSocket
from fastapi.responses import JSONResponse

MAX_FRAME = 8 * 1024 * 1024


def subject_digest(session):
    from gateway.session_identity import authenticated_subject
    values = [getattr(session, name, None) for name in ('provider', 'issuer', 'user_id')]
    if not all(isinstance(value, str) for value in values) or not values[0] or not values[2]:
        raise ValueError('Verified identity required')
    subject = authenticated_subject(dict(zip(('provider', 'issuer', 'user_id'), values)))
    return hashlib.sha256(subject.encode('utf-8')).hexdigest()


def home_digest(home):
    return hashlib.sha256(os.path.normcase(str(Path(home).resolve())).encode('utf-8')).hexdigest()


def validate_endpoint(discovery, home):
    endpoint = discovery.endpoint
    if discovery.state != 'ready' or endpoint is None or home_digest(endpoint.profile_id) != home_digest(home):
        raise ValueError('Gateway unavailable')
    if (not isinstance(endpoint.instance_id, str) or not endpoint.instance_id or
            type(endpoint.authority_epoch) is not int or endpoint.authority_epoch <= 0 or
            endpoint.runtime_protocol != 1):
        raise ValueError('Gateway unavailable')
    try:
        origin = urlsplit(endpoint.api_origin)
        if (origin.scheme not in ('http', 'https') or not origin.hostname or not origin.port or
                origin.username is not None or origin.password is not None or origin.path or origin.query or
                origin.fragment or not ipaddress.ip_address(origin.hostname).is_loopback):
            raise ValueError()
    except (TypeError, ValueError):
        raise ValueError('Gateway unavailable') from None
    return endpoint


def authorize_request(request, mobile, dashboard_identity, enforce, profile_names, check_device):
    enforce(request)
    if (getattr(request.state, 'hosted_http_authenticated', False) is True or
            getattr(request.state,'native_http_principal',None) is not None):
        raise HTTPException(501, 'Plugin socket unavailable for this profile')
    owner, provider = dashboard_identity(request)
    session = getattr(request.state, 'session', None)
    if (getattr(session, 'user_id', None) != owner or getattr(session, 'provider', None) != provider):
        raise HTTPException(401, 'Verified native session required')
    try:
        subject_digest(session)
    except ValueError:
        raise HTTPException(401, 'Verified native session required') from None
    cfg = mobile.settings()
    if not owner or not provider or cfg.get('owner_id') != owner or cfg.get('owner_provider') != provider:
        raise HTTPException(403, 'Owner not authorized')
    profiles = cfg['profiles'] if 'profiles' in cfg else profile_names()
    if (not isinstance(profiles, list) or not 1 <= len(profiles) <= 64 or
            any(not isinstance(p, str) or not p.strip() or len(p) > 80 for p in profiles) or
            len(set(profiles)) != len(profiles)):
        raise HTTPException(403, 'Profile configuration invalid')
    selected = request.query_params.getlist('beacon_profile')
    other = request.query_params.getlist('profile')
    if (set(request.query_params) - {'profile', 'beacon_profile'} or len(selected) != 1 or
            len(other) > 1 or (other and other[0] != selected[0]) or selected[0] not in profiles):
        raise HTTPException(403, 'Profile not authorized')
    profile = selected[0]
    check_device(request, owner, provider, profile)
    return owner, provider, profile


class TicketCache:
    def __init__(self, capacity=128, ttl=20, clock=time.monotonic):
        self.capacity, self.ttl, self.clock = capacity, ttl, clock
        self.lock, self.items = threading.Lock(), {}

    def issue(self, value, loop=None):
        with self.lock:
            now = self.clock()
            for key in [key for key, entry in self.items.items() if entry[0] <= now]:
                _, _, timer = self.items.pop(key)
                if timer is not None:
                    timer.cancel()
            if len(self.items) >= self.capacity:
                raise ValueError('Gateway busy')
            ticket = secrets.token_urlsafe(32)
            timer = loop.call_later(self.ttl, self.consume, ticket) if loop is not None else None
            self.items[ticket] = (now + self.ttl, value, timer)
            return ticket

    def consume(self, ticket):
        with self.lock:
            entry = self.items.pop(ticket, None)
            if entry and entry[2] is not None:
                entry[2].cancel()
            return entry[1] if entry and entry[0] > self.clock() else None

    def replace(self, ticket, value):
        with self.lock:
            entry = self.items.get(ticket)
            if not entry or entry[0] <= self.clock():
                self.items.pop(ticket, None)
                if entry and entry[2] is not None:
                    entry[2].cancel()
                return False
            self.items[ticket] = (entry[0], value, entry[2])
            return True

    def remaining(self, ticket):
        with self.lock:
            entry = self.items.get(ticket)
            return int(entry[0] - self.clock()) if entry else 0

    def deadline(self,ticket):
        with self.lock:
            entry=self.items.get(ticket)
            return entry[0] if entry else 0

    def clear(self):
        with self.lock:
            for _, _, timer in self.items.values():
                if timer is not None:
                    timer.cancel()
            self.items.clear()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Ambiguous frame')
        result[key] = value
    return result


def validate_frame(raw, profile, capabilities_seen):
    if not isinstance(raw, str) or len(raw.encode('utf-8')) > MAX_FRAME:
        raise ValueError('Invalid frame')
    try:
        frame = json.loads(raw, object_pairs_hook=_pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, RecursionError):
        raise ValueError('Invalid frame') from None
    if not isinstance(frame, dict):
        raise ValueError('Invalid frame')
    if 'method' not in frame:
        if 'id' not in frame or ('result' in frame) == ('error' in frame) or 'params' in frame:
            raise ValueError('Invalid response')
        return capabilities_seen
    if not isinstance(frame['method'], str) or not frame['method'] or 'result' in frame or 'error' in frame:
        raise ValueError('Invalid request')
    params = frame.get('params')
    if not isinstance(params, dict):
        raise ValueError('Invalid request')
    if frame['method'] == 'client.capabilities' and not capabilities_seen and 'profile' not in params:
        if set(params) != {'server_requests'} or type(params['server_requests']) is not bool:
            raise ValueError('Invalid capabilities')
        return True
    if params.get('profile') != profile:
        raise ValueError('Profile mismatch')
    return capabilities_seen


class BoundedWorkers:
    def __init__(self, capacity=4):
        self.permits = threading.BoundedSemaphore(capacity)
        self.executor = ThreadPoolExecutor(max_workers=capacity, thread_name_prefix='beacon-canonical')

    async def run(self, fn, *args):
        if not self.permits.acquire(blocking=False):
            raise ValueError('Gateway busy')
        try:
            future = self.executor.submit(contextvars.copy_context().run, fn, *args)
        except BaseException:
            self.permits.release()
            raise
        future.add_done_callback(lambda _: self.permits.release())
        wrapped = asyncio.wrap_future(future)
        wrapped.add_done_callback(lambda done: None if done.cancelled() else done.exception())
        return await asyncio.shield(wrapped)

    def close(self):
        self.executor.shutdown(wait=False, cancel_futures=True)


def native_policy(profile):
    from hermes_cli.web_server import _get_dashboard_plugins
    from hermes_cli.web_server_dashboard import _plugin_consent_withheld
    from hermes_cli.web_server_plugin_profiles import in_process_router_refused
    from hermes_cli.web_server_profiles import _config_profile_scope, _resolve_profile_dir
    if in_process_router_refused('beacon'):
        raise HTTPException(501, 'Plugin socket unavailable for this profile')
    plugin = next((p for p in _get_dashboard_plugins() if p.get('name') == 'beacon'), None)
    if not plugin or _plugin_consent_withheld(plugin):
        raise HTTPException(403, 'Plugin not authorized')
    with _config_profile_scope(profile):
        if _plugin_consent_withheld(plugin):
            raise HTTPException(403, 'Plugin not authorized')
        return _resolve_profile_dir(profile)


def discover(home):
    from hermes_cli.gateway_runtime import discover_gateway_endpoint
    from hermes_cli.config import get_process_hermes_home
    selected = validate_endpoint(discover_gateway_endpoint(home, timeout=2), home)
    host_home = get_process_hermes_home()
    if home_digest(home)!=home_digest(host_home):
        host = validate_endpoint(discover_gateway_endpoint(host_home,timeout=2),host_home)
        if (not getattr(selected,'control_home',None) or home_digest(selected.control_home)!=home_digest(host_home)
                or selected.api_origin!=host.api_origin or selected.instance_id!=host.instance_id
                or selected.authority_epoch!=host.authority_epoch):
            raise ValueError('Gateway unavailable')
    return selected


def canonical_binding(request, profile, home, nonce):
    runner = getattr(request.app.state, 'gateway_runner', None)
    module = sys.modules.get('gateway.run_api')
    launch = getattr(request.app.state, 'session_authority', None)
    descriptor = getattr(runner, 'session_runtime_descriptor', None)
    if (runner is None or module is None or getattr(module, '_ACTIVE_RUNNER', None) is not runner or
            launch is None or getattr(runner, 'session_authority', None) is not launch or
            not isinstance(descriptor, dict) or descriptor.get('state') != 'ready' or
            not isinstance(descriptor.get('instance_id'), str) or not descriptor['instance_id'] or
            type(descriptor.get('authority_epoch')) is not int or descriptor['authority_epoch'] <= 0 or
            getattr(runner, '_draining', True) or getattr(launch,'epoch',None)!=descriptor.get('authority_epoch') or
            not isinstance(nonce, str) or len(nonce) != 64 or any(c not in '0123456789abcdef' for c in nonce)):
        raise HTTPException(503, 'Canonical admission unavailable')
    from gateway.session_authorities import authority_for_home
    authority=authority_for_home(runner,home)
    if (authority is None or getattr(authority,'retiring',False) or getattr(authority,'retired',False) or
            not isinstance(getattr(authority,'profile_id',None),str) or not authority.profile_id or
            home_digest(authority.profile_id)!=home_digest(home) or
            getattr(authority,'instance_id',None)!=descriptor['instance_id'] or
            type(getattr(authority,'epoch',None)) is not int or authority.epoch<=0):
        raise HTTPException(503,'Canonical admission unavailable')
    session = getattr(request.state, 'session', None)
    try:
        subject = subject_digest(session)
    except ValueError:
        raise HTTPException(401, 'Verified native session required') from None
    return {'profile': profile, 'home': home_digest(home), 'instance': descriptor['instance_id'],
            'epoch': authority.epoch, 'gateway_epoch':descriptor['authority_epoch'], 'nonce': nonce, 'subject': subject}


def canonical_proof(request, profile, home, nonce):
    proof = canonical_binding(request, profile, home, nonce)
    session = request.state.session
    from hermes_cli.dashboard_auth.ws_tickets import mint_ticket
    proof['ticket'] = mint_ticket(user_id=session.user_id, provider=session.provider, issuer=session.issuer)
    return proof


def canonical_live_proof(request, profile, home, stored, runtime, nonce):
    proof = canonical_binding(request, profile, home, nonce)
    if (not isinstance(stored,str) or not stored or len(stored)>256 or runtime!=stored):
        raise HTTPException(403, 'Canonical conversation unavailable')
    from gateway.session_identity import authenticated_subject
    session = request.state.session
    principal = authenticated_subject({'user_id':session.user_id,'provider':session.provider,'issuer':session.issuer})
    native=sys.modules['beacon_native']
    execution=native.execution_subject(session.user_id,session.provider,principal)
    from gateway.session_authorities import authority_for_home
    authority = authority_for_home(request.app.state.gateway_runner,home)
    row = authority.db.get_session(stored)
    live = authority.sessions.get(runtime)
    def remote_viewer(actor):
        transport=authority.events.get(getattr(actor,'transport_id',None))
        identity=getattr(transport,'auth_identity',None)
        return (actor.subject==execution and actor.profile_id==authority.profile_id and
                'session:read' in actor.capabilities and isinstance(identity,dict) and
                identity.get('native_bootstrap') is not True and identity.get('provider')==session.provider and
                identity.get('user_id')==session.user_id and identity.get('issuer','')==session.issuer)
    if (not native.owns_row(session.user_id,session.provider,profile,stored,row,principal,authority.db) or live is None or
            not any(remote_viewer(actor) for actor in tuple(live.subscribers.values()))):
        raise HTTPException(403, 'Canonical conversation unavailable')
    return {**proof,'stored':stored,'runtime':runtime}


def validate_proof(proof, expected):
    if (not isinstance(proof, dict) or set(proof) != set(expected) | {'ticket'} or
            any(proof.get(key) != value or type(proof.get(key)) is not type(value) for key, value in expected.items())):
        raise ValueError('Canonical admission unavailable')
    ticket = proof['ticket']
    if not isinstance(ticket, str) or not 1 <= len(ticket) <= 256:
        raise ValueError('Canonical admission unavailable')
    return ticket


def exchange(endpoint, profile, access_token, nonce, stored=None, runtime=None, authority_only=False, timeout=5):
    origin = urlsplit(endpoint.api_origin)
    if (not isinstance(access_token, str) or not access_token or len(access_token) > 32768 or
            '\r' in access_token or '\n' in access_token):
        raise ValueError('Verified native session required')
    connection_type = http.client.HTTPSConnection if origin.scheme == 'https' else http.client.HTTPConnection
    if not isinstance(timeout,(int,float)) or not 0<timeout<=5:
        raise ValueError('Canonical admission unavailable')
    connection = connection_type(origin.hostname, origin.port, timeout=timeout)
    connection.set_debuglevel(0)
    deadline=time.monotonic()+timeout
    held=[None]
    def abort():
        current=held[0] or getattr(connection,'sock',None)
        if current is not None:
            try: current.shutdown(socket.SHUT_RDWR)
            except Exception: pass
        try: connection.close()
        except Exception: pass
    timer=threading.Timer(timeout,abort)
    timer.daemon=True
    route = 'canonical-authority' if authority_only else 'canonical-admission' if stored is None else 'canonical-session'
    path = '/api/plugins/beacon/mobile/' + route + '?beacon_profile=' + quote(profile, safe='')
    payload = {'nonce':nonce} if stored is None else {'nonce':nonce,'stored':stored,'runtime':runtime}
    try:
        timer.start()
        connection.connect()
        held[0]=getattr(connection,'sock',None)
        if time.monotonic()>=deadline: raise ValueError('Canonical admission unavailable')
        connection.request('POST', path, body=json.dumps(payload),
                           headers={'Authorization': 'Bearer ' + access_token, 'Content-Type': 'application/json'})
        response = connection.getresponse()
        data = response.read(16385)
        if response.status != 200 or len(data) > 16384:
            raise ValueError('Canonical admission unavailable')
        result = json.loads(data)
        if not isinstance(result, dict):
            raise ValueError('Canonical admission unavailable')
        return result
    except (OSError, ValueError, http.client.HTTPException):
        raise ValueError('Canonical admission unavailable') from None
    finally:
        timer.cancel()
        connection.close()


def _same_endpoint(left, right):
    return all(getattr(left, field) == getattr(right, field) for field in
               ('profile_id', 'instance_id', 'authority_epoch', 'runtime_protocol', 'api_origin'))


def verify_live_session(request, profile, stored, runtime):
    home = native_policy(profile)
    endpoint = discover(home)
    session = getattr(request.state,'session',None)
    nonce = secrets.token_hex(32)
    result = exchange(endpoint,profile,getattr(session,'access_token',None),nonce,stored,runtime)
    epoch=result.get('epoch') if isinstance(result,dict) else None
    if type(epoch) is not int or epoch<=0: raise LookupError('Canonical runtime proof unavailable')
    expected = {'profile':profile,'home':home_digest(home),'instance':endpoint.instance_id,
                'epoch':epoch,'gateway_epoch':endpoint.authority_epoch,'nonce':nonce,'subject':subject_digest(session),
                'stored':stored,'runtime':runtime}
    if (not isinstance(result,dict) or set(result)!=set(expected) or
            any(result.get(key)!=value or type(result.get(key)) is not type(value) for key,value in expected.items())):
        raise LookupError('Canonical runtime proof unavailable')
    return result


def _ready(raw):
    if not isinstance(raw, str) or len(raw.encode('utf-8')) > MAX_FRAME:
        return False
    try:
        frame = json.loads(raw, object_pairs_hook=_pairs)
        params = frame.get('params') if isinstance(frame, dict) else None
        payload = params.get('payload') if isinstance(params, dict) else None
        return (frame.get('method') == 'event' and params.get('type') == 'gateway.ready' and
                isinstance(payload, dict) and payload.get('session_authority') is True)
    except (ValueError, AttributeError, RecursionError):
        return False


def no_redirect_connect(url, **kwargs):
    from websockets.asyncio.client import connect
    class RefuseRedirect(connect):
        def process_redirect(self, exc):
            return exc
    return RefuseRedirect(url, **kwargs)


async def settle(awaitable):
    task = asyncio.ensure_future(awaitable)
    cancelled = None
    with anyio.CancelScope(shield=True):
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError as exc:
                cancelled = exc
        if cancelled is None:
            return task.result()
        try:
            task.result()
        except Exception:
            pass
    raise cancelled


async def relay_sockets(client, upstream, profile, mobile=None, binding=None, run=asyncio.to_thread):
    native=sys.modules.get('beacon_native')
    pending={}

    async def submission(raw):
        if mobile is None or binding is None or native is None: return raw
        frame=json.loads(raw,object_pairs_hook=_pairs)
        rid=frame.get('id')
        if type(rid) not in (str,int): return raw
        if type(rid) is str and len(rid)>256 or type(rid) is int and rid.bit_length()>64: return raw
        key=(type(rid),rid)
        if key in pending:
            pending[key]=(time.monotonic(),None)
            return raw  # Ambiguous in-flight response ids cannot authorize either input.
        for old in [k for k,v in pending.items() if time.monotonic()-v[0]>=30]: pending.pop(old)
        method=frame.get('method')
        if method not in ('prompt.submit','session.create') or len(pending)>=128: return raw
        params=frame.get('params',{})
        if method=='session.create':
            request=params.get('request_id')
            if request is None:
                request=params['request_id']=secrets.token_hex(32)
                raw=json.dumps(frame,ensure_ascii=False,separators=(',',':'))
            if not isinstance(request,str) or not 1<=len(request)<=256: return raw
            value=await run(native.prepare_created,mobile,binding,profile,request)
            pending[key]=(time.monotonic(),value)
            return raw
        session=params.get('session_id');request=params.get('submission_id') or params.get('input_id')
        if (not isinstance(session,str) or not isinstance(request,str) or
                not 1<=len(session)<=256 or not 1<=len(request)<=256): return raw
        try:
            payload=native.remote_payload(params)
            value={name:binding[name] for name in ('owner','provider','principal','home')}
            value.update(profile=profile,session=session,request=request,
                         payload_digest=native._payload_fingerprint(payload),
                         wire_digest=hashlib.sha256(raw.encode('utf-8')).hexdigest())
            pending[key]=(time.monotonic(),value)
        except Exception:
            pass  # Forward the original input; unsupported payloads acquire no authority.
        return raw

    async def confirmation(raw):
        if mobile is None or binding is None or native is None: return
        try:
            frame=json.loads(raw,object_pairs_hook=_pairs)
            if 'method' not in frame and type(frame.get('id')) in (str,int):
                saved=pending.pop((type(frame['id']),frame['id']),None)
                if (saved and saved[1] is not None and time.monotonic()-saved[0]<30 and
                        'error' not in frame and isinstance(frame.get('result'),dict)):
                    if saved[1].get('kind')=='create':
                        # The native row and its creation receipt can become visible a moment after
                        # the response; retry only that same exact proof, never a weaker one. The
                        # final wait is followed by one last exact check.
                        for delay in (0,0.05,0.1,0.2,0.4):
                            if delay: await asyncio.sleep(delay)
                            if await run(native.confirm_created,mobile,saved[1],frame['result']): break
                    else: await run(native.confirm_remote_receipt,mobile,saved[1],frame['result'])
            elif frame.get('method')=='event':
                params=frame.get('params',{})
                if params.get('type')=='message.start':
                    await run(native.confirm_remote_start,mobile,params.get('admission_id'),profile,
                              params.get('session_id'),params.get('authority_epoch'),params.get('execution_generation'))
        except Exception:
            return  # Uncertain proof never settles work; the native wire remains authoritative.

    async def outgoing():
        capabilities_seen = False
        while True:
            message = await client.receive()
            if message['type'] == 'websocket.disconnect':
                return
            raw = message.get('text')
            capabilities_seen = validate_frame(raw, profile, capabilities_seen)
            raw=await submission(raw)
            await upstream.send(raw)

    async def incoming():
        while True:
            raw = await upstream.recv()
            if not isinstance(raw, str) or len(raw.encode('utf-8')) > MAX_FRAME:
                raise ValueError('Invalid frame')
            await confirmation(raw)
            await client.send_text(raw)

    tasks = [asyncio.create_task(outgoing()), asyncio.create_task(incoming())]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    finally:
        for task in tasks:
            task.cancel()
        await settle(asyncio.gather(*tasks, return_exceptions=True))


def revalidate_binding(socket, binding, mobile, profile_names, check_device):
    cfg = mobile.settings()
    owner, provider, profile = binding['owner'], binding['provider'], binding['profile']
    if (cfg.get('owner_id') != owner or cfg.get('owner_provider') != provider or
            socket.headers.get('x-beacon-device-id') != binding['device'] or
            binding['expires'] <= time.time()):
        raise HTTPException(403, 'Admission expired')
    if set(socket.query_params) - {'profile', 'beacon_profile'}:
        raise HTTPException(403, 'Profile not authorized')
    for key in ('profile', 'beacon_profile'):
        values = socket.query_params.getlist(key)
        if len(values) > 1 or (values and values[0] != profile):
            raise HTTPException(403, 'Profile not authorized')
    profiles = cfg['profiles'] if 'profiles' in cfg else profile_names()
    if (not isinstance(profiles, list) or profile not in profiles or
            not 1 <= len(profiles) <= 64 or len(set(profiles)) != len(profiles)):
        raise HTTPException(403, 'Profile not authorized')
    check_device(socket, owner, provider, profile)


def build_router(mobile, dashboard_identity, enforce, profile_names, check_device, body, update_in_progress):
    cache, workers, sockets = TicketCache(), BoundedWorkers(), threading.BoundedSemaphore(8)
    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            cache.clear()
            workers.close()
    router = APIRouter(lifespan=lifespan)
    quiet_logger = logging.Logger('beacon.canonical.websocket', logging.WARNING)
    quiet_logger.addHandler(logging.NullHandler())

    async def bounded(fn, *args):
        try:
            return await asyncio.wait_for(workers.run(fn, *args), timeout=6)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, 'Canonical gateway unavailable') from None

    async def check_update():
        if await bounded(update_in_progress):
            raise HTTPException(503, 'Update in progress')

    async def authenticate(request):
        return await bounded(authorize_request, request, mobile, dashboard_identity, enforce, profile_names, check_device)

    @router.post('/mobile/canonical-admission')
    async def companion(request: Request):
        await check_update()
        _, _, profile = await authenticate(request)
        value = await body(request, 1024)
        if set(value) != {'nonce'}:
            raise HTTPException(400, 'Invalid admission')
        home = await bounded(native_policy, profile)
        proof = await bounded(canonical_proof, request, profile, home, value['nonce'])
        return JSONResponse(proof, headers={'Cache-Control': 'no-store'})

    @router.post('/mobile/canonical-session')
    async def companion_session(request: Request):
        await check_update()
        _, _, profile = await authenticate(request)
        value = await body(request, 2048)
        if set(value)!={'nonce','stored','runtime'}:
            raise HTTPException(400, 'Invalid admission')
        home = await bounded(native_policy, profile)
        proof = await bounded(canonical_live_proof,request,profile,home,value['stored'],value['runtime'],value['nonce'])
        return JSONResponse(proof,headers={'Cache-Control':'no-store'})

    @router.post('/mobile/canonical-authority')
    async def companion_authority(request: Request):
        await check_update()
        _, _, profile = await authenticate(request)
        value = await body(request,1024)
        if set(value)!={'nonce'}: raise HTTPException(400,'Invalid admission')
        home = await bounded(native_policy,profile)
        proof = await bounded(canonical_binding,request,profile,home,value['nonce'])
        return JSONResponse(proof,headers={'Cache-Control':'no-store'})

    @router.post('/mobile/gateway-ticket')
    async def admission(request: Request):
        await check_update()
        owner, provider, profile = await authenticate(request)
        value = await body(request, 1024)
        if value:
            raise HTTPException(400, 'Invalid admission')
        session = request.state.session
        expires = getattr(session, 'expires_at', None)
        if type(expires) is not int or expires <= time.time():
            raise HTTPException(401, 'Verified native session required')
        home = await bounded(native_policy, profile)
        endpoint = await bounded(discover, home)
        try:
            ticket = cache.issue(None, loop=asyncio.get_running_loop())
        except ValueError:
            raise HTTPException(503, 'Canonical gateway busy') from None
        try:
            nonce = secrets.token_hex(32)
            deadline=cache.deadline(ticket)
            proof = await bounded(exchange, endpoint, profile, getattr(session, 'access_token', None), nonce,
                                  None,None,False,min(5,deadline-time.monotonic()))
            epoch=proof.get('epoch') if isinstance(proof,dict) else None
            if type(epoch) is not int or epoch<=0: raise ValueError('Canonical admission unavailable')
            expected = {'profile': profile, 'home': home_digest(home), 'instance': endpoint.instance_id,
                        'epoch': epoch, 'gateway_epoch':endpoint.authority_epoch, 'nonce': nonce, 'subject': subject_digest(session)}
            upstream = validate_proof(proof, expected)
            binding = {'owner': owner, 'provider': provider, 'profile': profile,
                       'device': request.headers.get('x-beacon-device-id'), 'expires': expires,
                       'home': home_digest(home), 'endpoint': endpoint, 'epoch':epoch, 'upstream': upstream,
                       'subject':subject_digest(session),'access_token':session.access_token,'deadline':deadline}
            binding['principal']=sys.modules['beacon_gateway_tools'].request_principal(request,owner,provider)
            if not cache.replace(ticket, binding):
                raise ValueError('Admission expired')
            expires_in = min(cache.remaining(ticket), int(expires - time.time()))
            if expires_in < 1:
                raise ValueError('Admission expired')
        except asyncio.CancelledError:
            cache.consume(ticket)
            raise
        except Exception:
            cache.consume(ticket)
            raise HTTPException(503, 'Canonical gateway unavailable') from None
        return JSONResponse({'ticket': ticket, 'path': '/api/plugins/beacon/gateway/ws',
                             'profile': profile, 'canonical': True, 'expires_in': expires_in},
                            headers={'Cache-Control': 'no-store'})

    @router.websocket('/gateway/ws')
    async def socket_route(socket: WebSocket):
        from hermes_cli.web_server_boundary import ws_client_reason, ws_host_origin_reason
        protocols = [part.strip() for value in socket.headers.getlist('sec-websocket-protocol')
                     for part in value.split(',') if part.strip()]
        tickets = [value[len('beacon-ticket.'):] for value in protocols if value.startswith('beacon-ticket.')]
        if (protocols.count('beacon-canonical-v1') != 1 or len(tickets) != 1 or len(protocols) != 2 or
                ws_client_reason(socket, socket.app.state) is not None or
                ws_host_origin_reason(socket, socket.app.state) is not None):
            await socket.close(code=1008)
            return
        binding = cache.consume(tickets[0])
        if binding is None or not sockets.acquire(blocking=False):
            await socket.close(code=1008)
            return
        try:
            await check_update()
            await bounded(revalidate_binding, socket, binding, mobile, profile_names, check_device)
            home = await bounded(native_policy, binding['profile'])
            endpoint = await bounded(discover, home)
            if (home_digest(home) != binding['home'] or not _same_endpoint(endpoint, binding['endpoint'])):
                raise ValueError('Gateway changed')
            nonce=secrets.token_hex(32)
            timeout=min(5,binding['deadline']-time.monotonic())
            if timeout<=0: raise ValueError('Admission expired')
            proof=await bounded(exchange,endpoint,binding['profile'],binding.pop('access_token'),nonce,None,None,True,timeout)
            expected={'profile':binding['profile'],'home':binding['home'],'instance':endpoint.instance_id,
                      'epoch':binding['epoch'],'gateway_epoch':endpoint.authority_epoch,'nonce':nonce,'subject':binding['subject']}
            if not isinstance(proof,dict) or set(proof)!=set(expected) or any(
                    proof.get(key)!=value or type(proof.get(key)) is not type(value) for key,value in expected.items()):
                raise ValueError('Gateway authority changed')
            from hermes_cli.gateway_client import gateway_ws_target
            url, upstream_protocols = gateway_ws_target(endpoint, binding['upstream'])
            connection = no_redirect_connect(url, subprotocols=upstream_protocols, proxy=None, compression=None,
                                             open_timeout=5, close_timeout=2, max_size=MAX_FRAME, max_queue=1,
                                             logger=quiet_logger)
            upstream = await connection
            try:
                if upstream.subprotocol != 'hermes-gateway-v1':
                    raise ValueError('Gateway protocol unavailable')
                ready = await asyncio.wait_for(upstream.recv(), timeout=6)
                if not _ready(ready):
                    raise ValueError('Gateway not ready')
                await socket.accept(subprotocol='beacon-canonical-v1')
                await socket.send_text(ready)
                await relay_sockets(socket, upstream, binding['profile'],mobile,binding,bounded)
            finally:
                await settle(upstream.close())
        except Exception:
            try:
                await socket.close(code=1013)
            except Exception:
                pass
        finally:
            sockets.release()

    return router
