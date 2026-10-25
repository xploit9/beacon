"""plugin.yaml declares exactly the tools and hooks register() adds; Hermes' validator rejects a mismatch."""
from pathlib import Path
from types import SimpleNamespace

import yaml
from test_mobile import harness, load_private  # noqa: F401  (fixture)

ROOT = Path(__file__).resolve().parents[1]


def test_manifest_lists_every_registered_tool_and_hook(harness):
    tools, hooks = [], []
    load_private('__init__').register(SimpleNamespace(
        register_tool=lambda **kwargs: tools.append(kwargs['name']),
        register_hook=lambda name, handler: hooks.append(name)))
    manifest = yaml.safe_load((ROOT / 'plugin.yaml').read_text(encoding='utf-8'))
    assert sorted(tools) == sorted(manifest['provides_tools'])
    assert sorted(set(hooks)) == sorted(manifest['provides_hooks']) == sorted(manifest['hooks'])
