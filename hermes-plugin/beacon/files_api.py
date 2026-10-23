"""Phone text editing with native path policy, stale checks and atomic byte-preserving saves."""
import asyncio
import os
from pathlib import Path
import tempfile
from fastapi import APIRouter, HTTPException, Request


def create_file(path, content):
    from hermes_cli.web_routers.files import _fs_path
    if not isinstance(path, str) or not isinstance(content, str):
        raise HTTPException(400, 'File path and text are required')
    data = content.encode('utf-8')
    if len(data) > 1024 * 1024:
        raise HTTPException(413, 'File exceeds the 1 MiB editor limit')
    target = _fs_path(path)
    if not target.parent.is_dir():
        raise HTTPException(400, 'Choose an existing folder')
    staged = None
    try:
        fd, staged = tempfile.mkstemp(prefix='.beacon-create-', dir=target.parent)
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        # A hard link publishes complete bytes atomically and cannot replace an existing name.
        os.link(staged, target)
        return {'ok': True, 'path': str(target), 'content': content, 'byteSize': len(data)}
    except FileExistsError:
        raise HTTPException(409, 'A file already exists here. Choose another name or open it to edit.') from None
    except PermissionError:
        raise HTTPException(403, 'This folder is not writable') from None
    except OSError:
        raise HTTPException(400, 'Could not create the file in this folder') from None
    finally:
        if staged is not None:
            Path(staged).unlink(missing_ok=True)


def save_text(path, content, expected):
    target, staged = Path(path), None
    try:
        mode = target.stat().st_mode
        if not mode & 0o222:
            raise HTTPException(403, 'This file is not writable')
        original = target.read_bytes()
        try:
            current = original.decode('utf-8-sig').replace('\r\n', '\n')
        except UnicodeDecodeError:
            raise HTTPException(400, 'This file is not valid UTF-8. Download it to edit.') from None
        if current != expected:
            raise HTTPException(409, 'The file changed on your computer. Reopen it and review your retained draft.')
        newline = '\r\n' if b'\r\n' in original and b'\n' not in original.replace(b'\r\n', b'') else '\n'
        normalized = content.replace('\r\n', '\n')
        data = normalized.replace('\n', newline).encode('utf-8')
        if original.startswith(b'\xef\xbb\xbf'):
            data = b'\xef\xbb\xbf' + data
        if normalized == current:
            data = original
        if len(data) > 512 * 1024:
            raise HTTPException(400, 'Text exceeds the 512 KiB editor limit')
        fd, staged = tempfile.mkstemp(prefix='.beacon-edit-', dir=target.parent)
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(staged, mode)
        if target.read_bytes() != original:
            raise HTTPException(409, 'The file changed on your computer. Reopen it and review your retained draft.')
        os.replace(staged, target)
    except PermissionError:
        raise HTTPException(403, 'This file is not writable') from None
    except OSError:
        raise HTTPException(400, 'Could not save this file. Reopen it and try again.') from None
    finally:
        if staged is not None:
            Path(staged).unlink(missing_ok=True)


def build_router(identity, read_body, native):
    router = APIRouter()
    edit_lock = asyncio.Lock()

    def managed_path(request, path, *, write=False):
        from hermes_cli.web_server_files import _resolve_managed_path
        from hermes_cli.web_routers.files import _is_sensitive_path
        if not isinstance(path, str) or not path.strip():
            raise HTTPException(400, 'Choose a PC file')
        _, target, _ = _resolve_managed_path(path, request, for_write=write)
        if _is_sensitive_path(target):
            raise HTTPException(403, 'This protected file cannot be opened in the phone editor')
        return str(target)

    async def text_read(path):
        from hermes_cli.web_routers.files import fs_read_text
        value = await fs_read_text(path)
        value['content'] = '' if value.get('binary') else value.get('text', '').removeprefix('\ufeff').replace('\r\n', '\n')
        if value.get('binary'):
            value['edit_blocked_reason'] = 'Download this file to open it in a compatible app.'
        elif value.get('truncated'):
            value['edit_blocked_reason'] = 'This preview is shortened. Download the complete file to edit it.'
        elif '\ufffd' in value['content']:
            value['edit_blocked_reason'] = 'This file is not valid UTF-8. Download it to edit.'
        return value

    @router.get('/mobile/files/text')
    async def read_text(request: Request, path: str):
        identity(request)
        return await text_read(managed_path(request, path))

    @router.post('/mobile/files/text')
    async def write_text(request: Request):
        identity(request)
        payload = await read_body(request, limit=2 * 1024 * 1024)
        path = managed_path(request, payload.get('path'), write=True)
        content, expected = payload.get('content'), payload.get('expected_content')
        if any(isinstance(value, str) and '\ufffd' in value for value in (content, expected)):
            raise HTTPException(400, 'This file is not valid UTF-8. Download it to edit.')
        if not isinstance(content, str) or not isinstance(expected, str) or '\x00' in content:
            raise HTTPException(400, 'Reopen the file before saving text of up to 512 KiB')
        try:
            size = len(content.encode('utf-8')) + content.count('\n')
        except UnicodeEncodeError:
            raise HTTPException(400, 'Choose valid UTF-8 text before saving.') from None
        if size > 512 * 1024:
            raise HTTPException(400, 'Reopen the file before saving text of up to 512 KiB')
        async with edit_lock:
            current = await text_read(path)
            if current.get('binary') or current.get('truncated') or current.get('content') != expected:
                raise HTTPException(409, 'The file changed on your computer. Reopen it and review your retained draft.')
            await asyncio.to_thread(save_text, path, content, expected)
            return dict(await text_read(path), ok=True)

    @router.post('/mobile/files/create')
    async def create(request: Request):
        payload = await read_body(request, limit=2 * 1024 * 1024)
        owner, provider, profile, _ = identity(request)
        stored = payload.get('session_id')
        if stored:
            if not isinstance(stored, str):
                raise HTTPException(400, 'Invalid conversation')
            try:
                await asyncio.to_thread(native.reserve_conversation, owner, provider, profile, stored)
            except LookupError:
                raise HTTPException(404, 'Conversation is not available to this account') from None
            except (RuntimeError, OSError):
                raise HTTPException(503, 'Native conversation store is unavailable; try again later') from None
        path = managed_path(request, payload.get('path'), write=True)
        return await asyncio.to_thread(create_file, path, payload.get('content', ''))
    return router
