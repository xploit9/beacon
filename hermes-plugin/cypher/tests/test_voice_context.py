import sys
from types import SimpleNamespace

import pytest
from test_mobile import harness, reserve


def test_admission_defers_optional_discovery_and_keeps_authoritative_context(harness, monkeypatch):
    api, client, cfg, root, _ = harness
    call_id = reserve(client)
    voice = api.voice_context
    verified = []
    monkeypatch.setattr(voice, 'scoped', lambda profile, fn: fn(root))
    monkeypatch.setattr(voice, 'config', lambda root: {'memory': {'provider': 'hindsight'}})
    monkeypatch.setattr(voice, 'personality', lambda root: 'Owned personality')
    monkeypatch.setattr(voice, 'builtin_memory', lambda root, cfg: 'Owned curated memory')
    monkeypatch.setattr(voice, 'history', lambda *args: verified.append(args) or [{'role': 'user', 'content': 'Owned history'}])
    monkeypatch.setattr(voice, 'external_provider', lambda *args: pytest.fail('Admission must not scan live agents'))
    monkeypatch.setitem(sys.modules, 'tools.web_tools', SimpleNamespace(_ensure_web_plugins_loaded=lambda: pytest.fail('Admission must not load search plugins')))
    result = voice.context('owner', 'basic', 'default', 'native-stored', call_id)
    assert verified == [('owner', 'basic', 'default', 'native-stored')]
    assert result['profile'] == 'default' and result['selected_session_id'] == 'native-stored'
    assert result['context']['personality'] == 'Owned personality'
    assert result['context']['memory'] == 'Owned curated memory'
    assert result['context']['history'][0]['content'] == 'Owned history'
    assert result['capabilities']['skills'] and result['capabilities']['memory_recall'] and result['capabilities']['web_search']
    assert result['configured_memory_provider'] == 'hindsight'
    assert result['capability_details']['web_search'] == {'available': None, 'reason': 'verify_on_use'}
    assert result['capability_details']['memory_recall']['reason'] == 'verify_on_use'


def test_unowned_history_blocks_profile_reads(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    voice = api.voice_context
    monkeypatch.setattr(voice, 'history', lambda *args: (_ for _ in ()).throw(LookupError('Not owned')))
    monkeypatch.setattr(voice, 'scoped', lambda *args: pytest.fail('Unowned call cannot read profile'))
    with pytest.raises(LookupError):
        voice.context('other', 'basic', 'default', 'native-stored', call_id)
