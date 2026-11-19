"""Approvals for the agent's own phone (EMU-DESIGN §5.3, §5.4; contract docs/agent-phone/approvals-v1.md, owned by lane L3).

A record is a decide-only question for the owner: labels only in mobile.sqlite (app, button, amount, purpose, site, identifier),
never a secret or an image. It is shown to every linked device of the owner whatever Phone access says, and nothing runs on the
owner's phone: the yes lets the plugin act on the agent phone. The tool waits at most 120 s, as phone_tools does.

Also here, because the tool and the vault bridge share them in one process: the declines that stand 60 s for a task, the bound
purchase grants of a vault card (one combined yes covers that card fill and one order input at that amount, 5 minutes), the
policy ("Let {name} use its phone", "Ask before") and the activity list ("What {name} did on its phone").
"""
import json
import logging
import os
import re
import sys
import threading
import time
import uuid
from decimal import Decimal, InvalidOperation

log = logging.getLogger('beacon.agent_phone.approvals')

KINDS = ('agent_phone_action', 'vault_new_login', 'vault_save', 'vault_update', 'vault_code', 'needs_you')
RISKS = ('payment', 'unlabeled', 'send', 'delete', 'install', 'account', 'other')
ACTIONS = ('tap', 'long_press', 'swipe', 'type', 'key', 'fill')
LIVES = {'agent_phone_action': 120, 'vault_new_login': 120, 'vault_code': 120, 'vault_save': 600, 'vault_update': 600,
         'needs_you': 86400}
OPEN_LIMIT = 10
NEEDS_YOU_LIMIT = 5  # notices with no decision have their own cap: they never crowd out a payment question
PER_MINUTE = 30
WAIT_SECONDS = 120
POLL_SECONDS = 0.5
KEEP_SECONDS = 3600
DECLINE_SECONDS = 60
GRANT_SECONDS = 300
GRANT_STEPS = 1  # covered step taps (Continue, Confirm...) per grant: one, so Back to a step screen cannot cover another (R4-2)
ACTIVITY_KEEP = 500
ACTIVITY_DAYS = 30
NOTICE_KIND = 'agent_phone_approval'
DONE_KIND = 'agent_phone_done'  # §5.5: the line after an approved payment or send; no content in the push
DONE_REASONS = ('payment', 'send')
DONE_SECONDS = 3600
ASK_LEVELS = ('payments_and_sends', 'every_action')
ID = re.compile(r'[0-9a-f]{32}')
CODE = re.compile(r'[A-Za-z0-9]{4,10}')
# Labels only: each field the app may show, and its longest length. Nothing else is stored or sent.
FIELDS = {'risk': 20, 'action': 20, 'app': 80, 'label': 120, 'amount': 40, 'purpose': 120, 'identifier': 120, 'pin': 160,
          'line': 200, 'note': 200, 'reason': 40, 'site': 120}  # reason: the line's code (contract channel-v1 revision 5 §11.1)
FLAGS = ('pay',)  # true/false, never text: the app words its question by it (the tapped control itself says it pays)
BIND_FIELDS = ('package', 'window', 'label', 'bounds', 'amount', 'epoch')

_now = time.time
_mono = time.monotonic
_lock = threading.Lock()


def _mobile():
    return sys.modules['beacon_mobile']


def _clean(value, limit):
    if value is None:
        return None
    text = ' '.join(''.join(c if c >= ' ' and c != '\x7f' else ' ' for c in str(value)).split())
    try:
        text.encode('utf-8')
    except UnicodeEncodeError:
        text = text.encode('utf-8', 'replace').decode('utf-8')
    return text[:limit] or None


def clean_payload(kind, payload):
    """The record's payload as stored: whitelisted label fields, plain one-line text, cut to length. ValueError on a bad kind."""
    if kind not in KINDS or not isinstance(payload, dict):
        raise ValueError('invalid')
    out = {}
    for name, limit in FIELDS.items():
        value = _clean(payload.get(name), limit)
        if value is not None:
            out[name] = value
    if out.get('risk') not in (None, *RISKS) or out.get('action') not in (None, *ACTIONS):
        raise ValueError('invalid')
    if kind == 'agent_phone_action':
        if 'risk' not in out:
            raise ValueError('invalid')
        for name in FLAGS:
            out[name] = payload.get(name) is True  # absent or anything but a real true means false
    return out


# -- the process that acts on an answer --------------------------------------------------------------------------------

_HOLDER = {}


def holder_tag():
    """'<pid>:<start time>' of this process: a vault_save answered after a restart finds no live holder and is withdrawn."""
    if 'tag' not in _HOLDER:
        try:
            import psutil
            _HOLDER['tag'] = '%d:%.3f' % (os.getpid(), psutil.Process().create_time())
        except Exception:  # health: allow BLE001 -- without psutil the pid alone still names the process
            _HOLDER['tag'] = '%d:0' % os.getpid()
    return _HOLDER['tag']


def _holder_alive(tag):
    if not tag:
        return True
    if tag == holder_tag():
        return True
    try:
        pid, start = tag.split(':', 1)
        import psutil
        process = psutil.Process(int(pid))
        return float(start) == 0 or abs(process.create_time() - float(start)) < 0.01
    except Exception:  # health: allow BLE001 -- a holder that cannot be found is gone
        return False


# -- records -------------------------------------------------------------------------------------------------------------

def _expire(conn, now):
    """Ends what nobody can answer any more and drops what an ended record held."""
    conn.execute("UPDATE agent_phone_approvals SET status='expired',answer='' WHERE status='pending' AND expires<=?", (now,))
    for row in conn.execute("SELECT id,holder FROM agent_phone_approvals WHERE status='pending' AND kind IN ('vault_save','vault_update')").fetchall():
        if not _holder_alive(row['holder']):
            conn.execute("UPDATE agent_phone_approvals SET status='withdrawn' WHERE id=?", (row['id'],))
            conn.execute("UPDATE notices SET state='expired' WHERE id=? AND state='pending'", (row['id'],))
    conn.execute("UPDATE agent_phone_approvals SET answer='' WHERE answer!='' AND (status!='approved' OR decided<?)", (now - WAIT_SECONDS,))
    conn.execute("DELETE FROM agent_phone_approvals WHERE (kind!='needs_you' AND created<?) OR created<?",
                 (now - KEEP_SECONDS, now - LIVES['needs_you'] - KEEP_SECONDS))
    conn.execute('DELETE FROM agent_phone_activity WHERE at<?', (now - ACTIVITY_DAYS * 86400,))


def sweep():
    """The dashboard watcher's pass (beside mobile.phone_requests_sweep), so records end even when nobody asks about them."""
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        _expire(conn, _now())


def create(owner, profile, session, kind, payload, bind=None):
    """Queues one record and raises its notice (same id). Returns the id. RuntimeError when the owner's limits are reached."""
    if not isinstance(owner, str) or not owner or not isinstance(profile, str) or not profile:
        raise ValueError('invalid')
    stored = json.dumps(clean_payload(kind, payload), ensure_ascii=False, separators=(',', ':'))
    bound = json.dumps({k: (bind or {}).get(k) for k in BIND_FIELDS if (bind or {}).get(k) is not None}, separators=(',', ':'))
    record_id, now = uuid.uuid4().hex, _now()
    expires = now + LIVES[kind]
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        _expire(conn, now)
        if kind == 'needs_you':
            # The newest notices stay: the oldest beyond the cap ends.
            for row in conn.execute("SELECT id FROM agent_phone_approvals WHERE owner=? AND status='pending' AND kind='needs_you' "
                                    'ORDER BY created DESC LIMIT -1 OFFSET ?', (owner, NEEDS_YOU_LIMIT - 1)).fetchall():
                conn.execute("UPDATE agent_phone_approvals SET status='expired' WHERE id=?", (row['id'],))
                conn.execute("UPDATE notices SET state='expired' WHERE id=? AND state='pending'", (row['id'],))
        elif conn.execute("SELECT COUNT(*) FROM agent_phone_approvals WHERE owner=? AND status='pending' AND kind!='needs_you'",
                          (owner,)).fetchone()[0] >= OPEN_LIMIT:
            raise RuntimeError('Too many approvals are waiting')
        if conn.execute('SELECT COUNT(*) FROM agent_phone_approvals WHERE owner=? AND created>?', (owner, now - 60)).fetchone()[0] >= PER_MINUTE:
            raise RuntimeError('Too many approvals')
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0] >= 10000:
            raise RuntimeError('Notification outbox capacity reached')
        conn.execute('INSERT INTO agent_phone_approvals(id,owner,profile,session,kind,payload,bind,holder,created,expires) VALUES(?,?,?,?,?,?,?,?,?,?)',
                     (record_id, owner, profile, str(session or '')[:256], kind, stored, bound, holder_tag(), now, expires))
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)',
                     (record_id, owner, profile, NOTICE_KIND, 'agent-phone-' + record_id, now, expires))
    return record_id


def _shown(row, now):
    try:
        payload = json.loads(row['payload'])
    except (TypeError, ValueError):
        payload = {}
    payload = {k: v for k, v in payload.items() if k in FIELDS or k in FLAGS}
    return dict(payload, id=row['id'], kind=row['kind'], created_at=int(row['created']), expires_at=int(row['expires']),
                expires_in_seconds=max(0, int(row['expires'] - now)))


def open_records(owner):
    """Every record the owner may still decide, oldest first, for any linked device and any profile it has open."""
    now = _now()
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        _expire(conn, now)
        query = ("SELECT id,kind,payload,created,expires FROM agent_phone_approvals WHERE owner=? AND status='pending' AND expires>? "
                 'AND (kind=?)=? ORDER BY created LIMIT ?')
        rows = (conn.execute(query, (owner, now, 'needs_you', 0, OPEN_LIMIT)).fetchall()
                + conn.execute(query, (owner, now, 'needs_you', 1, NEEDS_YOU_LIMIT)).fetchall())
    return [_shown(row, now) for row in rows]


def decide(owner, record_id, approved, device, code=None):
    """A linked device's answer. A code only with a yes to a vault_code record. LookupError, ValueError('expired'|'settled'|'invalid')."""
    if not isinstance(record_id, str) or not ID.fullmatch(record_id) or type(approved) is not bool:
        raise ValueError('invalid')
    if code is not None and (not approved or not isinstance(code, str) or not CODE.fullmatch(code)):
        raise ValueError('invalid')
    now = _now()
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        _expire(conn, now)
        row = conn.execute('SELECT kind,status FROM agent_phone_approvals WHERE id=? AND owner=?', (record_id, owner)).fetchone()
        if not row:
            raise LookupError('Approval not found')
        if row['status'] in ('expired', 'withdrawn'):
            raise ValueError('expired')
        if row['kind'] == 'vault_code' and approved and code is None or row['kind'] != 'vault_code' and code is not None:
            raise ValueError('invalid')
        changed = conn.execute("UPDATE agent_phone_approvals SET status=?,device=?,decided=?,answer=? WHERE id=? AND status='pending'",
                               ('approved' if approved else 'denied', str(device or '')[:128], now,
                                json.dumps({'code': code}) if code else '', record_id)).rowcount
        if not changed:
            raise ValueError('settled')
        conn.execute("UPDATE notices SET state='answered' WHERE id=? AND owner=?", (record_id, owner))
    return approved


def status(owner, record_id):
    """'pending', 'approved', 'denied', 'expired', 'withdrawn', or None when it is not this owner's."""
    mobile = _mobile()
    with mobile.connect() as conn:
        row = conn.execute('SELECT status,expires FROM agent_phone_approvals WHERE id=? AND owner=?', (record_id, owner)).fetchone()
    if not row:
        return None
    return 'expired' if row['status'] == 'pending' and row['expires'] <= _now() else row['status']


def take_answer(owner, record_id):
    """A vault_code's code, read once: the column is emptied in the same step."""
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        row = conn.execute("SELECT answer FROM agent_phone_approvals WHERE id=? AND owner=? AND status='approved'", (record_id, owner)).fetchone()
        conn.execute("UPDATE agent_phone_approvals SET answer='' WHERE id=? AND owner=?", (record_id, owner))
    try:
        return json.loads(row['answer']).get('code') if row and row['answer'] else None
    except (TypeError, ValueError, AttributeError):
        return None


def withdraw(owner, record_id):
    """The asker stopped waiting: an open record ends so no late yes can act. Returns its status before."""
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        row = conn.execute('SELECT status FROM agent_phone_approvals WHERE id=? AND owner=?', (record_id, owner)).fetchone()
        if not row:
            return None
        if row['status'] == 'pending':
            conn.execute("UPDATE agent_phone_approvals SET status='withdrawn',answer='' WHERE id=?", (record_id,))
            conn.execute("UPDATE notices SET state='expired' WHERE id=? AND owner=? AND state='pending'", (record_id, owner))
        return row['status']


def wait(owner, record_id, seconds=WAIT_SECONDS, sleep=time.sleep):
    """Waits for the owner's answer: 'approved', 'denied' or 'expired' (also when the record is gone). An unanswered record is
    withdrawn when the wait ends, so it can never be approved late."""
    deadline = _mono() + min(seconds, WAIT_SECONDS)
    while True:
        state = status(owner, record_id)
        if state in ('approved', 'denied'):
            return state
        if state != 'pending':
            return 'expired'
        left = deadline - _mono()
        if left <= 0:
            break
        sleep(min(POLL_SECONDS, left))
    before = withdraw(owner, record_id)
    return before if before in ('approved', 'denied') else 'expired'


def ask(owner, profile, session, kind, payload, bind=None, seconds=WAIT_SECONDS, sleep=time.sleep):
    """create + wait. Returns (answer, record id); RuntimeError when the limits are reached."""
    record_id = create(owner, profile, session, kind, payload, bind)
    return wait(owner, record_id, seconds, sleep), record_id


def ask_owner_code(owner, profile, session, app, label, note=None, sleep=time.sleep):
    """L9's code(..., ask_owner=) seam: a vault_code record; the owner's code, or None. The code is read once and scrubbed."""
    record_id = create(owner, profile, session, 'vault_code', {'app': app, 'label': label, 'line': f'Enter the code for {label}.',
                                                               'reason': 'vault.code', 'note': note})
    answer = wait(owner, record_id, sleep=sleep)
    return take_answer(owner, record_id) if answer == 'approved' else None


# -- vault records raised through L9's notify ------------------------------------------------------------------------------

def vault_notifier(owner, profile, session, vault, spawn=None, sleep=time.sleep):
    """notify(kind, payload) for PhoneVault: vault_save / vault_update become records that this process applies with
    vault().decide_save(held, yes) when the owner answers (the held credential lives only here); needs_you becomes a record
    with no decision; saved becomes an activity line. vault is a callable returning the PhoneVault (it is built after this)."""
    spawn = spawn or (lambda target: threading.Thread(target=target, name='beacon-agent-phone-save', daemon=True).start())

    def notify(kind, payload):
        payload = payload if isinstance(payload, dict) else {}
        try:
            if kind == 'saved':
                # The row's words come from its action and outcome (L6); its label is the login's identifier, never an English
                # line (contract revision 5 §11.1).
                add_activity(owner, profile, payload.get('label') or '', 'save', payload.get('identifier') or '', True, 'saved')
            elif kind == 'needs_you':
                create(owner, profile, session, 'needs_you', {'app': payload.get('app'), 'label': payload.get('label'),
                                                               'site': payload.get('site'), 'line': payload.get('line'),
                                                               'reason': payload.get('reason')})
            elif kind in ('vault_save', 'vault_update'):
                held = payload.get('held')
                record_id = create(owner, profile, session, kind, {'label': payload.get('label'), 'identifier': payload.get('identifier'),
                                                                    'line': payload.get('line'), 'reason': payload.get('reason')})

                def apply():
                    answer = _wait_long(owner, record_id, LIVES[kind], sleep)
                    try:
                        vault().decide_save(held, answer == 'approved')
                    except Exception:  # health: allow BLE001 -- the held value drops on its own timer
                        log.info('agent phone approvals: a save answer could not be applied')
                spawn(apply)
            elif kind == 'vault_code':
                create(owner, profile, session, 'vault_code', {'app': payload.get('app'), 'label': payload.get('label'),
                                                                'line': payload.get('line'), 'reason': payload.get('reason'),
                                                                'note': payload.get('note')})
        except Exception:  # health: allow BLE001 -- a record that cannot be raised is a lost question, never a crash in the fill
            log.info('agent phone approvals: a %s record could not be raised', kind if kind in KINDS or kind == 'saved' else 'vault')
    return notify


def _wait_long(owner, record_id, seconds, sleep=time.sleep):
    """A save's wait in its holder process: up to its record's life, polling gently; withdrawn when it ends unanswered."""
    deadline = _mono() + seconds
    while _mono() < deadline:
        state = status(owner, record_id)
        if state in ('approved', 'denied'):
            return state
        if state != 'pending':
            return 'expired'
        sleep(1.0)
    withdraw(owner, record_id)
    return 'expired'


# -- declines that stand -------------------------------------------------------------------------------------------------

_declined = {}  # task id -> monotonic time the decline stops standing


def declined_for(task):
    """Seconds the owner's last no still stands for this task (0 when it does not)."""
    with _lock:
        left = _declined.get(task or 'default', 0) - _mono()
    return max(0, left)


def note_decline(task):
    now = _mono()
    with _lock:
        for key in [k for k, until in _declined.items() if until <= now]:
            _declined.pop(key, None)
        _declined[task or 'default'] = now + DECLINE_SECONDS


# -- amounts and the bound purchase grant ----------------------------------------------------------------------------------

_ARABIC_DIGITS = str.maketrans('٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹', '01234567890123456789')
_SYMBOLS = {'£': 'GBP', '€': 'EUR', '¥': 'JPY', '₹': 'INR', '₩': 'KRW', '₺': 'TRY', '₽': 'RUB', '₪': 'ILS', '﷼': 'SAR',
            '฿': 'THB', '₫': 'VND', '₦': 'NGN', '₱': 'PHP', 'US$': 'USD', '$': 'USD', 'ر.س': 'SAR', 'رس': 'SAR', 'ريال': 'SAR',
            'د.إ': 'AED', 'دإ': 'AED', 'درهم': 'AED', 'د.ك': 'KWD', 'دك': 'KWD', 'دينار': 'KWD', 'ر.ق': 'QAR', 'رق': 'QAR',
            'ج.م': 'EGP', 'جم': 'EGP', 'جنيه': 'EGP'}
CODES = ('USD', 'EUR', 'GBP', 'SAR', 'AED', 'KWD', 'QAR', 'BHD', 'OMR', 'EGP', 'JOD', 'INR', 'JPY', 'CNY', 'TRY', 'RUB', 'CHF',
         'CAD', 'AUD', 'NZD', 'SEK', 'NOK', 'DKK', 'PLN', 'BRL', 'MXN', 'ZAR', 'SGD', 'HKD', 'MYR', 'IDR', 'PHP', 'THB', 'VND',
         'KRW', 'NGN', 'PKR', 'ILS', 'RM', 'Rs')
_CURRENCY = ('(?:US\\$|A\\$|C\\$|[£$€¥₹₩₺₽₪﷼฿₫₦₱]|(?<![A-Za-z])(?:' + '|'.join(CODES) + ')(?![A-Za-z])'
             '|ر\\.?\\s?س|د\\.?\\s?إ|د\\.?\\s?ك|ر\\.?\\s?ق|ج\\.?\\s?م|ريال|درهم|دينار|جنيه)')
_NUMBER = '[0-9٠-٩۰-۹](?:[0-9٠-٩۰-۹]|[.,٬٫   ](?=[0-9٠-٩۰-۹]))*'
AMOUNT = re.compile('(?P<c1>' + _CURRENCY + ')\\s?(?P<n1>' + _NUMBER + ')|(?P<n2>' + _NUMBER + ')\\s?(?P<c2>' + _CURRENCY + ')')


def find_amounts(text):
    """Every currency amount in text as (currency code or the symbol as written, Decimal value, the text as shown)."""
    found = []
    for match in AMOUNT.finditer(text or ''):
        raw_currency = match.group('c1') or match.group('c2')
        value = _number(match.group('n1') or match.group('n2'))
        if value is None:
            continue
        currency = _SYMBOLS.get(raw_currency.replace(' ', ''), raw_currency.replace(' ', '').upper())
        found.append((currency, value, match.group(0).strip()))
    return found


def _number(text):
    digits = text.translate(_ARABIC_DIGITS).replace('٫', '.').replace('٬', ',')
    digits = re.sub(r'[\s  ]', '', digits)
    if ',' in digits and '.' in digits:
        decimal = '.' if digits.rfind('.') > digits.rfind(',') else ','
    elif digits.count(',') == 1 and len(digits.split(',')[1]) in (1, 2):
        decimal = ','
    elif digits.count('.') == 1 and len(digits.split('.')[1]) != 3:
        decimal = '.'
    else:
        decimal = None
    if decimal:
        whole, _, fraction = digits.rpartition(decimal)
        whole = whole.replace(',', '').replace('.', '')
        digits = whole + '.' + fraction
    else:
        digits = digits.replace(',', '').replace('.', '')
    try:
        return Decimal(digits).quantize(Decimal('0.01'))
    except (InvalidOperation, ValueError):
        return None


def normalize_amount(text):
    """(currency, Decimal) of the first amount in text, or None."""
    found = find_amounts(text)
    return (found[0][0], found[0][1]) if found else None


def _host(text):
    """The host a page address or an address bar shows, lower case, without scheme, port, path and a leading www."""
    text = re.sub(r'^[a-z][a-z0-9+.-]*://', '', str(text or '').strip().lower())
    host = re.split(r'[/?#:\s]', text, maxsplit=1)[0]
    return host[4:] if host.startswith('www.') else host


def same_site(host, site):
    """The page host is the site a web fill named, or a subdomain of it ("checkout.shop.example" for "shop.example"). A host that
    cannot be read, or a site that is empty, is no match."""
    host, site = _host(host), _host(site)
    return bool(host and site and (host == site or host.endswith('.' + site)))


def same_amount(a, b):
    """Two normalized amounts are the same value; when both name a currency, the same currency."""
    if not a or not b or a[1] != b[1]:
        return False
    return a[0] == b[0]


class PurchaseGrants:
    """The bound purchase grants of this process: (package, card handle, certificates, amount, task, session) for 5 minutes. A
    grant covers one step tap (GRANT_STEPS) of its purchase (Continue, Confirm...) and one order tap, which uses it up. Only the
    task and session that got the owner's yes can spend it."""

    def __init__(self, clock=None):
        self._clock = clock or _mono
        self._grants = []
        self._lock = threading.Lock()

    def record(self, package, handle, amount, certs=(), task=None, session=None, web_domain=None):
        """L9's on_card_approved. A grant whose amount cannot be read is kept but covers no order input. A web fill's grant names
        its site: in a browser the package is the same for every site, so the site is what the grant is bound to."""
        normalized = normalize_amount(amount) if isinstance(amount, str) else None
        with self._lock:
            now = self._clock()
            self._grants = [g for g in self._grants if g['until'] > now and not (g['package'] == package and g['handle'] == handle)]
            self._grants.append({'package': package, 'handle': handle, 'certs': tuple(sorted(certs or ())), 'amount': normalized,
                                 'shown': amount if isinstance(amount, str) else None, 'task': task, 'session': session,
                                 'web_domain': _host(web_domain) or None, 'steps': 0, 'until': now + GRANT_SECONDS})

    def covering(self, package, amounts, task=None, session=None, host=None):
        """The grant that covers one payment input in package, by this task and session, whose screen amounts (normalized)
        include its amount, or None. A grant that names a site covers an input only while the page's host (``host``, read from
        the address bar) is that site; a host that cannot be read covers nothing."""
        with self._lock:
            now = self._clock()
            for grant in self._grants:
                if (grant['until'] > now and grant['package'] == package and grant['task'] == task and grant['session'] == session
                        and grant['amount'] and any(same_amount(grant['amount'], a) for a in amounts)
                        and (not grant['web_domain'] or same_site(host, grant['web_domain']))):
                    return dict(grant)
        return None

    def step(self, grant):
        """One covered step tap of the purchase: True while the grant is there and has a step left (a second step asks)."""
        with self._lock:
            for g in self._grants:
                if g['package'] == grant['package'] and g['handle'] == grant['handle'] and g['until'] == grant['until']:
                    if g['steps'] >= GRANT_STEPS:
                        return False
                    g['steps'] += 1
                    return True
        return False

    def consume(self, grant):
        """Uses a grant up (the order tap, or a step that left the order behind); False when it is already gone (a second order
        asks again)."""
        with self._lock:
            for i, g in enumerate(self._grants):
                if g['package'] == grant['package'] and g['handle'] == grant['handle'] and g['until'] == grant['until']:
                    del self._grants[i]
                    return True
        return False

    def owned(self, package, task, session):
        """The live grant of this package, task and session whatever its amount or site, or None: what an input the owner answered by
        question belongs to (a covered input finds its grant by ``covering``)."""
        with self._lock:
            now = self._clock()
            return next((dict(g) for g in self._grants if g['until'] > now and g['package'] == package and g['task'] == task
                         and g['session'] == session), None)

    def retire(self, package, task, session):
        """Ends every grant of this package, task and session: the owner answered a payment input of that purchase by question (the
        grant covered nothing of it), so the card's yes must not cover that order's retry. True when one was there."""
        with self._lock:
            kept = [g for g in self._grants if not (g['package'] == package and g['task'] == task and g['session'] == session)]
            gone, self._grants = len(kept) != len(self._grants), kept
            return gone

    def clear(self):
        with self._lock:
            self._grants.clear()


GRANTS = PurchaseGrants()


def on_card_approved(package=None, handle=None, amount=None, certs=(), task=None, session=None, web_domain=None, **_):
    """The callback L9's PhoneVault gets as on_card_approved: its grant carries the task, the session of the call that asked and,
    for a web fill, the site."""
    if isinstance(package, str) and package and isinstance(handle, str) and handle:
        GRANTS.record(package, handle, amount, certs, task=task, session=session, web_domain=web_domain)


# -- policy and activity -----------------------------------------------------------------------------------------------------

def _policy_key(owner):
    return 'agent_phone_policy:' + owner


def policy(owner):
    """{'enabled': bool, 'ask': 'payments_and_sends' | 'every_action'}; the defaults until the owner changes them."""
    mobile = _mobile()
    with mobile.connect() as conn:
        row = conn.execute('SELECT value FROM meta WHERE key=?', (_policy_key(owner),)).fetchone()
    value = {}
    if row:
        try:
            value = json.loads(bytes(row[0]).decode('utf-8') if isinstance(row[0], (bytes, memoryview)) else row[0])
        except (TypeError, ValueError):
            value = {}
    enabled = value.get('enabled') if isinstance(value, dict) else None
    level = value.get('ask') if isinstance(value, dict) else None
    return {'enabled': enabled if isinstance(enabled, bool) else True, 'ask': level if level in ASK_LEVELS else ASK_LEVELS[0]}


def set_policy(owner, enabled=None, ask=None):
    if enabled is not None and type(enabled) is not bool or ask is not None and ask not in ASK_LEVELS:
        raise ValueError('invalid')
    current = policy(owner)
    if enabled is not None:
        current['enabled'] = enabled
    if ask is not None:
        current['ask'] = ask
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)', (_policy_key(owner), json.dumps(current)))
    return current


def add_activity(owner, profile, app, action, label, approved, outcome):
    """One line of "What {name} did on its phone": labels only. Returns its number (`seq`)."""
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        seq = conn.execute('INSERT INTO agent_phone_activity(owner,profile,at,app,action,label,approved,outcome) VALUES(?,?,?,?,?,?,?,?)',
                           (owner, profile, _now(), _clean(app, 80) or '', _clean(action, 20) or '', _clean(label, 120) or '',
                            1 if approved else 0, _clean(outcome, 40) or '')).lastrowid
        conn.execute('DELETE FROM agent_phone_activity WHERE owner=? AND seq NOT IN (SELECT seq FROM agent_phone_activity WHERE owner=? '
                     'ORDER BY seq DESC LIMIT ?)', (owner, owner, ACTIVITY_KEEP))
    return seq


def notify_done(owner, profile, reason, seq):
    """The push after an approved payment or send (§5.5): notice kind agent_phone_done, `reason` 'payment' | 'send',
    `activity_key` 'agent_phone_activity:<seq>' naming the activity row the app shows. Nothing else, so the push carries no
    content. Returns the notice id."""
    if reason not in DONE_REASONS or not isinstance(seq, int):
        raise ValueError('invalid')
    notice_id, now = uuid.uuid4().hex, _now()
    mobile = _mobile()
    with mobile.LOCK, mobile.connect() as conn:
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0] >= 10000:
            raise RuntimeError('Notification outbox capacity reached')
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason,activity_key) VALUES(?,?,?,?,?,?,?,?,?)',
                     (notice_id, owner, profile, DONE_KIND, 'agent-phone-done-' + notice_id, now, now + DONE_SECONDS, reason,
                      f'agent_phone_activity:{seq}'))
    return notice_id


def activity(owner, limit=50):
    limit = max(1, min(int(limit), 200))
    mobile = _mobile()
    with mobile.connect() as conn:
        rows = conn.execute('SELECT seq,at,app,action,label,approved,outcome FROM agent_phone_activity WHERE owner=? ORDER BY seq DESC LIMIT ?',
                            (owner, limit)).fetchall()
    return [{'seq': r['seq'], 'at': int(r['at']), 'app': r['app'], 'action': r['action'], 'label': r['label'],
             'approved': bool(r['approved']), 'outcome': r['outcome']} for r in rows]
