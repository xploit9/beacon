"""Native lifecycle observers. Tool arguments are never mailbox evidence."""
import json
import math
import time
import uuid


def completion(mobile,native,cfg,profile,session_id,turn_id,completed=False,failed=False,interrupted=False,**kwargs):
    if (completed is not True and failed is not True) or interrupted or not session_id or not turn_id: return
    owner=cfg.get('owner_id');provider=cfg.get('owner_provider')
    if not owner or not provider or profile not in cfg.get('profiles',[]): return
    row=native.verify(owner,provider,profile,session_id)
    kind='failure' if failed is True else ('task_result' if row.get('source')=='cron' else 'completion')
    key='completion:'+owner+':'+profile+':'+session_id+':'+str(turn_id)
    with mobile.LOCK,mobile.connect() as conn:
        if conn.execute('SELECT 1 FROM meta WHERE key=?',(key,)).fetchone(): return
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise ValueError('Notification capacity reached')
        conn.execute('INSERT INTO meta VALUES(?,?)',(key,b'notified'))
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)',(uuid.uuid4().hex,owner,profile,kind,session_id,time.time(),time.time()+86400))


def mailbox_facts(value,contract):
    """Normalize source JSON only; never parse model prose into mailbox facts."""
    if contract.get('adapter')=='gmail_metadata':
        if isinstance(value,dict): value=value.get('result')
        if not isinstance(value,str) or not value.startswith('Gmail Metadata: '): return None
        first,_,content=value.partition('\n')
        try: message=json.loads(first[len('Gmail Metadata: '):])
        except ValueError: return None
        if not isinstance(message,dict): return None
        value={**message,'snippet':content}
        contract={**contract,'adapter':'gmail'}
    if not isinstance(value,dict) or value.get('isError') is True: return None
    if contract.get('adapter','native')=='native': return value
    if contract.get('adapter')!='gmail': return None
    # Gmail API message fields. Account binding is checked before the exact read invocation.
    rows=value.get('messages',[value])
    if not isinstance(rows,list): return None
    output=[]
    for row in rows[:100]:
        if not isinstance(row,dict): continue
        labels=row.get('labelIds');stamp=row.get('internalDate');mid=row.get('id')
        if not isinstance(labels,list) or any(not isinstance(x,str) for x in labels): continue
        if not isinstance(stamp,str) or not stamp.isascii() or not stamp.isdigit(): continue
        output.append({'account':contract['account'],'message':{'id':mid,'received_at':int(stamp)/1000,'unread':'UNREAD' in labels,'snippet':row.get('snippet','')}})
    return output


def mailbox_observed(mobile,cfg,profile,session_id,tool_name,result,status,read_only_verified):
    contract=cfg.get('mailbox_evidence')
    if not isinstance(contract,dict) or read_only_verified is not True or status!='ok' or tool_name!=contract.get('tool'): return
    if profile not in cfg.get('profiles',[]) or not session_id: return
    if isinstance(result,str):
        try: result=json.loads(result)
        except (TypeError,ValueError): return
    if not isinstance(result,dict): return
    # The source contract must return its account and immutable message metadata, not model claims.
    account=result.get('account');message=result.get('message')
    if account!=contract.get('account') or not isinstance(message,dict): return
    mid=message.get('id');received=message.get('received_at')
    if not isinstance(mid,str) or not 1<=len(mid)<=256 or type(received) not in (int,float) or not math.isfinite(received): return
    if not 0<=time.time()-received<=86400 or message.get('unread') is not True: return
    owner=cfg.get('owner_id')
    if not owner: return
    evidence={'account':account,'message_id':mid,'received_at':received,'unread':True,'action_required':message.get('action_required'),'urgent':message.get('urgent'),'important':message.get('important'),'content_observed':any(isinstance(message.get(k),str) and bool(message[k].strip()) for k in ('subject','snippet','body','text')),'observed_at':time.time()}
    key='mail-evidence:'+owner+':'+profile+':'+session_id+':'+mid
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta VALUES(?,?)',(key,json.dumps(evidence).encode()))


def email_notice(mobile,native,cfg,profile,session_id,args,now):
    contract=cfg.get('mailbox_evidence')
    if not isinstance(contract,dict): raise ValueError('No verified read-only mailbox evidence contract configured')
    owner=cfg.get('owner_id');provider=cfg.get('owner_provider')
    classification=args.get('classification','source')
    if classification not in ('source','model'): raise ValueError('Invalid importance classification')
    mid=args.get('message_id');account=args.get('account')
    if not isinstance(mid,str) or not 1<=len(mid)<=256 or account!=contract.get('account'): raise ValueError('Mailbox identity does not match authorized account')
    if getattr(now.tzinfo,'key',None)!='Asia/Dubai' or not 10<=now.hour<22: raise ValueError('Quiet hours: no urgent-mail night override authorized')
    if args.get('unread') is not True or args.get('action_required') is not True: raise ValueError('Unread actionable evidence is required')
    urgent=args.get('urgent') is True
    important=args.get('important') is True
    if not urgent and not important: raise ValueError('Message must be urgent or important')
    # Legacy model-written reasons never become call context or grant urgency.
    reason=args.get('reason','');summary=args.get('summary','')
    if not isinstance(reason,str) or not isinstance(summary,str) or len(reason)>600 or len(summary)>8000: raise ValueError('Invalid legacy notice text')
    suspect=('ignore previous','ignore all','system prompt','override policy','send email','mark as read','tool_call')
    if any(term in (reason+' '+summary).lower() for term in suspect): raise ValueError('Instruction-like mailbox text cannot authorize a callback')
    stored=cfg.get('scheduled_callback_conversation_id')
    if cfg.get('scheduled_callbacks_enabled') is not True or not stored: raise ValueError('Configure the owned native callback conversation')
    native.verify(owner,provider,profile,stored)
    key='mail-evidence:'+owner+':'+profile+':'+session_id+':'+mid
    dedupe='mail-notified:'+owner+':'+profile+':'+account+':'+mid
    with mobile.LOCK,mobile.connect() as conn:
        row=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
        if not row: raise ValueError('Read the actual message using the verified read-only native mailbox tool first')
        proof=json.loads(row[0])
        if time.time()-proof['observed_at']>300 or not 0<=time.time()-proof['received_at']<=86400 or args.get('received_at')!=proof['received_at']: raise ValueError('Mailbox evidence is stale or does not match the message')
        if not urgent and time.time()-proof['received_at']<900: raise ValueError('Nonurgent important mail must be missed for at least15 minutes')
        if classification=='source' and (proof.get('action_required') is not True or (urgent and proof.get('urgent') is not True) or (not urgent and proof.get('important') is not True)): raise ValueError('Source importance flags unavailable; explicitly classify the untrusted message instead')
        if classification=='model' and proof.get('content_observed') is not True: raise ValueError('Read actual message content before an importance assessment')
        if classification=='model' and not reason.strip(): raise ValueError('Model importance assessment needs a reason')
        if conn.execute('SELECT 1 FROM meta WHERE key=?',(dedupe,)).fetchone(): return {'status':'duplicate'}
        if conn.execute('SELECT 1 FROM calls WHERE owner=? AND closed=0 AND created>?',(owner,time.time()-4*3600)).fetchone(): return {'status':'skipped_busy'}
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise ValueError('Notification capacity reached')
        event=uuid.uuid4().hex
        conn.execute('INSERT INTO meta VALUES(?,?)',(dedupe,b'notified'))
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)',(event,owner,profile,'incoming_call',stored,time.time(),time.time()+120,'Verified unread actionable email needs your attention'))
    return {'status':'pending','event_id':event,'conversation_id':stored}
