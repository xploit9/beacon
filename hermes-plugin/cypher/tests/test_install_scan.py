from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def test_native_installer_scan_allows_the_plugin_without_an_override():
    # A high finding turns Hermes' install verdict into "caution", which blocks a non-interactive install.
    guard = pytest.importorskip("tools.plugin_guard")
    result = guard.scan_plugin(ROOT)
    assert result.verdict == "safe", [(f.severity, f.file, f.pattern_id) for f in result.findings if f.severity in ("high", "critical")]
    assert guard.should_allow_plugin_install(result)[0] is True
