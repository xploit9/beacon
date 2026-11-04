"""Owner-scoped routes for the email draft card: read it, ask for a change, step between its versions, send it."""
import asyncio

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

BODY_LIMIT = 4096


def _version(value):
    if type(value) is not int or not 1 <= value <= 10 ** 9: raise HTTPException(400, 'A draft version is required')
    return value


def build_router(identity, read_body, drafts):
    router = APIRouter()
    base = '/mobile/email-drafts/{draft_id}'

    async def run(fn, *args):
        try: return await asyncio.to_thread(fn, *args)
        except drafts.DraftError as error: raise HTTPException(error.status, error.message) from None

    def answer(value):
        return JSONResponse(value, headers={'Cache-Control': 'no-store'})

    @router.get(base)
    async def read(draft_id: str, request: Request):
        owner, _, profile, _ = await asyncio.to_thread(identity, request)
        return answer(await run(drafts.read, owner, profile, draft_id))

    @router.get(base + '/history')
    async def history(draft_id: str, request: Request):
        owner, _, profile, _ = await asyncio.to_thread(identity, request)
        return answer(await run(drafts.history, owner, profile, draft_id))

    @router.post(base + '/revise')
    async def revise(draft_id: str, request: Request):
        owner, _, profile, _ = await asyncio.to_thread(identity, request)
        value = await read_body(request, BODY_LIMIT)
        if set(value) != {'instruction', 'version'} or not isinstance(value['instruction'], str): raise HTTPException(400, 'An instruction and the draft version are required')
        return answer(await run(drafts.revise, owner, profile, draft_id, value['instruction'], _version(value['version'])))

    @router.post(base + '/select')
    async def select(draft_id: str, request: Request):
        owner, _, profile, _ = await asyncio.to_thread(identity, request)
        value = await read_body(request, BODY_LIMIT)
        # Either a version to show, or the version on the card and a step of one place back or forward.
        if set(value) not in ({'version'}, {'version', 'step'}) or value.get('step', 1) not in (-1, 1) or type(value.get('step', 1)) is not int:
            raise HTTPException(400, 'A draft version, and optionally a step of -1 or 1, is required')
        return answer(await run(drafts.select, owner, profile, draft_id, _version(value['version']), value.get('step', 0)))

    @router.post(base + '/send')
    async def send(draft_id: str, request: Request):
        owner, provider, profile, _ = await asyncio.to_thread(identity, request)
        value = await read_body(request, BODY_LIMIT)
        if set(value) != {'version'}: raise HTTPException(400, 'A draft version is required')
        return answer(await run(drafts.send, owner, provider, profile, draft_id, _version(value['version'])))

    return router
