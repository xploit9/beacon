"""Native RPC adapter. Uses the same identity-stamped dispatcher as /api/ws."""
import threading
import uuid
import logging
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time

class ReplyTransport:
    def __init__(self, owner,provider):
        self.auth_identity={'user_id':owner,'provider':provider}
        self.done=threading.Event(); self.response=None; self.rid=None
    def write(self, obj):
        if obj.get('id')==self.rid:
            self.response=obj;self.done.set()
        return True
    def close(self): pass

def rpc(owner,provider,method,params):
    from tui_gateway import server
    transport=ReplyTransport(owner,provider);transport.rid=uuid.uuid4().hex
    result=server.dispatch({'jsonrpc':'2.0','id':transport.rid,'method':method,'params':params},transport=transport)
    if result is None:
        if not transport.done.wait(20): raise RuntimeError('Native RPC timeout')
        result=transport.response
    if not result or 'error' in result:
        error=result.get('error') if isinstance(result,dict) else None
        code=error.get('code') if isinstance(error,dict) else None
        logging.getLogger(__name__).warning('Beacon native RPC refused: code=%s',code if type(code) is int and -32768<=code<=65535 else 'unavailable')
        raise LookupError('Native session unavailable or forbidden')
    return result['result']

def _principal(owner,provider,principal):
    if principal is None:
        return None
    from gateway.session_identity import parse_authenticated_subject
    parts=parse_authenticated_subject(principal)
    if parts is None or parts[0]!=provider or parts[2]!=owner:
        raise LookupError('Native verified principal unavailable')
    return principal


def home_fingerprint(home):
    return hashlib.sha256(os.path.normcase(str(Path(home).resolve())).encode('utf-8')).hexdigest()


def profile_fingerprint(profile):
    from tui_gateway import server
    from hermes_constants import get_process_hermes_home
    return home_fingerprint(server._profile_home(profile) or getattr(server,'_hermes_home',None) or get_process_hermes_home())


def _payload_fingerprint(payload):
    return hashlib.sha256(json.dumps(payload,sort_keys=True,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode('utf-8')).hexdigest()


def remote_payload(params):
    """Use native's pure admission validators; never stage media or create a session."""
    from gateway.session_finite import admit_finite
    from gateway.session_surface import admit_surface
    from gateway.session_display import admit_display
    from gateway.session_cli_turn import admit_cli_turn
    allowed={'profile','session_id','text','submission_id','input_id','queued','finite','unattended',
             'surface','voice_context','interrupted','voice_turn','display_kind','title_preview',
             'turn_author','api_text','model_override','clarify_batch'}
    if set(params)-allowed or not isinstance(params.get('text'),str) or params.get('unattended') is True:
        raise ValueError('Remote admission payload unavailable')
    # Media capture writes native storage; this read-only proof refuses media-bearing turns.
    return {'text':params['text'],**admit_finite(params),**admit_surface(params),
            **admit_display(params),**admit_cli_turn(params)}


def confirm_remote_receipt(mobile,pending,receipt):
    """Record current remote authorization only after its matching native response."""
    try:
        from tui_gateway import server
        from hermes_state_runtime import find_session_admission
        names={'owner','provider','profile','principal','home','session','request','payload_digest','wire_digest'}
        if set(pending)!=names or not isinstance(receipt,dict): return False
        principal=_principal(pending['owner'],pending['provider'],pending['principal'])
        if profile_fingerprint(pending['profile'])!=pending['home']: return False
        if not all(isinstance(pending[k],str) and re.fullmatch(r'[A-Za-z0-9_.:-]{1,256}',pending[k]) for k in ('session','request')): return False
        ref=receipt.get('ref')
        admission=receipt.get('admission_id')
        epoch=receipt.get('authority_epoch')
        if (principal is None or not isinstance(ref,dict) or not isinstance(ref.get('profile_id'),str) or
                ref.get('session_id')!=pending['session'] or
                home_fingerprint(ref.get('profile_id'))!=pending['home'] or
                not isinstance(admission,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,160}',admission) or
                type(epoch) is not int or epoch<=0 or receipt.get('status') not in ('queued','started','terminal')): return False
        with server._profile_db({'profile':pending['profile']}) as db:
            if db is None: return False
            stored=db.get_session(pending['session'])
            row=find_session_admission(db,principal_id=principal,session_id=pending['session'],request_id=pending['request'])
        if (not stored or stored.get('user_id')!=principal or not row or row.get('admission_id')!=admission or
                row.get('principal_id')!=principal or row.get('target_session_id')!=pending['session'] or
                row.get('request_id')!=pending['request'] or row.get('owner_epoch')!=epoch or
                row.get('status') not in ('queued','started','terminal') or not isinstance(row.get('payload'),dict) or
                _payload_fingerprint(row['payload'])!=pending['payload_digest']): return False
        value={key:pending[key] for key in ('owner','provider','profile','home','session','request','principal','wire_digest')}
        value.update(admission_id=admission,epoch=epoch,payload_digest=_payload_fingerprint(row['payload']))
        if not mobile.confirm_remote_admission(value): return False
        generation=row.get('generation')
        if row.get('status')=='started' and type(generation) is int:
            if not confirm_remote_start(mobile,admission,pending['profile'],pending['session'],epoch,generation): return False
        # Snapshot an already-authorized job at acceptance, even when no phone polls overnight.
        continuation=sys.modules.get('beacon_voice_continuation')
        if continuation is not None:
            with mobile.connect() as conn:
                jobs=[dict(r) for r in conn.execute('''SELECT * FROM jobs WHERE owner=? AND provider=? AND profile=?
                    AND stored=? AND request=? AND principal=? AND epoch='canonical' ''',
                    (pending['owner'],pending['provider'],pending['profile'],pending['session'],pending['request'],principal))]
            for job in jobs:
                try: continuation.native_receipt(job,mobile.remote_admission(admission))
                except (LookupError,RuntimeError,ValueError): pass
        return True
    except Exception:
        return False


def confirm_remote_start(mobile,admission,profile,session,epoch,generation):
    try:
        from tui_gateway import server
        from hermes_state_runtime import find_session_admission
        binding=mobile.remote_admission(admission)
        if (not binding or binding['profile']!=profile or binding['session']!=session or
                binding['home']!=profile_fingerprint(profile) or
                binding['generation'] and binding['epoch']!=epoch or
                type(generation) is not int or generation<=0): return False
        with server._profile_db({'profile':profile}) as db:
            if db is None: return False
            stored=db.get_session(session)
            row=find_session_admission(db,principal_id=binding['principal'],session_id=session,request_id=binding['request'])
        if (not stored or stored.get('user_id')!=binding['principal'] or not row or row.get('admission_id')!=admission or
                row.get('principal_id')!=binding['principal'] or row.get('target_session_id')!=session or
                row.get('status')!='started' or row.get('owner_epoch')!=epoch or row.get('generation')!=generation or
                _payload_fingerprint(row['payload'])!=binding['payload_digest']): return False
        return mobile.start_remote_admission(admission,epoch,generation)
    except Exception:
        return False


def confirmed_admission(mobile,row,owner,provider,profile,stored,binding=None):
    durable=binding is not None
    binding=binding if durable else mobile.remote_admission(row['admission_id'])
    if not binding: raise LookupError('Remote admission provenance unavailable')
    principal=_principal(owner,provider,binding['principal'])
    if (not principal or any(binding[key]!=value for key,value in (
            ('admission_id',row['admission_id']),
            ('owner',owner),('provider',provider),('profile',profile),('home',profile_fingerprint(profile)),
            ('session',stored),('request',row['request_id']),('principal',row['principal_id']),
            ('payload_digest',_payload_fingerprint(row['payload']))))):
        raise LookupError('Remote admission provenance changed')
    if row['status'] in ('started','terminal') and type(row.get('generation')) is int:
        if binding['generation']==0:
            if (type(row['owner_epoch']) is not int or row['owner_epoch']<binding['epoch'] or
                    type(row['generation']) is not int or row['generation']<=0):
                raise LookupError('Remote admission generation unavailable')
            if durable:
                binding={**binding,'epoch':row['owner_epoch'],'generation':row['generation']}
            elif not mobile.start_remote_admission(row['admission_id'],row['owner_epoch'],row['generation']):
                raise LookupError('Remote admission generation unavailable')
            else: binding=mobile.remote_admission(row['admission_id'])
        if (not binding or binding['epoch']!=row['owner_epoch'] or binding['generation']!=row['generation']):
            raise LookupError('Remote admission generation changed')
    elif binding['epoch']!=row['owner_epoch'] or binding['generation']!=0:
        raise LookupError('Remote admission state changed')
    return binding


def remote_admission_active(binding):
    """Retain expired metadata only for its exact queued/running native input; no activation."""
    try:
        from tui_gateway import server
        from hermes_state_runtime import get_session_admission,_epoch
        with server._profile_db({'profile':binding['profile']}) as db:
            if db is None: return None
            row=get_session_admission(db,admission_id=binding['admission_id'])
            if row is None or row['status'] not in ('queued','started'): return False
            stored=db.get_session(binding['session'])
            if not stored or stored.get('user_id')!=binding['principal']: return False
            if row['status']=='started':
                with db._read_ctx() as conn: _epoch(conn,row['owner_epoch'])
        confirmed_admission(sys.modules['beacon_mobile'],row,binding['owner'],binding['provider'],binding['profile'],
                            binding['session'],binding)
        return True
    except (LookupError,ValueError,KeyError,TypeError):
        return False
    except Exception:
        return None


def current_principal(owner,provider,profile,stored):
    """An executing native row needs this exact remote authorization, never an inferred issuer."""
    try:
        from gateway.session_ingress import executing_admission,viewer_turn
        from gateway.session_authorities import authority_for_home
        from hermes_cli.profiles import profile_matches_home
        from hermes_constants import get_hermes_home
        from hermes_state_runtime import list_session_admissions
        runner=getattr(sys.modules.get('gateway.run_api'),'_ACTIVE_RUNNER',None)
        mobile=sys.modules.get('beacon_mobile')
        home=get_hermes_home()
        if runner is None or mobile is None or not profile_matches_home(profile,home): return None
        authority=authority_for_home(runner,home)
        descriptor=getattr(runner,'session_runtime_descriptor',{})
        if (authority is None or getattr(runner,'_draining',True) or descriptor.get('state')!='ready' or
                descriptor.get('instance_id')!=authority.instance_id or
                getattr(authority,'retiring',False) or getattr(authority,'retired',False) or
                home_fingerprint(authority.profile_id)!=home_fingerprint(home)):
            return None
        live=authority.sessions.get(stored)
        if live is None or not executing_admission.get() or viewer_turn.get() is not live: return None
        deadline=time.monotonic()+0.4
        while True:
            for row in list_session_admissions(authority.db,session_id=stored):
                if (row['target_session_id']!=stored or row['status']!='started' or row['owner_epoch']!=authority.epoch or
                        authority.executing.get(stored)!=row.get('generation')): continue
                try: binding=confirmed_admission(mobile,row,owner,provider,profile,stored)
                except LookupError: continue
                principal=binding['principal']
                saved=authority.db.get_session(stored)
                if (saved and saved.get('user_id')==principal and live.source.user_id==principal and
                        authority.sessions.get(stored) is live and viewer_turn.get() is live and
                        authority.executing.get(stored)==row['generation']): return principal
            if time.monotonic()>=deadline: return None
            time.sleep(0.02)  # The response pump may still be confirming this just-started turn.
    except Exception:
        return None


def verify(owner,provider,profile,stored,runtime=None,history=True,principal=None):
    from tui_gateway import server
    principal=_principal(owner,provider,principal)
    with server._profile_db({'profile':profile}) as db:
        # A store that cannot be opened says nothing about ownership: the caller may try again.
        if db is None: raise RuntimeError('Native conversation store unavailable')
        stored_row=db.get_session(stored)
        if not stored_row:
            raise LookupError('Native stored conversation not owned')
        row_owner=stored_row.get('user_id')
        if principal is not None and row_owner==principal:
            if runtime: raise LookupError('Canonical runtime proof unavailable')
            return stored_row
        if row_owner!=provider+':'+owner:
            raise LookupError('Native stored conversation not owned')
        if not runtime: return stored_row
        requested_home=server._profile_home(profile)
        expected_home=str(requested_home) if requested_home else None
        with server._sessions_lock:
            record=server._sessions.get(runtime)
            if not record: raise LookupError('Native runtime unavailable')
            if server._session_auth_user_id(record)!=provider+':'+owner:
                raise LookupError('Native session not owned')
            if (record.get('profile_home') or None)!=expected_home:
                raise LookupError('Native profile mismatch')
            current=record.get('session_key')
        if current!=stored:
            try: chain=db.get_compression_chain(stored)
            except Exception as exc:
                raise RuntimeError('Native compression lineage unavailable') from exc
            if not isinstance(chain,list) or not chain or chain[0]!=stored or current not in chain:
                raise LookupError('Native session is not a compression continuation')
            for sid in chain[:chain.index(current)+1]:
                try: segment=db.get_session(sid)
                except Exception as exc: raise RuntimeError('Native compression lineage unavailable') from exc
                if not segment or segment.get('user_id')!=provider+':'+owner:
                    raise LookupError('Native compression lineage not owned')
        with server._sessions_lock:
            if (server._sessions.get(runtime) is not record or record.get('session_key')!=current
                    or server._session_auth_user_id(record)!=provider+':'+owner
                    or (record.get('profile_home') or None)!=expected_home):
                raise RuntimeError('Native runtime changed during verification')
    result=rpc(owner,provider,'session.history',{'profile':profile,'session_id':runtime}) if history else stored_row
    with server._sessions_lock:
        if (server._sessions.get(runtime) is not record or record.get('session_key')!=current
                or server._session_auth_user_id(record)!=provider+':'+owner
                or (record.get('profile_home') or None)!=expected_home):
            raise RuntimeError('Native runtime changed during verification')
    return result


def reserve_conversation(owner,provider,profile,stored,principal=None):
    """Persist an already-owned live draft without changing its conversation identity."""
    from tui_gateway import server
    with server._profile_db({'profile':profile}) as db:
        if db is None: raise RuntimeError('Native conversation store unavailable')
        existing=db.get_session(stored)
    if existing is not None:
        return verify(owner,provider,profile,stored,principal=principal)
    requested_home=server._profile_home(profile)
    expected_home=str(requested_home) if requested_home else None
    expected_owner=provider+':'+owner
    def owned(record):
        return (record.get('session_key')==stored
                and server._session_auth_user_id(record)==expected_owner
                and (record.get('profile_home') or None)==expected_home
                and not record.get('_finalized'))
    with server._sessions_lock:
        matches=[(sid,record) for sid,record in server._sessions.items() if owned(record)]
        if len(matches)!=1: raise LookupError('Owned live draft unavailable')
        sid,record=matches[0]
    if server._ensure_session_db_row(record) is False:
        raise RuntimeError('Conversation reservation failed')
    with server._sessions_lock:
        if server._sessions.get(sid) is not record or not owned(record):
            raise LookupError('Conversation reservation changed')
    # The create-time title lands with the row, as Hermes' own _seed_row does, so a thread is named before its first message.
    if title:=record.get('pending_title'):
        try:
            with server._profile_db({'profile':profile},writer=True) as db:
                if db is not None and db.set_session_title(stored,title): record['pending_title']=None
        except Exception: pass  # a taken title stays queued; the first turn names the thread as before
    return verify(owner,provider,profile,stored,principal=principal)


def conversation_snapshot(owner, provider, profile, stored):
    """Read native history without resuming, attaching or prewarming an agent."""
    from tui_gateway import server
    # Newer Hermes rebinds session_history's functions onto server (method_ctx.bind_module); the module's own copy then
    # lacks server's names and raises NameError, which left every reply notification on its generic text.
    _history_to_messages = getattr(server, '_history_to_messages', None)
    if _history_to_messages is None:
        from tui_gateway.session_history import _history_to_messages
    expected_owner = provider + ':' + owner
    profile_home = server._profile_home(profile)
    expected_home = str(profile_home) if profile_home else None
    with server._sessions_lock:
        live = [(sid, row) for sid, row in server._sessions.items()
                if row.get('session_key') == stored
                and server._session_auth_user_id(row) == expected_owner
                and (row.get('profile_home') or None) == expected_home
                and not row.get('_finalized')]
        if len(live) > 1:
            # A lazy status probe opens its own live row beside the chat's runtime; every row here is the owner's own,
            # so read the one doing work, then one with an agent, then the oldest.
            live.sort(key=lambda item: (not (item[1].get('running') or item[1].get('inflight') or item[1].get('queued')),
                                        item[1].get('agent') is None))
        sid, active = live[0] if live else ('', {})
        running = bool(active.get('running') or active.get('inflight') or active.get('queued'))
        ephemeral = list(active.get('history') or [])
        info = {key: active[key] for key in ('model', 'provider', 'title', 'cwd', 'reasoning_effort') if key in active}
    with server._profile_db({'profile': profile}) as db:
        # A live chat still answers from its runtime; a saved one needs the store, and an unopenable store is not "not found".
        if db is None and not active: raise RuntimeError('Native conversation store unavailable')
        row = db.get_session(stored) if db else None
        if row is not None and row.get('user_id') != expected_owner:
            raise LookupError('Native conversation not owned')
        if row is None and not active:
            raise LookupError('Native conversation not owned')
        history = db.get_messages_as_conversation(stored, include_ancestors=True, include_row_ids=True) if row else ephemeral
    base = {key: row[key] for key in ('title', 'model', 'provider', 'cwd', 'reasoning_effort', 'hidden') if row and key in row}
    base.update(info)
    messages = _history_to_messages(history, profile_home=expected_home)
    return dict(base, session_id=stored, stored_session_id=stored, runtime_session_id=sid,
                profile=profile, messages=messages, message_count=len(messages),
                running=running, active_stream_id=sid if running else '',
                model_provider=base.get('provider') or '', workspace=base.get('cwd') or '',
                archived=bool(base.get('hidden')), passive_read=True)


def conversation_list(owner, provider, profile, archived=False):
    """The phone must not offer profile-wide rows that its call/read authority cannot open."""
    from tui_gateway import server
    result = rpc(owner, provider, 'session.list', {'profile': profile, 'limit': 200, 'include_hidden': archived})
    visible = set()
    if archived:
        current = rpc(owner, provider, 'session.list', {'profile': profile, 'limit': 200, 'include_hidden': False})
        visible = {row.get('id') for row in current.get('sessions', [])}
    selected = []
    seen = set()
    with server._profile_db({'profile': profile}) as db:
        if db is None: raise RuntimeError('Native conversation store unavailable')
        for item in result.get('sessions', []):
            original = item.get('id')
            if archived and original in visible: continue
            sid = item.get('stored_session_id') or item.get('resolved_id') or original
            row = db.get_session(sid) if isinstance(sid, str) else None
            if not row or row.get('user_id') != provider + ':' + owner or sid in seen: continue
            seen.add(sid)
            selected.append(dict(item, id=sid, stored_session_id=sid))
    return dict(result, sessions=selected)

THREAD_SOURCES = ('android_thread', 'phone')  # the app's Threads lane: Beacon threads and Relay threads
POST_KIND = 'cron_delivery'
# Hermes refuses a row while the thread's own turn runs or its context is being compressed; the post waits instead.
POST_BUSY = ('SessionTurnLeaseLostError', 'SessionCompressionInProgressError')


def threads(owner, provider, profile):
    """The owner's open threads, newest first: the chats a scheduled job can post into."""
    rows = conversation_list(owner, provider, profile)['sessions']
    return [{'id': row['id'], 'title': row.get('title') or ''} for row in rows if row.get('source') in THREAD_SOURCES]


def post(owner, provider, profile, stored, key, text, name):
    """Record a scheduled result in an owned conversation, as Hermes records any result that arrives between turns.

    The row is a user turn labelled like Hermes' own cron mirror ("[Cron delivery: ...]"), so alternation holds and
    the thread's next turn adopts it (tui_gateway.prompt_turn._adopt_out_of_band_turns) whether the thread is open
    or not. One row per ``key``, on the thread's live continuation; Hermes refuses it while a turn is running and the
    caller retries. The caller has verified that the owner holds ``stored``."""
    from tui_gateway import server
    with server._profile_db({'profile': profile}, writer=True) as db:
        if db is None: raise RuntimeError('Native conversation store unavailable')
        add = getattr(db, 'append_completion_note', None)
        if add is None: raise RuntimeError('Scheduled posts need a newer Hermes')
        # A compressed thread continues under a new id; a row on the closed parent would be refused for good, as Hermes' own delivery rows are.
        try: target = db.resolve_resume_session_id(stored) or stored
        except Exception: target = stored
        return add(target, key, '[Cron delivery: ' + name + ']\n' + text, POST_KIND, {'job': name})


PROJECT_CREATION_LOCK = threading.Lock()


def create_project(owner, provider, profile, request_id, name, description=''):
    if (not isinstance(request_id, str) or str(uuid.UUID(request_id)) != request_id
            or not isinstance(name, str) or not name.strip() or len(name) > 120
            or any(ord(c) < 32 for c in name) or not isinstance(description, str) or len(description) > 2000):
        raise ValueError('A stable request, project name and description are required')
    slug = 'phone-' + request_id
    # Keep the native lookup + create together even if the HTTP request is cancelled.
    with PROJECT_CREATION_LOCK:
        projects = rpc(owner, provider, 'projects.list', {'profile': profile}).get('projects')
        if not isinstance(projects, list):
            raise RuntimeError('Native project listing unavailable')
        for project in projects:
            if project.get('slug') == slug:
                if project.get('name') != name.strip() or (project.get('description') or '') != description:
                    raise ValueError('This project was already created. Refresh Projects to edit it; your draft is retained.')
                return {'project': project}
        return rpc(owner, provider, 'projects.create', {'profile': profile, 'name': name.strip(), 'description': description, 'slug': slug})


def provision(owner,provider,profile):
    """Save an empty owned chat without a live session: this also runs in Hermes' cron process, where session.create would start an agent."""
    from hermes_state_ids import new_session_id
    from tui_gateway import server
    stored=new_session_id()
    with server._profile_db({'profile':profile},writer=True) as db:
        if db is None: raise RuntimeError('Native conversation store unavailable')
        db.create_session(stored,source='android',user_id=provider+':'+owner)
    verify(owner,provider,profile,stored)
    return stored


def create(owner,provider,profile):
    from tui_gateway import server
    result=rpc(owner,provider,'session.create',{'profile':profile,'source':'android','title':'Beacon voice call','close_on_disconnect':False})
    sid=result['session_id'];record=None
    try:
        with server._sessions_lock:
            record=server._sessions.get(sid)
            if not record or server._session_auth_user_id(record)!=provider+':'+owner or record.get('session_key')!=result['stored_session_id']:
                record=None;raise RuntimeError('Native identity unavailable')
        if server._ensure_session_db_row(record) is False: raise RuntimeError('Voice history reservation failed')
        with server._sessions_lock:
            if server._sessions.get(sid) is not record or server._session_auth_user_id(record)!=provider+':'+owner:
                raise RuntimeError('Native reservation changed')
        verify(owner,provider,profile,result['stored_session_id'])
        return result['stored_session_id']
    except Exception:
        with server._sessions_lock:
            current=server._sessions.get(sid)
            close=record is not None and current is record and server._session_auth_user_id(record)==provider+':'+owner and not record.get('running')
        if close:
            try: rpc(owner,provider,'session.close',{'profile':profile,'session_id':sid})
            except Exception: pass  # Never close a replacement or another owner's session.
        raise
