"""Authenticated call-bound native callback route."""
from fastapi import APIRouter,HTTPException,Request

def build_router(call,read_body,feature,mobile,native,callbacks):
    router=APIRouter()
    @router.post('/mobile/calls/{call_id}/schedule-callback')
    async def schedule_callback(call_id:str,request:Request):
        value=await read_body(request,2048)
        owner,provider,row,cfg=await call(request,call_id)
        try: return await feature(callbacks.schedule,mobile,native,owner,provider,dict(row),cfg,value,timeout=8)
        except HTTPException as exc:
            if exc.status_code==504: raise HTTPException(504,'Callback schedule status is unknown. Read Tasks or retry the same request_id; never submit a new request.') from None
            raise
    return router
