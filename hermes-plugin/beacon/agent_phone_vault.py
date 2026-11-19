"""Hermes' vault on the agent phone (EMU-DESIGN §6): fills by handle through the Beacon helper, saves, sign-ups, codes.

The model only ever sees handles, labels, origins and identifiers. A value leaves Hermes' vault only in a `release` over the
session's sealed channel (agent_phone_channel), only for an armed request whose own fill context still binds the item:
an app item fills only in its package with the certificates pinned at first use (Android's anti-phishing rule), a site item
only in Chrome (certificate pinned at first sight) or in an app the site lists through Digital Asset Links, with an exact
origin. Cards keep Hermes' own consent (request_elicitation_consent, surface vault-payment): one combined question naming
the shop, the amount and the card. Filled values are registered in Beacon's mask set and with Hermes' redaction.

A captured credential (Android's save, a sign-up's generated password, a temporary password) is held in memory only until
the owner decides: never on disk, in a log or a result, dropped after a no or 10 minutes. The sidecar beside the pointer keeps
handle -> package, certificates, status and last success: metadata only.

Profiles: in an agent turn the vault is the session's own (Hermes' backends), and the caller refuses a session whose profile
is not the phone's owning profile. In the dashboard (setup sessions) it is VaultStore(base_dir=<owning profile's home>/vault),
never the request's profile; 1Password and Bitwarden need a per-session unlock, so they are not offered there.
Hermes is never patched; every Hermes name used here is read by compat.py and checked by the Hermes contract test.
"""
import functools
import json
import logging
import os
import re
import secrets
import string
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlsplit

log = logging.getLogger('beacon.agent_phone.vault')

UPDATE_LINE = 'Beacon needs an update to use saved logins on the phone with this Hermes.'
CHROME = 'com.android.chrome'
TRUSTED_SOURCES = ('system', 'play')  # Root 17 Nov: pin Chrome only when it is the system or Play-installed one
HOLD_SECONDS = 600  # a captured credential's longest life in memory
ARM_SECONDS = 20
RESULT_SECONDS = 12
DECLINE_SECONDS = 60
# The owner's yes to a first use of a login in an app stands until a fill uses it, for at most this long: a fill that filled
# nothing (the entry didn't show) and the agent's retry are one question (Root, EMU-JOIN fu2 journey, 17 Nov).
PIN_YES_SECONDS = 300
MAX_HELD = 10
DAL_SECONDS = 600
# "Grants the target permission to retrieve sign-in credentials stored for the source" (Google, Digital Asset Links relation
# strings); handle_all_urls is about links only and shares no sign-in.
DAL_RELATIONS = ('delegate_permission/common.get_login_creds',)
NOT_CONNECTED = "The phone's helper is not connected. Try again in a moment."
LOGIN_ROLES = ('username', 'password')
SECRET_ROLES = frozenset({'password', 'new_password', 'otp', 'card_number', 'cardholder_name', 'exp_date', 'exp_month',
                          'exp_year', 'cvc', 'billing_postal_code'})
FAILURE_WORDS = re.compile(r"(?i)wrong password|incorrect|invalid|try again|doesn't match|does not match|didn't match|"
                           r"already (?:exists|registered|in use|taken)|too (?:short|weak)|failed|"
                           r'غير صحيح|غير صالح|خطأ|حاول مرة أخرى|مستخدم بالفعل|موجود بالفعل|غير متطابق|فشل')
PASSWORD_ALPHABET = string.ascii_letters + string.digits + '-_.!#%*+'


def _hermes():
    """Hermes' own pieces as they are now, or None when this Hermes moved one (compat.py watches these same imports)."""
    try:
        from agent.redact import register_vault_redaction_value
        from agent.vault_backends import UnlockRequired, backend_for_handle, enabled_backends
        from agent.vault_backends.unlock import can_prompt_here, get_code_prompt_callback, get_save_login_prompt_callback
        from agent.vault_store import VaultError, VaultStore, get_vault_store, normalize_origin
        from tools.approval_prompt import request_elicitation_consent
        from tools.browser_vault_context import saved_origins
        from tools.browser_vault_tool import browser_vault_unlock
    except Exception:
        return None
    return SimpleNamespace(register=register_vault_redaction_value, UnlockRequired=UnlockRequired, backend_for_handle=backend_for_handle,
                           enabled_backends=enabled_backends, can_prompt_here=can_prompt_here, code_prompt=get_code_prompt_callback,
                           save_prompt=get_save_login_prompt_callback, VaultError=VaultError, VaultStore=VaultStore,
                           get_vault_store=get_vault_store, normalize_origin=normalize_origin, consent=request_elicitation_consent,
                           saved_origins=saved_origins, unlock=browser_vault_unlock)


def owning_home(profile):
    """The owning profile's home by Hermes' own profile resolution, as canonical_transport.native_policy resolves it."""
    from hermes_cli.web_server_profiles import _resolve_profile_dir
    return Path(_resolve_profile_dir(profile))


def sidecar_path(home):
    return Path(home) / 'plugin-data' / 'beacon' / 'agent-phone' / 'vault-state.json'


def _step_key(context):
    """Where a sign-in step counts: the app, and in a browser the exact site (every site in Chrome shares its package)."""
    return context['package'], (context.get('web_domain') or '').lower()


def _view(field_id):
    """An AutofillId's view part: Android writes it as <view>@<autofill session>, and a new session (refresh) renumbers the
    session part of the same field (measured in gate B5)."""
    return str(field_id).split('@', 1)[0]


def forget_phone(home=None, profile=None, *, delete_logins):
    """Remove (L1a's remove_phone, before the phone's folder goes): always deletes the vault sidecar; with delete_logins, also
    the vault items saved from this phone (the sidecar marks them saved_on_phone), never one the owner saved elsewhere, even
    if the phone pinned it. Returns how many items it deleted. Idempotent: a second call finds no sidecar and returns 0.
    The sidecar and the vault live in the owning profile's home: given profile, that home is resolved by Hermes' own profile
    resolution, and home (the Hermes home, or that profile's home) is processed too; each sidecar only names items of the
    vault beside it, so processing both is safe whichever home the caller has. Raises only when the vault can't be read; L1a
    then removes the phone and says the logins stayed."""
    homes = []
    if profile:
        homes.append(owning_home(profile))
    if home is not None:
        homes.append(Path(home))
    deleted = 0
    for place in dict.fromkeys(Path(h).resolve() for h in homes):
        path = sidecar_path(place)
        if delete_logins and path.exists():
            hermes = _hermes()
            if hermes is None:
                raise LookupError(UPDATE_LINE)
            store = hermes.VaultStore(base_dir=place / 'vault')
            marked = [handle for handle, entry in Sidecar(path)._read()['items'].items()
                      if isinstance(entry, dict) and entry.get('saved_on_phone') is True]
            present = {meta.id for meta in store.list_items()}
            for handle in marked:
                if handle in present and store.remove_item(handle):
                    deleted += 1
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        try:
            path.parent.rmdir()  # only when nothing else is in it
        except OSError:
            pass
    log.info('agent phone vault: forgot the phone (%d saved login(s) deleted)', deleted)
    return deleted


def _say(error, kind, **extra):
    return dict(extra, success=False, error_type=kind, error=error)


def _link_errors(method):
    """A channel that dropped (agent_phone_channel.ChannelError) is a plain refusal for the tool, never a traceback."""
    @functools.wraps(method)
    def wrapped(self, *args, **kwargs):
        try:
            return method(self, *args, **kwargs)
        except Exception as exc:
            if type(exc).__name__ == 'ChannelError':
                return _say(NOT_CONNECTED, 'not_connected')
            raise
    return wrapped


def generate_password(length=20):
    """A strong password made on the host: letters, digits and a few symbols most sites accept; one of each class at least."""
    while True:
        value = ''.join(secrets.choice(PASSWORD_ALPHABET) for _ in range(length))
        if (any(c.islower() for c in value) and any(c.isupper() for c in value) and any(c.isdigit() for c in value)
                and any(c in '-_.!#%*+' for c in value)):
            return value


def identifier_type(identifier):
    """As Hermes' browser_vault save_login decides it."""
    return 'email' if '@' in identifier else ('phone' if identifier.lstrip('+').isdigit() else 'username')


def code_hosts(origin):
    """The `hosts` of an arm_code/note_password/open_link sender rule (contract §6.4): the item's host and, for www., its parent."""
    host = (urlsplit(origin or '').hostname or '').lower().rstrip('.') if origin and '://' in origin else ''
    if not host or host.count('.') < 1 or origin.startswith('android://'):
        return []
    hosts = [host]
    if host.startswith('www.') and host[4:].count('.') >= 1:
        hosts.append(host[4:])
    return hosts


# -- what a screen says after a submit (S14, and its reverse for sign-ups) ------------------------------------------

def _tree_nodes(xml_text):
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(xml_text) if xml_text else None
    except ET.ParseError:
        return None
    nodes = list(root.iter('node')) if root is not None else []
    return nodes if sum(1 for n in nodes if n.get('text') or n.get('content-desc')) >= 2 else None


def _has_error(nodes):
    return bool(FAILURE_WORDS.search(' '.join((n.get('text') or '') + ' ' + (n.get('content-desc') or '') for n in nodes)))


def _password_fields(nodes):
    return [n for n in nodes if n.get('password') == 'true']


def _emptied(fields):
    return bool(fields) and all(not (n.get('text') or '') or n.get('text') == (n.get('hint') or '') for n in fields)


def submit_outcome(trees):
    """'failure', 'success' or 'unknown' from the trees seen in the 10 s after a submit, oldest first.

    failure needs the form still there (a password field): with an error from the failure lexicon (English and Arabic), or
    come back emptied. success: the last readable screen has no password field and no error. Anything else is unknown, also
    a lexicon word on a screen without the form (landing pages say "try again" too); a sign-up then commits "not confirmed"."""
    readable = [nodes for nodes in (_tree_nodes(xml_text) for xml_text in trees) if nodes is not None]
    if not readable:
        return 'unknown'
    for nodes in readable:
        if _password_fields(nodes) and _has_error(nodes):
            return 'failure'
    last = readable[-1]
    fields = _password_fields(last)
    if not fields:
        return 'unknown' if _has_error(last) else 'success'
    if len(readable) > 1 and _emptied(fields):
        return 'failure'  # the form came back with the password emptied
    return 'unknown'


# -- the sidecar ------------------------------------------------------------------------------------------------------

class Sidecar:
    """handle -> {package, certs, status, last_success, replaces}; browsers -> {package: certs}. Metadata only, owner-only file."""

    def __init__(self, path):
        self.path = Path(path)
        self._lock = threading.Lock()

    def _read(self):
        try:
            data = json.loads(self.path.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            data = {}
        if not isinstance(data, dict):
            data = {}
        data.setdefault('items', {})
        data.setdefault('browsers', {})
        return data

    def _write(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        part = self.path.with_name(f'{self.path.name}.part-{os.getpid()}-{threading.get_ident()}')
        part.write_text(json.dumps(data, indent=1, sort_keys=True), encoding='utf-8')
        try:
            os.chmod(part, 0o600)
        except OSError:
            pass
        os.replace(part, self.path)

    def item(self, handle):
        with self._lock:
            return dict(self._read()['items'].get(handle) or {})

    def update(self, handle, **fields):
        with self._lock:
            data = self._read()
            entry = data['items'].setdefault(handle, {})
            entry.update({k: v for k, v in fields.items() if v is not None})
            self._write(data)
            return dict(entry)

    def drop(self, handle):
        with self._lock:
            data = self._read()
            if data['items'].pop(handle, None) is not None:
                self._write(data)

    def browser(self, package, certs=None):
        """The certificates pinned for a browser; the first sight pins them (a system browser's package can't be taken)."""
        with self._lock:
            data = self._read()
            pinned = data['browsers'].get(package)
            if pinned is None and certs:
                data['browsers'][package] = sorted(certs)
                self._write(data)
                return sorted(certs)
            return pinned


# -- vault sources ----------------------------------------------------------------------------------------------------

class AgentSource:
    """The session's own vault in an agent turn: every enabled Hermes backend (the caller checked the profile)."""

    owner_only = False

    def __init__(self, hermes):
        self.h = hermes

    def items(self):
        found, locked = [], []
        for backend in self.h.enabled_backends():
            if backend.needs_unlock and not backend.is_unlocked():
                locked.append({'backend': backend.name, 'display_name': backend.display_name,
                               'unlock': f'Unlock {backend.display_name} in Beacon first.'})
                continue
            try:
                found.extend((backend, meta) for meta in backend.list_items())
            except Exception:  # health: allow BLE001 -- one manager failing leaves the others listed
                locked.append({'backend': backend.name, 'display_name': backend.display_name, 'unlock': 'unavailable'})
        return found, locked

    def backend(self, handle):
        return self.h.backend_for_handle(handle)

    def local_store(self):
        return self.h.get_vault_store()


class LocalSource(AgentSource):
    """The owning profile's local vault, by folder: the dashboard's setup sessions."""

    owner_only = True

    def __init__(self, hermes, home):
        super().__init__(hermes)
        self.store = hermes.VaultStore(base_dir=Path(home) / 'vault')
        source = self

        class Local:
            name, display_name, prefix, needs_unlock = 'local', 'Hermes vault', 'vault_', False

            def owns(self, handle):
                return handle.startswith('vault_')

            def is_unlocked(self):
                return True

            def fill_context(self):
                from contextlib import nullcontext
                return nullcontext()

            def list_items(self):
                return source.store.list_items()

            def get_meta(self, handle):
                return source.store.get_meta(handle)

            def resolve_password(self, handle):
                return str(source.store.resolve_secret(handle).get('password') or '')

            def resolve_otp(self, handle):
                from agent.vault_store import totp_now
                seed = str(source.store.resolve_secret(handle).get('otp_secret') or '')
                return totp_now(seed) if seed else None

            def resolve_secret(self, handle):
                return {k: str(v) for k, v in source.store.resolve_secret(handle).items()}

        self.local = Local()

    def items(self):
        return [(self.local, meta) for meta in self.store.list_items()], []

    def backend(self, handle):
        return self.local if self.local.owns(handle) else None

    def local_store(self):
        return self.store


# -- the bridge -------------------------------------------------------------------------------------------------------

class Held:
    """A captured or generated credential waiting for the owner, in memory only; it forgets its values when it ends."""

    def __init__(self, kind, values, context, origin, label, wall):
        self.id = 'held_' + secrets.token_urlsafe(12)
        self.kind, self.values, self.context, self.origin, self.label = kind, dict(values), context, origin, label
        self.at, self.tries, self.old_handle = wall(), 0, None

    def forget(self):
        for key in list(self.values):
            self.values[key] = None
        self.values.clear()


class PhoneVault:
    """One profile's vault on the agent phone. Build it with for_agent() or for_dashboard(); attach each channel session with
    handler(session, owner=...). notify(kind, payload) carries labels only to Beacon's records (L3) and the setup view (L2)."""

    def __init__(self, source, sidecar, masks, hermes, *, notify=None, app_label=None, dal_fetch=None, on_card_approved=None,
                 refresh=None, wall=time.time, clock=time.monotonic):
        self.source, self.sidecar, self.masks, self.h = source, sidecar, masks, hermes
        # Android keeps the first fill answer of a screen for its autofill session, and that answer came before the arm (the
        # context is read from it), so tapping the field again asks nothing (measured in gates B5 and B8, 17 Nov). refresh()
        # resets Android's autofill right after an arm: `cmd autofill reset` through PhoneAdb (L2/L3 wire it); the driver then
        # moves focus off the field and taps it again, and the helper is asked anew. `destroy sessions` was not enough after an
        # empty first answer (a one-field code screen). Without refresh, fills only work where the arm comes first.
        self.refresh = refresh
        self.notify = notify or (lambda kind, payload: None)
        self.app_label = app_label or (lambda package: package)
        self.dal_fetch = dal_fetch or _fetch_asset_links
        self.on_card_approved = on_card_approved or (lambda **grant: None)
        self.wall, self.clock = wall, clock
        self._lock = threading.RLock()
        self._armed = {}  # arm_id -> dict (handle, kind, package, certs, web, roles, focus, until, owner, held)
        self._results = {}  # arm_id -> [Event, filled count or None, reason]
        self._held = {}  # held id -> Held
        self._timers = {}
        self._declined = {}  # task id -> monotonic time the decline stops standing
        self._grants = {}  # (package, handle) -> {certs, amount, until}
        self._pin_yes = {}  # (task id, handle, package, certs) -> monotonic time the owner's unused yes stops standing
        self._dal = {}  # (domain, package) -> (verdict, time)
        # (package, web domain) -> clock time of the last sign-in step there: an agent fill's arm (login, sign-up, card) or a
        # code that filled (a code is used once). A code from a notification counts only if it came after it (L3 run 5).
        self._steps = {}
        self._used = {}  # item handle -> clock time its last temporary password or sign-in link was taken (each is used once)
        self._offering = threading.Lock()

    @classmethod
    def for_agent(cls, profile, owning_profile, masks, *, home=None, **kw):
        """In an agent turn. A session of another profile is refused: the phone has one owning profile (§6.2). home is the
        owning profile's home (the sidecar's place); in the turn that is Hermes' current home."""
        hermes = _hermes()
        if hermes is None:
            raise LookupError(UPDATE_LINE)
        if profile != owning_profile:
            raise PermissionError("Only the phone's own profile can use its saved logins.")
        if home is None:
            from hermes_constants import get_hermes_home
            home = get_hermes_home()
        return cls(AgentSource(hermes), Sidecar(sidecar_path(home)), masks, hermes, **kw)

    @classmethod
    def for_dashboard(cls, owning_profile, masks, *, home=None, **kw):
        """In the dashboard: the owning profile's local vault by folder, never the request's profile."""
        hermes = _hermes()
        if hermes is None:
            raise LookupError(UPDATE_LINE)
        home = Path(home) if home is not None else owning_home(owning_profile)
        return cls(LocalSource(hermes, home), Sidecar(sidecar_path(home)), masks, hermes, **kw)

    # -- listing -----------------------------------------------------------------------------------------------------
    def vault_list(self):
        """The same entries as Hermes' browser_vault list, plus the app of android:// items and Beacon's status: never a secret."""
        pairs, locked = self.source.items()
        items = []
        for backend, meta in pairs:
            entry = {'handle': meta.id, 'backend': backend.name, 'label': meta.label, 'kind': meta.kind, 'origin': meta.origin,
                     'available': meta.kind in ('login', 'payment') or bool(meta.origin)}
            if meta.identifier:
                entry.update(identifier=meta.identifier, identifier_type=meta.identifier_type)
            if meta.origin and meta.origin.startswith('android://'):
                entry['app'] = self.sidecar.item(meta.id).get('package') or meta.origin[len('android://'):]
            status = self.sidecar.item(meta.id).get('status')
            if status in ('didnt_work', 'not_confirmed'):
                entry['status'] = status
            items.append(entry)
        out = {'success': True, 'items': items}
        if locked:
            out['locked'] = locked
        return out

    # -- binding -----------------------------------------------------------------------------------------------------
    def _origin_of(self, context):
        if context.get('web_domain'):
            scheme = (context.get('web_scheme') or '').lower()
            if scheme not in ('https', 'http'):
                return None
            try:
                return self.h.normalize_origin(f"{scheme}://{context['web_domain']}")
            except Exception:  # health: allow BLE001 -- an origin Hermes cannot read binds nothing
                return None
        return 'android://' + context['package'].lower()

    def bind(self, meta, context, fetch=True):
        """(True, how) when this item may fill in this fill context, else (False, the owner-readable reason).
        fetch=False keeps it to local checks (setup offers run on every fill request; only an explicit fill fetches)."""
        package, certs = context.get('package') or '', sorted(context.get('certs') or [])
        if not package or not certs:
            return False, "Beacon can't tell which app this is."
        origins = list(self.h.saved_origins(meta))
        web = context.get('web_domain')
        if not origins:
            if meta.kind == 'payment':
                return True, 'card'  # a card saved with no site: any app, behind the owner's combined question
            return False, 'This saved item has no app or site.'
        app_origins = [o for o in origins if o.startswith('android://')]
        site_origins = [o for o in origins if not o.startswith('android://')]
        if not web and app_origins:
            if 'android://' + package.lower() not in app_origins:
                return False, 'This saved login is for another app.'
            pinned = self.sidecar.item(meta.id)
            if pinned.get('package') and pinned['package'] != package:
                return False, 'This saved login is for another app.'
            if pinned.get('certs') and sorted(pinned['certs']) != certs:
                return False, "This app isn't the one the login was saved in."
            return True, 'app'
        if web and site_origins:
            origin = self._origin_of(context)
            if origin is None or origin not in site_origins:
                return False, 'This saved login is for another site.'
            if package == CHROME:
                if context.get('source') not in TRUSTED_SOURCES:
                    return False, "This isn't the phone's own Chrome."
                pinned = self.sidecar.browser(CHROME, certs)
                return (True, 'site') if sorted(pinned) == certs else (False, "This browser isn't the phone's Chrome.")
            if self._asset_links(urlsplit(origin).hostname, package, certs, fetch):
                return True, 'site-app'
            return False, "This app isn't linked to that site."
        if not web and site_origins and package != CHROME:
            for site in site_origins:
                if self._asset_links(urlsplit(site).hostname, package, certs, fetch):
                    return True, 'app-site'
            return False, 'This saved login is for a site, not this app.'
        return False, 'This saved login is for another app.'

    def _asset_links(self, domain, package, certs, fetch=True):
        """Digital Asset Links: the site lists this app (package + a signing certificate) for sign-ins. Cached 10 minutes."""
        key, now = (domain, package, tuple(certs)), self.clock()
        with self._lock:
            cached = self._dal.get(key)
            if cached and now - cached[1] < DAL_SECONDS:
                return cached[0]
        if not fetch:
            return False
        verdict = False
        try:
            statements = self.dal_fetch(domain)
            fingerprints = {':'.join(c[i:i + 2] for i in range(0, 64, 2)).upper() for c in certs}
            for s in statements if isinstance(statements, list) else []:
                target = s.get('target') if isinstance(s, dict) else None
                if (isinstance(target, dict) and target.get('namespace') == 'android_app' and target.get('package_name') == package
                        and any(r in DAL_RELATIONS for r in s.get('relation') or [])
                        and fingerprints & {str(f).upper() for f in target.get('sha256_cert_fingerprints') or []}):
                    verdict = True
                    break
        except Exception:  # health: allow BLE001 -- an unreadable statement list links nothing
            verdict = False
        with self._lock:
            self._dal[key] = (verdict, now)
        return verdict

    # -- channel events ----------------------------------------------------------------------------------------------
    def handler(self, session, owner=False):
        """The on_event callback for one channel session. owner=True for a setup session the owner holds."""
        def on_event(message):
            t = message['t']
            if t == 'release_request':
                self._release(session, message, owner)
            elif t in ('filled', 'fill_failed', 'code_filled'):
                self._settle(message)
            elif t == 'save':
                self._captured(message, owner)
            elif t == 'fill_context':
                if message['context'].get('package') == CHROME and message['context'].get('source') in TRUSTED_SOURCES:
                    self.sidecar.browser(CHROME, message['context'].get('certs'))  # first sight pins Chrome's certificate
                if owner:
                    self._offer_later(session, message['context'])
        return on_event

    def _settle(self, message):
        with self._lock:
            waiting = self._results.get(message['arm_id'])
        if waiting is not None:
            waiting[1] = message.get('fields', 0) if message['t'] != 'fill_failed' else 0
            waiting[2] = message.get('reason')
            waiting[3] = message.get('targets')  # code_filled: the fields the helper filled itself (§6.4), never values
            waiting[0].set()

    def _release(self, session, message, owner):
        with self._lock:
            armed = self._armed.pop(message['arm_id'], None)  # one release per arm, whichever entry was picked
        item = armed['items'].get(message['item_id']) if armed else None
        values = None
        try:
            values = self._values_for(armed, item, message, owner) if item else None
        except Exception:  # health: allow BLE001 -- any failure is a deny; the text may hold a value and is never kept
            log.info('agent phone vault: release refused')
            values = None
        try:
            if values:
                session.answer('release', req=message['req'], values=values)
            else:
                session.answer('deny', req=message['req'])
        finally:
            if values:
                for key in list(values):
                    values[key] = None

    def _values_for(self, armed, item, message, owner):
        context, now = message['context'], self.clock()
        if now > armed['until'] or not set(message['roles']) <= set(item['roles']):
            return None
        if (context.get('package') != armed['package'] or sorted(context.get('certs') or []) != armed['certs']
                or context.get('web_domain') != armed['web']):
            return None
        # The focused field must be one of the arm's own form fields that this item fills. Not the exact field: Android answers
        # from the fill request of whichever field of the form gained focus first after the refresh (gate B5, 17 Nov).
        focused = {_view(f['id']) for f in context.get('fields') or [] if f.get('focused')}
        form = {view for view, role in armed.get('form', {}).items() if role in item['roles']}
        if form and not focused & form:
            return None
        roles = set(message['roles'])
        held = item.get('held')
        if held is not None:  # a generated or owner-typed value Beacon holds: no vault item yet
            pool = {k: v for k, v in held.values.items() if v}
            values = {r: pool[r] for r in roles if r in pool}
        else:
            values = self._resolve(item, context, roles, owner)
        if not values:
            return None
        if item.get('pin') and item.get('handle'):  # an approved first sight: the owner's setup, or their yes on the action's card
            self.sidecar.update(item['handle'], package=context['package'], certs=sorted(context['certs']))
        for role, value in values.items():
            if role in SECRET_ROLES:
                if self.masks.register(value, context['package']) is False:
                    # The record of this fill could not be written: the stop would not know a filled screen is on the phone and could save it
                    # in a snapshot. Nothing is released (a card's one yes is spent: the agent asks again).
                    log.info('agent phone vault: no release, the fill record could not be written')
                    return None
                self.h.register(value)
        log.info('agent phone vault: released %d field(s) of a %s item', len(values), item['item_kind'])
        return values

    def _unpinned_app(self, meta):
        """An app login whose app certificates Beacon has not pinned yet (saved outside the phone)."""
        return (any(o.startswith('android://') for o in self.h.saved_origins(meta))
                and not self.sidecar.item(meta.id).get('certs'))

    def _resolve(self, item, context, roles, owner):
        backend = self.source.backend(item['handle'])
        if backend is None:
            return None
        with backend.fill_context():
            meta = backend.get_meta(item['handle'])  # read again inside the context: it must still bind this context
            if meta is None or meta.kind != item['item_kind'] or not self.bind(meta, context)[0]:
                return None
            if self._unpinned_app(meta) and not item.get('pin'):
                return None  # no silent first pin by the agent
            if meta.kind == 'payment' and not owner:
                with self._lock:
                    grant = self._grants.pop((context['package'], meta.id), None)  # one card fill per yes
                if grant is None or self.clock() > grant['until'] or grant['certs'] != sorted(context['certs']):
                    return None
            if item['kind'] == 'otp':
                code = backend.resolve_otp(meta.id) if item.get('code') is None else item['code']
                return {'otp': code} if code and 'otp' in roles else None
            if meta.kind == 'login':
                out = {}
                if 'username' in roles and meta.identifier:
                    out['username'] = meta.identifier
                if 'password' in roles:
                    out['password'] = backend.resolve_password(meta.id)
                if 'otp' in roles and meta.has_otp:
                    out['otp'] = backend.resolve_otp(meta.id) or ''
                return {k: v for k, v in out.items() if v}
            secret = backend.resolve_secret(meta.id)
            if meta.kind == 'payment':
                month, year = str(secret.get('exp_month') or '').zfill(2), str(secret.get('exp_year') or '')
                if len(year) == 2:
                    year = '20' + year
                secret = dict(secret, exp_month=month, exp_year=year, exp_date=f'{month}/{year[-2:]}' if year else '')
            return {r: str(secret[r]) for r in roles if secret.get(r)}

    # -- fills -------------------------------------------------------------------------------------------------------
    def _context(self, session):
        context = session.request('context').get('context')
        if not context or not any(f.get('focused') for f in context.get('fields') or []):
            return None
        return context

    def _arm(self, session, context, kind, items, *, focus, wait):
        """Arms one fill with one dropdown entry per item (contract rev 2). Items are dicts of handle, item_kind, label, roles,
        held, code and pin. Returns (arm_id, waiting, reply), or None when no field on the screen takes any of them."""
        present = {f['role'] for f in context['fields']}
        wire, kept = [], {}
        for item in items[:16]:
            roles = [r for r in item['roles'] if r in present]
            if roles:
                item_id = 'item_' + secrets.token_urlsafe(12)
                kept[item_id] = dict(item, kind=kind, roles=roles)
                wire.append({'item_id': item_id, 'label': item['label'][:120], 'roles': roles})
        if not wire:
            return None
        arm_id = 'arm_' + secrets.token_urlsafe(12)
        waiting = [threading.Event(), None, None, None]
        with self._lock:
            self._armed.clear()  # one arm at a time, as on the helper
            self._armed[arm_id] = {'package': context['package'], 'certs': sorted(context['certs']), 'web': context.get('web_domain'),
                                   'focus': focus, 'until': self.clock() + ARM_SECONDS, 'items': kept,
                                   'form': {_view(f['id']): f['role'] for f in context['fields']}}
            if wait:
                self._results[arm_id] = waiting
        try:
            reply = session.request('arm', arm_id=arm_id, kind=kind, package=context['package'], web_domain=context.get('web_domain'),
                                    items=wire, ttl_ms=ARM_SECONDS * 1000)
        except Exception:
            with self._lock:
                self._armed.pop(arm_id, None)
                self._results.pop(arm_id, None)
            raise
        return arm_id, waiting, reply

    def _step(self, context, at):
        """Records a sign-in step in this app or site: a code screen after it counts codes from `at` on."""
        key = _step_key(context)
        with self._lock:
            self._steps[key] = max(at, self._steps.get(key, at))

    def _item_step(self, meta, since):
        """The step a temporary password or a sign-in link must come after (the codes' rule, EMU-JOIN with Root, 17 Nov): the
        latest of Beacon's own fills and codes in the item's app or site, the last password or link it took for this item (each
        is used once), and since (the caller's submit, on this vault's clock). None: no step, so nothing from a notification
        counts."""
        hosts = {h for o in self.h.saved_origins(meta) for h in code_hosts(o)}
        origin = meta.origin or ''
        app = self.sidecar.item(meta.id).get('package') or (origin[len('android://'):] if origin.startswith('android://') else None)
        with self._lock:
            steps = [at for (package, site), at in self._steps.items() if (app and package == app and not site) or site in hosts]
            used = self._used.get(meta.id)
        steps += [at for at in (used, since) if isinstance(at, (int, float)) and not isinstance(at, bool)]
        return max(steps) if steps else None

    def _age(self, step):
        return max(1, min(HOLD_SECONDS * 1000, int((self.clock() - step) * 1000)))

    def _arm_and_pick(self, session, driver, context, *, kind, item):
        focus = next((f['id'] for f in context['fields'] if f.get('focused')), None)
        started = self.clock()
        armed = self._arm(session, context, kind, [item], focus=focus, wait=True)
        if armed is None:
            return _say('No field here takes that saved item.', 'no_field')
        arm_id, waiting, reply = armed
        filled = 0
        try:
            if reply['t'] != 'ack':
                return _say("The phone's helper couldn't offer the saved item.", 'helper_refused')
            if kind != 'otp':
                self._step(context, started)  # the form this fills is submitted next; a code it asks for comes after this
            self._refresh()
            if not driver.pick_entry(context, item['label'], item['roles']):  # the roles: the only fields it may empty first
                session.request('disarm', arm_id=arm_id)
                return _say("Beacon's entry didn't show under the field. Tap the field and try again.", 'no_entry')
            if not waiting[0].wait(RESULT_SECONDS):
                return _say("The phone didn't confirm the fill.", 'fill_timeout')
            filled = int(waiting[1] or 0)
            if not filled:
                return _say('Nothing was filled.', 'fill_refused' if waiting[2] == 'denied' else 'fill_failed')
            if kind == 'otp':
                self._step(context, self.clock())  # a code is used once: the next code screen needs a newer one
            return {'success': True, 'filled_fields': filled, 'kind': item['item_kind'], 'app': self._app(context)}
        finally:
            with self._lock:
                self._armed.pop(arm_id, None)
                self._results.pop(arm_id, None)
            if not filled:
                # Android keeps this arm's entry for the screen after the helper is disarmed: a later tap on another field
                # showed it again, the popup took the screen and the agent's next actions were refused until it pressed Back
                # (EMU-JOIN fu2 journey, 17 Nov). Resetting Android's autofill drops it; a fill that worked leaves Android be.
                self._refresh()

    def _refresh(self):
        if self.refresh is not None:
            try:
                self.refresh()
            except Exception:  # health: allow BLE001 -- a failed refresh only means the entry may not show; the tap says so
                log.info('agent phone vault: autofill refresh failed')

    def _app(self, context):
        return context.get('web_domain') or self.app_label(context['package'])

    def _backend_meta(self, handle):
        backend = self.source.backend(handle)
        if backend is None:
            return None, None, _say('No saved item with that handle. Use agent_phone action=vault_list.', 'not_found')
        if backend.needs_unlock and not backend.is_unlocked() and not self._unlock(backend):
            return backend, None, _say(f'Unlock {backend.display_name} in Beacon first.', 'unlock_required')
        try:
            meta = backend.get_meta(handle)
        except self.h.UnlockRequired:
            return backend, None, _say(f'Unlock {backend.display_name} in Beacon first.', 'unlock_required')
        if meta is None:
            return backend, None, _say('No saved item with that handle. Use agent_phone action=vault_list.', 'not_found')
        return backend, meta, None

    def _unlock(self, backend):
        """Hermes' own unlock prompt (vault.unlock_prompt, answered in Beacon) when this turn has a viewer; never in setup."""
        if self.source.owner_only or not self.h.can_prompt_here():
            return False
        try:
            return bool(json.loads(self.h.unlock(backend.name)).get('success'))
        except Exception:  # health: allow BLE001 -- a failed unlock is the plain refusal
            return False

    def _pin_question(self, meta, context):
        """An app login Beacon never pinned: in an agent turn the owner says yes once, inside the action's own approval card
        (L3 folds the question in and calls again with pin_approved=True). Never a silent first pin by the agent."""
        app = self._app(context)
        return _say(f'The owner approves using the {meta.label} login in {app} once.', 'pin_approval_needed',
                    question=f'Use the {meta.label} login in {app}?')

    @_link_errors
    def fill(self, handle, session, driver, *, task_id='default', owner=False, shop=None, amount=None, pin_approved=False,
             session_id=None):
        """Fills the focused field (and its form) from a vault item. driver.pick_entry(context, label) taps the field, waits
        for Beacon's dropdown entry and taps it (S9). Counts only come back. ``session_id`` is the chat session of the call (not
        the helper channel ``session``): a card's purchase grant carries it."""
        backend, meta, refusal = self._backend_meta(handle)
        if refusal:
            return refusal
        context = self._context(session)
        if context is None:
            return _say('Focus the field to fill first.', 'no_field')
        ok, why = self.bind(meta, context)
        if not ok:
            return _say(why, 'not_bound')
        if meta.kind not in ITEM_ROLES:
            return _say('That saved item cannot be filled here.', 'not_fillable')
        unpinned = self._unpinned_app(meta)
        yes_key = (task_id, meta.id, context['package'], tuple(sorted(context['certs'])))
        if unpinned and not owner:
            if pin_approved:
                with self._lock:
                    self._pin_yes[yes_key] = self.clock() + PIN_YES_SECONDS
            elif not self._yes_stands(yes_key):
                return self._pin_question(meta, context)
        grant = None
        if meta.kind == 'payment' and not owner:
            refusal, grant = self._card_question(meta, context, task_id, shop, amount, session_id)
            if refusal:
                return refusal
        label = f'{meta.label} · {meta.identifier}' if meta.kind == 'login' and meta.identifier else meta.label
        result = self._arm_and_pick(session, driver, context, kind=meta.kind,
                                    item={'handle': meta.id, 'item_kind': meta.kind, 'label': label, 'roles': ITEM_ROLES[meta.kind],
                                          'held': None, 'code': None, 'pin': unpinned})
        if result.get('success'):
            with self._lock:
                self._pin_yes.pop(yes_key, None)  # used: the item is pinned to this app now
            if grant is not None:
                # The owner's order pass exists only for a card that really went in: a yes whose fill never happened (no entry,
                # a timeout, nothing filled) leaves nothing behind that the agent could spend on whatever card the shop has.
                self.on_card_approved(**grant)  # L3 records the bound purchase grant: this card, this app, this amount, this task
        elif grant is not None:
            with self._lock:
                self._grants.pop((context['package'], meta.id), None)  # the release never used this yes
        return result

    def _yes_stands(self, key):
        now = self.clock()
        with self._lock:
            for old in [k for k, until in self._pin_yes.items() if until <= now]:
                self._pin_yes.pop(old, None)
            return key in self._pin_yes

    def _card_question(self, meta, context, task_id, shop, amount, session_id=None):
        """Hermes' own card consent, worded as one combined question (§5.3). (refusal, grant): a yes keeps the vault's own grant
        for the one release now; the bound purchase grant for this app is returned for ``fill`` to record once the card is in."""
        now = self.clock()
        with self._lock:
            left = self._declined.get(task_id, 0) - now
        if left > 0:
            return _say('The owner declined or missed this payment a moment ago. Nothing was filled. Ask them first.', 'payment_declined'), None
        where = context.get('web_domain') or shop or self._app(context)  # a web fill names the site, not the browser
        message = f'Pay {amount} at {where} with {meta.label}?' if amount else f'Fill {meta.label} in {where}?'
        answer = self.h.consent(message, ('The agent wants to enter your saved card on its phone. The card number and security '
                                          'code never enter the conversation. Approve only if you intend to pay here.'),
                                surface='vault-payment', title='Confirm payment card fill?')
        if answer != 'accept':
            with self._lock:
                for task in [t for t, until in self._declined.items() if until <= now]:
                    self._declined.pop(task, None)
                self._declined[task_id] = now + DECLINE_SECONDS
            return _say('The owner did not confirm this card. Nothing was filled. Do not retry; ask them instead.', 'payment_declined'), None
        grant = {'package': context['package'], 'handle': meta.id, 'amount': amount, 'certs': sorted(context['certs']), 'task': task_id,
                 'session': session_id, 'web_domain': context.get('web_domain')}  # every fill that has a site carries it (R2-7)
        with self._lock:
            self._grants[(context['package'], meta.id)] = {k: v for k, v in grant.items() if k not in ('task', 'session', 'web_domain')} | {'until': now + 300}
        return None, grant

    # -- codes ------------------------------------------------------------------------------------------------------
    @_link_errors
    def code(self, session, driver, *, handle=None, mail_packages=(), task_id='default', pin_approved=False, ask_owner=None,
             since=None):
        """A one-time code for the focused code field, in this order: the item's authenticator key; a notification from the
        site's own app or e-mail address, which the helper fills itself; Hermes' code prompt when a viewer is attached;
        otherwise the owner is asked on the phone: ask_owner(app, label) -> code or None (L3's
        agent_phone_approvals.ask_owner_code, the record's answer read once within 120 s), or a vault_code notice without it.
        A code the owner typed fills through the same held-code path and binding as any other.

        A notification counts only if it came after the sign-in step that led to this code screen, and the newest such one
        wins (L3 run 5 filled an earlier sign-in's code). The step is the later of Beacon's own last fill in this app or site
        (or the last code it filled there) and since: the caller's submit, a time on this vault's clock (time.monotonic())
        taken right before the tap. With no step at all, no notification counts and the owner is asked."""
        context = self._context(session)
        if context is None or not any(f['role'] == 'otp' and f.get('focused') for f in context['fields']):
            return _say('Focus the code field first.', 'no_field')
        meta = None
        if handle:
            _, meta, refusal = self._backend_meta(handle)
            if refusal:
                return refusal
            ok, why = self.bind(meta, context)
            if not ok:
                return _say(why, 'not_bound')
            unpinned = self._unpinned_app(meta)
            if unpinned and not pin_approved:
                return self._pin_question(meta, context)
            if meta.has_otp:
                result = self._arm_and_pick(session, driver, context, kind='otp',
                                            item={'handle': meta.id, 'item_kind': meta.kind, 'label': meta.label, 'roles': ('otp',),
                                                  'held': None, 'code': None, 'pin': unpinned})
                if result.get('success'):
                    return dict(result, source='authenticator')
        label = meta.label if meta is not None else self._app(context)
        senders = {'packages': [], 'hosts': [], 'mail_packages': [p for p in mail_packages][:16]}
        if meta is not None:
            senders['hosts'] = [h for o in self.h.saved_origins(meta) for h in code_hosts(o)][:16]
            pinned = self.sidecar.item(meta.id).get('package')
            if pinned or (meta.origin or '').startswith('android://'):
                senders['packages'] = [pinned or meta.origin[len('android://'):]]
        elif not context.get('web_domain'):
            senders['packages'] = [context['package']]
        else:
            senders['hosts'] = code_hosts(self._origin_of(context) or '')
        with self._lock:
            step = self._steps.get(_step_key(context))
        if isinstance(since, (int, float)) and not isinstance(since, bool):
            step = since if step is None else max(step, since)
        if step is None:
            log.info('agent phone vault: no sign-in step for this code screen; no notification counts')
        elif senders['packages'] or senders['hosts']:
            if self.masks.mark_filled(context['package']) is False:
                # The helper puts the code in itself, so the host never sees it: the app's record is what tells the stop a filled screen may
                # be on the phone. Without it nothing is armed (R4-4).
                log.info('agent phone vault: no code armed, the fill record could not be written')
                return _say('Beacon could not record this fill on the phone, so no code was put in. Nothing was filled.', 'no_record')
            # The helper takes the newest code posted at most max_age_ms ago (contract §6.4): only codes since the step.
            max_age_ms = max(1, min(HOLD_SECONDS * 1000, int((self.clock() - step) * 1000)))
            arm_id = 'arm_' + secrets.token_urlsafe(12)
            waiting = [threading.Event(), None, None, None]
            with self._lock:
                self._results[arm_id] = waiting
            try:
                reply = session.request('arm_code', arm_id=arm_id, package=context['package'], label=label[:120], senders=senders,
                                        max_age_ms=max_age_ms, ttl_ms=ARM_SECONDS * 1000)
                if reply['t'] == 'code_result' and reply['found']:
                    self._refresh()  # as after any arm: the next tap asks the helper again
                if reply['t'] == 'code_result' and reply['found'] and driver.pick_entry(context, label, ('otp',)):
                    if waiting[0].wait(RESULT_SECONDS) and waiting[1]:
                        self._step(context, self.clock())  # a code is used once: the next code screen needs a newer one
                        # The host never sees this code: the read guard masks the field it went into instead (fu1).
                        if self.masks.watch_fields(context['package'], waiting[3] or []) is False:
                            # The code is already in and there is nothing to refuse; the app is masked in this process, but a restart would lose it.
                            log.warning('agent phone vault: the code app could not be recorded; a Hermes restart would show the code')
                        return {'success': True, 'filled_fields': int(waiting[1]), 'kind': 'otp', 'source': 'notification',
                                'app': self._app(context)}
            finally:
                with self._lock:
                    self._results.pop(arm_id, None)
        prompt = self.h.code_prompt()
        if prompt is not None and self.h.can_prompt_here():
            typed = (prompt(self._app(context), '') or '').strip().replace(' ', '').replace('-', '')
            if typed:
                return self._fill_typed_code(session, driver, context, label, typed)
        if ask_owner is not None:
            try:
                typed = ask_owner(self._app(context), label)
            except Exception:  # health: allow BLE001 -- a failed ask is a plain refusal; its text may quote the code
                typed = None
            typed = (typed or '').strip().replace(' ', '').replace('-', '') if isinstance(typed, str) else ''
            if not typed:
                return _say(f'The owner did not enter the code for {label}. Nothing was filled.', 'code_declined')
            return self._fill_typed_code(session, driver, context, label, typed)
        self.notify('vault_code', {'app': self._app(context), 'label': label, 'line': f'Enter the code for {label}.', 'reason': 'vault.code'})
        return _say(f'The owner is asked to enter the code for {label} on the phone.', 'needs_owner')

    def _fill_typed_code(self, session, driver, context, label, typed):
        """A code the owner typed (Hermes' prompt or Beacon's record): held in memory for this one fill, then forgotten."""
        held = Held('code', {'otp': typed}, context, None, label, self.wall)
        try:
            result = self._arm_and_pick(session, driver, context, kind='otp',
                                        item={'handle': None, 'item_kind': 'otp', 'label': label, 'roles': ('otp',),
                                              'held': held, 'code': None, 'pin': False})
        finally:
            held.forget()
        return dict(result, source='owner') if result.get('success') else result

    # -- logins the agent has no item for ----------------------------------------------------------------------------
    @_link_errors
    def save_login(self, session, driver, *, label='', task_id='default'):
        """Hermes' own save prompt (vault.save_login, answered on Beacon's masked card), then the fill. Needs a viewer."""
        context = self._context(session)
        if context is None:
            return _say('Focus the login field first.', 'no_field')
        origin = self._origin_of(context)
        if origin is None:
            return _say("Beacon can't tell which site this is.", 'no_origin')
        site = label.strip() or context.get('web_domain') or self.app_label(context['package'])
        prompt = self.h.save_prompt()
        if prompt is None or not self.h.can_prompt_here() or self.source.owner_only:
            self.notify('needs_you', {'app': self._app(context), 'site': site, 'line': f'Sign in to {site} on the phone.', 'reason': 'vault.sign_in'})
            return _say('Ask the owner to sign in on the phone. They have been told.', 'prompt_unavailable')
        answer = prompt(origin, site)
        try:
            if not answer or not answer.get('password') or not answer.get('identifier'):
                return _say('The owner chose not to save a login here. Do not ask again this turn.', 'save_declined')
            identifier = str(answer['identifier']).strip()
            meta = self.source.local_store().add_item('login', site, {'identifier_type': identifier_type(identifier),
                                                                    'identifier': identifier, 'password': str(answer['password'])},
                                                     origin=origin)
        except Exception:  # health: allow BLE001 -- the store's message may quote the value; a fixed line instead
            return _say('The login could not be saved.', 'save_failed')
        finally:
            if answer:
                answer.clear()
        pins = {'package': context['package'], 'certs': sorted(context['certs'])} if origin.startswith('android://') else {}
        self.sidecar.update(meta.id, status='ok', saved_on_phone=True, **pins)
        filled = self.fill(meta.id, session, driver, task_id=task_id)
        return {'success': True, 'handle': meta.id, 'identifier': identifier, 'fill': filled}

    # -- sign-ups and password resets --------------------------------------------------------------------------------
    @_link_errors
    def new_login(self, session, driver, *, identifier, label, replaces=None, pending=None):
        """After the owner's yes to "Create a login for <label> with <identifier>?" (or "Change the <label> password?"):
        a strong password made here fills the new-password fields. Android raises no save for the helper's own values (S11),
        so settle_signup() commits it from the result the screen shows. pending= retries a sign-up that failed once with
        the same held password (the site may already know it)."""
        context = self._context(session)
        if context is None or not any(f['role'] == 'new_password' for f in context['fields']):
            return _say('Focus the new-password field first.', 'no_field')
        origin = self._origin_of(context)
        if origin is None:
            return _say("Beacon can't tell which site this is.", 'no_origin')
        if replaces is not None:
            backend, old, refusal = self._backend_meta(replaces)
            if refusal:
                return refusal
            if backend.name != 'local':
                return _say(f'Update it in {backend.display_name}. A new password can be kept in Hermes\' vault instead.', 'manager_item')
        if pending is not None:
            with self._lock:
                held = self._held.get(pending)
            if held is None or held.kind != 'new_login' or held.origin != origin:
                return _say('That sign-up is no longer waiting. Start it again.', 'not_pending')
        else:
            held = Held('new_login', {'username': identifier, 'new_password': generate_password()}, context, origin, label, self.wall)
            held.old_handle = replaces
            self._hold(held)
        result = self._arm_and_pick(session, driver, context, kind='new_login',
                                    item={'handle': None, 'item_kind': 'login', 'label': f'{label} · {identifier}',
                                          'roles': ('username', 'new_password'), 'held': held, 'code': None, 'pin': False})
        if not result.get('success'):
            if pending is None:
                self._drop(held.id)  # nothing was filled: the generated password has no account behind it
            return result
        return dict(result, pending=held.id, next='Submit the form, then report what the screen shows (sign-up result).')

    def settle_signup(self, pending, outcome):
        """outcome from submit_outcome(): success commits; unknown commits marked "not confirmed" (the owner already said yes,
        and a new account's only password must not be dropped); failure keeps it for one retry, then drops it."""
        with self._lock:
            held = self._held.get(pending)
        if held is None or held.kind != 'new_login':
            return _say('That sign-up is no longer waiting. Nothing was saved.', 'not_pending')
        if outcome == 'failure':
            held.tries += 1
            if held.tries > 1:
                self._drop(held.id)
                return {'success': False, 'saved': False, 'retry': False}
            return {'success': False, 'saved': False, 'retry': True}
        if outcome not in ('success', 'unknown'):
            return _say('Say success, failure or unknown.', 'bad_outcome')
        try:
            meta = self._commit(held, status='ok' if outcome == 'success' else 'not_confirmed')
        finally:
            self._drop(held.id)
        if meta is None:
            return _say('The login could not be saved.', 'save_failed')
        out = {'success': True, 'saved': True, 'handle': meta.id}
        if outcome == 'unknown':
            out['not_confirmed'] = True
        return out

    def login_result(self, handle, outcome):
        """After a fill and submit: a failure marks the item "didn't work" and tells the owner; a success confirms the item and
        finishes a replacement (the old item goes once the new one signed in)."""
        _, meta, refusal = self._backend_meta(handle)
        if refusal:
            return refusal
        if outcome == 'failure':
            self.sidecar.update(handle, status='didnt_work')
            self.notify('needs_you', {'label': meta.label, 'line': f"The {meta.label} login didn't work.", 'reason': 'vault.login_failed'})
            return {'success': False, 'status': 'login_failed'}
        if outcome == 'success':
            entry = self.sidecar.update(handle, status='ok', last_success=datetime.now(timezone.utc).isoformat(timespec='seconds'))
            old = entry.get('replaces')
            if old and self.source.local_store().remove_item(old):
                self.sidecar.drop(old)
            return {'success': True, 'status': 'ok'}
        return {'success': True, 'status': 'unknown'}

    # -- captured saves (Android's save, a temporary password) --------------------------------------------------------
    def _captured(self, message, owner):
        context, values = message['context'], message['values']
        identifier = (values.get('username') or '').strip()
        password = values.get('password') or values.get('new_password')
        origin = self._origin_of(context)
        if not identifier or not password or origin is None:
            log.info('agent phone vault: a save without an identifier, password or origin was dropped')
            self.notify('saved', {'line': "Couldn't save that login: the app didn't give its username.", 'reason': 'vault.saved_no_username'})
            return
        label = context.get('web_domain') or self.app_label(context['package'])
        held = Held('save', {'username': identifier, 'password': password}, context, origin, label, self.wall)
        same = self._same_logins(origin, identifier)
        if owner and not same:
            try:
                meta = self._commit(held, status='ok')
            finally:
                held.forget()
            if meta is not None:
                self.notify('saved', {'label': label, 'identifier': identifier, 'line': f'Saved: {label} · {identifier}', 'reason': 'vault.saved'})
            return
        self._hold(held)
        if same:
            held.old_handle = same[0]
            self.notify('vault_update', {'held': held.id, 'label': label, 'identifier': identifier,
                                         'line': f'Replace the saved {label} login?', 'reason': 'vault.replace'})
        else:
            self.notify('vault_save', {'held': held.id, 'label': label, 'identifier': identifier, 'line': f'Save the new {label} login?', 'reason': 'vault.save_new'})

    @_link_errors
    def open_link(self, session, opener, *, handle, mail_packages=(), since=None):
        """A sign-in link the site e-mailed (contract rev 3): the helper hands it over (note_link), Beacon checks it again, and
        opener(url) opens it on the phone: L3 runs `am start -a android.intent.action.VIEW -d <url>` through the private adb
        wrapper (Android 16 blocks the helper from starting it). The address never reaches the model, a log or a result.
        As for codes: only a link that came after the sign-in step (``_item_step``, ``since`` = the caller's submit), the
        newest, and once."""
        _, meta, refusal = self._backend_meta(handle)
        if refusal:
            return refusal
        senders = {'packages': [], 'hosts': [h for o in self.h.saved_origins(meta) for h in code_hosts(o)][:16],
                   'mail_packages': list(mail_packages)[:16]}
        if not senders['hosts']:
            return _say('This saved login has no site to match a link from.', 'no_site')
        step = self._item_step(meta, since)
        if step is None:
            return _say('Ask the site for the sign-in link on the phone first; only a link sent after that is used.', 'no_step')
        reply = session.request('note_link', senders=senders, max_age_ms=self._age(step))
        if reply['t'] != 'note_link_result' or not reply['found']:
            return _say('No sign-in link from that site arrived on the phone.', 'not_found')
        taken = self.clock()  # used once, from when it was taken, but only once it opened (a failed open can be tried again)
        url, reply['url'] = reply['url'], None
        parts = urlsplit(url)
        host = (parts.hostname or '').lower()
        if parts.scheme != 'https' or not any(host == h or host.endswith('.' + h) for h in senders['hosts']):
            return _say("That link isn't from the site's own address. Nothing was opened.", 'link_mismatch')
        try:
            opened = bool(opener(url))
        except Exception:  # health: allow BLE001 -- the opener's message may quote the address; a fixed line instead
            opened = False
        if not opened:
            return _say("The phone didn't open the link.", 'open_failed')
        with self._lock:
            self._used[meta.id] = max(taken, self._used.get(meta.id, taken))
        return {'success': True, 'opened': 'link'}

    @_link_errors
    def hold_temporary_password(self, session, *, handle, mail_packages=(), since=None):
        """A temporary password the site e-mailed: the helper reads it (note_password); Beacon holds it until the owner's yes.
        As for codes: only one that came after the sign-in step (``_item_step``), the newest, and once."""
        _, meta, refusal = self._backend_meta(handle)
        if refusal:
            return refusal
        senders = {'packages': [], 'hosts': [h for o in self.h.saved_origins(meta) for h in code_hosts(o)][:16],
                   'mail_packages': list(mail_packages)[:16]}
        step = self._item_step(meta, since)
        if step is None:
            return _say('Ask the site for a new password on the phone first; only one sent after that is used.', 'no_step')
        reply = session.request('note_password', senders=senders, max_age_ms=self._age(step))
        if reply['t'] != 'note_password_result' or not reply['found']:
            return _say('No new password from that site arrived on the phone.', 'not_found')
        with self._lock:
            self._used[meta.id] = self.clock()  # used once: the next one must be newer
        context = {'package': '', 'certs': [], 'web_domain': None, 'web_scheme': None, 'fields': []}
        held = Held('save', {'username': meta.identifier or '', 'password': reply['value']}, context, meta.origin, meta.label, self.wall)
        reply['value'] = None
        held.old_handle = meta.id
        self._hold(held)
        self.notify('vault_save', {'held': held.id, 'label': meta.label, 'identifier': meta.identifier,
                                   'line': f'Save the new {meta.label} login?', 'reason': 'vault.save_new'})
        return {'success': True, 'asked_owner': True}

    def decide_save(self, held_id, yes):
        """The owner's answer to a vault_save / vault_update record: yes commits (replacing the old item), no forgets it."""
        with self._lock:
            held = self._held.get(held_id)
        if held is None or held.kind not in ('save',):
            return _say('That login is no longer waiting. Nothing was saved.', 'not_pending')
        if not yes:
            self._drop(held_id)
            return {'success': True, 'saved': False}
        try:
            meta = self._commit(held, status='ok')
            if meta is not None and held.old_handle and held.old_handle != meta.id:
                if self.source.local_store().remove_item(held.old_handle):
                    self.sidecar.drop(held.old_handle)
        finally:
            self._drop(held_id)
        if meta is None:
            return _say('The login could not be saved.', 'save_failed')
        self.notify('saved', {'label': held.label, 'line': f'Saved: {held.label}', 'reason': 'vault.saved_label'})
        return {'success': True, 'saved': True, 'handle': meta.id}

    def pending(self):
        """What waits for the owner: labels and kinds only."""
        with self._lock:
            return [{'held': h.id, 'kind': h.kind, 'label': h.label} for h in self._held.values()]

    def _same_logins(self, origin, identifier):
        try:
            items = self.source.local_store().list_items()
        except Exception:  # health: allow BLE001 -- an unreadable store has no duplicates to replace
            return []
        return [m.id for m in items if m.kind == 'login' and m.origin == origin and (m.identifier or '') == identifier]

    def _commit(self, held, status):
        values = held.values
        identifier = (values.get('username') or '').strip()
        password = values.get('password') or values.get('new_password')
        if not identifier or not password:
            return None
        try:
            meta = self.source.local_store().add_item('login', held.label, {'identifier_type': identifier_type(identifier),
                                                                           'identifier': identifier, 'password': password},
                                                     origin=held.origin)
        except Exception:  # health: allow BLE001 -- the store's message may quote the value; it is never passed on
            log.info('agent phone vault: a held login could not be written')
            return None
        context = held.context
        pins = {'package': context['package'], 'certs': sorted(context['certs'])} if context.get('package') and not context.get('web_domain') else {}
        self.sidecar.update(meta.id, status=status, replaces=held.old_handle if held.kind == 'new_login' else None,
                            saved_on_phone=True, **pins)
        log.info('agent phone vault: a login was saved (%s)', status)
        return meta

    def _hold(self, held):
        with self._lock:
            while len(self._held) >= MAX_HELD:
                self._drop(next(iter(self._held)))
            self._held[held.id] = held
            timer = threading.Timer(HOLD_SECONDS, self._drop, args=(held.id,))
            timer.daemon = True
            self._timers[held.id] = timer
        timer.start()

    def _drop(self, held_id):
        with self._lock:
            held = self._held.pop(held_id, None)
            timer = self._timers.pop(held_id, None)
        if timer is not None:
            timer.cancel()
        if held is not None:
            held.forget()

    def forget(self):
        """Session end: every held value and arm goes."""
        with self._lock:
            ids = list(self._held)
            self._armed.clear()
            self._grants.clear()
        for held_id in ids:
            self._drop(held_id)

    # -- setup sessions: the owner's items in the app's dropdown -----------------------------------------------------
    def _offer_later(self, session, context):
        if not self._offering.acquire(blocking=False):
            return  # an offer is being armed; the next fill request offers again
        threading.Thread(target=self._offer, args=(session, context), name='beacon-agent-phone-offer', daemon=True).start()

    def _offer(self, session, context):
        try:
            roles = {f['role'] for f in context.get('fields') or []}
            wanted = 'payment' if roles & set(ITEM_ROLES['payment']) else 'login' if roles & {'username', 'password'} else None
            if wanted is None:
                return
            form = {_view(f['id']) for f in context.get('fields') or []}
            with self._lock:
                live = any(a['package'] == context['package'] and a.get('web') == context.get('web_domain')
                           and set(a.get('form', {})) == form and self.clock() <= a['until'] for a in self._armed.values())
            if live:
                return  # this form's entries are armed already: the request after a refresh must not re-arm and refresh again
            pairs, _ = self.source.items()
            items = []
            for _, meta in reversed(pairs):  # newest first: every item that fits this screen, so the owner picks the account
                if meta.kind == wanted and self.bind(meta, context, fetch=False)[0]:
                    label = f'{meta.label} · {meta.identifier}' if meta.identifier else meta.label
                    items.append({'handle': meta.id, 'item_kind': meta.kind, 'label': label, 'roles': ITEM_ROLES[meta.kind],
                                  'held': None, 'code': None, 'pin': self._unpinned_app(meta)})
            if items:
                armed = self._arm(session, context, wanted, items, focus=None, wait=False)
                if armed and armed[2].get('t') == 'ack':
                    self._refresh()  # the owner's next tap on the field shows the entries
        except Exception:  # health: allow BLE001 -- an offer that fails just shows no Beacon entry
            log.info('agent phone vault: no offer for this screen')
        finally:
            self._offering.release()

ITEM_ROLES = {'login': ('username', 'password'),
              'payment': ('card_number', 'cardholder_name', 'exp_date', 'exp_month', 'exp_year', 'cvc', 'billing_postal_code'),
              'address': ('address_line1', 'address_line2', 'city', 'state', 'postal_code', 'country')}


def _fetch_asset_links(domain):
    """The site's Digital Asset Links statements over HTTPS (3 s, no redirect to another host)."""
    import urllib.request
    url = f'https://{domain}/.well-known/assetlinks.json'
    request = urllib.request.Request(url, headers={'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=3) as response:  # noqa: S310 -- fixed https URL on the item's own host
        if urlsplit(response.geturl()).hostname != domain:
            return []
        return json.loads(response.read(65536).decode('utf-8'))
