"""EMU-JOIN follow-up 1: the keeper and Hermes updates, the code field the helper fills itself, Show passwords.

Hermes on Windows stages each update into a new runtime generation and then removes the superseded ones nobody leases
(hermes_cli.runtime_state.collect_generations). The keeper leases the generation it runs from and follows Hermes' record of the
current one. The collector test runs Hermes' own code (from HERMES_AGENT_SOURCE) in a temporary Hermes home, so a change to its
lease protocol fails here. Test data is synthetic.
"""
import importlib.util
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

PLUGIN = Path(__file__).resolve().parents[1]
HERMES_SOURCE = os.environ.get('HERMES_AGENT_SOURCE')
SHOP = 'dev.beacon.fixture.shop'


def load(name):
    key = 'beacon_' + name
    if key not in sys.modules:
        spec = importlib.util.spec_from_file_location(key, PLUGIN / f'{name}.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        spec.loader.exec_module(module)
    return sys.modules[key]


@pytest.fixture
def m():
    return SimpleNamespace(**{name: load('agent_phone_' + name) for name in
                              ('install', 'host', 'keeper', 'tools', 'channel', 'redact')})


def runtime(state, name, selected=False):
    """One Hermes runtime generation under an install's state folder, as Hermes' installer leaves it: a venv with its
    pyvenv.cfg and interpreter file, the lease-managed marker, and (when selected) the install's facts.json naming it."""
    venv = state / 'environments' / name / 'venv'
    scripts = venv / ('Scripts' if os.name == 'nt' else 'bin')
    scripts.mkdir(parents=True)
    (venv / 'pyvenv.cfg').write_text('home = synthetic\n', encoding='utf-8')
    python = scripts / ('python.exe' if os.name == 'nt' else 'python3')
    python.write_bytes(b'synthetic interpreter')
    (venv.parent / '.lease-managed').touch()
    if selected:
        (state / 'facts.json').write_text(json.dumps({'packages': {'venv': {'environment': str(venv)}}}), encoding='utf-8')
    return SimpleNamespace(venv=venv, python=python, generation=venv.parent)


# -- 3. the keeper and Hermes updates --------------------------------------------------------------------------------------

HERMES_CODE = r'''
import json, sys
from pathlib import Path
from pm.environments import install_state_dir
from hermes_cli.runtime_state import collect_generations, leases_held
project, action = Path(sys.argv[1]), sys.argv[2]
if action == 'state':
    print(install_state_dir(project))
elif action == 'held':
    print(leases_held(Path(sys.argv[3])))
else:
    print(json.dumps([p.name for p in collect_generations(project, min_age_seconds=0)]))
'''


def hermes(home, project, *args):
    """Hermes' own code, from its source, in a temporary Hermes home; -B so nothing is written into the source tree."""
    env = dict(os.environ, HERMES_HOME=str(home), PYTHONPATH=HERMES_SOURCE, PYTHONDONTWRITEBYTECODE='1',
               HERMES_DISABLE_LAZY_INSTALLS='1')
    done = subprocess.run([sys.executable, '-B', '-c', HERMES_CODE, str(project), *map(str, args)], env=env,
                          capture_output=True, text=True, timeout=120, cwd=str(home))
    assert done.returncode == 0, done.stderr[-2000:]
    return done.stdout.strip().splitlines()[-1]


@pytest.mark.skipif(not HERMES_SOURCE, reason='needs HERMES_AGENT_SOURCE (Hermes source)')
def test_hermes_own_cleanup_after_an_update_skips_the_keepers_runtime_until_it_lets_go(tmp_path, m):
    home, project = tmp_path / 'hermes-home', tmp_path / 'checkout'
    home.mkdir()
    project.mkdir()
    state = Path(hermes(home, project, 'state'))
    assert state.is_relative_to(home)  # the temporary home, never a real one
    old = runtime(state, 'old')
    new = runtime(state, 'new', selected=True)  # the update: a new generation, recorded as Hermes' current one
    held = m.keeper.lease(old.venv)  # the keeper on the old runtime holds its lease
    assert held is not None and held[1].parent == old.generation / '.leases'
    assert hermes(home, project, 'held', old.generation) == 'True'
    assert json.loads(hermes(home, project, 'collect')) == [] and old.venv.is_dir()  # skipped whole, not half-deleted
    m.keeper.unlease(held)
    assert hermes(home, project, 'held', old.generation) == 'False'
    assert json.loads(hermes(home, project, 'collect')) == ['old'] and not old.generation.exists() and new.venv.is_dir()


def test_the_keeper_reads_hermes_record_of_the_current_runtime(tmp_path, m):
    state = tmp_path / 'installs' / '0123456789abcdef'
    old, new = runtime(state, 'old'), runtime(state, 'new', selected=True)
    assert m.keeper.superseded(old.python) == new.python
    assert m.keeper.superseded(new.python) is None
    (state / 'facts.json').write_text('{"packages": {}}', encoding='utf-8')
    assert m.keeper.superseded(old.python) is None  # no record: stay
    other = tmp_path / 'venv'
    (other / 'Scripts').mkdir(parents=True)
    (other / 'pyvenv.cfg').write_text('home = x\n', encoding='utf-8')
    assert m.keeper.superseded(other / 'Scripts' / 'python.exe') is None  # not a Hermes runtime generation at all
    assert m.keeper.lease(other) is None and m.keeper.superseded(None) is None


def keeper_folder(tmp_path, m, conf=None):
    folder = tmp_path / 'phone'
    (folder / 'run').mkdir(parents=True)
    (folder / 'keeper.py').write_bytes((PLUGIN / 'agent_phone_keeper.py').read_bytes())
    (folder / 'run' / 'keeper.json').write_text(json.dumps(conf or {'idle_minutes': 30}), encoding='utf-8')
    return folder


def test_a_keeper_on_a_replaced_runtime_starts_again_there_and_exits_without_stopping_the_phone(tmp_path, m, monkeypatch):
    state = tmp_path / 'installs' / '0123456789abcdef'
    old, new = runtime(state, 'old'), runtime(state, 'new', selected=True)
    folder = keeper_folder(tmp_path, m)
    moved, stopped = [], []
    monkeypatch.setattr(m.keeper, 'handover', lambda f, conf, python: moved.append(Path(python)) or True)
    monkeypatch.setattr(m.keeper, 'stop', lambda *a: stopped.append(a))
    assert m.keeper.watch(folder, sleep=lambda s: None, max_ticks=2, python=old.python) == 'superseded'
    assert moved == [new.python] and stopped == []
    assert not list((old.generation / '.leases').iterdir())  # its lease is let go on the way out
    # When the current runtime can't run it, the keeper stays where it is (still leased) and keeps watching.
    monkeypatch.setattr(m.keeper, 'handover', lambda f, conf, python: False)
    leases = []
    monkeypatch.setattr(m.keeper, 'decide', lambda *a: leases.append(len(list((old.generation / '.leases').iterdir()))) or 'gone')
    assert m.keeper.watch(folder, sleep=lambda s: None, max_ticks=2, python=old.python) == 'gone' and leases == [1]


def test_a_keeper_that_keeper_json_no_longer_names_exits(tmp_path, m, monkeypatch):
    other = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
    try:
        folder = keeper_folder(tmp_path, m, {'pid': other.pid, 'idle_minutes': 30})
        monkeypatch.setattr(m.keeper, 'stop', lambda *a: pytest.fail('a replaced keeper never stops the phone'))
        assert m.keeper.watch(folder, sleep=lambda s: None, max_ticks=2) == 'replaced'
        (folder / 'run' / 'keeper.json').write_text(json.dumps({'pid': os.getpid()}), encoding='utf-8')
        assert m.keeper.watch(folder, sleep=lambda s: None, max_ticks=2) == 'gone'  # named: it carries on (no phone here)
    finally:
        other.kill()


def test_handover_starts_a_real_keeper_on_the_new_interpreter_and_records_it(tmp_path, m):
    folder = keeper_folder(tmp_path, m, {'plugin_dir': str(PLUGIN), 'idle_minutes': 30, 'pid': os.getpid()})
    assert m.keeper.handover(folder, {'plugin_dir': str(PLUGIN), 'idle_minutes': 30}, sys.executable) is True
    conf = json.loads((folder / 'run' / 'keeper.json').read_text(encoding='utf-8'))
    assert conf['pid'] != os.getpid() and conf['python'] == sys.executable and conf['via'] == 'keeper' and conf['idle_minutes'] == 30
    assert not (folder / 'run' / 'keeper-next.spec.json').exists() and not (folder / 'run' / 'keeper-next.json').exists()
    log, deadline = folder / 'run' / 'keeper.log', time.time() + 60  # the new keeper finds no phone running here and exits
    while time.time() < deadline and not (log.exists() and 'keeper exits' in log.read_text(encoding='utf-8')):
        time.sleep(0.2)
    assert 'phone not running; keeper exits' in log.read_text(encoding='utf-8')
    assert m.keeper.handover(folder, conf, tmp_path / 'no-such-python.exe') is False


@pytest.fixture
def phone(tmp_path, m):
    home = tmp_path / 'hermes-home'
    folder = tmp_path / 'Local' / 'Beacon' / 'agent-phone' / '0123456789abcdef'
    (folder / 'run').mkdir(parents=True)
    m.install.write_pointer(home, folder, profile='default', dashboard=None)
    return SimpleNamespace(home=home, folder=folder)


def test_plugin_load_moves_a_keeper_off_a_replaced_runtime_and_keeps_the_phone(phone, m, monkeypatch, tmp_path):
    import psutil
    state = tmp_path / 'installs' / '0123456789abcdef'
    old, new = runtime(state, 'old'), runtime(state, 'new', selected=True)
    keeper = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])  # stands in for the old keeper process
    try:
        born = psutil.Process(keeper.pid).create_time()
        host = m.install.phone_host(phone.folder)
        m.host.write_json(host.folder.keeper_conf, {'pid': keeper.pid, 'create_time': born, 'python': str(old.python)})
        spawned = []

        def spawn_keeper(python=None, replace=False):
            spawned.append((Path(python), replace))
            m.host.write_json(host.folder.keeper_conf, {'pid': 4242, 'python': python})

        monkeypatch.setattr(host, '_spawn_keeper', spawn_keeper)
        monkeypatch.setattr(m.host, 'keeper_python', lambda env, candidates=None, **kw: str(candidates[0]) if candidates else None)
        monkeypatch.setattr(host, 'stop', lambda *a, **kw: pytest.fail('following the runtime never stops the phone'))
        assert host.follow_runtime() == 'moved'
        assert spawned == [(new.python, True)] and keeper.wait(10) is not None  # the old keeper is ended
        assert (phone.folder / 'keeper.py').read_bytes() == (PLUGIN / 'agent_phone_keeper.py').read_bytes()
    finally:
        keeper.kill()


def test_plugin_load_leaves_a_current_keeper_alone_and_never_reads_its_own_interpreter(phone, m, monkeypatch, tmp_path):
    import psutil
    state = tmp_path / 'installs' / '0123456789abcdef'
    old, new = runtime(state, 'old'), runtime(state, 'new', selected=True)
    keeper = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
    try:
        born = psutil.Process(keeper.pid).create_time()
        host = m.install.phone_host(phone.folder)
        monkeypatch.setattr(host, '_spawn_keeper', lambda **kw: pytest.fail('nothing to move'))
        m.host.write_json(host.folder.keeper_conf, {'pid': keeper.pid, 'create_time': born, 'python': str(new.python)})
        assert host.follow_runtime() == 'current'
        m.host.write_json(host.folder.keeper_conf, {'pid': keeper.pid, 'create_time': born})  # a keeper from before fu1
        assert host.follow_runtime() == 'current'  # its interpreter (read from the process) is no Hermes generation here
        m.host.write_json(host.folder.keeper_conf, {'pid': keeper.pid, 'create_time': born, 'python': str(old.python)})
        monkeypatch.setattr(m.host, 'keeper_python', lambda env, candidates=None, **kw: None)
        assert host.follow_runtime() == 'kept' and keeper.poll() is None  # the new runtime can't run it: the old one stays
    finally:
        keeper.kill()
    m.host.write_json(host.folder.keeper_conf, {'pid': keeper.pid, 'create_time': born, 'python': str(old.python)})
    keeper.wait(10)
    assert host.follow_runtime() == 'no keeper'


def test_the_spawned_keeper_records_its_interpreter(phone, m):
    host = m.install.phone_host(phone.folder)
    host.spawner = SimpleNamespace(spawn=lambda *a, **kw: {'pid': 4321, 'create_time': 1.0, 'via': 'keeper'})
    host._spawn_keeper(python='C:/hermes/installs/k/environments/g/venv/Scripts/python.exe')
    conf = m.host.read_json(host.folder.keeper_conf)
    assert conf['python'] == 'C:/hermes/installs/k/environments/g/venv/Scripts/python.exe' and conf['pid'] == 4321


def test_after_load_checks_the_keeper_and_never_raises(m, monkeypatch):
    monkeypatch.setattr(m.tools, 'pointer', lambda: None)
    assert m.tools.follow_keeper() is None
    monkeypatch.setattr(m.tools, 'pointer', lambda: {'folder': 'x', 'profile': 'default'})
    monkeypatch.setattr(m.tools, '_host', lambda folder: SimpleNamespace(follow_runtime=lambda: 1 / 0))
    assert m.tools.follow_keeper() is None
    monkeypatch.setattr(m.tools, '_host', lambda folder: SimpleNamespace(follow_runtime=lambda: 'moved'))
    assert m.tools.follow_keeper() == 'moved'


# -- 2. the code the helper fills itself -----------------------------------------------------------------------------------

def test_code_filled_carries_fields_never_values(m):
    c = m.channel
    arm = 'arm_' + 'C' * 16
    target = {'id': '1073741832', 'view_id': None, 'bounds': [63, 344, 1017, 470]}
    for message in ({'t': 'code_filled', 'arm_id': arm, 'fields': 1},  # a helper from before revision 4
                    {'t': 'code_filled', 'arm_id': arm, 'fields': 1, 'targets': [target]},
                    {'t': 'code_filled', 'arm_id': arm, 'fields': 0, 'targets': []}):
        assert c.decode(json.dumps(message).encode(), c.C2H)['t'] == 'code_filled'
    for bad in ([dict(target, value='482913')], [dict(target, bounds=[1, 2, 3])], [{'id': ''}], {'id': '1'}, [target] * 17):
        with pytest.raises(c.Closed):
            c.decode(json.dumps({'t': 'code_filled', 'arm_id': arm, 'fields': 1, 'targets': bad}).encode(), c.C2H)


def screen(code_text, package=SHOP, field='[63,344][1017,470]', resource_id='', extra=''):
    """The shop's code screen as uiautomator dumps it: its field has no resource id (built in code), as in B8."""
    return (f'<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0">'
            f'<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="{package}" content-desc="" '
            f'password="false" bounds="[0,0][1080,2400]">'
            f'<node index="0" text="Kanto Foods · Enter code" resource-id="" class="android.widget.TextView" package="{package}" '
            f'content-desc="" password="false" bounds="[63,250][1017,330]" />'
            f'<node index="1" text="{code_text}" hint="Code" resource-id="{resource_id}" class="android.widget.EditText" '
            f'package="{package}" content-desc="code" password="false" bounds="{field}" />'
            f'<node index="2" text="Verify" resource-id="" class="android.widget.Button" package="{package}" content-desc="" '
            f'password="false" bounds="[63,530][1017,650]" />{extra}</node></hierarchy>')


TARGET = {'id': '1073741832', 'view_id': None, 'bounds': [63, 344, 1017, 470]}


def shown(r, xml, masks, package=SHOP):
    tree, boxes, counts = r.mask_tree(xml, masks, package)
    return tree, boxes, counts


def test_the_code_field_the_helper_filled_is_masked_until_the_phone_stops(m):
    """PHONEFIX-121 follow-up 10 (R5-1): the helper's field was found by position alone, so it may be the wrong box: it is a digest field
    and is never shown again, whatever the agent types into it; the code itself is also registered by value."""
    r, now = m.redact, [100.0]
    masks = r.MaskSet(clock=lambda: now[0])
    tree, _, counts = shown(r, screen('222222'), masks)
    assert '222222' in tree and not counts  # before fu1's report, nothing told the guard about this code
    masks.watch_fields(SHOP, [TARGET])
    tree, _, counts = shown(r, screen(''), masks)  # the capture can come before Android puts the code in
    assert counts == {'filled_code': 1}
    tree, boxes, counts = shown(r, screen('222222'), masks)
    assert '222222' not in tree and counts == {'filled_code': 1} and (63, 344, 1017, 470) in boxes
    now[0] += 60
    tree, _, _ = shown(r, screen('222222', field='[63,144][1017,270]'), masks)  # the keyboard went: same field, moved
    assert '222222' not in tree
    tree, _, _ = shown(r, screen('2222223'), masks)  # one character added: the code is still in it
    assert '222222' not in tree
    tree, _, counts = shown(r, screen('123'), masks)  # the agent cleared it and typed something else: still hidden (a guess is never let go)
    assert '123' not in tree and counts == {'filled_code': 1}


def test_a_field_that_moved_before_the_first_capture_still_has_its_code_masked(m):
    """The helper's bounds come from the fill request; the keyboard may pan the screen before the first capture. A centre that
    misses every text field never places the mask on a layout: every text field of the app is watched instead."""
    r = m.redact
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [TARGET])
    tree, boxes, counts = shown(r, screen('222222', field='[63,144][1017,270]'), masks)
    assert '222222' not in tree and counts == {'filled_code': 1} and boxes == [(63, 144, 1017, 270)]


def test_a_centre_on_another_text_field_still_masks_the_code(m):
    r = m.redact
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [TARGET])
    note = ('<node index="3" text="pick-up at 6" resource-id="" class="android.widget.EditText" package="dev.beacon.fixture.shop" '
            'content-desc="" password="false" bounds="[63,344][1017,470]" />')  # where the code field was when filled
    tree, _, counts = shown(r, screen('222222', field='[63,700][1017,826]', extra=note), masks)
    assert '222222' not in tree and counts == {'filled_code': 2}


def test_a_keeper_that_could_not_start_leaves_the_old_one_recorded(phone, m, monkeypatch, tmp_path):
    import psutil
    state = tmp_path / 'installs' / '0123456789abcdef'
    old, new = runtime(state, 'old'), runtime(state, 'new', selected=True)
    keeper = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
    try:
        born = psutil.Process(keeper.pid).create_time()
        host = m.install.phone_host(phone.folder)
        record = {'pid': keeper.pid, 'create_time': born, 'python': str(old.python)}
        m.host.write_json(host.folder.keeper_conf, record)

        def failing(python=None, replace=False):
            m.host.write_json(host.folder.keeper_conf, {'plugin_dir': 'x'})  # as _spawn_keeper does before it starts one
            raise m.host.PhoneError("The phone's process could not start.")

        monkeypatch.setattr(host, '_spawn_keeper', failing)
        monkeypatch.setattr(m.host, 'keeper_python', lambda env, candidates=None, **kw: str(candidates[0]) if candidates else None)
        assert host.follow_runtime() == 'kept' and keeper.poll() is None
        assert m.host.read_json(host.folder.keeper_conf) == record
    finally:
        keeper.kill()


def test_the_mask_survives_another_apps_screen_and_the_apps_own_screen_without_the_field(m):
    """PHONEFIX-121 follow-up 6 (R3-1): another app's tree (the status bar's window, a prompt) says nothing about a watch: it neither
    masks it nor ends it. Follow-up 10 (R5-1): neither does a screen of the app's own with no such field; a field found by position alone
    is a digest field, and the app a code app, until the stop."""
    r = m.redact
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [TARGET])
    assert '222222' not in shown(r, screen('222222'), masks)[0]
    shown(r, screen('', package='com.example.other'), masks, package='com.example.other')  # another app in front
    assert masks.watching(SHOP) is True
    no_field = ('<hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="dev.beacon.fixture.shop" '
                'content-desc="" password="false" bounds="[0,0][1080,2400]">'
                '<node index="0" text="Kanto Foods · Welcome" resource-id="" class="android.widget.TextView" package="dev.beacon.fixture.shop" '
                'content-desc="" password="false" bounds="[63,250][1017,330]" />'
                '<node index="1" text="Signed in" resource-id="" class="android.widget.TextView" package="dev.beacon.fixture.shop" '
                'content-desc="" password="false" bounds="[63,344][1017,470]" /></node></hierarchy>')
    shown(r, no_field, masks)  # the shop's own screen, no code field in it
    assert masks.watching(SHOP) is True
    assert 'kanto-user' not in shown(r, screen('kanto-user', package=SHOP), masks)[0]  # a text field of the code app: hidden
    assert '222222' not in shown(r, screen('Your code 222222 was used'), masks)[0]  # the code itself stays masked anywhere


def test_a_field_is_found_by_its_view_id_and_unplaced_fields_mask_every_text_field(m):
    r = m.redact
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'id': '9', 'view_id': f'{SHOP}:id/code', 'bounds': None}])
    tree, _, counts = shown(r, screen('482913', resource_id=f'{SHOP}:id/code'), masks)
    assert '482913' not in tree and counts == {'filled_code': 1}
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [])  # a helper from before fu1: no targets
    other_field = ('<node index="3" text="note" resource-id="" class="android.widget.EditText" package="dev.beacon.fixture.shop" '
                   'content-desc="" password="false" bounds="[63,700][1017,800]" />')
    tree, _, counts = shown(r, screen('482913', extra=other_field), masks)
    assert '482913' not in tree and 'note' not in tree and counts == {'filled_code': 2}
    assert 'text="Verify"' in tree and 'text="Kanto Foods · Enter code"' in tree  # buttons and labels stay readable


def test_an_unreadable_screen_in_that_app_is_refused_and_the_image_is_blacked_out(m):
    from PIL import Image
    r = m.redact
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [TARGET])
    assert r.mask_capture('<hierarchy/>', None, masks, SHOP) == {'refused': r.REFUSED}
    image = Image.new('RGB', (720, 1600), (250, 250, 200))
    out = r.mask_capture(screen('222222'), image, masks, SHOP, screen=(1080, 2400))
    assert out['masked'] == {'filled_code': 1} and out['image'].getpixel((360, 271)) == (0, 0, 0)
    assert out['image'].getpixel((360, 100)) == (250, 250, 200)


# -- Show passwords ----------------------------------------------------------------------------------------------------

def test_first_start_turns_show_passwords_off(tmp_path, m):
    class Session:
        def __init__(self):
            self.calls = []

        def shell(self, args, timeout=60, **_):
            self.calls.append(args if isinstance(args, str) else tuple(args))
            return SimpleNamespace(stdout='', returncode=0)

    s = Session()
    adb = SimpleNamespace(session=lambda: __import__('contextlib').nullcontext(s))
    folder = m.host.Folder(tmp_path / 'p')
    folder.run.mkdir(parents=True)
    result = m.host.PhoneHost(folder).first_start(adb)
    assert result['show_password_off'] == 'ok' and ('settings', 'put', 'system', 'show_password', '0') in s.calls
    s2 = Session()
    adb2 = SimpleNamespace(session=lambda: __import__('contextlib').nullcontext(s2))
    assert m.host.PhoneHost(folder).first_start(adb2)['show_password_off'] == 'done'
