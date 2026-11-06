"""Authenticated call-bound direct Hermes and MCP endpoints."""
import asyncio
import contextvars
import importlib.util
import logging
from pathlib import Path
import sys
import threading
from fastapi import APIRouter, Depends, HTTPException, Request, Response

logger=logging.getLogger(__name__)

key='beacon_gateway_tools'
if key not in sys.modules:
    spec=importlib.util.spec_from_file_location(key,Path(__file__).with_name('gateway_tools.py'))
    module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)


async def _never_updating():
    return None


async def _no_store(response:Response):
    response.headers['Cache-Control']='no-store'


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

    async def bounded(fn,*args,receipt=False):
        # Disconnects must not cancel a mutation receipt or replay its execution.
        permits=receipt_admissions if receipt else admissions
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
        try: return await asyncio.wait_for(asyncio.shield(task),15)
        except asyncio.TimeoutError:
            logger.warning('Beacon call %s timed out; worker_stack=%s',fn.__name__,sys.modules[key].worker_stack(worker))
            raise HTTPException(504,'Tool lookup still settling; inspect the same request identity') from None

    @router.get('/mobile/calls/{call_id}/tools')
    async def discover(call_id:str,request:Request,query:str=''):
        binding,_=await scope(request,call_id,True)
        await refuse_while_updating()
        return await bounded(gateway.discover,binding[2],query)

    @router.post('/mobile/calls/{call_id}/tools/invoke')
    async def invoke(call_id:str,request:Request):
        binding,session_id=await scope(request,call_id,True)
        value=await body(request,20000)
        await refuse_while_updating()
        principal=sys.modules[key].request_principal(request,binding[0],binding[1])
        return await bounded(gateway.invoke,binding,value,session_id,principal)

    @router.get('/mobile/calls/{call_id}/tools/requests/{request_id}')
    async def status(call_id:str,request_id:str,request:Request):
        binding,_=await scope(request,call_id)
        return await bounded(gateway.status,binding,request_id,receipt=True)

    @router.post('/mobile/calls/{call_id}/tools/requests/{request_id}/decision')
    async def decision(call_id:str,request_id:str,request:Request):
        binding,_=await scope(request,call_id,True)
        value=await body(request,1024)
        return await bounded(gateway.decide,binding,request_id,value)

    @router.post('/mobile/calls/{call_id}/tools/requests/{request_id}/cancel')
    async def cancel(call_id:str,request_id:str,request:Request):
        binding,_=await scope(request,call_id)
        return await bounded(gateway.cancel,binding,request_id,receipt=True)

    return router
