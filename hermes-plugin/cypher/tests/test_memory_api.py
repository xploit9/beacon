import os
import importlib.util
from pathlib import Path
import sys
import pytest
from fastapi import HTTPException

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cypher_memory_test', ROOT / 'memory_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

@pytest.fixture
def native_home(tmp_path, monkeypatch):
    # Use actual installed native handlers against an isolated profile, never owner notes.
    native = Path(os.environ.get('HERMES_AGENT_SOURCE') or Path.home()/'AppData'/'Local'/'hermes'/'hermes-agent')
    if not native.is_dir():
        pytest.skip('Installed native Hermes source required')
    monkeypatch.syspath_prepend(str(native))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    (tmp_path / 'memories').mkdir()
    return tmp_path


def test_stale_revision_does_not_erase_concurrent_agent_write(native_home):
    from tools.memory_tool_store import MemoryStore
    root = native_home
    path = root / 'memories' / 'MEMORY.md'
    path.write_text('Original note', encoding='utf-8')
    before = api.documents(root)['documents'][0]
    store = MemoryStore()
    store.load_from_disk()
    assert store.add('memory', 'Concurrent agent note')['success']
    concurrent = path.read_bytes()
    with pytest.raises(HTTPException) as error:
        api.update(root, {'target': 'memory', 'content': 'Phone draft', 'revision': before['revision']})
    assert error.value.status_code == 409
    assert path.read_bytes() == concurrent


def test_save_empty_unicode_and_other_document_untouched(native_home):
    root = native_home
    user = root / 'memories' / 'USER.md'
    user.write_text('User preferences', encoding='utf-8')
    before = api.documents(root)['documents'][0]
    result = api.update(root, {'target': 'memory', 'content': 'مرحبا', 'revision': before['revision']})
    assert result['document']['content'] == 'مرحبا'
    api.update(root, {'target': 'memory', 'content': '', 'revision': result['document']['revision']})
    assert (root / 'memories' / 'MEMORY.md').read_bytes() == b''
    assert user.read_text(encoding='utf-8') == 'User preferences'


def test_first_save_creates_missing_memories_directory(native_home):
    root=native_home;directory=root/'memories';directory.rmdir()
    before=api.documents(root)['documents'][0]
    saved=api.update(root,{'target':'memory','content':'First note','revision':before['revision']})
    assert saved['document']['content']=='First note'


def test_read_only_memory_write_maps_permission_error_to_forbidden(native_home,monkeypatch):
    import utils
    before=api.documents(native_home)['documents'][0]
    def fail(*args,**kwargs): raise PermissionError('private filesystem detail')
    monkeypatch.setattr(utils,'atomic_write_text',fail)
    with pytest.raises(HTTPException) as error:
        api.update(native_home,{'target':'memory','content':'New note','revision':before['revision']})
    assert error.value.status_code==403
    assert 'private filesystem detail' not in str(error.value.detail)


def test_limits_invalid_encoding_and_traversal_preserve_bytes(native_home):
    root = native_home
    before = api.documents(root)['documents'][0]
    with pytest.raises(HTTPException):
        api.update(root, {'target': 'memory', 'content': 'x' * 5000, 'revision': before['revision']})
    assert not (root / 'memories' / 'MEMORY.md').exists()
    with pytest.raises(HTTPException):
        api.path_for(root, '../config.yaml')
    path = root / 'memories' / 'USER.md'
    path.write_bytes(b'\xff')
    with pytest.raises(HTTPException):
        api.documents(root)
    assert path.read_bytes() == b'\xff'

from test_mobile import harness


def test_router_requires_verified_owner_and_authorized_profile(harness, native_home):
    host, client, cfg, _, app = harness
    app.include_router(api.build_router(host.identity, host.body, lambda profile, fn: fn(native_home)), prefix='/api/plugins/cypher-fixture')
    url = '/api/plugins/cypher-fixture/memory/documents'
    result = client.get(url)
    assert result.status_code == 200
    note = result.json()['documents'][0]
    assert client.put(url, json={'target': 'memory', 'content': 'Isolated owner note', 'revision': note['revision']}).status_code == 200
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(url).status_code == 403
    assert client.put(url, json={'target': 'memory', 'content': '', 'revision': note['revision']}).status_code == 403
    client.headers['x-fixture-owner'] = 'owner'
    assert client.get(url + '?profile=foreign').status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url).status_code == 401
