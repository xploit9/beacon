"""Black-box tests for the Beacon installer; all Hermes commands are fakes."""

from __future__ import annotations

import json
import os
from contextlib import closing
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest


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
    print("Dashboard: https://beacon.example.test\nPairing code: ABC-723\nexpires in 10 min")
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


if __name__ == "__main__":
    unittest.main()
