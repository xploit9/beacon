import importlib.util
from pathlib import Path
import pytest
from fastapi import HTTPException

spec = importlib.util.spec_from_file_location('cypher_skills_test', Path(__file__).resolve().parents[1] / 'skills_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


@pytest.fixture
def native_home(tmp_path, monkeypatch):
    native = Path('C:/Users/XPLoiT999/AppData/Local/hermes/hermes-agent')
    if not native.is_dir():
        pytest.skip('Installed native Hermes source required')
    monkeypatch.syspath_prepend(str(native))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    (tmp_path / 'skills').mkdir()
    (tmp_path / 'config.yaml').write_text('skills:\n  disabled: [mail]\n', encoding='utf-8')
    from tools import skills_tool, skill_manager_tool
    for module in (skills_tool, skill_manager_tool):
        monkeypatch.setattr(module, 'HERMES_HOME', tmp_path)
        monkeypatch.setattr(module, 'SKILLS_DIR', tmp_path / 'skills')
    yield tmp_path


def create(root, name='mail', extra=''):
    directory = root / 'skills' / name
    directory.mkdir()
    content = f'---\nname: {name}\ndescription: Read work mail\n{extra}---\nSign in first.\n'
    (directory / 'SKILL.md').write_text(content, encoding='utf-8')
    return directory / 'SKILL.md'


def test_inventory_includes_disabled_incompatible_skills_and_names_missing_secret(native_home):
    create(native_home, extra='platforms: [not-a-real-os]\nrequired_environment_variables:\n  - name: CYPHER_TEST_MISSING_SECRET\n    required_for: work mail\n')
    rows = api.inventory(native_home)['items']
    row = next(row for row in rows if row['name'] == 'mail')
    assert not row['enabled']
    assert row['readiness'] == 'setup_needed'
    assert any('not-a-real-os' in note for note in row['requirements'])
    assert any('CYPHER_TEST_MISSING_SECRET' in note for note in row['requirements'])
    assert 'content' not in row


def test_skill_search_contracts_keep_project_quarantine(native_home, monkeypatch):
    from agent import skill_utils
    create(native_home)
    project = native_home / 'project-skills'
    project.mkdir()
    hidden = project / 'hidden'
    hidden.mkdir()
    (hidden / 'SKILL.md').write_text('---\nname: hidden\ndescription: Quarantined\n---\nBody', encoding='utf-8')
    # Project files must pass through Hermes' quarantine iterator.
    calls = []
    monkeypatch.setattr(skill_utils, 'iter_project_skill_files', lambda directory: calls.append(directory) or iter(()))
    monkeypatch.setattr(skill_utils, 'get_project_skills_dirs', lambda: [project])
    monkeypatch.setattr(skill_utils, 'get_all_skills_dirs', lambda: [native_home / 'skills'])
    rows = api.inventory(native_home)['items']
    assert [row['name'] for row in rows] == ['mail']
    assert calls == [project]
    # Exercise the fallback lookup too; the manager may not index a project's alias.
    from tools import skill_manager_tool
    original = skill_manager_tool._find_skill
    monkeypatch.setattr(skill_manager_tool, '_find_skill', lambda name: None if name == 'missing' else original(name))
    with pytest.raises(HTTPException) as error:
        api.document(native_home, 'missing')
    assert error.value.status_code == 404
    assert calls == [project, project]


def test_alias_lookup_skips_unreadable_skill(native_home, monkeypatch):
    from tools import skill_manager_tool, skills_tool
    broken = native_home / 'skills' / 'broken' / 'SKILL.md'
    alias_dir = native_home / 'skills' / 'alias'
    alias_file = alias_dir / 'SKILL.md'
    broken.parent.mkdir();alias_dir.mkdir()
    alias_file.write_text('---\nname: mail-alias\n---\nAlias body\n', encoding='utf-8')
    monkeypatch.setattr(skill_manager_tool, '_find_skill', lambda name: None)
    monkeypatch.setattr(skill_manager_tool, '_validate_name', lambda name: None)
    monkeypatch.setattr(api, 'skill_files', lambda native: [broken, alias_file])
    monkeypatch.setattr(skills_tool, '_read_skill_text', lambda path: (_ for _ in ()).throw(OSError()) if path == broken else '---\nname: mail-alias\n---\nAlias body')
    monkeypatch.setattr(skills_tool, '_parse_frontmatter', lambda text: ({'name': 'mail-alias'}, 'Alias body'))

    result = api.document(native_home, 'mail-alias')

    assert result['name'] == 'mail-alias' and result['content'].endswith('Alias body\n')


def test_native_edit_validates_and_revision_preserves_concurrent_write(native_home):
    path = create(native_home)
    before = api.document(native_home, 'mail')
    path.write_text(before['content'] + 'Concurrent host edit.\n', encoding='utf-8')
    concurrent = path.read_bytes()
    with pytest.raises(HTTPException) as error:
        api.update(native_home, {'name': 'mail', 'content': before['content'], 'revision': before['revision']})
    assert error.value.status_code == 409
    assert path.read_bytes() == concurrent
    current = api.document(native_home, 'mail')
    with pytest.raises(HTTPException) as error:
        api.update(native_home, {'name': 'mail', 'content': 'Invalid frontmatter', 'revision': current['revision']})
    assert error.value.status_code == 400
    assert path.read_bytes() == concurrent
    result = api.update(native_home, {'name': 'mail', 'content': current['content'] + 'مرحبا\n', 'revision': current['revision']})
    assert result['success']
    assert result['document']['content'].endswith('مرحبا\n')
    assert result['document']['revision'] != current['revision']
    path.write_text(result['document']['content'].replace('name: mail', 'name: mail-alias'), encoding='utf-8')
    alias = api.document(native_home, 'mail-alias')
    assert alias['edit_name'] == 'mail'
    assert api.update(native_home, {'name': 'mail-alias', 'content': alias['content'] + 'Alias edit.\n', 'revision': alias['revision']})['success']


def test_native_toggles_persist_and_essential_skill_cannot_report_disabled(native_home):
    import asyncio
    create(native_home, 'hermes-agent')
    create(native_home)
    row = next(row for row in api.inventory(native_home)['items'] if row['name'] == 'hermes-agent')
    assert row['essential'] and row['enabled']
    from hermes_cli.skills_config import save_disabled_skills
    from hermes_cli.config import load_config
    save_disabled_skills(load_config(), {'hermes-agent', 'mail'})
    assert next(row for row in api.inventory(native_home)['items'] if row['name'] == 'hermes-agent')['enabled']
    from hermes_cli.web_models import SkillToggle
    from hermes_cli.web_routers.skills import toggle_skill
    asyncio.run(toggle_skill(SkillToggle(name='mail', enabled=True)))
    assert next(row for row in api.inventory(native_home)['items'] if row['name'] == 'mail')['enabled']
    asyncio.run(toggle_skill(SkillToggle(name='mail', enabled=False)))
    assert not next(row for row in api.inventory(native_home)['items'] if row['name'] == 'mail')['enabled']
    asyncio.run(toggle_skill(SkillToggle(name='hermes-agent', enabled=False)))
    assert next(row for row in api.inventory(native_home)['items'] if row['name'] == 'hermes-agent')['enabled']


from test_mobile import harness


def test_router_rejects_foreign_owner_and_profile(harness, native_home):
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity, host.body, lambda profile, fn: fn(native_home)), prefix='/api/plugins/cypher/mobile')
    create(native_home)
    url = '/api/plugins/cypher/mobile/skills'
    assert client.get(url).status_code == 200
    assert client.get(url + '/content?name=mail').status_code == 200
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(url).status_code == 403
    assert client.put(url + '/content', json={}).status_code == 403
    client.headers['x-fixture-owner'] = 'owner'
    assert client.get(url + '?profile=foreign').status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url).status_code == 401


@pytest.mark.parametrize('content, status', [(b'x' * (api.MAX_BYTES + 1), 413), (b'\xff\xfeinvalid', 409)], ids=['oversized', 'invalid-text'])
def test_uneditable_skill_content_is_bounded_and_actionable(native_home, content, status):
    path = create(native_home)
    path.write_bytes(content)
    with pytest.raises(HTTPException) as error:
        api.document(native_home, 'mail')
    assert error.value.status_code == status
    assert path.read_bytes() == content
