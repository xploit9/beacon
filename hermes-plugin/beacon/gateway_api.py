"""Authenticated call-bound direct Hermes and MCP endpoints."""
import asyncio
import importlib.util
from pathlib import Path
import sys
import threading
from fastapi import APIRouter, HTTPException, Request

key='beacon_gateway_tools'
if key not in sys.modules:
    spec=importlib.util.spec_from_file_location(key,Path(__file__).with_name('gateway_tools.py'))
    module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)


def build_router(call, body, scoped, mobile):
    router=APIRouter()
    gateway=sys.modules[key].Gateway(mobile,scoped)
    metadata_scope=getattr(getattr(gateway,'backend',None),'scoped_metadata',None)
    if callable(metadata_scope): gateway.scoped=metadata_scope
    admissions=threading.BoundedSemaphore(4)

    async def scope(request,call_id,writable=False):
        owner,provider,row,_=await call(request,call_id,writable)
        return (owner,provider,row['profile'],call_id),row['conversation']

    async def bounded(fn,*args):
        # Disconnects must not cancel a mutation receipt or replay its execution.
        if not admissions.acquire(blocking=False): raise HTTPException(429,'Tool request capacity reached')
        def run():
            try: return fn(*args)
            finally: admissions.release()
        task=asyncio.create_task(asyncio.to_thread(run))
        task.add_done_callback(lambda done: done.exception() if not done.cancelled() else None)
        try: return await asyncio.wait_for(asyncio.shield(task),15)
        except asyncio.TimeoutError:
            raise HTTPException(504,'Tool lookup still settling; inspect the same request identity') from None

    @router.get('/mobile/calls/{call_id}/tools')
    async def discover(call_id:str,request:Request,query:str=''):
        binding,_=await scope(request,call_id,True)
        return await bounded(gateway.discover,binding[2],query)

    @router.post('/mobile/calls/{call_id}/tools/invoke')
    async def invoke(call_id:str,request:Request):
        binding,session_id=await scope(request,call_id,True)
        value=await body(request,20000)
        return await bounded(gateway.invoke,binding,value,session_id)

    @router.get('/mobile/calls/{call_id}/tools/requests/{request_id}')
    async def status(call_id:str,request_id:str,request:Request):
        binding,_=await scope(request,call_id)
        return await bounded(gateway.status,binding,request_id)

    @router.post('/mobile/calls/{call_id}/tools/requests/{request_id}/decision')
    async def decision(call_id:str,request_id:str,request:Request):
        binding,_=await scope(request,call_id,True)
        value=await body(request,1024)
        return await bounded(gateway.decide,binding,request_id,value)

    @router.post('/mobile/calls/{call_id}/tools/requests/{request_id}/cancel')
    async def cancel(call_id:str,request_id:str,request:Request):
        binding,_=await scope(request,call_id)
        return await bounded(gateway.cancel,binding,request_id)

    return router
