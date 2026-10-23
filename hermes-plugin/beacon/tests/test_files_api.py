from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
import pytest
from fastapi import HTTPException

spec = importlib.util.spec_from_file_location('beacon_files_test', Path(__file__).parents[1] / 'files_api.py')
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
        return {'text': Path(path).read_text(encoding='utf-8', errors='replace'), 'binary': False, 'truncated': False}

    files = ModuleType('hermes_cli.web_routers.files')
    files._fs_path = lambda path: Path(path)
    files._is_sensitive_path = lambda path: Path(path).name in {'.env', '.env.local', 'credentials.json'}
    files.fs_read_text = read_text
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
    native = SimpleNamespace(reserve_conversation=lambda *args: None)
    app.state.native = native
    app.include_router(api.build_router(identity, body, native))
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


@pytest.mark.parametrize('failure', [OSError('native store unavailable'), RuntimeError('native store unavailable')])
def test_native_conversation_store_errors_map_to_503(file_client, failure):
    def fail(*args): raise failure
    file_client.app.state.native.reserve_conversation = fail

    response = file_client.post('/mobile/files/create', json={
        'path': 'ignored.md', 'content': 'fixture', 'session_id': 'native-stored',
    })

    assert response.status_code == 503
    assert 'native conversation store' in response.text.lower()


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


@pytest.mark.parametrize('newline', [b'\n', b'\r\n'])
@pytest.mark.parametrize('bom', [b'', b'\xef\xbb\xbf'])
def test_pc_editor_preserves_original_encoding_and_newline_bytes(file_client, tmp_path, newline, bom):
    target = tmp_path / 'notes.txt'
    target.write_bytes(bom + b'First' + newline)
    read = file_client.get('/mobile/files/text', params={'path': str(target)})
    assert read.json()['content'] == 'First\n'
    result = file_client.post('/mobile/files/text', json={
        'path': str(target), 'expected_content': read.json()['content'], 'content': 'First\r\nSecond\r\n'})
    assert result.status_code == 200, result.text
    assert target.read_bytes() == bom + b'First' + newline + b'Second' + newline
    assert result.json()['content'] == 'First\nSecond\n'


@pytest.mark.parametrize('bom', [b'', b'\xef\xbb\xbf'])
def test_pc_editor_unchanged_mixed_newlines_preserve_exact_bytes(file_client, tmp_path, bom):
    target = tmp_path / 'mixed.txt'
    original = bom + b'a\r\nb\nc'
    target.write_bytes(original)
    text = file_client.get('/mobile/files/text', params={'path': str(target)}).json()['content']
    saved = file_client.post('/mobile/files/text', json={
        'path': str(target), 'expected_content': text, 'content': text})
    assert saved.status_code == 200, saved.text
    assert target.read_bytes() == original


def test_pc_editor_mixed_newlines_do_not_force_crlf_on_edit(file_client, tmp_path):
    target = tmp_path / 'mixed.txt'
    target.write_bytes(b'a\r\nb\nc')
    saved = file_client.post('/mobile/files/text', json={
        'path': str(target), 'expected_content': 'a\nb\nc', 'content': 'a\nb\nc\nd'})
    assert saved.status_code == 200, saved.text
    assert target.read_bytes() == b'a\nb\nc\nd'


@pytest.mark.parametrize('original,content', [(b'bad\xff\n', 'bad\ufffd\n'), (b'valid\n', 'bad\ufffd\n')])
def test_pc_editor_refuses_replacement_characters_without_changing_file(file_client, tmp_path, original, content):
    target = tmp_path / 'legacy.txt'
    target.write_bytes(original)
    read = file_client.get('/mobile/files/text', params={'path': str(target)}).json()
    result = file_client.post('/mobile/files/text', json={
        'path': str(target), 'expected_content': read['content'], 'content': content})
    assert result.status_code == 400
    assert 'UTF-8' in result.json()['detail']
    assert target.read_bytes() == original


def test_pc_editor_replace_failure_keeps_original_and_removes_staging(file_client, tmp_path, monkeypatch):
    target = tmp_path / 'notes.txt'
    target.write_bytes(b'Original\r\n')
    def fail(*args): raise OSError('private disk path')
    monkeypatch.setattr(api.os, 'replace', fail)
    result = file_client.post('/mobile/files/text', json={
        'path': str(target), 'expected_content': 'Original\n', 'content': 'Reviewed\n'})
    assert result.status_code == 400
    assert 'private disk path' not in result.text
    assert target.read_bytes() == b'Original\r\n'
    assert list(tmp_path.iterdir()) == [target]


def test_pc_editor_rechecks_a_desktop_change_before_atomic_publication(file_client, tmp_path, monkeypatch):
    target = tmp_path / 'notes.txt'
    target.write_bytes(b'Original\n')
    fsync = api.os.fsync
    def desktop_change(fd):
        fsync(fd)
        target.write_bytes(b'Desktop edit\n')
    monkeypatch.setattr(api.os, 'fsync', desktop_change)
    response = file_client.post('/mobile/files/text', json={
        'path': str(target), 'expected_content': 'Original\n', 'content': 'Phone edit\n'})
    assert response.status_code == 409
    assert target.read_bytes() == b'Desktop edit\n'
    assert list(tmp_path.iterdir()) == [target]


def test_pc_editor_rejects_unencodable_unicode_without_touching_the_file(file_client, tmp_path):
    target = tmp_path / 'notes.txt'
    target.write_bytes(b'Original\n')
    response = file_client.post('/mobile/files/text', content=json.dumps({
        'path': str(target), 'expected_content': 'Original\n', 'content': '\ud800'}),
        headers={'content-type': 'application/json'})
    assert response.status_code == 400 and 'UTF-8' in response.json()['detail']
    assert target.read_bytes() == b'Original\n'


def test_pc_editor_refuses_read_only_file_without_leaving_a_read_only_temp(file_client, tmp_path):
    target = tmp_path / 'notes.txt'
    target.write_bytes(b'Original\n')
    target.chmod(0o444)
    try:
        response = file_client.post('/mobile/files/text', json={
            'path': str(target), 'expected_content': 'Original\n', 'content': 'Changed\n'})
        assert response.status_code == 403
        assert target.read_bytes() == b'Original\n'
        assert list(tmp_path.iterdir()) == [target]
    finally:
        target.chmod(0o600)
