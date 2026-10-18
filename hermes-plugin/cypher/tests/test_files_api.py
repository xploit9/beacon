from concurrent.futures import ThreadPoolExecutor
import importlib.util
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
import pytest
from fastapi import HTTPException

spec = importlib.util.spec_from_file_location('cypher_files_test', Path(__file__).parents[1] / 'files_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


@pytest.fixture(autouse=True)
def native_file_adapter(tmp_path, monkeypatch):
    """Keep the native filesystem boundary temporary and independent of Hermes startup."""
    root = tmp_path.resolve()

    def managed_path(path, request, *, for_write=False):
        target = Path(path).resolve()
        try:
            target.relative_to(root)
        except ValueError:
            raise HTTPException(403, 'Outside the native files root') from None
        if for_write and not target.parent.is_dir():
            raise HTTPException(400, 'Choose an existing folder')
        return None, target, root

    async def read_text(path):
        return {'text': Path(path).read_text(encoding='utf-8'), 'binary': False, 'truncated': False}

    async def write_text(value):
        Path(value.path).write_text(value.content, encoding='utf-8')

    files = ModuleType('hermes_cli.web_routers.files')
    files._fs_path = lambda path: Path(path)
    files._is_sensitive_path = lambda path: Path(path).name in {'.env', '.env.local', 'credentials.json'}
    files.fs_read_text = read_text
    files.fs_write_text = write_text
    files.FsWriteText = lambda path, content: SimpleNamespace(path=path, content=content)
    web_files = ModuleType('hermes_cli.web_server_files')
    web_files._resolve_managed_path = managed_path
    hermes_cli = ModuleType('hermes_cli'); hermes_cli.__path__ = []
    routers = ModuleType('hermes_cli.web_routers'); routers.__path__ = []
    monkeypatch.setitem(sys.modules, 'hermes_cli', hermes_cli)
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_routers', routers)
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_routers.files', files)
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server_files', web_files)

def test_new_file_preserves_unicode_and_line_endings(tmp_path):
    target = tmp_path / 'notes.txt'
    text = 'first\r\nثاني\r\n'
    result = api.create_file(str(target), text)
    assert result['ok'] and target.read_bytes() == text.encode('utf-8')
    assert result['byteSize'] == len(target.read_bytes())

def test_existing_file_is_never_replaced(tmp_path):
    target = tmp_path / 'existing.txt'
    target.write_bytes(b'keep this')
    with pytest.raises(HTTPException) as error: api.create_file(str(target), 'replacement')
    assert error.value.status_code == 409 and target.read_bytes() == b'keep this'
    assert list(tmp_path.iterdir()) == [target]

def test_concurrent_creators_have_exactly_one_winner(tmp_path):
    target = tmp_path / 'race.txt'
    def attempt(value):
        try: return api.create_file(str(target), value)['content']
        except HTTPException as error:
            assert error.status_code == 409
            return None
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(attempt, ['one', 'two']))
    assert len([value for value in results if value is not None]) == 1
    assert target.read_text(encoding='utf-8') == next(value for value in results if value is not None)


@pytest.fixture
def file_client(tmp_path, monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    monkeypatch.setenv('HERMES_DASHBOARD_FILES_ROOT', str(tmp_path))
    def identity(request):
        if request.headers.get('x-owner') != 'owner':
            raise HTTPException(401, 'Sign in')
        return 'owner', 'basic', 'default', {}
    async def body(request, limit): return await request.json()
    app = FastAPI()
    app.include_router(api.build_router(identity, body, SimpleNamespace(reserve_conversation=lambda *args: None)))
    return TestClient(app, headers={'x-owner': 'owner'})


def test_pc_text_editor_requires_no_conversation_and_preserves_stale_draft(file_client, tmp_path):
    path = str(tmp_path / 'notes.md')
    assert file_client.post('/mobile/files/create', json={'path': path, 'content': 'Original'}).status_code == 200
    assert file_client.get('/mobile/files/text', params={'path': path}).json()['content'] == 'Original'
    result = file_client.post('/mobile/files/text', json={'path': path, 'expected_content': 'Original', 'content': 'Reviewed'})
    assert result.status_code == 200 and result.json()['content'] == 'Reviewed'
    assert Path(path).read_text('utf-8') == 'Reviewed'
    assert file_client.post('/mobile/files/text', json={'path': path, 'expected_content': 'Original', 'content': 'Stale'}).status_code == 409
    assert Path(path).read_text('utf-8') == 'Reviewed'


def test_pc_editor_enforces_native_root_and_sensitive_file_policy(file_client, tmp_path):
    protected = tmp_path / '.env'; protected.write_text('SECRET=private', encoding='utf-8')
    outside = tmp_path.parent / 'outside-native-files.txt'; outside.write_text('Preserve', encoding='utf-8')
    for path in (protected, outside):
        assert file_client.get('/mobile/files/text', params={'path': str(path)}).status_code == 403
        assert file_client.post('/mobile/files/text', json={'path': str(path), 'expected_content': path.read_text('utf-8'), 'content': 'No'}).status_code == 403
    assert file_client.post('/mobile/files/create', json={'path': str(protected), 'content': 'No'}).status_code == 403
    assert file_client.get('/mobile/files/text', params={'path': str(protected)}, headers={'x-owner': ''}).status_code == 401
    assert protected.read_text('utf-8') == 'SECRET=private' and outside.read_text('utf-8') == 'Preserve'


def test_pc_editor_repeated_windows_newline_saves_do_not_add_blank_lines(file_client, tmp_path):
    target = tmp_path / 'windows-notes.txt'
    target.write_bytes('First\r\nثاني\r\n'.encode('utf-8'))
    expected = 'First\nثاني\n'
    assert file_client.get('/mobile/files/text', params={'path': str(target)}).json()['content'] == expected
    for suffix in ('Third', 'Fourth'):
        content = expected + suffix + '\n'
        saved = file_client.post('/mobile/files/text', json={'path': str(target), 'expected_content': expected, 'content': content.replace('\n', '\r\n')})
        assert saved.status_code == 200 and saved.json()['content'] == content
        assert b'\r\r\n' not in target.read_bytes()
        assert target.read_text('utf-8') == content
        expected = content
