"""Profile-scoped installed skill requirements and revision-checked native edits."""
import asyncio
import hashlib
from fastapi import APIRouter, HTTPException, Request

MAX_BYTES = 512 * 1024


def skill_files(native):
    from agent.skill_utils import get_project_skills_dirs, get_all_skills_dirs, iter_project_skill_files, iter_skill_index_files
    project_dirs = get_project_skills_dirs()
    for directory in dict.fromkeys(project_dirs + get_all_skills_dirs()):
        files = iter_project_skill_files(directory) if directory in project_dirs else iter_skill_index_files(directory, 'SKILL.md')
        yield from (path for path in files if not any(part in native._EXCLUDED_SKILL_DIRS for part in path.parts))


def requirements(frontmatter, env_snapshot):
    from agent.skill_utils import skill_matches_platform, skill_matches_environment
    from tools.skills_tool_setup import _get_required_environment_variables, _is_env_var_persisted
    from hermes_platform import declaration
    from hermes_platform.resolver.availability import availability
    notes = []
    if not skill_matches_platform(frontmatter):
        notes.append('Host platform does not match: ' + str(frontmatter.get('platforms')))
    if not skill_matches_environment(frontmatter):
        notes.append('Not offered in this environment: ' + str(frontmatter.get('environments')))
    apps = frontmatter.get('requires_apps') or []
    for name in apps if isinstance(apps, list) else [apps]:
        decl = declaration.lookup(str(name).strip())
        state = availability(decl) if decl is not None and decl.app is not None else None
        if state is None:
            notes.append(f'App {name}: no registered host setup')
        elif not state.offerable:
            notes.append(f'App {name}: {state.state.replace("_", " ")}' + (f'; needs version {state.min_version}' if state.min_version else ''))
    for entry in _get_required_environment_variables(frontmatter):
        if not entry.get('optional') and not _is_env_var_persisted(entry['name'], env_snapshot):
            note = f"Set {entry['name']} in this profile’s host secrets"
            if entry.get('required_for'):
                note += f" for {entry['required_for']}"
            if entry.get('help'):
                note += '. ' + entry['help']
            notes.append(note)
    return notes


def inventory(root):
    from agent.skill_utils import ESSENTIAL_SKILLS
    from hermes_cli.config import load_config
    from hermes_cli.skills_config import get_disabled_skills
    from tools import skills_tool as native
    disabled = get_disabled_skills(load_config())
    env = native.load_env()
    rows, seen, warnings = [], set(), []
    for path in skill_files(native):
        try:
            frontmatter, body = native._parse_frontmatter(native._read_skill_text(path)[:4000])
            name = frontmatter.get('name', path.parent.name)[:native.MAX_NAME_LENGTH]
            if name in seen:
                continue
            seen.add(name)
            description = frontmatter.get('description') or next((line.strip() for line in body.splitlines() if line.strip() and not line.startswith('#')), '')
            needs = requirements(frontmatter, env)
            rows.append({'name': name, 'description': native._truncate_description(description),
                         'category': native._get_category_from_path(path), 'enabled': name not in disabled,
                         'essential': name in ESSENTIAL_SKILLS, 'requirements': needs,
                         'readiness': 'setup_needed' if needs else 'requirements_met'})
        except Exception:
            warnings.append(f'Could not inspect skill {path.parent.name}; check its SKILL.md on the host.')
    return {'items': rows, 'warnings': warnings}


def document(root, name):
    from tools.skill_manager_tool import _find_skill, _validate_name
    from tools import skills_tool as native
    # Every segment is a native identifier: a category folder may precede the name, but '..', backslashes and rooted paths never pass.
    if not isinstance(name, str) or not name or any(_validate_name(part) for part in name.split('/')):
        raise HTTPException(400, 'Choose a valid skill name')
    found = _find_skill(name)
    if not found:
        for candidate in skill_files(native):
            try:
                frontmatter, _ = native._parse_frontmatter(native._read_skill_text(candidate)[:4000])
            except Exception:
                continue
            if frontmatter.get('name', candidate.parent.name) == name:
                found = {'path': candidate.parent}
                break
    if not found:
        raise HTTPException(404, 'Skill not found in this profile')
    path = found['path'] / 'SKILL.md'
    with path.open('rb') as source:
        raw = source.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise HTTPException(413, 'Skill is too large for the phone editor')
    try:
        edit_name = path.parent.relative_to(native._skills_dir()).as_posix()
    except ValueError:
        edit_name = path.parent.name
    editable = _find_skill(edit_name)
    if not editable or editable['path'].resolve() != path.parent.resolve():
        edit_name = None
    try:
        content = raw.decode('utf-8-sig').replace('\r\n', '\n')
    except UnicodeDecodeError:
        raise HTTPException(409, 'Skill instructions have invalid text encoding; repair them on the computer') from None
    return {'name': name, 'content': content, 'revision': hashlib.sha256(raw).hexdigest(), 'edit_name': edit_name}


def update(root, payload):
    from tools.skill_manager_tool import _edit_skill, _skill_mutation_lock
    from hermes_cli.web_routers.skills import _clear_skills_prompt_cache
    name, content, revision = payload.get('name'), payload.get('content'), payload.get('revision')
    original = document(root, name) # validate and resolve the native lock key
    if not isinstance(content, str) or not content.strip() or len(content.encode('utf-8')) > MAX_BYTES or not isinstance(revision, str):
        raise HTTPException(400, 'Nonempty instructions and the original revision are required')
    with _skill_mutation_lock(original['edit_name'] or name):
        current = document(root, name)
        if current['revision'] != revision:
            raise HTTPException(409, 'Skill changed elsewhere. Keep your draft, reload and merge before saving.')
        if not current['edit_name']:
            raise HTTPException(409, 'Another installed skill shadows this directory. Adjust it on the host after resolving the duplicate name.')
        result = _edit_skill(current['edit_name'], content.replace('\r\n', '\n'))
        if not result.get('success'):
            raise HTTPException(400, result.get('error', 'Hermes rejected the skill instructions'))
        _clear_skills_prompt_cache()
        return {'success': True, 'document': document(root, name)}


def build_router(identity, read_body, scoped):
    router = APIRouter()

    @router.get('/skills')
    async def list_skills(request: Request):
        _, _, profile, _ = identity(request)
        return await asyncio.to_thread(scoped, profile, inventory)

    @router.get('/skills/content')
    async def read(request: Request, name: str):
        _, _, profile, _ = identity(request)
        return await asyncio.to_thread(scoped, profile, lambda root: document(root, name))

    @router.put('/skills/content')
    async def write(request: Request):
        _, _, profile, _ = identity(request)
        payload = await read_body(request, limit=MAX_BYTES * 2)
        return await asyncio.to_thread(scoped, profile, lambda root: update(root, payload))

    return router
