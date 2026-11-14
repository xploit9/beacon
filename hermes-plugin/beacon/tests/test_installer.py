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
    print(json.dumps([{"name": name, "status": "disabled" if name in state.get("disabled", []) else "enabled", "version": state.get("versions", {}).get(name, "")} for name in state["plugins"]]))
elif len(args) == 3 and args[:2] == ["plugins", "enable"]:
    state["disabled"] = [name for name in state.get("disabled", []) if name != args[2]]
    state_path.write_text(json.dumps(state), encoding="utf-8")
    print("fake plugin enabled")
elif args == ["config", "get", "dashboard.public_url", "--json"]:
    print(json.dumps(state.get("public_url", "")))
elif args == ["config", "get", "dashboard.port", "--json"]:
    print(json.dumps(state.get("dashboard_port", 9119)))
elif args == ["dashboard", "--status"]:
    print("1 hermes dashboard/serve process(es) running:" if state.get("dashboard_running") else "No hermes dashboard or serve processes running.")
elif len(args) >= 3 and args[:2] == ["plugins", "install"]:
    assert args[2] == "xploit9/beacon/hermes-plugin/beacon" and "--enable" in args
    if "beacon" in state["plugins"] and "--force" not in args:
        print("Plugin 'beacon' is already installed; use --force to reinstall.", file=sys.stderr)
        raise SystemExit(1)
    state["plugins"] = sorted(set(state["plugins"] + ["beacon"]))
    if "--ref" in args:
        state.setdefault("pinned", {})["beacon"] = args[args.index("--ref") + 1]
    state_path.write_text(json.dumps(state), encoding="utf-8")
    print("fake plugin installed")
elif args == ["plugins", "update", "beacon"]:
    # Hermes refuses to pull a plugin that was installed on one commit (hermes_cli/plugins_cmd_update.py).
    if "beacon" in state.get("pinned", {}):
        print("Plugin 'beacon' is pinned to a commit; run `hermes plugins install ... --force --ref <sha>` to move it.", file=sys.stderr)
        raise SystemExit(1)
    print("fake plugin updated")
elif args == ["dashboard", "--stop"]:
    print("fake dashboard stopped")
else:
    print("unexpected fake Hermes command: " + repr(args), file=sys.stderr)
    raise SystemExit(2)
'''


COMMIT = "0123456789abcdef0123456789abcdef01234567"

# Runs the installer with GitHub's API answered from FAKE_GITHUB; every other address (the dashboard probe) is untouched.
GITHUB_WRAPPER = r'''import io, json, os, runpy, sys, urllib.error, urllib.request

facts = json.loads(os.environ["FAKE_GITHUB"])
real_open = urllib.request.OpenerDirector.open


class Answer(io.BytesIO):
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def fake_open(self, request, *args, **kwargs):
    url = getattr(request, "full_url", request)
    if not url.startswith("https://api.github.com/"):
        return real_open(self, request, *args, **kwargs)
    with open(os.environ["FAKE_GITHUB_LOG"], "a", encoding="utf-8") as log:
        log.write(json.dumps([url, request.get_header("Accept")]) + "\n")
    row = facts["commit"] if "/commits/" in url else facts["latest"]
    if row["status"] != 200:
        raise urllib.error.HTTPError(url, row["status"], "fake", {}, io.BytesIO(b""))
    return Answer(row["body"].encode("utf-8"))


urllib.request.OpenerDirector.open = fake_open
installer, *rest = sys.argv[1:]
sys.argv = [installer, *rest]
runpy.run_path(installer, run_name="__main__")
'''


def github(tag: str = "v1.2.0", commit: str = COMMIT, *, latest: int = 200, commits: int = 200, **release: object) -> str:
    body = {"tag_name": tag, "draft": False, "prerelease": False, **release}
    return json.dumps({"latest": {"status": latest, "body": json.dumps(body)}, "commit": {"status": commits, "body": commit}})


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
        self.github_log = self.base / "github.jsonl"
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
        result["FAKE_GITHUB"] = github()
        result["FAKE_GITHUB_LOG"] = str(self.github_log)
        for key, value in changes.items():
            if value is None:
                result.pop(key, None)
            else:
                result[key] = value
        return result

    def run_installer(self, *args: str, input_text: str = "", **env_changes: str | None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "-c", GITHUB_WRAPPER, str(INSTALLER), *args],
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

    PINNED_INSTALL = ["plugins", "install", "xploit9/beacon/hermes-plugin/beacon", "--force", "--ref", COMMIT, "--enable"]

    def test_a_fresh_install_is_pinned_to_the_latest_release(self) -> None:
        install = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertEqual(install.returncode, 0, install.stderr + install.stdout)
        run = [args for _, args in self.commands() if args[:2] in (["plugins", "install"], ["plugins", "update"])]
        self.assertEqual(run, [self.PINNED_INSTALL])
        asked = [json.loads(line) for line in self.github_log.read_text(encoding="utf-8").splitlines()]
        self.assertEqual([url for url, _ in asked], ["https://api.github.com/repos/xploit9/beacon/releases/latest",
                                                      "https://api.github.com/repos/xploit9/beacon/commits/v1.2.0"])
        self.assertEqual(asked[1][1], "application/vnd.github.sha")

    def test_an_existing_install_is_reinstalled_pinned_and_never_git_pulled(self) -> None:
        # Once the app (or this installer) has installed Beacon it is pinned, and Hermes refuses `plugins update` for a pinned plugin.
        for state in ({"plugins": ["beacon"]}, {"plugins": ["beacon"], "pinned": {"beacon": "f" * 40}}):
            with self.subTest(pinned="pinned" in state):
                self.log.unlink(missing_ok=True)
                self.write_state(**state)
                update = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
                self.assertEqual(update.returncode, 0, update.stderr + update.stdout)
                self.assertIn("Paste this link into Beacon: https://beacon.example.test", update.stdout)
                self.assertFalse(any(args[:2] == ["plugins", "update"] for _, args in self.commands()))
                self.assertIn(self.PINNED_INSTALL, [args for _, args in self.commands()])
                self.assertEqual(json.loads(self.state_path.read_text(encoding="utf-8"))["pinned"], {"beacon": COMMIT})

    def test_a_newer_beacon_is_left_alone_and_the_link_is_still_printed(self) -> None:
        # The owner can be on a candidate build while the latest published release is older; "install Beacon" must not put the older one over it.
        for installed in ("1.2.1", "1.3.0", "2.0.0", "1.10.0"):
            with self.subTest(installed=installed):
                self.log.unlink(missing_ok=True)
                self.write_state(plugins=["beacon"], versions={"beacon": installed}, pinned={"beacon": "f" * 40})
                result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n",
                                            FAKE_GITHUB=github(tag="v1.9.0" if installed == "1.10.0" else "v1.2.0"))
                self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
                self.assertFalse(any(args[:2] in (["plugins", "install"], ["plugins", "update"]) for _, args in self.commands()))
                said = [line for line in result.stdout.splitlines() if "newer than the latest release" in line]
                self.assertEqual(len(said), 1, result.stdout)
                self.assertIn(installed, said[0])
                self.assertNotIn("Beacon plugin installation completed", result.stdout)
                self.assertIn("Paste this link into Beacon: https://beacon.example.test", result.stdout)
                self.assertEqual(json.loads(self.state_path.read_text(encoding="utf-8"))["pinned"], {"beacon": "f" * 40})

    def changing_commands(self) -> list[list[str]]:
        return [args for _, args in self.commands() if args[:2] in (["plugins", "install"], ["plugins", "update"], ["plugins", "enable"])]

    def test_a_newer_beacon_that_is_switched_off_is_switched_on_and_nothing_is_installed(self) -> None:
        # R11 Nice 8: leaving a newer Beacon alone must not leave it off; the link printed next would point at a dashboard with no Beacon tab.
        self.write_state(plugins=["beacon"], versions={"beacon": "1.3.0"}, pinned={"beacon": "f" * 40}, disabled=["beacon"])
        result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(self.changing_commands(), [["plugins", "enable", "beacon"]])
        self.assertEqual(json.loads(self.state_path.read_text(encoding="utf-8"))["disabled"], [])
        self.assertEqual(json.loads(self.state_path.read_text(encoding="utf-8"))["pinned"], {"beacon": "f" * 40})
        self.assertIn("newer than the latest release", result.stdout)
        self.assertNotIn("Beacon plugin installation completed", result.stdout)
        self.assertIn("Paste this link into Beacon: https://beacon.example.test", result.stdout)

    def test_a_newer_beacon_that_is_already_on_runs_nothing(self) -> None:
        self.write_state(plugins=["beacon"], versions={"beacon": "1.3.0"}, pinned={"beacon": "f" * 40})
        result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(self.changing_commands(), [])

    def test_an_older_beacon_that_is_switched_off_still_gets_only_the_pinned_install(self) -> None:
        # Only the newer-Beacon branch switches a plugin on by itself; the pinned install already carries --enable.
        self.write_state(plugins=["beacon"], versions={"beacon": "1.1.33"}, disabled=["beacon"])
        result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(self.changing_commands(), [self.PINNED_INSTALL])

    def test_an_equal_older_or_unreadable_version_is_pinned_to_the_release_as_before(self) -> None:
        for installed in ("1.2.0", "1.1.33", "1.2.0-rc1", "nightly", ""):
            with self.subTest(installed=installed):
                self.log.unlink(missing_ok=True)
                self.write_state(plugins=["beacon"], versions={"beacon": installed})
                result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
                self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
                self.assertIn(self.PINNED_INSTALL, [args for _, args in self.commands()])
                self.assertNotIn("newer than the latest release", result.stdout)
                self.assertIn("Paste this link into Beacon: https://beacon.example.test", result.stdout)

    def test_the_scan_line_says_what_a_forced_install_still_does(self) -> None:
        result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n")
        self.assertIn("Hermes' security scan still runs; a dangerous verdict still stops the install", result.stdout)
        self.assertNotIn("scan and prompts", result.stdout)

    def test_a_release_that_cannot_be_pinned_stops_before_anything_changes(self) -> None:
        cases = {
            "rate limited": (github(latest=403), "GitHub is busy"),
            "too many requests": (github(commits=429), "GitHub is busy"),
            "no release": (github(latest=404), "latest Beacon release"),
            "tag names no commit": (github(commits=422), "latest Beacon release"),
            "not a commit": (github(commit="main"), "latest Beacon release"),
            "short commit": (github(commit=COMMIT[:12]), "latest Beacon release"),
            "upper-case commit": (github(commit=COMMIT.upper()), "latest Beacon release"),
            "draft": (github(draft=True), "latest Beacon release"),
            "prerelease": (github(prerelease=True), "latest Beacon release"),
            "tag that is not a version": (github(tag="nightly"), "latest Beacon release"),
        }
        for name, (answers, message) in cases.items():
            with self.subTest(name):
                self.log.unlink(missing_ok=True)
                self.write_state(plugins=["beacon"], pinned={"beacon": "f" * 40})
                result = self.run_installer("--home", str(self.home), "--profile", "default", input_text="\n", FAKE_GITHUB=answers)
                self.assertEqual(result.returncode, 1, result.stderr + result.stdout)
                self.assertIn(message, result.stdout)
                self.assertFalse(any(args[:2] in (["plugins", "install"], ["plugins", "update"]) for _, args in self.commands()))
                self.assertNotIn("Paste this link", result.stdout)

    def test_the_link_to_paste_is_printed_and_nothing_is_paired(self) -> None:
        result = self.run_installer("--home", str(self.home), "--profile", "default")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn("Paste this link into Beacon: https://beacon.example.test", result.stdout)
        self.assertNotIn("Pairing", result.stdout)
        self.assertFalse(any(args[:1] == ["beacon"] for _, args in self.commands()))

    def test_without_a_public_https_url_no_link_is_printed(self) -> None:
        self.write_state(public_url="")
        result = self.run_installer("--home", str(self.home), "--profile", "default")
        self.assertEqual(result.returncode, 1, result.stderr + result.stdout)
        self.assertNotIn("Paste this link", result.stdout)
        self.assertIn("Beacon can't connect a phone", result.stdout)

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
        mutating = (["plugins", "install"], ["plugins", "update"], ["dashboard", "--stop"])
        self.assertFalse(any(args[:2] in mutating for _, args in self.commands()))
        self.assertFalse(self.github_log.exists(), "a dry run makes no network call")


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


def test_public_url_is_configured_before_dashboard_restart(installer, tmp_path, monkeypatch):
    from argparse import Namespace
    events = []
    monkeypatch.setattr(installer.shutil, 'which', lambda name: 'hermes')
    monkeypatch.setattr(installer, '_select_home', lambda home: tmp_path)
    monkeypatch.setattr(installer, '_select_profile', lambda home, profile: 'default')
    monkeypatch.setattr(installer, '_installed_plugins', lambda *args: {'beacon': ''})
    monkeypatch.setattr(installer, '_release_commit', lambda: (COMMIT, '1.2.0'))  # no network in these
    monkeypatch.setattr(installer, '_dashboard_running', lambda *args: True)
    monkeypatch.setattr(installer, '_dashboard_port', lambda *args: 9119)
    monkeypatch.setattr(installer, '_public_url', lambda *args: None)
    monkeypatch.setattr(installer, '_run_stream', lambda *args: None)
    monkeypatch.setattr(installer, '_configure_tailscale', lambda *args: events.append('configure') or 'https://beacon.example.test')
    monkeypatch.setattr(installer, '_restart_dashboard', lambda *args: events.append(('restart', args[-1])))
    assert installer._install(Namespace(home=None, profile=None, dry_run=False, yes=True, tailscale=True)) == 0
    assert events == ['configure', ('restart', 'https://beacon.example.test')]


def test_declining_restart_does_not_publish_new_route(installer, tmp_path, monkeypatch):
    from argparse import Namespace
    events = []
    monkeypatch.setattr(installer.shutil, 'which', lambda name: 'hermes')
    monkeypatch.setattr(installer, '_select_home', lambda home: tmp_path)
    monkeypatch.setattr(installer, '_select_profile', lambda home, profile: 'default')
    monkeypatch.setattr(installer, '_installed_plugins', lambda *args: {'beacon': ''})
    monkeypatch.setattr(installer, '_release_commit', lambda: (COMMIT, '1.2.0'))  # no network in these
    monkeypatch.setattr(installer, '_dashboard_running', lambda *args: True)
    monkeypatch.setattr(installer, '_dashboard_port', lambda *args: 9119)
    monkeypatch.setattr(installer, '_public_url', lambda *args: None)
    monkeypatch.setattr(installer, '_run_stream', lambda *args: None)
    monkeypatch.setattr(installer, '_yes_no', lambda prompt: False)
    monkeypatch.setattr(installer, '_configure_tailscale', lambda *args: events.append('configure') or 'https://beacon.example.test')
    monkeypatch.setattr(installer, '_restart_dashboard', lambda *args: events.append('restart'))
    assert installer._install(Namespace(home=None, profile=None, dry_run=False, yes=False, tailscale=True)) == 1
    assert events == []


def test_restart_probe_requires_configured_public_route(installer, monkeypatch):
    clock = iter([0, 0, 2])
    checked = []
    monkeypatch.setattr(installer.time, 'monotonic', lambda: next(clock))
    monkeypatch.setattr(installer.time, 'sleep', lambda delay: None)
    monkeypatch.setattr(installer, '_route_status', lambda url: checked.append(url) or (200 if url.startswith('http://') else None))
    assert not installer._wait_for_beacon_route(9119, 'https://beacon.example.test', None, seconds=1)
    assert any(url.startswith('https://beacon.example.test/') for url in checked)


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


def test_no_document_installs_beacon_without_pinning_the_release():
    # A bare `plugins install xploit9/beacon...` clones the default branch, whatever is on it: not the published release, and not pinned.
    documents = [ROOT / "README.md", ROOT / "hermes-plugin" / "beacon" / "README.md", *sorted((ROOT / "docs").glob("*.md"))]
    assert len(documents) >= 3
    for path in documents:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "plugins install xploit9/beacon" in line:
                assert "--ref" in line, f"{path.relative_to(ROOT)}:{number} installs Beacon without --ref"


def test_the_plugin_readme_installs_through_the_installer_not_the_default_branch():
    text = (ROOT / "hermes-plugin" / "beacon" / "README.md").read_text(encoding="utf-8")
    install = text.split("## Install", 1)[1].split("\n## ", 1)[0]
    assert "python beacon_install.py" in install
    assert "plugins install xploit9/beacon/hermes-plugin/beacon --force --ref <commit of the latest release> --enable" in install
    assert "plugins update beacon" in install and "does not work once Beacon is pinned" in install


def test_the_scan_wording_is_true_for_a_forced_install():
    # `--force` accepts a caution verdict without asking (Hermes tools/plugin_guard.py); only a dangerous verdict stops.
    said = "Hermes' security scan still runs; a dangerous verdict still stops the install"
    guide = (ROOT / "docs" / "install-with-agent.md").read_text(encoding="utf-8")
    script = INSTALLER.read_text(encoding="utf-8")
    assert said in guide and said in script
    for name, text in (("guide", guide), ("installer", script)):
        assert "scan and prompts" not in text, name


def test_agent_guide_points_at_the_installer():
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    guide = (ROOT / "docs" / "install-with-agent.md").read_text(encoding="utf-8")
    assert "raw.githubusercontent.com/xploit9/beacon/main/hermes-plugin/beacon/install/beacon_install.py" in readme
    assert INSTALLER.is_file()
    for phrase in ("plugins install xploit9/beacon/hermes-plugin/beacon --force --ref", "--dry-run"):
        assert phrase in guide, phrase
    # A pinned plugin cannot be pulled: no document may send a reader to `plugins update beacon` as the way to update.
    for name, text in (("guide", guide), ("plugin README", (ROOT / "hermes-plugin" / "beacon" / "README.md").read_text(encoding="utf-8"))):
        assert "or update with `hermes -p <profile> plugins update beacon`" not in text, name
        assert "use `hermes -p <profile> plugins update beacon`" not in text, name


if __name__ == "__main__":
    unittest.main()
