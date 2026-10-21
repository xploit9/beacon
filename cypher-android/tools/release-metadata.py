#!/usr/bin/env python3
"""Create the named stable APK asset and manifest used by the Hermes release feed."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

MAX_APK_BYTES = 256 * 1024 * 1024


class ReleaseMetadataError(ValueError):
    pass


def _sha256(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):
            digest.update(block)
    return digest.hexdigest()


def _build_tools_key(path):
    return tuple(int(part) if part.isdigit() else 0 for part in path.name.split('.'))


def find_tool(name, explicit=None):
    if explicit:
        path=Path(explicit).expanduser()
        if path.is_file(): return str(path)
        raise ReleaseMetadataError(f'{name} was not found at the supplied path.')
    env_name=name.upper()
    configured=os.environ.get(env_name)
    if configured:
        path=Path(configured).expanduser()
        if path.is_file(): return str(path)
        raise ReleaseMetadataError(f'{env_name} does not point to a file.')
    # Windows SDKs ship aapt.exe but apksigner.bat.
    executables=(name+'.exe',name+'.bat') if os.name=='nt' else (name,)
    for sdk in (os.environ.get('ANDROID_SDK_ROOT'),os.environ.get('ANDROID_HOME')):
        if not sdk: continue
        roots=Path(sdk)/'build-tools'
        if roots.is_dir():
            for version in sorted((item for item in roots.iterdir() if item.is_dir()),key=_build_tools_key,reverse=True):
                for executable in executables:
                    candidate=version/executable
                    if candidate.is_file(): return str(candidate)
    found=shutil.which(name)
    if found: return found
    raise ReleaseMetadataError(f'{name} was not found; set ANDROID_SDK_ROOT or pass --{name}.')


def _run(args):
    flags=subprocess.CREATE_NO_WINDOW if sys.platform=='win32' else 0
    try:
        result=subprocess.run(args,capture_output=True,text=True,check=True,timeout=30,shell=False,creationflags=flags)
    except (OSError,subprocess.SubprocessError):
        raise ReleaseMetadataError('Android could not verify the stable APK.') from None
    return result.stdout


def _plugin_version():
    manifest=Path(__file__).resolve().parents[2]/'hermes-plugin'/'cypher'/'plugin.yaml'
    try: lines=manifest.read_text(encoding='utf-8-sig').splitlines()
    except OSError: raise ReleaseMetadataError('The Beacon plugin version is unavailable.') from None
    for line in lines:
        if line.startswith('version:'):
            value=line.split(':',1)[1].split('#',1)[0].strip().strip("'\"")
            if re.fullmatch(r'\d+\.\d+\.\d+',value): return value
            break
    raise ReleaseMetadataError('The Beacon plugin version is invalid.')


def metadata_for_apk(apk_path, aapt, apksigner):
    apk=Path(apk_path).expanduser()
    if not apk.is_file() or apk.is_symlink(): raise ReleaseMetadataError('Choose a built stable APK file.')
    size=apk.stat().st_size
    if not 1<=size<=MAX_APK_BYTES: raise ReleaseMetadataError('The stable APK size is outside the supported limit.')
    badging=_run([aapt,'dump','badging',str(apk)])
    certificates=_run([apksigner,'verify','--print-certs',str(apk)])
    identity=re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",badging)
    digests=re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})',certificates)
    if not identity or identity[1]!='dev.hermes.android.stable':
        raise ReleaseMetadataError('The APK is not the Beacon stable package.')
    version_code=int(identity[2]);version_name=identity[3]
    if version_code<=20 or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}',version_name):
        raise ReleaseMetadataError('The stable APK version is invalid.')
    if len(digests)!=1: raise ReleaseMetadataError('The stable APK must have one verified signing identity.')
    return {
        'artifact_filename':f'Beacon-{version_name}.apk',
        'package_name':identity[1],
        'version_code':version_code,
        'version_name':version_name,
        'plugin_version':_plugin_version(),
        'certificate_sha256':digests[0].lower(),
        'sha256':_sha256(apk),
        'size_bytes':size,
    }


def _atomic_copy(source, destination, digest):
    if destination.is_symlink(): raise ReleaseMetadataError('The release artifact path is unsafe.')
    if destination.exists():
        if destination.is_file() and _sha256(destination)==digest: return
        raise ReleaseMetadataError('This Beacon APK filename already contains different bytes.')
    temporary=destination.with_name('.'+destination.name+'.'+uuid.uuid4().hex+'.tmp')
    try:
        with Path(source).open('rb') as incoming,temporary.open('xb') as outgoing:
            shutil.copyfileobj(incoming,outgoing,1024*1024);outgoing.flush();os.fsync(outgoing.fileno())
        if _sha256(temporary)!=digest: raise ReleaseMetadataError('The stable APK changed while it was being copied.')
        os.replace(temporary,destination)
    finally:
        try: temporary.unlink()
        except FileNotFoundError: pass


def _atomic_write(destination, content):
    if destination.is_symlink(): raise ReleaseMetadataError('The release metadata path is unsafe.')
    if destination.exists():
        if destination.is_file() and destination.read_bytes()==content: return
        raise ReleaseMetadataError('This Beacon version already has different release metadata.')
    temporary=destination.with_name('.'+destination.name+'.'+uuid.uuid4().hex+'.tmp')
    try:
        with temporary.open('xb') as stream:
            stream.write(content);stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,destination)
    finally:
        try: temporary.unlink()
        except FileNotFoundError: pass


def write_release_metadata(apk_path, output_dir=None, *, aapt=None, apksigner=None):
    source=Path(apk_path).expanduser()
    if source.is_symlink(): raise ReleaseMetadataError('Choose a built stable APK file.')
    apk=source.resolve(strict=True)
    metadata=metadata_for_apk(apk,find_tool('aapt',aapt),find_tool('apksigner',apksigner))
    output=Path(output_dir).expanduser() if output_dir else apk.parent
    output.mkdir(parents=True,exist_ok=True)
    artifact=output/metadata['artifact_filename']
    manifest=output/f"Beacon-{metadata['version_name']}.json"
    _atomic_copy(apk,artifact,metadata['sha256'])
    content=(json.dumps(metadata,indent=2,sort_keys=True)+'\n').encode('utf-8')
    _atomic_write(manifest,content)
    return artifact,manifest,metadata


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path,help='built, signed stable APK')
    parser.add_argument('--output-dir',type=Path,help='asset output directory (defaults to the APK directory)')
    parser.add_argument('--aapt',help='Android aapt executable; defaults to ANDROID_SDK_ROOT build-tools')
    parser.add_argument('--apksigner',help='Android apksigner executable; defaults to ANDROID_SDK_ROOT build-tools')
    args=parser.parse_args(argv)
    try:
        artifact,manifest,_=write_release_metadata(args.apk,args.output_dir,aapt=args.aapt,apksigner=args.apksigner)
    except (OSError,ReleaseMetadataError):
        parser.error('Could not create release metadata. Check the stable APK and Android signing tools.')
    print(f'Created {artifact.name} and {manifest.name}')
    return 0


if __name__=='__main__':
    raise SystemExit(main())
