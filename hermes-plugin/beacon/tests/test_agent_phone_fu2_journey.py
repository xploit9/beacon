"""EMU-JOIN follow-up 2, the owner's journey (17 Nov): one sign-in raised two "use a saved login" questions, and Beacon's own fill
entry, left on the screen, blocked the agent until it pressed Back. Each test failed on the code before its fix
(logs/fu2-journey-red.log). Test data is synthetic."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from test_agent_phone_tools import node, phone, run, screen_xml  # noqa: E402,F401
from test_agent_phone_vault import V, app_login, ctx, fill, hermes, world  # noqa: E402,F401
from test_mobile import harness  # noqa: E402,F401

ENTRY = 'Beacon · Kanto Foods · agent.join@example.com'
RESET = ('shell', ['cmd', 'autofill', 'reset'])


# -- one question per sign-in ------------------------------------------------------------------------------------------------

def test_a_fill_that_filled_nothing_keeps_the_owners_yes_for_the_retry(world):  # noqa: F811
    login = app_login(world, pinned=False)
    first, _, _ = fill(world, login.id, ctx())
    assert first['error_type'] == 'pin_approval_needed'  # the one question
    failed, _, _ = fill(world, login.id, ctx(), pin_approved=True, tap=False)
    assert failed['error_type'] == 'no_entry'  # the journey's first fill: nothing was filled, the login is not pinned
    assert not V.Sidecar(V.sidecar_path(world.home)).item(login.id)
    elsewhere, _, _ = fill(world, login.id, ctx(), task_id='another-task')
    assert elsewhere['error_type'] == 'pin_approval_needed'  # the yes was for this task only
    retry, _, _ = fill(world, login.id, ctx())
    assert retry['success'] and V.Sidecar(V.sidecar_path(world.home)).item(login.id)['certs'] == [ctx()['certs'][0]]


def test_the_owners_unused_yes_lapses_and_never_covers_another_app_or_certificate(world):  # noqa: F811
    login = app_login(world, pinned=False)
    fill(world, login.id, ctx(), pin_approved=True, tap=False)
    other_cert, _, _ = fill(world, login.id, ctx(certs=('AB' * 32,)))
    assert other_cert['error_type'] in ('pin_approval_needed', 'not_bound')
    world.clock.now += V.PIN_YES_SECONDS + 1
    late, _, _ = fill(world, login.id, ctx())
    assert late['error_type'] == 'pin_approval_needed'


def test_an_arm_that_filled_nothing_resets_androids_autofill_and_one_that_filled_does_not(world):  # noqa: F811
    login = app_login(world)
    resets = []
    world.vault.refresh = lambda: resets.append(1)
    result, _, _ = fill(world, login.id, ctx(), tap=False)
    assert result['error_type'] == 'no_entry' and len(resets) == 2  # after the arm (B5), and again when nothing was filled
    resets.clear()
    result, _, _ = fill(world, login.id, ctx())
    assert result['success'] and len(resets) == 1  # after the arm only: a fill that worked leaves Android be


# -- no Beacon entry in the agent's way ---------------------------------------------------------------------------------------

def left_entry(world, under):
    """Beacon's dropdown entry over `under`, as the agent's read found it in the journey: Android's autofill reset closes it."""
    world.xml = screen_xml(node('android.widget.TextView', text=ENTRY, bounds=(40, 420, 700, 500), clickable=True, package='android'),
                           package='android', title='')

    def dump():
        if RESET in world.calls:
            world.xml = under
    world.on_dump = dump


def test_a_beacon_entry_left_on_the_screen_is_closed_before_the_agent_sees_it(phone):  # noqa: F811
    tools, approvals, world, api = phone
    left_entry(world, world.xml)
    result = run(tools, action='capture')
    summary = json.loads(result['text_summary'])
    assert RESET in world.calls
    assert summary['package'] == 'dev.beacon.fixture.shop' and 'Beacon ·' not in json.dumps(summary, ensure_ascii=False)


def test_the_agents_tap_lands_on_the_app_not_on_a_left_entry(phone):  # noqa: F811
    tools, approvals, world, api = phone
    cart = screen_xml(node(text='View cart', bounds=(63, 1100, 1017, 1226)))
    left_entry(world, cart)
    result = run(tools, action='tap', coordinate=[540, 1163], purpose='Open the cart')
    assert RESET in world.calls and result.get('success') is True and world.inputs and world.inputs[0][0] == 'tap'



# -- Android's 3-character cap: no dropdown under a field holding more than 3 characters (block 10 and a rehearsal phone, 18 Nov) --

TYPED = 'agent.join@example.com'
FIELD = (63, 344, 1017, 470)
LOGIN_ROLES = ('username', 'password', 'otp')
FOCUSED_EMAIL = {'fields': [{'id': '1073741824', 'role': 'username', 'focused': True}]}


def identifier_page(text, entry=False):
    """An identifier-first page: one email field, Next, and Beacon's entry under the field only when Android shows it."""
    nodes = [node('android.widget.EditText', text=text, desc='email', bounds=FIELD, focused=True),
             node(text='Next', bounds=(63, 700, 1017, 826))]
    if entry:
        nodes.append(node('android.widget.TextView', text=ENTRY, clickable=True, package='android', bounds=(63, 470, 1017, 570)))
    return screen_xml(*nodes)


def androids_cap(world):
    """Android's FillUi: a tap on the field shows the entry only once the field holds no more than 3 characters. Here the field
    is emptied only by Ctrl+A then Backspace."""
    def emptied():
        keys = [i for i in world.inputs if i[0] == 'key']
        return any(k[2] == 30 for k in keys) and any(k[1] == 'Backspace' for k in keys)

    def on_tap(x, y):
        if FIELD[1] <= y <= FIELD[3]:
            world.xml = identifier_page('' if emptied() else TYPED, entry=emptied())
    world.tap_hooks.append(on_tap)


def test_the_entry_already_under_the_next_field_after_tab_is_picked_there_without_back(phone):  # noqa: F811
    tools, approvals, world, api = phone
    from test_agent_phone_tools import login_with_entry, taps
    world.xml = login_with_entry()  # after Tab the entry shows under the empty password field (every rehearsal round)
    driver = tools.Driver(tools.Phone(tools_host(world), {}), FIELD)
    assert driver.pick_entry({}, 'Kanto Foods') is True
    assert [i[1] for i in world.inputs if i[0] == 'key'] == ['Tab'] and taps(world) == [('tap', 123, 650, 0.05)]


def test_an_identifier_the_agent_typed_is_emptied_then_the_entry_shows_under_it(phone):  # noqa: F811
    tools, approvals, world, api = phone
    world.xml = identifier_page(TYPED)
    androids_cap(world)
    driver = tools.Driver(tools.Phone(tools_host(world), {}), FIELD)
    assert driver.pick_entry(FOCUSED_EMAIL, 'Kanto Foods', LOGIN_ROLES) is True
    keys = [(i[1], i[2]) for i in world.inputs if i[0] == 'key']
    assert keys[:4] == [(None, 29), (None, 30), (None, 29), ('Backspace', None)]  # select all, delete: only after a tap on it
    assert world.inputs[0] == ('tap', 540, 407, 0.05) and world.inputs[-1] == ('tap', 123, 520, 0.05)  # the field, then the entry



def test_next_on_an_identifier_page_does_not_settle_the_login_its_password_page_does(phone):  # noqa: F811
    """Block 11 (18 Nov 04:41): Next after the email page's fill was judged a failed sign-in (the password page opens with its
    field empty, which reads as "the form came back emptied"), so the item was marked "didn't work" and the owner told so."""
    tools, approvals, world, api = phone
    from test_agent_phone_tools import capture, element
    world.xml = identifier_page('')
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    password_page = screen_xml(node('android.widget.EditText', desc='password', password=True, bounds=(63, 470, 1017, 596), focused=True),
                               node(text='Sign in', bounds=(63, 848, 1017, 974)))
    world.after_input, world.outcome = password_page, 'failure'
    tapped = run(tools, action='tap', element=element(capture(tools), 'Next'), purpose='Go to the password page')
    assert 'login' not in tapped and not [c for c in world.vault.calls if c[0] == 'login_result']
    world.outcome = 'success'
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    tapped = run(tools, action='tap', element=element(capture(tools), 'Sign in'), purpose='Sign in')
    assert tapped['login'] == 'ok' and [c for c in world.vault.calls if c[0] == 'login_result'] == [('login_result', 'vault_login', 'success')]


# -- one question per sign-in: the submit after an approved fill (block 14, 18 Nov) -----------------------------------------------

def login_page(submit='Sign in', package=None):
    from test_agent_phone_tools import PKG
    package = package or PKG
    return screen_xml(node('android.widget.EditText', desc='email', bounds=FIELD, package=package),
                      node('android.widget.EditText', desc='password', password=True, bounds=(63, 470, 1017, 596), package=package),
                      node(text=submit, bounds=(63, 848, 1017, 974), package=package), package=package)


def submit_after_fill(tools, approvals, world, label, risk='account', task='task-1'):
    """The model's own calls in block 14: fill, then a tap on the form's submit with risk=account. Returns (result, questions)."""
    from test_agent_phone_tools import capture, element, owner_answers
    thread, seen = owner_answers(approvals, True)
    declared = {'risk': risk} if risk else {}
    tapped = run(tools, task=task, action='tap', element=element(capture(tools), label), purpose='Submit the sign-in form', **declared)
    thread.join()
    return tapped, seen


def test_the_sign_in_tap_after_an_approved_fill_asks_nothing_more_even_declared_an_account_change(phone):  # noqa: F811
    """Block 14 (18 Nov 07:18): the model filled the login (its one question), then tapped Sign in with risk=account, and the
    owner was asked a second time, "Beacon wants to change an account"."""
    tools, approvals, world, api = phone
    world.xml = login_page()
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    world.after_input, world.outcome = screen_xml(node(text='Signed in')), 'success'
    tapped, seen = submit_after_fill(tools, approvals, world, 'Sign in')
    assert seen == [] and tapped['success'] is True and tapped['approved_by_owner'] is False and tapped['login'] == 'ok'


def test_any_submit_of_the_filled_form_is_covered_but_not_in_another_app_after_the_window_or_in_another_task(phone):  # noqa: F811
    tools, approvals, world, api = phone
    from test_agent_phone_tools import PKG
    world.xml = login_page('Continue')
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    world.xml, world.package = login_page('Continue', package='com.example.other'), 'com.example.other'
    tapped, seen = submit_after_fill(tools, approvals, world, 'Continue')
    assert len(seen) == 1 and seen[0]['risk'] == 'account'  # another app
    world.xml, world.package = login_page('Continue'), PKG
    for pending in tools._pending.values():
        pending['until'] = 0  # the fill's 10 minutes are over
    tapped, seen = submit_after_fill(tools, approvals, world, 'Continue')
    assert len(seen) == 1
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    tapped, seen = submit_after_fill(tools, approvals, world, 'Continue', task='task-2')
    assert len(seen) == 1  # the fill was another task's
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    world.after_input, world.outcome = screen_xml(node(text='Signed in')), 'success'
    tapped, seen = submit_after_fill(tools, approvals, world, 'Continue')
    assert seen == [] and tapped['approved_by_owner'] is False  # this task, this app, inside the window


def test_a_real_account_change_still_asks_after_a_fill(phone):  # noqa: F811
    tools, approvals, world, api = phone
    world.xml = login_page('Confirm sign out')
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    tapped, seen = submit_after_fill(tools, approvals, world, 'Confirm sign out', risk=None)
    assert len(seen) == 1 and seen[0]['risk'] == 'account'  # a submit, but its words are a real change
    world.xml = login_page('Save')
    tapped, seen = submit_after_fill(tools, approvals, world, 'Save', task='task-2')  # a profile's Save, no fill in this task
    assert len(seen) == 1 and seen[0]['risk'] == 'account'


def test_the_fill_covers_one_submit_never_a_later_save_on_another_page(phone):  # noqa: F811
    """An identifier-first fill is not settled by its Next (no password field), so it still waits when the agent goes on to a
    profile page: that page's Save, said to change the account, asks."""
    tools, approvals, world, api = phone
    world.xml = identifier_page('')
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    tapped, seen = submit_after_fill(tools, approvals, world, 'Next')
    assert seen == [] and tapped['approved_by_owner'] is False  # the fill's own submit
    world.xml = screen_xml(node('android.widget.EditText', desc='display name', bounds=FIELD), node(text='Save', bounds=(63, 700, 1017, 826)))
    tapped, seen = submit_after_fill(tools, approvals, world, 'Save')
    assert len(seen) == 1 and seen[0]['risk'] == 'account'


def test_a_next_that_declares_nothing_still_spends_the_cover(phone):  # noqa: F811
    """The tool-driven Next of blocks 11-12 declared nothing; it is still the fill's one covered submit, so a later Save that
    says it changes the account asks, even on a page with the same email field."""
    tools, approvals, world, api = phone
    world.xml = identifier_page('')
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    tapped, seen = submit_after_fill(tools, approvals, world, 'Next', risk=None)
    assert seen == []
    world.xml = screen_xml(node('android.widget.EditText', desc='email', bounds=FIELD), node(text='Save', bounds=(63, 700, 1017, 826)))
    tapped, seen = submit_after_fill(tools, approvals, world, 'Save')
    assert len(seen) == 1 and seen[0]['risk'] == 'account'


def test_the_cover_is_for_the_form_the_fill_filled(phone):  # noqa: F811
    """A fill never submitted (the app signed in by itself, or the agent moved on) covers no other page's submit."""
    tools, approvals, world, api = phone
    world.xml = login_page()
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    world.xml = screen_xml(node('android.widget.EditText', desc='display name', bounds=FIELD), node(text='Save', bounds=(63, 700, 1017, 826)))
    tapped, seen = submit_after_fill(tools, approvals, world, 'Save')
    assert len(seen) == 1 and seen[0]['risk'] == 'account'


def test_the_fill_covers_only_the_agents_word_every_action_still_asks(phone):  # noqa: F811
    tools, approvals, world, api = phone
    approvals.set_policy('owner', ask='every_action')
    world.xml = login_page('Continue')
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    tapped, seen = submit_after_fill(tools, approvals, world, 'Continue')
    assert len(seen) == 1 and seen[0]['risk'] == 'other'  # as the same tap without risk=account


def test_the_fill_covers_its_submit_in_the_classifiers_other_languages(phone):  # noqa: F811
    tools, approvals, world, api = phone
    for label in ('Weiter', '次へ', 'ادامه'):
        world.xml = login_page(label)
        assert run(tools, action='fill', handle='vault_login')['success'] is True
        tapped, seen = submit_after_fill(tools, approvals, world, label)
        assert seen == [] and tapped['approved_by_owner'] is False, label


SIGN_INS = ('Sign in', 'Sign-in', 'Sign in.', 'Log in', 'Login', 'تسجيل الدخول', 'Se connecter', 'Connexion', 'Iniciar sesión',
            'Iniciar sessão', 'Entrar', 'Anmelden', 'Einloggen', 'Accedi', 'Giriş yap', 'Войти', 'Вход', '登录', '登入', 'ログイン', 'サインイン',
            '로그인', 'साइन इन', 'लॉग इन', 'سائن ان', 'لاگ ان', 'ورود')
NOT_SIGN_INS = ('Change login email', 'Login & security', 'Remove sign-in method', 'Update login', 'Sign in with another account')
ACCOUNT_CHANGES = ('Sign out', 'Log out', 'Change password', 'Delete account', 'Add account', 'Switch account', 'تسجيل الخروج',
                   'تغيير كلمة المرور', 'حذف الحساب', 'Déconnexion', 'Cerrar sesión', 'Abmelden', 'Esci', 'Çıkış yap', 'Выйти', 'Выход',
                   '退出登录', 'ログアウト', '로그아웃')


def test_a_sign_in_after_an_approved_fill_asks_nothing_in_any_language_the_classifier_reads(phone):  # noqa: F811
    """One mechanism (PHONEFIX-121 P3-5): the fill's own cover. A sign-in label alone no longer drops the agent's `account`, so
    each sign-in word is proven where the cover applies: the submit of the form an approved fill filled."""
    tools, approvals, world, api = phone
    from test_agent_phone_tools import index_of, screen_of
    for label in SIGN_INS:
        world.xml = login_page(label)
        assert run(tools, action='fill', handle='vault_login')['success'] is True, label
        tapped, seen = submit_after_fill(tools, approvals, world, label)
        assert seen == [] and tapped['approved_by_owner'] is False, label
    for label in SIGN_INS:  # with no fill behind it, the agent's own word stands
        screen = screen_of(tools, screen_xml(node(text=label)))
        assert tools.assess(screen, 'tap', index_of(screen, label), declared='account')[0] == 'account', label
    for label in ACCOUNT_CHANGES:
        screen = screen_of(tools, screen_xml(node(text=label)))
        target = index_of(screen, label)
        assert tools.assess(screen, 'tap', target, declared='account')[0] in ('account', 'delete'), label
    for label in NOT_SIGN_INS + ('Edit profile',):
        screen = screen_of(tools, screen_xml(node(text=label)))
        assert tools.assess(screen, 'tap', index_of(screen, label), declared='account')[0] is not None, label  # it still asks


def tools_host(world):
    from test_agent_phone_tools import FakeHost
    return FakeHost(world)

# -- the helper is the phone's keyboard: its settings are the owner's ---------------------------------------------------------

def test_the_agent_cannot_change_the_phones_keyboard_away_from_beacon(phone):  # noqa: F811
    tools, approvals, world, api = phone
    from test_agent_phone_tools import index_of, screen_of
    page = screen_of(tools, screen_xml(node('android.widget.TextView', text='On-screen keyboard', clickable=False, package='com.android.settings'),
                                       node('android.widget.Switch', text='Beacon', package='com.android.settings'),
                                       node('android.widget.Switch', text='Gboard', package='com.android.settings'),
                                       package='com.android.settings', title='Keyboard'), package='com.android.settings')
    assert 'helper' in tools.hard_block(page, 'tap', index_of(page, 'Gboard'))


# -- the helper is the phone's keyboard (Root after L5-fu1) ----------------------------------------------------------------------

IME = 'dev.hermes.agentphone.helper/.HelperInputMethod'
GBOARD = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'


def first_start(tmp_path, keyboard_now, helper=True, name='p'):
    """The host's first start against adb whose phone has keyboard_now as its default input method."""
    import contextlib
    import hashlib
    from types import SimpleNamespace
    host = host_module()
    apk = tmp_path / 'helper.apk'
    apk.write_bytes(b'helper-bytes')
    calls = []

    class Session:
        def shell(self, args, timeout=60, **_):
            calls.append(args if isinstance(args, str) else tuple(args))
            text = args if isinstance(args, str) else ' '.join(args)
            if text == 'settings get secure default_input_method':
                return SimpleNamespace(stdout=keyboard_now + '\n', returncode=0)
            if 'dumpsys window' in text:
                return SimpleNamespace(stdout='  mCurrentFocus=Window{1 u0 com.google.android.apps.nexuslauncher/.Launcher}\n', returncode=0)
            if text == 'settings get secure autofill_service':
                return SimpleNamespace(stdout='dev.hermes.agentphone.helper/.HelperAutofillService\n', returncode=0)
            return SimpleNamespace(stdout='', returncode=0)

        def install(self, path):
            return SimpleNamespace(stdout='Success', returncode=0)
    folder = host.Folder(tmp_path / name)
    folder.run.mkdir(parents=True, exist_ok=True)
    kw = dict(helper_apk=apk, helper_sha256=hashlib.sha256(b'helper-bytes').hexdigest()) if helper else {}
    h = host.PhoneHost(folder, **kw)
    result = h.first_start(SimpleNamespace(session=lambda: contextlib.nullcontext(Session())))
    return result, calls, h


def host_module():
    from test_agent_phone_fu2 import load
    return load('agent_phone_host')


def test_first_start_makes_the_helper_the_phones_keyboard_after_installing_it(tmp_path):
    result, calls, _ = first_start(tmp_path, GBOARD)
    assert result['helper'] == 'ok' and result['keyboard'] == 'ok'
    step = f'ime enable {IME} && ime set {IME}'
    assert step in calls and calls.index(step) > next(i for i, c in enumerate(calls) if c == ('settings', 'put', 'secure',
                                                                                               'autofill_service',
                                                                                               'dev.hermes.agentphone.helper/.HelperAutofillService'))


def test_every_start_puts_the_helpers_keyboard_back_and_leaves_it_when_it_is_there(tmp_path):
    first_start(tmp_path, GBOARD, name='p')
    again, calls, _ = first_start(tmp_path, GBOARD, name='p')  # something else became the keyboard since
    assert again['keyboard'] == 'ok' and f'ime enable {IME} && ime set {IME}' in calls
    kept, calls, _ = first_start(tmp_path, IME, name='p')
    assert kept['keyboard'] == 'done' and not any('ime set' in str(c) for c in calls)


def test_without_the_helper_the_phone_keeps_androids_own_keyboard(tmp_path):
    result, calls, _ = first_start(tmp_path, GBOARD, helper=False)
    assert result['keyboard'] == 'waits-for-helper' and not any('ime ' in str(c) for c in calls)


# -- the owner's stop ends an open view as stopped (L5 live, Root msg_53a63d3021ad) -------------------------------------------

def test_frames_failing_while_the_owner_stops_the_phone_end_the_view_as_stopped(tmp_path):
    import asyncio
    import test_agent_phone_stream as st
    stopping = [False]

    async def main():
        socket, log = st.FakeSocket(), []
        session, lease, holder = st.make(tmp_path, socket, log, phone_stopping=lambda: stopping[0])
        session.lease_check_seconds = 60  # the frames' path alone: the watch doesn't look in time
        task = asyncio.create_task(session.run())
        await st.until(lambda: 'frames' in holder)
        stopping[0] = True  # the stop route marks the stop, then the host takes the emulator down
        holder['frames'].failed = 'ProtocolError'
        holder['frames'].on_frame()
        return await task, socket
    reason, socket = st.run(main())
    assert reason == 'stopped'
    assert socket.of('bye') == [{'t': 'bye', 'reason': 'stopped'}] and socket.closed == 1000


def test_a_stop_under_way_ends_the_view_as_stopped_before_its_frames_fail(tmp_path):
    import asyncio
    import test_agent_phone_stream as st
    stopping = [False]

    async def main():
        socket, log = st.FakeSocket(), []
        session, lease, holder = st.make(tmp_path, socket, log, phone_stopping=lambda: stopping[0])
        task = asyncio.create_task(session.run())
        await st.until(lambda: 'frames' in holder)
        stopping[0] = True
        return await task, socket, lease
    reason, socket, lease = st.run(main())
    assert reason == 'stopped' and socket.of('bye') == [{'t': 'bye', 'reason': 'stopped'}]
    assert not lease.holds(st.VIEWER)  # a stopped phone's lease is given back, not kept for a retry


def test_the_view_route_tells_its_stream_when_the_owner_stops_the_phone():
    source = (Path(__file__).resolve().parents[1] / 'agent_phone_api.py').read_text(encoding='utf-8')
    assert "phone_stopping=lambda: jobs.phone == 'stopping'" in source
    stop = source[source.index("    @router.post('/mobile/agent-phone/stop')"):]
    assert stop.index("jobs.phone = 'stopping'") < stop.index('threading.Thread(target=run_stop')  # marked before the host stops
