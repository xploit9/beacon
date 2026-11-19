"""The read guard for the agent phone's captures (EMU-DESIGN §6.5): what a capture may show the model after a fill.

FLAG_SECURE hides pixels from `screencap`, not text from the accessibility tree, and Beacon's host capture sees secure screens
anyway (S1, S6), so the masks apply to the `uiautomator` tree and to the image. Masked: text holding a value Beacon filled,
password fields, card numbers that pass the Luhn check, expiry and security-code fields, and notification text that carries a
code or a password. A login's identifier stays visible: it is metadata. A screen with no readable tree in an app where a fill
happened is refused instead of shown. Hermes' own redaction (register_vault_redaction_value) is a second layer, never the only one.

A code the helper read from a notification and filled itself never reaches the host (contract §6.4), so there is no value to
look for: the helper reports the fields it filled (code_filled ``targets``: AutofillId, view id, screen bounds) and the guard
masks those fields' text in every capture and tree until the field changes or the screen goes (EMU-JOIN fu1).
"""
import hashlib
import hmac
import json
import os
import re
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

MASK = '••••'
REFUSED = 'The screen has a saved login on it.'
MAX_VALUES = 256
SHADE_PACKAGES = frozenset({'com.android.systemui'})
_CARD = re.compile(r'(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])')
_CVC_LABEL = re.compile(r'(?i)\b(?:cvc|cvv|csc|cvn|cid)\b|security code|رمز الأمان')
_EXP_LABEL = re.compile(r'(?i)\bexp|mm\s*/\s*yy|valid (?:thru|through)|تاريخ الانتهاء')
_CVC_VALUE = re.compile(r'\s*[0-9]{3,4}\s*')
_EXP_VALUE = re.compile(r'\s*[0-9]{1,2}\s*/\s*(?:[0-9]{2}|[0-9]{4})\s*')
_CODE_WORD = re.compile(r'(?i)code|otp|verification|verify|passcode|رمز')
_CODE = re.compile(r'(?<![0-9])[0-9]{4,8}(?![0-9])')
_PASSWORD_WORD = re.compile(r'(?i)password|passcode|كلمة المرور|كلمة السر')
_BOUNDS = re.compile(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
_TEXT_ATTRS = ('text', 'content-desc')
_DIGITS = re.compile(r'\d{1,8}')
_DASHES_AND_SPACES = {ord(c): None for c in ' \u00a0\u2009\u202f-\u2010\u2011\u2012\u2013\u2014'}
SETTLE_SECONDS = 10  # code_filled is sent as Android takes the value: text that changes this soon after is the fill landing
MAX_WATCHES = 8
STORE_VERSION = 1
MAX_DIGESTS = 2048
MAX_WORDS = 64  # words of one text tried against the kept digests
PUNCTUATION = '.,;:!?()[]{}"\'<>'  # around a word, a full stop or a quote does not hide a value


def luhn(text):
    """True when text holds 13-19 digits (spaces or dashes between them allowed) that pass the Luhn check."""
    digits = re.sub(r'[ -]', '', text)
    if not digits.isdigit() or not 13 <= len(digits) <= 19:
        return False
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = int(ch)
        if i % 2:
            d = d * 2 - 9 if d > 4 else d * 2
        total += d
    return total % 10 == 0


def _digest(key, text):
    return hmac.new(key, text.encode('utf-8'), hashlib.sha256).hexdigest()


def _read_store(path):
    """{'key', 'packages', 'code_apps', 'digests'} from a kept-masks file, or None when it is missing or not ours."""
    try:
        data = json.loads(Path(path).read_text(encoding='utf-8'))
        key = bytes.fromhex(data['key'])
        if data.get('v') != STORE_VERSION or len(key) != 32:
            return None
        return {'key': key, 'packages': {p for p in data.get('packages', []) if isinstance(p, str)},
                'code_apps': {p for p in data.get('code_apps', []) if isinstance(p, str)},
                'digests': list(dict.fromkeys(d for d in data.get('digests', []) if isinstance(d, str) and len(d) == 64))}  # oldest first
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None


class MaskSet:
    """The values Beacon filled in this profile's phone session, held in memory (newest MAX_VALUES), and, when ``store`` names a
    file in the phone folder's ``run/``, kept there for the next process as HMAC digests (never the values) with the apps they
    were filled in: a gateway restart, or the dashboard's setup session, no longer starts from an empty set. The file is
    owner-only, shared by every process of this phone, and lives as long as the phone's Quick Boot snapshot, whose restored screen
    still shows what was filled: ``PhoneHost`` deletes it when it drops that snapshot, not at every stop. A digest matches a whole
    text or one word of it, with the punctuation around the word stripped; a value embedded in a longer word ("code=482913") is
    not found by digest. A code the helper filled itself (``watch_fields``) keeps its app in the file as a *code app*, outside the
    newest-MAX_WATCHES cap, but not its watch: every process masks every text field of a code app until the stop (REVIEW-FIX-PHONE-121
    R5-2, R6-1), so a Hermes restart or eight later code fills no longer show a code that is still on its page. A new phone session
    (``run/session``) makes a process forget everything but the values it holds."""

    def __init__(self, clock=time.monotonic, store=None):
        self._values = {}
        self._lock = threading.Lock()
        self._filled = set()  # apps a fill happened in: an unreadable screen there is refused
        self._code_apps = set()  # apps Beacon put a code into: every text field of them is masked until the stop (kept in the file)
        self._code_kept = set()  # the code apps the file names
        self._session = self._session_stamp = None  # the phone session this process's sets belong to (``run/session``), and that file's stamp
        self._watches = []  # code fills the helper did itself: fields to mask (filled_nodes)
        self._clock = clock
        self._store = Path(store) if store else None
        self._session_path = self._store.with_name('session') if self._store is not None else None
        self._store_key = None  # keys the kept digests; lives in the file beside them
        self._digests = set()
        self._order = []  # the kept digests, oldest first: the file keeps the newest MAX_DIGESTS
        self._foreign = set()  # the kept digests no value in memory made: what a restart or another process registered
        self._kept = set()  # the apps the file names
        self._seen = None  # the file's mtime when it was last read; None before it was ever there
        self._seen_id = None  # and its identity (inode, size): two writes in one clock tick have one mtime, but an atomic replace is a new file

    @property
    def filled_packages(self):
        self._sync()
        return self._filled

    # -- the kept file --
    def _sync(self, forget=True):
        """Takes in what another process kept (the setup session in the dashboard, or this gateway before a restart), and forgets
        everything when the file that was there is gone: the phone stopped."""
        if self._store is None:
            return
        self._check_session()
        try:
            info = self._store.stat()
        except OSError:
            if self._seen is not None:
                if forget:
                    self._forget()
                else:
                    self._seen = None  # to be written again
            return
        stamp = info.st_mtime_ns
        if stamp == self._seen and (info.st_ino, info.st_size) == self._seen_id:
            return
        data = _read_store(self._store)
        with self._lock:
            if data:
                if data['key'] != self._store_key:
                    self._store_key = data['key']
                    self._order = [_digest(data['key'], v) for v in self._values]
                    self._digests = set(self._order)
                self._order += [d for d in data['digests'] if d not in self._digests]
                self._digests |= set(data['digests'])
                self._foreign = self._digests - {_digest(self._store_key, v) for v in self._values}
                self._filled |= data['packages']
                self._kept |= data['packages']
                self._code_apps |= data['code_apps']
                self._code_kept |= data['code_apps']
            self._seen, self._seen_id = stamp, (info.st_ino, info.st_size)

    def _check_session(self):
        """A start of the phone writes a new session id (``PhoneHost.new_session``, ``run/session``). A process that kept sets for the last
        session (code apps, watches, fill records, what the file named) forgets them when it sees the next id, and keeps its values, which
        are digested again under the new session's key: more is hidden, never less (REVIEW-FIX-PHONE-121 R6-3). Without a session file there
        is nothing to compare."""
        try:
            info = self._session_path.stat()
        except OSError:
            return
        stamp = (info.st_mtime_ns, info.st_ino, info.st_size)  # an atomic replace is a new file: two starts in one clock tick still differ
        if stamp == self._session_stamp:
            return
        try:
            current = self._session_path.read_text(encoding='utf-8').strip()
        except OSError:
            return
        self._session_stamp = stamp
        if self._session is not None and current != self._session:
            with self._lock:
                self._filled.clear()
                self._kept.clear()
                self._code_apps.clear()
                self._code_kept.clear()
                self._watches.clear()
                self._digests.clear()
                self._order.clear()
                self._foreign.clear()
                self._store_key = self._seen = None
        self._session = current

    def _forget(self):
        with self._lock:
            self._values.clear()
            self._filled.clear()
            self._watches.clear()
            self._digests.clear()
            self._order.clear()
            self._foreign.clear()
            self._kept.clear()
            self._code_apps.clear()
            self._code_kept.clear()
            self._store_key = self._seen = None

    def _persist(self, values=(), package=None):
        """Writes the digests and the apps (atomically, owner-only) when something in them is new. Never makes the phone folder's
        ``run/``: a removed phone stays removed. A file that can't be written leaves the in-memory masks as they are. True when the
        record is on disk (or no store is kept: an in-memory set has no record to lose), False when it could not be written: a fill
        whose record is missing is a fill the stop would not see, so the vault does not release it."""
        if self._store is None:
            return True
        if not self._store.parent.is_dir():
            return False
        self._sync(forget=False)  # merge what another process kept meanwhile
        try:
            with self._lock:
                if self._store_key is None:
                    self._store_key = os.urandom(32)
                    self._order = [_digest(self._store_key, v) for v in self._values]
                    self._digests = set(self._order)
                fresh = [d for d in dict.fromkeys(_digest(self._store_key, v) for v in values) if d not in self._digests]
                if self._seen is not None and not fresh and (package is None or package in self._kept) and self._code_apps <= self._code_kept:
                    return True
                ordered, packages = (self._order + fresh)[-MAX_DIGESTS:], set(self._filled)  # the newest digests, not the smallest
                code_apps = set(self._code_apps)
                data = {'v': STORE_VERSION, 'key': self._store_key.hex(), 'packages': sorted(packages), 'code_apps': sorted(code_apps),
                        'digests': ordered}
            tmp = self._store.with_name(self._store.name + '.tmp')
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, 'w', encoding='utf-8') as out:
                json.dump(data, out)
            written = os.stat(tmp)  # the stamp of what this process wrote, before the replace: another process's replace right after ours is then read
            os.replace(tmp, self._store)
            with self._lock:  # only a write that landed counts as kept: a failed one is tried again at the next register
                self._order = ordered
                self._digests |= set(ordered)
                self._kept |= packages
                self._code_kept |= code_apps
            self._seen, self._seen_id = written.st_mtime_ns, (written.st_ino, written.st_size)
            return True
        except OSError:
            return False

    # -- registering and finding --
    def register(self, value, package=None):
        """Keeps a filled value (and its app) masked. True when its record is on disk or none is kept, False when the record could not be
        written (the caller must not release the value then)."""
        if not isinstance(value, str) or not value.strip():
            return True
        self._sync()  # a stop since the last look empties the set before this value joins it
        added = []
        with self._lock:
            for v in (value, value.replace('\r', '').replace('\n', '')):
                if len(v) >= 3:
                    self._values.pop(v, None)
                    self._values[v] = None
                    added.append(v)
            while len(self._values) > MAX_VALUES:
                del self._values[next(iter(self._values))]
            if package:
                self._filled.add(package)
        return self._persist(added, package)

    def found_in(self, text):
        if not text:
            return False
        self._sync()
        with self._lock:
            if any(v in text for v in self._values):
                return True
            digests, key = self._foreign, self._store_key  # a value in memory was found above; only the others need the digests
            if not digests or key is None:
                return False
            words = text.split()[:MAX_WORDS]
            candidates = {text, text.strip(), *words, *(w.strip(PUNCTUATION) for w in words)}
            return any(len(c) >= 3 and _digest(key, c) in digests for c in candidates)

    def clear(self):
        with self._lock:
            self._values.clear()
            self._filled.clear()
            self._watches.clear()
            self._digests.clear()
            self._foreign.clear()
            self._kept.clear()
            self._code_apps.clear()
            self._code_kept.clear()
            self._order.clear()
            self._store_key = self._seen = None
        if self._store is not None:
            for path in (self._store, self._store.with_name(self._store.name + '.tmp')):
                try:
                    path.unlink()
                except OSError:
                    pass

    def watch_fields(self, package, targets):
        """The helper filled a code it read itself into these fields of ``package`` (code_filled ``targets``, §6.4). The app becomes a
        code app at once (kept in the masks file, outside the newest MAX_WATCHES cap): every text field of it is masked in every process
        until the stop. A code can reach the page by a path no field bookkeeping follows (a sheet of the same app with the same view id,
        a box over the code field's place, a restart before the first shown capture), so no judgement about which box it went into is
        left to make (REVIEW-FIX-PHONE-121 R6-1). The watch only registers the code's own text by value, once, when a shown capture
        finds its field. True when the record is on disk (or no store is kept), False when it could not be written."""
        if not package:
            return True
        self._sync()  # a stop or a new phone session since the last look empties the set before this watch joins it
        watch = _Watch(package, targets, self._clock())
        with self._lock:
            self._watches.append(watch)
            del self._watches[:-MAX_WATCHES]
            self._filled.add(package)
            self._code_apps.add(package)  # Beacon put a code into this app: every text field of it is masked until the stop (R6-1)
        return self._persist(package=package)

    def mark_filled(self, package):
        """The app's fill record, written before a code the helper reads and fills itself is armed (the host never sees that code, so
        the record is what tells the stop a filled screen may be on the phone). True when it is on disk or none is kept, False when it
        could not be written: the vault arms nothing then."""
        if not package:
            return True
        self._sync()
        with self._lock:
            self._filled.add(package)
        return self._persist(package=package)

    def watching(self, package):
        """True while a code the helper filled in this app is watched, or the app is a code app (every text field of it is masked)."""
        self._sync()
        with self._lock:
            return package in self._code_apps or any(w.package == package for w in self._watches)

    def filled_nodes(self, nodes, package, commit=True):
        """ids of the nodes in this capture (``nodes``: (tree path, node) pairs) that are masked code fields: every text field of a code
        app (see ``watch_fields``), whatever its text is, in any tree and in a tentative read too, and the fields of the watches found by
        their view id or position. The code a found field shows is registered, so it stays masked wherever it appears (an appended character
        included). A watch that has not been placed (no tree of its app has shown a field yet) stays until one does; a field found by its view
        id ends when it is gone from its own app's tree or its text changed, one found by position alone never does. Only a tree that is shown
        moves a watch (``commit``): a tentative read (a capture's first or second read, another window of the screen, an input's screen)
        places and compares on copies and registers, ends and remembers nothing; and another app's tree says nothing about a watch, it
        neither masks nor ends it (REVIEW-FIX-PHONE-121 R3-1: the status bar's window ended the watch of the app in front, and its code
        reached the model as text)."""
        self._sync()  # the stop empties the set, and a code app another process kept (Hermes restarted) joins it
        with self._lock:
            watches = list(self._watches)
            code_apps = set(self._code_apps)
        hits, ended, now = set(), [], self._clock()
        for w in watches:
            if package is not None and package != w.package:
                continue
            app = [(path, n) for path, n in nodes if n.get('package') == w.package]
            if not app:
                continue  # nothing of this app in this tree
            if commit:
                if w.fields is None:
                    found = _place(app, w.targets)
                    if found:  # a read that places nothing (a sheet of the same app, with no text field) says nothing: the watch stays unplaced
                        w.fields = found
                fields = w.fields if w.fields is not None else {}
            else:
                fields = {p: dict(f) for p, f in w.fields.items()} if w.fields is not None else _place(app, w.targets)
            by_path = dict(app)
            settling = now - w.since < SETTLE_SECONDS
            for path, f in list(fields.items()):
                node = by_path.get(path)
                if node is None or node.get('class') != f['class']:
                    if f['placed']:
                        del fields[path]
                    continue  # a field found by position alone that one capture does not show says nothing
                text = _own_text(node)
                if text and f['value']:
                    if f['placed'] and f['seen'] and not settling and not self.found_in(text):
                        del fields[path]  # the field changed: its new text is shown
                        continue
                    if commit and (settling or not f['seen']):
                        self.register(text, w.package)
                    if commit:
                        f['seen'] = True
                hits.add(id(node))
            if commit and w.fields is not None and not fields:
                ended.append(w)  # the fields it had placed are gone from its own app's tree
        if ended:
            with self._lock:
                self._watches = [w for w in self._watches if w not in ended]
        if code_apps:  # every text field of a code app, whatever watch the cap kept (R5-2) and whichever process filled the code, and every
            # view that is only digits (a code drawn one digit per view); outside a code app digits are the app's own (a price, a count)
            hits |= {id(n) for _, n in nodes if n.get('package') in code_apps and (_editable(n) or _digits_only(n))}
        return hits

    def __len__(self):
        return len(self._values)


def _bounds(node):
    found = _BOUNDS.fullmatch(node.get('bounds') or '')
    return tuple(int(x) for x in found.groups()) if found else None


class _Watch:
    """One code fill by the helper: its app, the fields it reported and, once placed in a capture, {tree path: field}."""

    def __init__(self, package, targets, since):
        self.package, self.since = package, since
        self.targets = [t for t in (targets or []) if isinstance(t, dict)]
        self.fields = None


def _editable(node):
    return 'EditText' in (node.get('class') or '')


def _digits_only(node):
    """A node whose whole text, or whole content description, is only digits (1 to 8, spaces and dashes ignored): one cell of an OTP widget
    that draws a digit per view and keeps the code in a hidden box (REVIEW-FIX-PHONE-121 R7-4)."""
    return any(_DIGITS.fullmatch((node.get(attr) or '').translate(_DASHES_AND_SPACES)) for attr in _TEXT_ATTRS)


def _holding(pairs, target):
    """The nodes holding the centre of the target's reported bounds, smallest first."""
    box = target.get('bounds')
    if not (isinstance(box, (list, tuple)) and len(box) == 4):
        return []
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    found = []
    for path, node in pairs:
        b = _bounds(node)
        if b and b[0] <= cx <= b[2] and b[1] <= cy <= b[3]:
            found.append(((b[2] - b[0]) * (b[3] - b[1]), path, node))
    return [(path, node) for _, path, node in sorted(found, key=lambda x: x[0])]


def _place(app, targets):
    """{tree path: field} for the reported fields in this capture of their app: by view id (bounds break a tie), else the
    smallest text field holding the centre of the reported bounds; only a text field ever counts. The bounds come from the
    fill request, before the keyboard may have moved the screen, so a field placed by position alone may be the wrong one and is
    never dropped. Every text field of the app is masked anyway (the app is a code app: ``watch_fields``); what a found field adds is
    that its text is registered by value, so the code stays masked wherever else it shows."""
    edits = [(p, n) for p, n in app if _editable(n)]
    fields = {}
    for t in targets:
        view_id = t.get('view_id')
        named = [(p, n) for p, n in app if view_id and n.get('resource-id') == view_id]
        if named:
            found = (_holding(named, t) or named)[:1]
        else:
            found = _holding(edits, t)[:1]
        if found:
            path, node = found[0]
            # Found by view id: placed (dropped when it is gone or its text changed). Found by position alone it may be the wrong box (a sheet's
            # only box can lie over the code field), so it is never dropped (R5-1); either way its text is registered by value.
            fields[path] = {'class': node.get('class'), 'placed': bool(named), 'value': True, 'seen': False}
    return fields


def _paths(element, path=()):
    """(tree path, node) for every node, in document order; the path is the child positions from the root."""
    for i, child in enumerate(element):
        here = path + (i,)
        if child.tag == 'node':
            yield here, child
        yield from _paths(child, here)


def _label(node):
    return ' '.join(node.get(k) or '' for k in ('resource-id', 'hint', 'content-desc'))


def _own_text(node):
    """The node's text when it is a value, not the field's hint shown in an empty field."""
    text = node.get('text') or ''
    return '' if text and text == (node.get('hint') or '') else text


def _reason(node, masks):
    text, desc = _own_text(node), node.get('content-desc') or ''
    if masks.found_in(text) or masks.found_in(desc):
        return 'registered'
    if node.get('password') == 'true' and text:
        return 'password'
    if any(_CARD.search(t) and any(luhn(m.group(0)) for m in _CARD.finditer(t)) for t in (text, desc)):
        return 'card'
    label = _label(node)
    if text and _CVC_LABEL.search(label) and _CVC_VALUE.fullmatch(text):
        return 'cvc'
    if text and _EXP_LABEL.search(label) and _EXP_VALUE.fullmatch(text):
        return 'expiry'
    if node.get('package') in SHADE_PACKAGES:
        joined = text + ' ' + desc
        if _CODE_WORD.search(joined) and _CODE.search(joined) or _PASSWORD_WORD.search(joined):
            return 'notification'
    return None


def readable(root):
    return sum(1 for n in root.iter('node') if (n.get('text') or n.get('content-desc'))) >= 2


def mask_tree(xml_text, masks, package=None, commit=True):
    """(masked xml, boxes to black out, counts by reason) or (None, None, {'refused': REFUSED}).

    package is the app in front; a screen there without a readable tree is refused when a fill happened in that app. With no
    package given, any fill in this session refuses it (fail closed). ``commit`` False is a tentative read: it moves no code watch."""
    try:
        root = ET.fromstring(xml_text) if xml_text else None
    except ET.ParseError:
        root = None
    if root is None or not readable(root):
        if package in masks.filled_packages or package is None and masks.filled_packages:
            return None, None, {'refused': REFUSED}
        if root is None:
            return '', [], {}
    boxes, counts = [], {}
    nodes = list(_paths(root))
    filled = masks.filled_nodes(nodes, package, commit) if hasattr(masks, 'filled_nodes') else set()
    for _, node in nodes:
        why = 'filled_code' if id(node) in filled else _reason(node, masks)
        if why is None:
            continue
        counts[why] = counts.get(why, 0) + 1
        for attr in _TEXT_ATTRS:
            if node.get(attr):
                node.set(attr, MASK)
        box = _bounds(node)
        if box:
            boxes.append(box)
    return ET.tostring(root, encoding='unicode'), boxes, counts


def mask_image(image, boxes, screen=(1080, 2400)):
    """Blacks out boxes (in screen coordinates) on a Pillow image of any size; returns the same image."""
    if image is None or not boxes:
        return image
    from PIL import ImageDraw
    draw = ImageDraw.Draw(image)
    sx, sy = image.width / screen[0], image.height / screen[1]
    for x0, y0, x1, y1 in boxes:
        draw.rectangle([int(x0 * sx) - 1, int(y0 * sy) - 1, int(x1 * sx + 0.999) + 1, int(y1 * sy + 0.999) + 1], fill=(0, 0, 0))
    return image


def field_boxes(xml_text):
    """The boxes of every text field in a tree (a text field is an EditText, or a password field) and of every view that is only digits
    (a code drawn one digit per view): what a window of a code app is masked by when no tree of it is shown."""
    try:
        root = ET.fromstring(xml_text) if xml_text else None
    except ET.ParseError:
        return []
    boxes = []
    for node in root.iter('node') if root is not None else ():
        if _editable(node) or node.get('password') == 'true' or _digits_only(node):
            box = _bounds(node)
            if box:
                boxes.append(box)
    return boxes


def mask_capture(xml_text, image, masks, package=None, screen=(1080, 2400), commit=True):
    """Masks a capture before anything of it reaches the model: {'tree', 'image', 'masked'} or {'refused': line}."""
    tree, boxes, counts = mask_tree(xml_text, masks, package, commit)
    if tree is None:
        return {'refused': counts['refused']}
    return {'tree': tree, 'image': mask_image(image, boxes, screen), 'masked': counts}
