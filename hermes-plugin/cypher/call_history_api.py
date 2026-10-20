"""Owner-scoped call history from Cypher's existing call and invitation records."""
import asyncio
import base64
import json
import math
import time

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse


def _read_cursor(value, mobile):
    if value is None:
        return None
    try:
        if not value or len(value) > 256:
            raise ValueError()
        raw = base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)
        created, call_id = json.loads(raw)
        if (type(created) not in (int, float) or not math.isfinite(created) or created < 0
                or not isinstance(call_id, str) or not mobile.ID.fullmatch(call_id)):
            raise ValueError()
        return float(created), call_id
    except (ValueError, TypeError, json.JSONDecodeError):
        raise HTTPException(400, 'Invalid call history cursor') from None


def _write_cursor(created, call_id):
    raw = json.dumps([created, call_id], separators=(',', ':'), allow_nan=False).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip('=')


def _stored_object(raw):
    try:
        value = json.loads(raw or '{}')
        return value if isinstance(value, dict) else {}
    except (TypeError, ValueError):
        return {}


def _page(mobile, owner, profile, limit, before):
    mobile.prune_history(owner)
    now = time.time()
    days = mobile.history_retention_days(owner)
    cutoff = now - days * 86400 if days else None

    def query(table, columns, extra, args):
        where = 'owner=? AND profile=?' + extra
        values = [owner, profile, *args]
        if cutoff is not None:
            where += ' AND created>=?'
            values.append(cutoff)
        if before is not None:
            where += ' AND (created<? OR (created=? AND id<?))'
            values.extend((before[0], before[0], before[1]))
        return conn.execute(
            f'SELECT {columns} FROM {table} WHERE {where} ORDER BY created DESC,id DESC LIMIT ?',
            (*values, limit + 1),
        ).fetchall()

    with mobile.LOCK, mobile.connect() as conn:
        mobile.prune_notices(conn)
        calls = query(
            'calls c',
            "c.id,c.conversation,c.created,c.ended,c.closed,c.status,c.voice_config,"
            "COALESCE(NULLIF(c.callback_context,'{}'),(SELECT json_object('id',n.id,'reason',n.reason) "
            "FROM notices n WHERE n.call_id=c.id AND n.owner=c.owner AND n.profile=c.profile "
            "AND n.state='answered' ORDER BY n.created DESC LIMIT 1),'{}') AS callback_context,"
            "(SELECT CASE WHEN l.feedback_verdict IS NULL THEN NULLIF(l.email_notice_id,'') END "
            "FROM call_log l WHERE l.call_id=c.id AND l.owner=c.owner AND l.profile=c.profile LIMIT 1) AS email_notice_id,"
            "EXISTS(SELECT 1 FROM timeline t WHERE t.call_id=c.id "
            "AND json_extract(t.payload,'$.type')='transcript') AS has_transcript",
            '', (),
        )
        call_logs = query(
            'call_log l', "l.id,l.call_id,l.conversation,l.created,l.expires,l.state,l.reason,"
            "CASE WHEN l.feedback_verdict IS NULL THEN NULLIF(l.email_notice_id,'') END AS email_notice_id",
            " AND (l.state!='answered' OR NOT EXISTS (SELECT 1 FROM calls c "
            "WHERE c.id=l.call_id AND c.owner=l.owner AND c.profile=l.profile))", (),
        )

    rows = []
    for row in calls:
        try:
            created = float(row['created'])
            if not math.isfinite(created) or created < 0:
                continue
        except (TypeError, ValueError):
            continue
        callback = _stored_object(row['callback_context'])
        voice = _stored_object(row['voice_config'])
        try:
            ended = float(row['ended']) if row['ended'] is not None else None
            duration = max(0, int(ended - created)) if ended is not None and math.isfinite(ended) else None
        except (TypeError, ValueError, OverflowError):
            duration = None
        rows.append({
            '_created': created,
            'id': row['id'],
            'direction': 'incoming' if callback.get('id') else 'outgoing',
            'state': 'active' if not row['closed'] else 'failed' if row['status'] == 'failed' else 'completed',
            'started_at': int(created),
            'duration_s': duration,
            'provider': voice.get('provider') if isinstance(voice.get('provider'), str) else None,
            'conversation_id': row['conversation'],
            'reason': (callback['reason'][:120] or None) if isinstance(callback.get('reason'), str) else None,
            'has_transcript': bool(row['has_transcript']),
            **({'email_notice_id': row['email_notice_id']} if row['email_notice_id'] else {}),
        })
    for row in call_logs:
        try:
            created = float(row['created'])
            expires = float(row['expires'])
            if not math.isfinite(created) or created < 0 or not math.isfinite(expires):
                continue
        except (TypeError, ValueError):
            continue
        state = 'missed' if row['state'] == 'pending' and expires <= now else 'active' if row['state'] == 'pending' else row['state']
        if state not in {'active', 'missed', 'declined', 'answered'}:
            continue
        rows.append({
            '_created': created,
            'id': row['call_id'] or row['id'],
            'direction': 'incoming',
            'state': 'completed' if state == 'answered' else state,
            'started_at': int(created),
            'duration_s': None,
            'provider': None,
            'conversation_id': row['conversation'],
            'reason': (row['reason'][:120] or None) if isinstance(row['reason'], str) else None,
            'has_transcript': False,
            **({'email_notice_id': row['email_notice_id']} if row['email_notice_id'] else {}),
        })

    rows.sort(key=lambda item: (item['_created'], item['id']), reverse=True)
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = _write_cursor(rows[-1]['_created'], rows[-1]['id']) if has_more and rows else None
    for row in rows:
        row.pop('_created')
    return {'calls': rows, 'next': next_cursor}


def build_router(identity, mobile):
    router = APIRouter()

    @router.get('/mobile/calls')
    async def call_history(request: Request):
        owner, _, profile, _ = identity(request)
        raw_limit = request.query_params.get('limit', '50')
        # ASCII digits only: str.isdigit() also accepts '²', which int() rejects.
        if not (raw_limit.isascii() and raw_limit.isdigit()) or not 1 <= int(raw_limit) <= 50:
            raise HTTPException(400, 'Invalid call history limit')
        before = _read_cursor(request.query_params.get('before'), mobile)
        result = await asyncio.to_thread(_page, mobile, owner, profile, int(raw_limit), before)
        return JSONResponse(result, headers={'Cache-Control': 'no-store'})

    return router
