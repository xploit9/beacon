from test_callbacks import load
from fastapi import FastAPI,HTTPException
from fastapi.testclient import TestClient
from types import SimpleNamespace

def test_route_binds_authority_and_allows_receipt_after_call_end():
    api=load('callback_api');seen=[]
    async def call(request,cid):
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

import time
import pytest
from test_mobile import harness
from test_job_watch import add_job, job_row


@pytest.mark.parametrize('boundary',['closed','expired','closed_after_admission'])
def test_new_job_callback_subscription_requires_live_admission(harness,monkeypatch,boundary):
    api,client,_,_,_=harness
    call_id,job_id=add_job(api,client,monkeypatch)
    if boundary=='closed_after_admission':
        original=api.call
        async def close_after_admission(*args,**kwargs):
            result=await original(*args,**kwargs)
            with api.mobile.LOCK,api.mobile.connect() as conn:
                conn.execute('UPDATE calls SET closed=1 WHERE id=?',(call_id,))
            return result
        monkeypatch.setattr(api,'call',close_after_admission)
    else:
        with api.mobile.LOCK,api.mobile.connect() as conn:
            if boundary=='closed': conn.execute('UPDATE calls SET closed=1 WHERE id=?',(call_id,))
            else: conn.execute('UPDATE calls SET created=? WHERE id=?',(time.time()-5*3600,call_id))
    result=client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/callback',json={'scope':'all'})
    assert result.status_code==409, result.text
    assert job_row(api,job_id)['callback']==0
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0]==0


def test_job_callback_accepted_during_call_remains_subscribed_after_hangup(harness,monkeypatch):
    api,client,_,_,_=harness
    call_id,job_id=add_job(api,client,monkeypatch)
    response=client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/callback',json={'job_id':job_id})
    assert response.status_code==200 and response.json()['job_ids']==[job_id]
    with api.mobile.LOCK,api.mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?',(call_id,))
        conn.execute("UPDATE jobs SET status='completed' WHERE id=?",(job_id,))
    api.watch('owner','basic','default')
    assert job_row(api,job_id)['status']=='callback_pending'
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE job_id=? AND kind='incoming_call'",(job_id,)).fetchone()[0]==1
