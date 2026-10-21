from pathlib import Path
import os
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]


def test_native_installer_scan_allows_the_plugin_without_an_override():
    # A high finding turns Hermes' install verdict into "caution", which blocks a non-interactive install.
    guard = pytest.importorskip("tools.plugin_guard")
    result = guard.scan_plugin(ROOT)
    assert result.verdict == "safe", [(f.severity, f.file, f.pattern_id) for f in result.findings if f.severity in ("high", "critical")]
    assert guard.should_allow_plugin_install(result)[0] is True


def test_plugin_declares_no_dependencies_already_provided_by_hermes():
    import yaml

    manifest = yaml.safe_load((ROOT / "plugin.yaml").read_text(encoding="utf-8"))

    assert manifest["python_dependencies"] == []
    assert (ROOT / "requirements.txt").read_text(encoding="utf-8").strip() == ""


def test_plugin_api_imports_when_firebase_admin_is_unavailable():
    script = r"""
import importlib.abc
import importlib.util
import sys

class BlockFirebaseAdmin(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "firebase_admin" or fullname.startswith("firebase_admin."):
            raise ModuleNotFoundError(fullname)

sys.dont_write_bytecode = True
sys.meta_path.insert(0, BlockFirebaseAdmin())
spec = importlib.util.spec_from_file_location("cypher_dashboard_import_probe", sys.argv[1])
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
"""
    env = os.environ.copy()
    hermes_source = str(Path(sys.prefix).parent)
    env["PYTHONPATH"] = os.pathsep.join(filter(None, [hermes_source, env.get("PYTHONPATH", "")]))

    result = subprocess.run(
        [sys.executable, "-c", script, str(ROOT / "dashboard" / "plugin_api.py")],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=15,
    )

    assert result.returncode == 0, result.stderr
