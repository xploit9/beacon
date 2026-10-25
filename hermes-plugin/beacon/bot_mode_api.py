"""Phone projection of native Bot Mode; Hermes owns chats and hosted Discussion rooms."""
import asyncio
import hashlib
import json
import math
from fastapi import APIRouter, HTTPException, Request

CANONICAL_TITLE = 'Bot Chat'
CHAT_LOCK = asyncio.Lock()
SPACE_PAGE_SIZE = 100
SPACE_SCAN_PAGES = 10
SPACE_SCAN_LIMIT = SPACE_PAGE_SIZE * SPACE_SCAN_PAGES


def space_id(owner, provider, profile, request_id=None):
    parts = [owner, provider, profile] + ([request_id] if request_id is not None else [])
    scope = json.dumps(parts, ensure_ascii=False, separators=(',', ':'))
    return 'beacon-' + hashlib.sha256(scope.encode()).hexdigest()


def authorized_members(members, profiles):
    return isinstance(members, list) and bool(members) and all(
        isinstance(m, dict) and isinstance(m.get('target'), dict) and
        m['target'].get('kind') == 'local' and isinstance(m['target'].get('profile'), str) and
        m['target']['profile'] in profiles and m.get('profile') == m['target']['profile']
        for m in members)


def build_router(identity, body, native):
    router = APIRouter(prefix='/mobile/bot-mode')

    async def rpc(owner, provider, profile, method, **params):
        try:
            result = await asyncio.to_thread(native.rpc, owner, provider, method, dict(params, profile=profile))
        except LookupError:
            raise HTTPException(409, 'Native Bot Mode operation unavailable or forbidden; refresh before retrying') from None
        except (RuntimeError, OSError):
            raise HTTPException(503, 'Native Bot Mode service unavailable; refresh before retrying') from None
        if not isinstance(result, dict):
            raise HTTPException(503, 'Native Bot Mode returned an invalid result; refresh before retrying')
        return result

    async def capabilities(owner, provider, profile):
        try:
            result = await rpc(owner, provider, profile, 'groups.capabilities')
        except HTTPException:
            result = {}
        methods = result.get('methods')
        if not isinstance(methods, list) or not all(isinstance(m, str) for m in methods):
            methods = []
        # TUI has no member setter; its retry rows cannot distinguish unknown work from deferred work.
        return {'rename': 'groups.rename' in methods, 'delete': 'groups.disband' in methods,
                'approval': 'groups.approve' in methods, 'change_members': False, 'retry': False}

    async def roster(request):
        owner, provider, profile, cfg = identity(request)
        result = await rpc(owner, provider, profile, 'profiles.list', include_sessions=False)
        rows = []
        for row in result.get('profiles', []):
            name = row.get('name')
            if name not in cfg['profiles']:
                continue
            meta = (row.get('ui_meta') or {}).get('hermes-bots') or {}
            if not isinstance(meta, dict):
                meta = {}
            rows.append({'profile': name, 'title': meta.get('title') or row.get('display_name') or name,
                         'description': row.get('description') or '', 'managed': bool(meta)})
        return owner, provider, profile, rows

    async def room(request, rid):
        owner, provider, profile, cfg = identity(request)
        if not isinstance(rid, str) or not rid or len(rid) > 128:
            raise HTTPException(400, "Choose a native shared space")
        result = await rpc(owner, provider, profile, 'groups.state', room_id=rid)
        value = result.get('room') or {}
        if not isinstance(value, dict) or value.get('room_id') != rid:
            raise HTTPException(503, 'Native shared-space identity was not confirmed; refresh')
        members = value.get('members') or []
        # Revoking profile access also revokes old shared-space access.
        if not authorized_members(members, cfg['profiles']):
            raise HTTPException(403, 'Shared space contains an unauthorized bot')
        return owner, provider, profile, rid, result

    @router.get('')
    async def snapshot(request: Request):
        owner, provider, profile, bots = await roster(request)
        _, _, _, cfg = identity(request)
        rooms = await rpc(owner, provider, profile, 'groups.list', limit=100, offset=0)
        def authorized(row):
            members = row.get('members') or []
            return authorized_members(members, cfg['profiles'])
        return {'bots': bots, 'spaces': [row for row in rooms.get('rooms', []) if authorized(row)],
                'has_more_spaces': rooms.get('next_offset') is not None,
                'capabilities': await capabilities(owner, provider, profile)}

    @router.get('/space/{rid}')
    async def read_space(rid: str, request: Request):
        owner, provider, profile, rid, state = await room(request, rid)
        since = max(0, int(state['room'].get('latest_seq', 0)) - 200)
        events, cursor = [], since
        while len(events) < 200:
            page = await rpc(owner, provider, profile, 'groups.log', room_id=rid, since_seq=cursor, limit=200-len(events))
            next_cursor, batch = page.get('cursor'), page.get('events')
            if not isinstance(next_cursor, int) or isinstance(next_cursor, bool) or not isinstance(batch, list) or len(batch) > 200-len(events) or next_cursor < cursor or (page.get('has_more') and (next_cursor == cursor or not batch)):
                raise HTTPException(503, 'Native shared-space replay returned an invalid cursor or page')
            events.extend(batch)
            cursor, log = next_cursor, page
            if not page.get('has_more'):
                break
        log['events'] = events
        log['earlier_events_omitted'] = since > 0
        return dict(state, **log, capabilities=await capabilities(owner, provider, profile))

    @router.post('/space')
    async def create_space(request: Request):
        owner, provider, profile, bots = await roster(request)
        value = await body(request, 4096)
        chosen = value.get('profiles')
        allowed = {row['profile'] for row in bots}
        if set(value) not in ({'profiles'}, {'profiles', 'request_id'}) or not isinstance(chosen, list) or not 2 <= len(chosen) <= 6 or any(not isinstance(p, str) or p not in allowed for p in chosen) or len(set(chosen)) != len(chosen):
            raise HTTPException(400, 'Choose two to six authorized bots')
        request_id = value.get('request_id')
        if 'request_id' in value and (not isinstance(request_id, str) or not 1 <= len(request_id) <= 100 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in request_id)):
            raise HTTPException(400, 'A stable creation identity is required')
        chosen = sorted(chosen)
        # Older clients have no request identity; keep their retry behavior and existing room.
        rid = space_id(owner, provider, profile, request_id or 'members:' + ','.join(chosen))
        offset = 0
        for _ in range(SPACE_SCAN_PAGES):
            rooms = await rpc(owner, provider, profile, 'groups.list', limit=SPACE_PAGE_SIZE, offset=offset)
            page = rooms.get('rooms') if isinstance(rooms,dict) else None
            if not isinstance(page,list) or len(page)>SPACE_PAGE_SIZE:
                raise HTTPException(503, 'Shared-space list could not be refreshed; try again')
            for existing in page:
                members = existing.get('members') or []
                if (existing.get('room_id') in ({rid} if request_id else {rid, space_id(owner, provider, profile)})
                        and authorized_members(members, allowed)
                        and sorted(m['profile'] for m in members) == chosen):
                    return {'room': existing}
            following = rooms.get('next_offset')
            if following is None:
                break
            if not isinstance(following, int) or isinstance(following, bool) or following <= offset:
                raise HTTPException(503, 'Shared-space list could not be refreshed; try again')
            offset = following
        else:
            raise HTTPException(409, f'Beacon checked {SPACE_SCAN_LIMIT:,} group rooms and stopped before creating a duplicate. Archive unused rooms in Hermes, then try again.')
        members = [{'member_id': p, 'profile': p, 'handle': 'hermes' if p == 'default' else p,
                    'target': {'kind': 'local', 'profile': p}} for p in chosen]
        return await rpc(owner, provider, profile, 'groups.create', room_id=rid, name='Shared space', members=members)

    @router.post('/space/{rid}/rename')
    async def rename_space(rid: str, request: Request):
        value = await body(request, 4096)
        name, event, expected = value.get('name'), value.get('event_id'), value.get('expected_name')
        if (set(value) != {'name', 'event_id', 'expected_name'}
                or not isinstance(name, str) or not name.strip() or len(name) > 120
                or any(ord(c) < 32 for c in name)
                or not isinstance(expected, str) or len(expected) > 256
                or not isinstance(event, str) or not 1 <= len(event) <= 100
                or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in event)):
            raise HTTPException(400, 'Enter a shared-space name of up to 120 characters')
        async with CHAT_LOCK:
            owner, provider, profile, rid, state = await room(request, rid)
            current = state['room'].get('name')
            if current not in (expected, name.strip()):
                raise HTTPException(409, 'The name changed elsewhere. Refresh before saving again.')
            if not (await capabilities(owner, provider, profile))['rename']:
                raise HTTPException(409, 'Native shared-space renaming is unavailable; refresh')
            result = await rpc(owner, provider, profile, 'groups.rename', room_id=rid, event_id=event, name=name.strip())
            if (result.get('room') or {}).get('name') != name.strip():
                raise HTTPException(503, 'The new name was not confirmed. Refresh before retrying.')
            return result

    @router.post('/space/{rid}/members')
    async def set_members(rid: str, request: Request):
        await room(request, rid)
        raise HTTPException(409, 'This Hermes version cannot change bots in an existing space. Create another space with the bots you need; this space and its history are kept.')

    @router.post('/space/{rid}/delete')
    async def delete_space(rid: str, request: Request):
        owner, provider, profile, cfg = identity(request)
        value = await body(request, 4096)
        event, expected = value.get('event_id'), value.get('expected_name')
        if (set(value) != {'event_id', 'expected_name'} or not isinstance(expected, str) or not expected or len(expected) > 256
                or not isinstance(event, str) or not 1 <= len(event) <= 100
                or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in event)):
            raise HTTPException(400, 'A confirmed shared-space name and stable deletion identity are required')
        if not isinstance(rid, str) or not rid or len(rid) > 128:
            raise HTTPException(400, 'Choose a native shared space')
        async with CHAT_LOCK:
            state = await rpc(owner, provider, profile, 'groups.state', room_id=rid, include_disbanded=True)
            current = state.get('room') or {}
            if not isinstance(current, dict) or current.get('room_id') != rid:
                raise HTTPException(503, 'Native shared-space identity was not confirmed; refresh')
            if not authorized_members(current.get('members'), cfg['profiles']):
                raise HTTPException(403, 'Shared space contains an unauthorized bot')
            if current.get('name') != expected:
                raise HTTPException(409, 'The name changed elsewhere. Refresh before deleting.')
            if not (await capabilities(owner, provider, profile))['delete']:
                raise HTTPException(409, 'Native shared-space deletion is unavailable. Refresh before trying again.')
            try:
                # The HTTP event identity is a Stop correlation, never a native deletion-event ID.
                result = await rpc(owner, provider, profile, 'groups.disband', room_id=rid, cancel_id=event)
            except HTTPException as exc:
                raise HTTPException(exc.status_code, 'Deletion is unconfirmed; Hermes may have started stopping the bots. Refresh this space before retrying.') from None
            receipt = result.get('tombstone')
            receipt = receipt if isinstance(receipt, dict) else {}
            stamp, native_event = receipt.get('disbanded_at'), receipt.get('event')
            if (receipt.get('room_id') != rid or type(stamp) not in (int, float) or not math.isfinite(stamp) or stamp <= 0
                    or type(receipt.get('idempotent')) is not bool or not isinstance(native_event, dict)
                    or native_event.get('room_id') != rid or native_event.get('kind') != 'room.disbanded'
                    or native_event.get('event_id') != 'system:room-disbanded'):
                raise HTTPException(503, 'Deletion was not confirmed. Refresh this space before retrying.')
            reread = await rpc(owner, provider, profile, 'groups.state', room_id=rid, include_disbanded=True)
            retired = reread.get('room') or {}
            if (not isinstance(retired, dict) or retired.get('room_id') != rid
                    or type(retired.get('disbanded_at')) not in (int, float) or retired.get('disbanded_at') != stamp):
                raise HTTPException(503, 'Deletion was not confirmed by native state. Refresh before retrying.')
            if not authorized_members(retired.get('members'), cfg['profiles']):
                raise HTTPException(403, 'Shared space contains an unauthorized bot')
            return result

    @router.post('/space/{rid}/messages')
    async def send_space(rid: str, request: Request):
        owner, provider, profile, rid, _ = await room(request, rid)
        value = await body(request, 65536)
        text, event = value.get('text'), value.get('event_id')
        if set(value) != {'text', 'event_id'} or not isinstance(text, str) or not text.strip() or len(text) > 16000 or not isinstance(event, str) or not 1 <= len(event) <= 100 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in event):
            raise HTTPException(400, 'A message and stable event identity are required')
        return await rpc(owner, provider, profile, 'groups.send', room_id=rid, event_id=event, payload={'text': text, 'thread_id': rid})

    @router.post('/space/{rid}/action')
    async def room_action(rid: str, request: Request):
        owner, provider, profile, rid, state = await room(request, rid)
        value = await body(request, 4096)
        pending = (state.get('driver_status') or {}).get('pending_actions') or []
        kind = value.get('kind')
        if kind in ('retry', 'discard'):
            raise HTTPException(409, 'This Hermes version cannot safely resolve work whose outcome may be unknown in Beacon. Review it in Hermes; do not replay it blindly.')
        fields = {'kind', 'task_id', 'member_id', 'request_id', 'execution_generation', 'choice'}
        if (set(value) != fields or kind != 'approval' or value.get('choice') not in ('once', 'deny')
                or any(not isinstance(value.get(k), str) or not value[k] for k in ('task_id', 'member_id', 'request_id'))
                or type(value.get('execution_generation')) is not int or value['execution_generation'] < 1):
            raise HTTPException(400, 'Choose an exact pending native action')
        coordinates = fields - {'choice'}
        if not any(isinstance(action, dict) and all(action.get(k) == value.get(k) for k in coordinates)
                   and type(action.get('execution_generation')) is int
                   and isinstance(action.get('approval'), dict) and isinstance(action['approval'].get('choices'), list)
                   and value['choice'] in action['approval']['choices'] for action in pending):
            raise HTTPException(409, 'This native action is no longer pending; refresh')
        if not (await capabilities(owner, provider, profile))['approval']:
            raise HTTPException(409, 'Native shared-space approval is unavailable; refresh')
        result = await rpc(owner, provider, profile, 'groups.approve', room_id=rid, task_id=value['task_id'],
                           member_id=value['member_id'], request_id=value['request_id'],
                           execution_generation=value['execution_generation'], choice=value['choice'])
        if result.get('approved') is not True or not isinstance(result.get('result'), dict):
            raise HTTPException(503, 'Approval was not confirmed. Refresh before trying again.')
        return result

    @router.post('/chat')
    async def open_chat(request: Request):
        value = await body(request, 1024)
        if set(value) != {'bot'} or not isinstance(value.get('bot'), str):
            raise HTTPException(400, 'Choose a bot')
        owner, provider, _, _ = identity(request)
        _, _, bot, _ = identity(request, value['bot'])
        # Native exact-title registry is the authority; no saved chat-pointer mirror.
        def canonical():
            def lookup():
                rows = native.rpc(owner, provider, 'session.list', {'profile': bot, 'title': CANONICAL_TITLE, 'include_hidden': True, 'limit': 200}).get('sessions', [])
                match = next((r for r in rows if (r.get('root_title') or r.get('title')) == CANONICAL_TITLE), None)
                if match:
                    stored = match.get('resolved_id') or match['id']
                    native.verify(owner, provider, bot, stored)
                    return stored
            roster = native.rpc(owner, provider, 'profiles.list', {'profile': bot, 'include_sessions': False}).get('profiles', [])
            row = next((r for r in roster if r.get('name') == bot), None)
            if row is None:
                raise LookupError('Bot no longer exists')
            if not isinstance((row.get('ui_meta') or {}).get('hermes-bots'), dict):
                applied = native.rpc(owner, provider, 'profiles.configure', {'profile': bot, 'name': bot,
                    'ui_meta': {'hermes-bots': {'title': row.get('display_name') or bot}},
                    'ui_meta_expected_revisions': {'hermes-bots': (row.get('ui_meta_revisions') or {}).get('hermes-bots', 0)}})
                if not (applied.get('applied') or {}).get('ui_meta'):
                    raise RuntimeError('Bot metadata changed; refresh before retrying')
            existing = lookup()
            if existing:
                return existing
            created = native.rpc(owner, provider, 'session.create', {'profile': bot, 'title': CANONICAL_TITLE, 'hidden': True, 'follow_profile_config': True, 'source': 'android', 'close_on_disconnect': False})
            try:
                native.rpc(owner, provider, 'session.title', {'profile': bot, 'session_id': created['session_id'], 'title': CANONICAL_TITLE})
            except LookupError:
                # A desktop/peer may have claimed the title during creation. Adopt only an owned winner.
                winner = lookup()
                if winner:
                    return winner
                raise
            native.verify(owner, provider, bot, created['stored_session_id'])
            return created['stored_session_id']
        try:
            async with CHAT_LOCK:
                stored = await asyncio.to_thread(canonical)
        except LookupError:
            raise HTTPException(409, 'Bot Chat is unavailable or belongs to another owner') from None
        except (RuntimeError, OSError):
            raise HTTPException(503, 'Native Bot Chat is unavailable') from None
        return {'profile': bot, 'stored_session_id': stored, 'title': CANONICAL_TITLE}

    return router
