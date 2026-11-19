"""Which of the phone's chats hold a reply Hermes lost in a restart, read without resuming or writing anything.

Hermes keeps a turn its previous run lost as an ``unknown`` admission until someone lets it go (``prompt.resolve_unknown``,
Desktop's Discard). Its passive feeds do not say so: ``session.active_list`` and ``session.list`` report such a chat as idle
(or starting), ``session.info`` builds an agent, and ``session.resume`` resumes one. The phone shows the lost reply on the chat
it opens and in its chat, project and bot lists before the person acts, so it asks here. Each lost reply comes with the
admission id and the generation Hermes stamped on it: what Discard (``prompt.resolve_unknown``) takes.

The read is Hermes' own state API: ``hermes_state_runtime.list_session_admissions`` on a ``SessionDB`` opened read-only (a
``mode=ro`` connection), and for a bot its Bot Chat by Hermes' own exact-title lookup (``resolve_session_by_title``). Hermes'
browse opener is not used, because it may add missing schema to an unowned store. Nothing is resumed, claimed or written.
"""
import asyncio
import re
from contextlib import closing
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request

# The most chats one read covers: the phone asks for the rows its list shows.
MAX_CHATS = 200
# The most bots one read covers (Hermes' own profile cap for one owner).
MAX_BOTS = 64
# Hermes' stored chat ids, as the app's own history reads accept them (NativeOperationPolicy.sessionPath).
CHAT_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}')


def _lost(db, chat):
    """The chat's lost replies and what Hermes holds queued behind them; None when it holds none."""
    from hermes_state_runtime import list_session_admissions
    pending = list_session_admissions(db, session_id=chat)
    unknown = [{'admission_id': row['admission_id'], 'execution_generation': row['generation']}
               for row in pending if row.get('status') == 'unknown']
    if not unknown:
        return None
    return {'waiting': sum(1 for row in pending if row.get('status') == 'queued'), 'unknown': unknown}


def lost_chats(state_db, chat_ids):
    """{chat id: {'waiting': n, 'unknown': [{admission_id, execution_generation}]}} for each chat holding a lost reply."""
    from hermes_state import SessionDB
    found = {}
    with closing(SessionDB(Path(state_db), read_only=True)) as db:
        for chat in chat_ids:
            lost = _lost(db, chat)
            if lost is not None:
                found[chat] = lost
    return found


def lost_bot_chat(state_db):
    """A bot's Bot Chat (its id, found by Hermes' exact-title lookup) when it holds a lost reply; None otherwise."""
    from hermes_state import SessionDB
    with closing(SessionDB(Path(state_db), read_only=True)) as db:
        chat = db.resolve_session_by_title(db.CANONICAL_BOT_CHAT_TITLE)
        lost = _lost(db, chat) if chat else None
        return {'session_id': chat, **lost} if lost is not None else None


def profile_state_db(profile):
    """The profile's own ``state.db``: its home, or the dashboard's launch home for the launch profile."""
    from tui_gateway import server
    return Path(server._profile_home(profile) or server._hermes_home) / 'state.db'


def _readable(path):
    # A profile whose gateway never ran has no store, so no chat of it holds anything.
    return path.is_file() and path.stat().st_size > 0


def build_router(identity, state_db=profile_state_db):
    router = APIRouter()

    @router.get('/mobile/lost-turns')
    async def lost_turns(request: Request, ids: str = '', bots: str = ''):
        _, _, profile, settings = await asyncio.to_thread(identity, request)
        chats = list(dict.fromkeys(chat for chat in ids.split(',') if chat))
        if len(chats) > MAX_CHATS or any(not CHAT_ID.fullmatch(chat) for chat in chats):
            raise HTTPException(400, 'Invalid chat list')
        wanted = list(dict.fromkeys(bot for bot in bots.split(',') if bot))
        if len(wanted) > MAX_BOTS:
            raise HTTPException(400, 'Invalid bot list')
        # A bot is one of the owner's own profiles: one this phone may not use is never probed and never answered, and does not
        # cost the others their answer.
        wanted = [bot for bot in wanted if bot in settings.get('profiles', [])]
        answer = {'lost': {}}
        if wanted:
            answer['bots'] = {}
        try:
            path = state_db(profile)
            if chats and _readable(path):
                answer['lost'] = await asyncio.to_thread(lost_chats, path, chats)
            for bot in wanted:
                bot_db = state_db(bot)
                if _readable(bot_db) and (lost := await asyncio.to_thread(lost_bot_chat, bot_db)) is not None:
                    answer['bots'][bot] = lost
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, 'Hermes state is unavailable') from None
        return answer

    return router
