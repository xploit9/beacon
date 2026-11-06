"""Fixed, offline Android build inside the pinned repair sandbox."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


TASKS = ['testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest', 'assembleStable', 'lintDebug', 'lintStable']


def check_environment():
    if sys.platform != 'linux' or os.name != 'posix': raise ValueError('Unsupported build environment')
    java = subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '-version'], stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15, check=True).stdout
    if not re.search(rb'version "21\.', java): raise ValueError('Unsupported Java version')
    for version in ('35.0.0', '36.0.0'):
        base = Path('/opt/android-sdk/build-tools') / version
        if not all((base / name).is_file() for name in ('aapt2', 'd8', 'zipalign', 'source.properties')):
            raise ValueError('Official Android build tools unavailable')
    if not Path('/opt/android-sdk/platforms/android-36/android.jar').is_file(): raise ValueError('Android platform unavailable')


def command():
    return ['/opt/gradle/gradle-8.14.6/bin/gradle', '--offline', '--no-daemon', '--max-workers=2', '--rerun-tasks',
            '--project-dir=/workspace/beacon-android', '--project-cache-dir=/scratch/project-cache', '-g', '/scratch/gradle-home',
            '-I', '/opt/beacon-repair/gradle-init.gradle', '-Pkotlin.project.persistent.dir=/scratch/kotlin',
            '-Pkotlin.compiler.execution.strategy=in-process',
            '-Duser.home=/scratch/home', '-Djava.io.tmpdir=/scratch/tmp',
            '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Duser.home=/scratch/home -Djava.io.tmpdir=/scratch/tmp', *TASKS]


def _prepare_cache(source, manifest_path, scratch):
    source, manifest_path, scratch = Path(source), Path(manifest_path), Path(scratch)
    if source.is_symlink() or not source.is_dir() or manifest_path.is_symlink() or not manifest_path.is_file() or manifest_path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError('Public dependency cache unavailable')
    manifest = json.loads(manifest_path.read_bytes())
    if not isinstance(manifest, dict) or not manifest or len(manifest) > 200000: raise ValueError('Public dependency cache unavailable')
    root = source.resolve(strict=True); target = scratch / 'gradle-home' / 'caches' / 'modules-2'
    total = 0
    for name, expected in sorted(manifest.items()):
        if (not isinstance(name, str) or not name or len(name) > 600 or '\\' in name or ':' in name or
                any(part in ('', '.', '..') for part in name.split('/')) or not isinstance(expected, str) or not re.fullmatch(r'[0-9a-f]{64}', expected)):
            raise ValueError('Public dependency cache unavailable')
        path = root
        for part in name.split('/'):
            path = path / part
            if path.is_symlink(): raise ValueError('Public dependency cache unavailable')
        if not path.is_file() or not path.resolve(strict=True).is_relative_to(root): raise ValueError('Public dependency cache unavailable')
        size = path.stat().st_size; total += size
        if size > 512 * 1024 * 1024 or total > 6 * 1024**3: raise ValueError('Public dependency cache unavailable')
        destination = target.joinpath(*name.split('/')); destination.parent.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        with path.open('rb') as incoming, destination.open('xb') as outgoing:
            for block in iter(lambda: incoming.read(1024 * 1024), b''):
                digest.update(block); outgoing.write(block)
        if digest.hexdigest() != expected: raise ValueError('Public dependency cache unavailable')


def main():
    if sys.argv[1:] != ['final_build']: return 2
    scratch = Path('/scratch')
    if not scratch.is_dir() or any(scratch.iterdir()): return 2
    try:
        check_environment()
        for name in ('home', 'tmp', 'project-cache', 'android-home'): (scratch / name).mkdir()
        _prepare_cache('/opt/beacon-repair/gradle-cache', '/opt/beacon-repair/gradle-cache-manifest.json', scratch)
        allowed = {'PATH', 'LANG', 'LC_ALL', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_HOME'}
        env = {key: value for key, value in os.environ.items() if key in allowed}
        env.update(HOME='/scratch/home', TMPDIR='/scratch/tmp', GRADLE_USER_HOME='/scratch/gradle-home', ANDROID_USER_HOME='/scratch/android-home')
        return subprocess.run(command(), cwd='/workspace/beacon-android', env=env, stdin=subprocess.DEVNULL, check=False).returncode
    except (OSError, ValueError, TypeError, subprocess.SubprocessError): return 2


if __name__ == '__main__':
    raise SystemExit(main())
