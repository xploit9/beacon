"""Native RPC adapter. Uses the same identity-stamped dispatcher as /api/ws."""
import threading
import uuid

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
    if not result or 'error' in result: raise LookupError('Native session unavailable or forbidden')
    return result['result']

def verify(owner,provider,profile,stored,runtime=None,history=True):
    from tui_gateway import server
    with server._profile_db({'profile':profile}) as db:
        # A store that cannot be opened says nothing about ownership: the caller may try again.
        if db is None: raise RuntimeError('Native conversation store unavailable')
        stored_row=db.get_session(stored)
        if not stored_row or stored_row.get('user_id')!=provider+':'+owner:
            raise LookupError('Native stored conversation not owned')
    if runtime:
        with server._sessions_lock:
            record=server._sessions.get(runtime)
            if not record or record.get('session_key')!=stored or server._session_auth_user_id(record)!=provider+':'+owner:
                raise LookupError('Native session not owned')
            requested_home=server._profile_home(profile)
            if (record.get('profile_home') or None)!=(str(requested_home) if requested_home else None):
                raise LookupError('Native profile mismatch')
        return rpc(owner,provider,'session.history',{'profile':profile,'session_id':runtime}) if history else stored_row
    return stored_row

def reserve_conversation(owner,provider,profile,stored):
    """Persist an already-owned live draft without changing its conversation identity."""
    from tui_gateway import server
    with server._profile_db({'profile':profile}) as db:
        if db is None: raise RuntimeError('Native conversation store unavailable')
        existing=db.get_session(stored)
    if existing is not None:
        return verify(owner,provider,profile,stored)
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
    return verify(owner,provider,profile,stored)


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
    assignment = {}
    if row and hasattr(server, '_session_project_assignment'):
        from pathlib import Path
        from hermes_constants import get_hermes_home
        from hermes_cli import projects_db
        with projects_db.connect_closing(Path(expected_home or get_hermes_home()) / 'projects.db') as conn:
            assignment = server._session_project_assignment(projects_db, conn, row, expected_owner)
    messages = _history_to_messages(history, profile_home=expected_home)
    return dict(base, session_id=stored, stored_session_id=stored, runtime_session_id=sid,
                profile=profile, messages=messages, message_count=len(messages),
                running=running, active_stream_id=sid if running else '',
                model_provider=base.get('provider') or '', workspace=base.get('cwd') or '',
                archived=bool(base.get('hidden')), passive_read=True, project_id=assignment.get("project_id"), project_detached=assignment.get("detached", False))


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
