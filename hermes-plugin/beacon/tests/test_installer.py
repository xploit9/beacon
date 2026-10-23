"""Black-box tests for the Beacon installer; all Hermes commands are fakes."""

from __future__ import annotations

import json
import os
from contextlib import closing
import importlib.util
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import pytest


ROOT = Path(__file__).resolve().parents[3]
INSTALLER = ROOT / "hermes-plugin" / "beacon" / "install" / "beacon_install.py"


FAKE_HERMES = r'''import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
profile = "default"
if len(args) >= 2 and args[0] in {"-p", "--profile"}:
    profile, args = args[1], args[2:]
home = Path(os.environ["HERMES_HOME"])
profile_home = home if profile == "default" else home / "profiles" / profile
state_path = Path(os.environ["FAKE_HERMES_STATE"])
log_path = Path(os.environ["FAKE_HERMES_LOG"])
state = json.loads(state_path.read_text(encoding="utf-8"))
with log_path.open("a", encoding="utf-8") as stream:
    stream.write(json.dumps([profile, args]) + "\n")

if args == ["plugins", "list", "--json"]:
    print(json.dumps([{"name": name, "status": "enabled"} for name in state["plugins"]]))
elif args == ["config", "get", "dashboard.public_url", "--json"]:
    print(json.dumps(state.get("public_url", "")))
elif args == ["config", "get", "dashboard.port", "--json"]:
    print(json.dumps(state.get("dashboard_port", 9119)))
elif args == ["dashboard", "--status"]:
    print("1 hermes dashboard/serve process(es) running:" if state.get("dashboard_running") else "No hermes dashboard or serve processes running.")
elif len(args) >= 3 and args[:2] == ["plugins", "install"]:
    assert args[2] == "xploit9/beacon/hermes-plugin/beacon" and "--enable" in args
    state["plugins"] = sorted(set(state["plugins"] + ["beacon"]))
    state_path.write_text(json.dumps(state), encoding="utf-8")
    print("fake plugin installed")
elif args == ["plugins", "update", "beacon"]:
    print("fake plugin updated")
elif len(args) >= 2 and args[:2] == ["beacon", "pair"]:
    # Real Hermes reroutes plugin stdout to stderr once its gateway module loads.
    print("Dashboard: https://beacon.example.test\nPairing code: ABC-723\nexpires in 10 min", file=sys.stderr)
elif args == ["dashboard", "--stop"]:
    print("fake dashboard stopped")
else:
    print("unexpected fake Hermes command: " + repr(args), file=sys.stderr)
    raise SystemExit(2)
'''


class InstallerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.home = self.base / "hermes-home"
        self.home.mkdir()
        self.user_home = self.base / "user"
        self.user_home.mkdir()
        (self.user_home / ".hermes").mkdir()
        (self.user_home / 'AppData' / 'Local' / 'hermes').mkdir(parents=True)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        self.fake_script = self.bin / "fake_hermes.py"
        self.fake_script.write_text(FAKE_HERMES, encoding="utf-8")
        self.log = self.base / "commands.jsonl"
        self.state_path = self.base / "state.json"
        self.write_state()
        if os.name == "nt":
            self.hermes_command = self.bin / "hermes.cmd"
            command = subprocess.list2cmdline([sys.executable, str(self.fake_script)])
            self.hermes_command.write_text("@echo off\r\n" + command + " %*\r\n", encoding="utf-8")
        else:
            self.hermes_command = self.bin / "hermes"
            self.hermes_command.write_text(
                f"#!{sys.executable}\nexec({FAKE_HERMES!r})\n", encoding="utf-8"
            )
            self.hermes_command.chmod(0o755)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_state(self, **changes: object) -> None:
        state = {
            "plugins": [],
            "public_url": "https://beacon.example.test",
            "dashboard_port": 9119,
            "dashboard_running": False,
            "require_backup_before_remove": False,
        }
        state.update(changes)
        self.state_path.write_text(json.dumps(state), encoding="utf-8")

    def env(self, **changes: str | None) -> dict[str, str]:
        result = os.environ.copy()
        result["PATH"] = str(self.bin) + os.pathsep + result.get("PATH", "")
        result["HOME"] = str(self.user_home)
        result["USERPROFILE"] = str(self.user_home)
        result['LOCALAPPDATA'] = str(self.user_home / 'AppData' / 'Local')
        result["HERMES_HOME"] = str(self.home)
        result["FAKE_HERMES_STATE"] = str(self.state_path)
        result["FAKE_HERMES_LOG"] = str(self.log)
        for key, value in changes.items():
            if value is None:
                result.pop(key, None)
            else:
                result[key] = value
        return result

    def run_installer(self, *args: str, input_text: str = "", **env_changes: str | None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(INSTALLER), *args],
            cwd=ROOT,
            env=self.env(**env_changes),
            input=input_text,
            text=True,
            capture_output=True,
            timeout=20,
            check=False,
        )

    def commands(self) -> list[tuple[str, list[str]]]:
        if not self.log.exists():
            return []
        return [(entry[0], entry[1]) for entry in map(json.loads, self.log.read_text(encoding="utf-8").splitlines())]

    def test_home_and_profile_prompts_only_when_ambiguous(self) -> None:
        only_default = self.run_installer("--dry-run", HERMES_HOME=None)
        self.assertEqual(only_default.returncode, 0, only_default.stderr)
        self.assertNotIn("Select Hermes home", only_default.stdout)
        self.assertNotIn("Select Hermes profile", only_default.stdout)

        other_home = self.base / "other-home"
        other_home.mkdir()
        multiple_homes = self.run_installer("--dry-run", input_text="1\n", HERMES_HOME=str(other_home))
        self.assertEqual(multiple_homes.returncode, 0, multiple_homes.stderr)
        self.assertIn("Select Hermes home", multiple_homes.stdout)
        self.assertNotIn("Select Hermes profile", multiple_homes.stdout)

        (self.home / "profiles" / "work").mkdir(parents=True)
        (self.home / "profiles" / "dev").mkdir()
        multiple_profiles = self.run_installer("--home", str(self.home), "--dry-run", input_text="2\n")
        self.assertEqual(multiple_profiles.returncode, 0, multiple_profiles.stderr)
        self.assertIn("Select Hermes profile", multiple_profiles.stdout)

        skipped = self.run_installer("--home", str(self.home), "--profile", "default", "--dry-run")
        self.assertEqual(skipped.returncode, 0, skipped.stderr)
        self.assertNotIn("Select Hermes home", skipped.stdout)
        self.assertNotIn("Select Hermes profile", skipped.stdout)

    def test_installs_when_missing_and_updates_when_already_installed(self) -> None:
        install = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertEqual(install.returncode, 0, install.stderr + install.stdout)
        self.assertTrue(any(args[:2] == ["plugins", "install"] for _, args in self.commands()))
        self.assertFalse(any(args[:2] == ["plugins", "update"] for _, args in self.commands()))

        self.log.unlink()
        self.write_state(plugins=["beacon"])
        update = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertEqual(update.returncode, 0, update.stderr + update.stdout)
        self.assertFalse(any(args[:2] == ["plugins", "install"] for _, args in self.commands()))
        self.assertIn(["plugins", "update", "beacon"], [args for _, args in self.commands()])

    def test_pair_block_is_printed(self) -> None:
        result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn("Dashboard: https://beacon.example.test", result.stdout)
        self.assertIn("Pairing code: ABC-723", result.stdout)
        self.assertIn("expires in 10 min", result.stdout)
        self.assertIn(["beacon", "pair", "--name", "My phone"], [args for _, args in self.commands()])

    def test_failed_scheduled_task_falls_back_to_a_detached_dashboard(self) -> None:
        spec = importlib.util.spec_from_file_location("beacon_install_under_test", INSTALLER)
        installer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(installer)
        calls: list[list[str]] = []
        route_back = iter([False, True])  # the task never brings Beacon back; the detached dashboard does
        with mock.patch.object(installer.sys, "platform", "win32"), \
                mock.patch.object(installer, "_windows_dashboard_task", return_value="Hermes Dashboard"), \
                mock.patch.object(installer, "_dashboard_pids", return_value={1}), \
                mock.patch.object(installer.subprocess, "run",
                                  side_effect=lambda argv, **_: calls.append(argv) or subprocess.CompletedProcess(argv, 0)), \
                mock.patch.object(installer, "_run_mutation", side_effect=lambda *args: calls.append(list(args[3:]))), \
                mock.patch.object(installer, "_detached", side_effect=lambda argv, home: calls.append(["detached", *argv[3:]])), \
                mock.patch.object(installer, "_wait_for_beacon_route", side_effect=lambda *args, **kwargs: next(route_back)):
            installer._restart_dashboard("hermes", self.home, "default", 9119, None)
        self.assertEqual(calls, [
            ["schtasks", "/end", "/tn", "Hermes Dashboard"], ["dashboard", "--stop"],
            ["schtasks", "/run", "/tn", "Hermes Dashboard"], ["dashboard", "--stop"],
            ["detached", "dashboard", "--no-open", "--port", "9119"],
        ])

    def test_dry_run_changes_nothing(self) -> None:
        self.write_state(plugins=["beacon"])
        before = sorted((str(path.relative_to(self.home)), path.read_bytes() if path.is_file() else None)
                        for path in self.home.rglob("*"))

        result = self.run_installer("--home", str(self.home), "--profile", "default", "--dry-run")

        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        after = sorted((str(path.relative_to(self.home)), path.read_bytes() if path.is_file() else None)
                       for path in self.home.rglob("*"))
        self.assertEqual(after, before)
        mutating = (["plugins", "install"], ["plugins", "update"], ["beacon", "pair"], ["dashboard", "--stop"])
        self.assertFalse(any(args[:2] in mutating for _, args in self.commands()))


@pytest.fixture
def installer():
    spec = importlib.util.spec_from_file_location('beacon_install_test', INSTALLER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize('configured', [True, False])
def test_windows_default_home_matches_hermes(installer, tmp_path, monkeypatch, configured):
    monkeypatch.setattr(installer.sys, 'platform', 'win32')
    monkeypatch.delenv('HERMES_HOME', raising=False)
    monkeypatch.delenv('HERMES_DATA_DIR_SUFFIX', raising=False)
    monkeypatch.setattr(installer.Path, 'home', lambda: tmp_path)
    if configured:
        monkeypatch.setenv('LOCALAPPDATA', str(tmp_path / 'local-data'))
        expected = tmp_path / 'local-data' / 'hermes'
    else:
        monkeypatch.delenv('LOCALAPPDATA', raising=False)
        expected = tmp_path / 'AppData' / 'Local' / 'hermes'
    assert installer._candidate_homes() == [expected.resolve()]


def test_tailscale_serve_runs_in_background_with_a_deadline(installer, tmp_path, monkeypatch):
    commands = []
    monkeypatch.setattr(installer.shutil, 'which', lambda name: 'tailscale')
    monkeypatch.setattr(installer.subprocess, 'run', lambda argv, **kwargs:
        commands.append((argv, kwargs)) or subprocess.CompletedProcess(argv, 0))
    monkeypatch.setattr(installer, '_tailscale_url', lambda: 'https://beacon.example.test')
    monkeypatch.setattr(installer, '_public_url', lambda *args: 'https://beacon.example.test')
    monkeypatch.setattr(installer, '_run_mutation', lambda *args: None)
    assert installer._configure_tailscale('hermes', tmp_path, 'default', 9119, allow=True) == 'https://beacon.example.test'
    assert commands[0][0] == ['tailscale', 'serve', '--bg', '9119']
    assert commands[0][1]['timeout'] == 60


def test_tailscale_timeout_is_a_clear_installer_failure(installer, tmp_path, monkeypatch):
    monkeypatch.setattr(installer.shutil, 'which', lambda name: 'tailscale')
    def fail(argv, **kwargs): raise subprocess.TimeoutExpired(argv, 60)
    monkeypatch.setattr(installer.subprocess, 'run', fail)
    with pytest.raises(installer.InstallError, match='Tailscale'):
        installer._configure_tailscale('hermes', tmp_path, 'default', 9119, allow=True)


@pytest.mark.parametrize('status,ready', [(200, True), (401, False), (404, False)])
def test_restart_probe_needs_a_public_beacon_route_not_a_generic_auth_response(installer, monkeypatch, status, ready):
    clock = iter([0, 0, 2])
    checked = []
    monkeypatch.setattr(installer.time, 'monotonic', lambda: next(clock))
    monkeypatch.setattr(installer.time, 'sleep', lambda delay: None)
    monkeypatch.setattr(installer, '_route_status', lambda url: checked.append(url) or status)
    assert installer._wait_for_beacon_route(9119, None, None, seconds=1, profile='work') is ready
    assert checked == ['http://127.0.0.1:9119/dashboard-plugins/beacon/manifest.json?profile=work']


def test_scheduled_task_match_requires_the_selected_home_and_profile(installer, tmp_path, monkeypatch):
    import csv
    import io
    rows = []
    for name, command in [('unrelated', 'hermes -p other dashboard'),
                          ('selected', f'cmd /c set HERMES_HOME={tmp_path}&& hermes -p work dashboard')]:
        row = [''] * 9
        row[1], row[8] = name, command
        rows.append(row)
    output = io.StringIO()
    csv.writer(output).writerows(rows)
    monkeypatch.setattr(installer.subprocess, 'run', lambda *args, **kwargs:
                        subprocess.CompletedProcess(args[0], 0, stdout=output.getvalue()))
    assert installer._windows_dashboard_task(tmp_path, 'work') == 'selected'
    assert installer._windows_dashboard_task(tmp_path, 'default') is None


@pytest.mark.parametrize('payload,status', [
    (b'{"name":"beacon","api":"plugin_api.py"}', 200),
    (b'<html>Sign in</html>', None), (b'{"name":"another-plugin"}', None)])
def test_restart_probe_verifies_the_beacon_manifest_payload(installer, monkeypatch, payload, status):
    from contextlib import nullcontext
    from types import SimpleNamespace
    monkeypatch.setattr(installer, 'urlopen', lambda *args, **kwargs:
        nullcontext(SimpleNamespace(status=200, read=lambda limit: payload)))
    assert installer._route_status('https://beacon.example.test/dashboard-plugins/beacon/manifest.json') == status


def test_status_pid_selection_includes_machine_dashboard_for_named_profile(installer, tmp_path, monkeypatch):
    status = '4 hermes dashboard/serve process(es) running:\n' + '\n'.join([
        '    PID 10 [dashboard]: hermes -p work dashboard --port 9119',
        '    PID 20 [dashboard]: hermes -p other dashboard --port 9119',
        '    PID 30 [dashboard]: hermes -p work dashboard --port 9220',
        '    PID 40 [dashboard]: hermes -p default dashboard --port 9119 --open-profile work',
        '    PID 50 [dashboard]: hermes -p default dashboard --isolated --port 9119'])
    monkeypatch.setattr(installer, '_quiet', lambda *args: subprocess.CompletedProcess(args, 0, stdout=status))
    assert installer._dashboard_pids('hermes', tmp_path, 'work', 9119) == {10,40}
    assert installer._dashboard_pids('hermes', tmp_path, 'default', 9119) == {40,50}


@pytest.fixture
def service_process(tmp_path,monkeypatch):
    environment=['HERMES_HOME='+str(tmp_path)]
    read_bytes=Path.read_bytes
    def read(path):
        if path.as_posix()=='/proc/10/environ':
            if environment and isinstance(environment[0],Exception): raise environment[0]
            return ('\0'.join(environment)+'\0').encode()
        return read_bytes(path)
    monkeypatch.setattr(Path,'read_bytes',read)
    return environment


@pytest.mark.parametrize('profile,command', [
    ('default','/usr/bin/hermes dashboard --port 9119'),
    ('work','/usr/bin/hermes -p default dashboard --port=9119 --open-profile work'),
    ('work','/usr/bin/python3 -m hermes_cli.main -p default dashboard --port 9119'),
    ('work','/usr/bin/python3 /opt/venv/bin/hermes -p work dashboard --isolated --port 9119'),
])
def test_systemd_restart_proves_selected_home_dashboard_and_port(installer,tmp_path,monkeypatch,service_process,profile,command):
    calls=[]
    shown=(f'LoadState=loaded\nActiveState=active\nMainPID=10\nEnvironment="HERMES_HOME={tmp_path}"\n'
           f'EnvironmentFiles=\nUnsetEnvironment=\nExecStart={{ path={command.split()[0]} ; argv[]={command} ; ignore_errors=no ; }}\n')
    monkeypatch.setattr(installer.sys,'platform','linux')
    monkeypatch.setattr(installer.shutil,'which',lambda name:'/usr/bin/systemctl')
    def run(argv,**kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv,0,stdout='active' if 'is-active' in argv else shown)
    monkeypatch.setattr(installer.subprocess,'run',run)
    monkeypatch.setattr(installer,'_dashboard_pids',lambda *args:{10})
    monkeypatch.setattr(installer,'_wait_for_beacon_route',lambda *args,**kwargs:True)
    monkeypatch.setattr(installer,'_run_mutation',lambda *args:pytest.fail('verified service must not use raw stop'))
    monkeypatch.setattr(installer,'_detached',lambda *args:pytest.fail('verified service must not relaunch detached'))
    installer._restart_dashboard('hermes',tmp_path,profile,9119,None)
    assert [argv for argv in calls if 'restart' in argv]==[['systemctl','--user','restart','hermes-dashboard']]


@pytest.mark.parametrize('change', [
    'foreign_home','missing_home','duplicate_home','environment_file','unset_home','unknown_launch',
    'wrong_port','dynamic_port','foreign_isolated_profile','multiple_launches','unreadable_unit',
    'pid_not_selected','missing_pid','relative_home','ambiguous_port',
    'live_foreign_home','live_unknown_home','live_unreadable_home',
])
def test_unproven_systemd_unit_never_falls_through_to_disruption(installer,tmp_path,monkeypatch,service_process,change):
    calls=[]
    environment=f'"HERMES_HOME={tmp_path}"'
    command='/usr/bin/hermes -p default dashboard --port 9119'
    files='';unset=''
    if change=='foreign_home': environment=f'"HERMES_HOME={tmp_path / "foreign"}"'
    if change=='missing_home': environment=''
    if change=='duplicate_home': environment+=' "HERMES_HOME=/another/home"'
    if change=='relative_home': environment='HERMES_HOME=relative-home'
    if change=='environment_file': files='/some/override.env (ignore_errors=no)'
    if change=='unset_home': unset='HERMES_HOME'
    if change=='unknown_launch': command='/bin/sh -c "hermes dashboard --port 9119"'
    if change=='wrong_port': command=command.replace('9119','9220')
    if change=='dynamic_port': command=command.replace('9119','0')
    if change=='ambiguous_port': command+=' --port 9220'
    if change=='live_foreign_home': service_process[:]=['HERMES_HOME='+str(tmp_path/'foreign')]
    if change=='live_unknown_home': service_process.clear()
    if change=='live_unreadable_home': service_process[:]=[PermissionError('fixture inaccessible process environment')]
    if change=='foreign_isolated_profile': command='/usr/bin/hermes -p other dashboard --isolated --port 9119'
    start=f'{{ path={command.split()[0]} ; argv[]={command} ; ignore_errors=no ; }}'
    if change=='multiple_launches': start+=' '+start
    pid='20' if change=='pid_not_selected' else '' if change=='missing_pid' else '10'
    shown=(f'LoadState=loaded\nActiveState=active\nMainPID={pid}\nEnvironment={environment}\n'
           f'EnvironmentFiles={files}\nUnsetEnvironment={unset}\nExecStart={start}\n')
    monkeypatch.setattr(installer.sys,'platform','linux')
    monkeypatch.setattr(installer.shutil,'which',lambda name:'/usr/bin/systemctl')
    def run(argv,**kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv,1 if change=='unreadable_unit' else 0,
            stdout='active' if 'is-active' in argv else shown)
    monkeypatch.setattr(installer.subprocess,'run',run)
    monkeypatch.setattr(installer,'_dashboard_pids',lambda *args:{10})
    monkeypatch.setattr(installer,'_wait_for_beacon_route',lambda *args,**kwargs:True)
    monkeypatch.setattr(installer,'_run_mutation',lambda *args:pytest.fail('unproven unit must not stop a dashboard'))
    monkeypatch.setattr(installer,'_detached',lambda *args:pytest.fail('unproven unit must not relaunch a dashboard'))
    with pytest.raises(installer.InstallError,match='[Mm]anual') as failure:
        installer._restart_dashboard('hermes',tmp_path,'work',9119,None)
    assert str(tmp_path) in str(failure.value) and '9119' in str(failure.value)
    assert all('restart' not in argv for argv in calls)


def test_no_systemd_unit_keeps_the_existing_detached_restart_path(installer,tmp_path,monkeypatch):
    monkeypatch.setattr(installer.sys,'platform','linux')
    monkeypatch.setattr(installer.shutil,'which',lambda name:'/usr/bin/systemctl')
    monkeypatch.setattr(installer.subprocess,'run',lambda argv,**kwargs:
        subprocess.CompletedProcess(argv,4,stdout='LoadState=not-found\n'))
    assert installer._systemd_dashboard_unit(tmp_path,'work',9119,{10}) is None


@pytest.mark.parametrize('current,ready', [({10}, False), ({11}, True)])
def test_successful_manifest_does_not_hide_an_unchanged_dashboard_process(installer, tmp_path, monkeypatch, current, ready):
    clock = iter([0, 0, 2])
    monkeypatch.setattr(installer.time, 'monotonic', lambda: next(clock))
    monkeypatch.setattr(installer.time, 'sleep', lambda delay: None)
    monkeypatch.setattr(installer, '_route_status', lambda url: 200)
    monkeypatch.setattr(installer, '_dashboard_pids', lambda *args: current)
    assert installer._wait_for_beacon_route(9119, None, None, seconds=1, profile='work',
        hermes='hermes', home=tmp_path, previous_pids={10}) is ready


if __name__ == "__main__":
    unittest.main()
