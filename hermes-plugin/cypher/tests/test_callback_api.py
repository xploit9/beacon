from test_callbacks import load
from fastapi import FastAPI,HTTPException
from fastapi.testclient import TestClient
from types import SimpleNamespace

def test_route_binds_authority_and_allows_receipt_after_call_end():
    api=load('callback_api');seen=[]
    def call(request,cid):
        if cid!='owned':raise HTTPException(404)
        return 'owner','basic',{'profile':'default','conversation':'original','closed':1},{'configured':True}
    async def body(request,limit):return await request.json()
    async def feature(fn,*args,**kw):return fn(*args)
    def schedule(*args):seen.append(args);return {'status':'scheduled'}
    app=FastAPI();app.include_router(api.build_router(call,body,feature,'mobile','native',SimpleNamespace(schedule=schedule)))
    c=TestClient(app)
    assert c.post('/mobile/calls/foreign/schedule-callback',json={}).status_code==404
    assert c.post('/mobile/calls/owned/schedule-callback',json={'request_id':'r'}).json()['status']=='scheduled'
    assert seen[0][2:4]==('owner','basic') and seen[0][4]['conversation']=='original'
