"""Owner-only profile notes, using Hermes' memory lock and atomic writer."""
import asyncio
import hashlib
from pathlib import Path
from fastapi import APIRouter, HTTPException, Request

FILES = {'memory': 'MEMORY.md', 'user': 'USER.md'}
MAX_BYTES = 128 * 1024


def path_for(root, target):
    if not isinstance(target, str) or target not in FILES:
        raise HTTPException(400, 'Choose memory or user notes')
    directory = Path(root) / 'memories'
    path = directory / FILES[target]
    for candidate in (directory, path):
        if candidate.is_symlink() or getattr(candidate, 'is_junction', lambda: False)():
            raise HTTPException(409, 'Linked memory files cannot be edited here')
    return path


def document(root, target, cfg):
    from tools.memory_tool import get_builtin_memory_store_flags, get_builtin_memory_config
    path = path_for(root, target)
    if path.exists() and path.stat().st_size > MAX_BYTES:
        raise HTTPException(413, 'Notes are too large for the phone editor')
    raw = path.read_bytes() if path.exists() else b''
    if len(raw) > MAX_BYTES:
        raise HTTPException(413, 'Notes are too large for the phone editor')
    try:
        content = raw.decode('utf-8-sig')
    except UnicodeDecodeError:
        raise HTTPException(409, 'Notes have invalid text encoding; repair on the computer') from None
    section = get_builtin_memory_config(cfg)
    enabled = get_builtin_memory_store_flags(cfg)[0 if target == 'memory' else 1]
    return {'target': target, 'content': content, 'revision': hashlib.sha256(raw).hexdigest(),
            'enabled': enabled, 'limit': int(section.get('memory_char_limit' if target == 'memory' else 'user_char_limit', 2200 if target == 'memory' else 1375))}


def documents(root):
    from hermes_cli.config import load_config
    cfg = load_config()
    return {'documents': [document(root, target, cfg) for target in FILES]}


def update(root, payload):
    from hermes_cli.config import load_config
    from tools.memory_tool_store import MemoryStore, _scan_memory_content
    from utils import atomic_write_text
    target = payload.get('target')
    path = path_for(root, target)
    content, revision = payload.get('content'), payload.get('revision')
    if not isinstance(content, str) or not isinstance(revision, str) or len(revision) != 64:
        raise HTTPException(400, 'Content and original revision are required')
    if len(content.encode('utf-8')) > MAX_BYTES:
        raise HTTPException(413, 'Notes are too large')
    if _scan_memory_content(content):
        raise HTTPException(400, 'Hermes rejected unsafe memory content')
    # Share the native writer lock: a stale phone edit must not replace newer agent notes.
    try:
        with MemoryStore._file_lock(path):
            current = document(root, target, load_config())
            if current['revision'] != revision:
                raise HTTPException(409, 'Notes changed elsewhere. Keep your draft, reload and merge before saving.')
            if len(content) > current['limit']:
                raise HTTPException(400, f"Notes exceed the {current['limit']} character limit")
            atomic_write_text(path, content, tmp_prefix='.mem_')
            return {'ok': True, 'document': document(root, target, load_config())}
    except PermissionError:
        raise HTTPException(403, 'Profile memory notes are not writable') from None
    except OSError:
        raise HTTPException(400, 'Could not save notes in this profile') from None


def build_router(identity, read_body, scoped):
    router = APIRouter()

    @router.get('/memory/documents')
    async def read(request: Request):
        _, _, profile, _ = identity(request)
        return await asyncio.to_thread(scoped, profile, documents)

    @router.put('/memory/documents')
    async def write(request: Request):
        _, _, profile, _ = identity(request)
        payload = await read_body(request, limit=MAX_BYTES * 2)
        return await asyncio.to_thread(scoped, profile, lambda root: update(root, payload))

    return router
