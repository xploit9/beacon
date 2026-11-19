"""Authenticated call-bound direct Hermes and MCP endpoints."""
import asyncio
import contextlib
import contextvars
import importlib.util
import logging
from pathlib import Path
import sys
import threading
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import JSONResponse

logger=logging.getLogger(__name__)

# A lookup that is still running after its limit is answered with a 504 and left to finish. Each limit is clearly below what the phone
# waits for that route (DirectVoice.kt), so the plugin always speaks first and its timeout is logged:
#   tool search 10 s (phone 15 s; the search itself answers within gateway_tools.DISCOVER_LIST_WAIT),
#   invoke and approval decision 15 s (phone 20 s),
#   receipt reads, status and cancel, 5 s (phone 8 s; a receipt is a local read, so a slow one is a stuck one).
# The call's search, memory and skills reads are dashboard routes (plugin_api.feature) with their own 12 s and 8 s limits against the phone's 15 s.
LOOKUP_LIMIT=15
DISCOVER_LIMIT=10
RECEIPT_LIMIT=5
# Call contract v3: an invoke or decision may wait this long for its result (CALLS-CONTRACT.md section 2). The wait holds no
# execution permit and no route permit; it reads the receipt again only after a receipt was written (or every half second).
MAX_WAIT_MS=8000
WAIT_STEP=0.02
WAIT_REREAD=0.5

key='beacon_gateway_tools'
if key not in sys.modules:
    spec=importlib.util.spec_from_file_location(key,Path(__file__).with_name('gateway_tools.py'))
    module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)


async def _never_updating():
    return None


async def _no_store(response:Response):
    response.headers['Cache-Control']='no-store'


def v3_request(request):
    return request.headers.get('x-beacon-call-contract','').strip()=='3'


def wait_budget(value):
    """wait_ms from a v3 body, removed from it; absent is 0, the v2 answer-at-once."""
    wait=value.pop('wait_ms',0)
    if type(wait) is not int or not 0<=wait<=MAX_WAIT_MS: raise HTTPException(400,'wait_ms must be an integer from 0 to 8000')
    return wait


def error_code(error):
    """The contract's request-level code for a refused call route (CALLS-CONTRACT.md section 2)."""
    detail=str(error.detail).casefold()
    if error.status_code in (400,413,415) or 'approval each time' in detail: return 'invalid_request'
    if error.status_code in (401,403): return 'not_owned'
    if error.status_code==404: return 'request_unknown' if 'tool request' in detail else 'call_unknown'
    if error.status_code==409: return 'call_closed' if 'closed' in detail or 'expired' in detail else 'request_conflict'
    if error.status_code==429: return 'capacity'
    if error.status_code==503: return 'hermes_updating' if 'updat' in detail else 'unavailable'
    return 'timeout' if error.status_code==504 else 'unavailable'


def refusal(error):
    return JSONResponse({'detail':error.detail,'error_code':error_code(error)},status_code=error.status_code,
                        headers={**(error.headers or {}),'Cache-Control':'no-store'})


async def settle(read, receipt, wait_ms, request=None):
    """Waits up to wait_ms for a running receipt to reach any other state, without a thread or a permit."""
    if wait_ms<=0 or receipt.get('status')!='running': return receipt
    changes=sys.modules[key].RECEIPT_CHANGES
    loop=asyncio.get_running_loop();deadline=loop.time()+wait_ms/1000
    seen=changes[0];read_at=loop.time()
    while receipt.get('status')=='running':
        remaining=deadline-loop.time()
        if remaining<=0: break
        await asyncio.sleep(min(WAIT_STEP,remaining))
        if changes[0]==seen and loop.time()-read_at<WAIT_REREAD: continue
        if request is not None and await request.is_disconnected(): break
        seen=changes[0];read_at=loop.time()
        receipt=await asyncio.to_thread(read)
    return receipt


def build_router(call, body, scoped, mobile, refuse_while_updating=_never_updating):
    router=APIRouter(dependencies=[Depends(_no_store)])
    gateway=sys.modules[key].Gateway(mobile,scoped)
    metadata_scope=getattr(getattr(gateway,'backend',None),'scoped_metadata',None)
    if callable(metadata_scope): gateway.scoped=metadata_scope
    admissions=threading.BoundedSemaphore(4)
    # Receipt controls must remain available while expensive workers are stalled.
    receipt_admissions=threading.BoundedSemaphore(4)

    async def scope(request,call_id,writable=False):
        owner,provider,row,_=await call(request,call_id,writable)
        return (owner,provider,row['profile'],call_id),row['conversation']

    async def bounded(fn,*args,receipt=False,limit=None):
        # Disconnects must not cancel a mutation receipt or replay its execution.
        permits=receipt_admissions if receipt else admissions
        limit=limit or (RECEIPT_LIMIT if receipt else LOOKUP_LIMIT)
        if not permits.acquire(blocking=False): raise HTTPException(429,'Tool request capacity reached')
        worker=None
        def run():
            nonlocal worker
            worker=threading.current_thread()
            try: return fn(*args)
            finally: permits.release()
        context=contextvars.copy_context()
        try: task=asyncio.get_running_loop().run_in_executor(None,context.run,run)
        except BaseException:
            permits.release()
            raise
        task.add_done_callback(lambda done: done.exception() if not done.cancelled() else None)
        try: return await asyncio.wait_for(asyncio.shield(task),limit)
        except asyncio.TimeoutError:
            logger.warning('Beacon call %s timed out; worker_stack=%s',fn.__name__,sys.modules[key].worker_stack(worker))
            raise HTTPException(504,'Tool lookup still settling; inspect the same request identity') from None

    @router.get('/mobile/calls/{call_id}/tools')
    async def discover(call_id:str,request:Request,query:str=''):
        make=getattr(sys.modules[key],'StageTimer',None)
        timer=make() if make else None
        stage=timer.stage if timer else (lambda name: contextlib.nullcontext())
        try:
            with stage('authorise'):
                binding,_=await scope(request,call_id,True)
                await refuse_while_updating()
            return await bounded(gateway.discover,binding[2],query,timer,limit=DISCOVER_LIMIT)
        finally:
            if timer:
                # Stage names and seconds only. A slow request, or one whose lookup is still running, is a warning.
                slow=timer.elapsed()>=2.0 or bool(timer.running)
                (logger.warning if slow else logger.info)('Beacon call discover stages: %s',timer.summary())

    @router.post('/mobile/calls/{call_id}/tools/invoke')
    async def invoke(call_id:str,request:Request):
        v3=v3_request(request)
        try:
            binding,session_id=await scope(request,call_id,True)
            value=await body(request,20000)
            v3=v3 or 'tool' in value
            wait=wait_budget(value)
            await refuse_while_updating()
            principal=sys.modules[key].request_principal(request,binding[0],binding[1])
            receipt=await bounded(gateway.invoke,binding,value,session_id,principal)
            if not v3: return receipt
            if 'tool_id' in value: receipt=await bounded(gateway.status_v3,binding,receipt['request_id'],receipt=True)
            return await settle(lambda:gateway.status_v3(binding,receipt['request_id']),receipt,wait,request)
        except HTTPException as error:
            if v3: return refusal(error)
            raise

    @router.get('/mobile/calls/{call_id}/tools/requests/{request_id}')
    async def status(call_id:str,request_id:str,request:Request):
        v3=v3_request(request)
        try:
            binding,_=await scope(request,call_id)
            return await bounded(gateway.status_v3 if v3 else gateway.status,binding,request_id,receipt=True)
        except HTTPException as error:
            if v3: return refusal(error)
            raise

    @router.post('/mobile/calls/{call_id}/tools/requests/{request_id}/decision')
    async def decision(call_id:str,request_id:str,request:Request):
        v3=v3_request(request)
        try:
            binding,_=await scope(request,call_id,True)
            value=await body(request,1024)
            v3=v3 or 'wait_ms' in value
            wait=wait_budget(value)
            # Slots full: the card stays needs_approval (nothing started), so 429 capacity is the true answer and it may be approved again.
            receipt=await bounded(gateway.decide,binding,request_id,value)
            if not v3: return receipt
            receipt=await bounded(gateway.status_v3,binding,request_id,receipt=True)
            return await settle(lambda:gateway.status_v3(binding,request_id),receipt,wait,request)
        except HTTPException as error:
            if v3: return refusal(error)
            raise

    @router.post('/mobile/calls/{call_id}/tools/requests/{request_id}/cancel')
    async def cancel(call_id:str,request_id:str,request:Request):
        binding,_=await scope(request,call_id)
        return await bounded(gateway.cancel,binding,request_id,receipt=True)

    return router
