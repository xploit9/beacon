"""Fixed pytest entrypoint baked into a pinned, network-isolated repair image."""
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


STAGES = frozenset({'reproduction', 'regression_red', 'regression_green', 'reproduction_green',
                    'final_reproduction', 'final_regression', 'final_unit', 'final_plugin'})


def run(stage, nodes, workspace, scratch, config):
    workspace, scratch, config = Path(workspace), Path(scratch), Path(config)
    if (stage not in STAGES or not isinstance(nodes, list) or not 1 <= len(nodes) <= 128 or
            workspace.is_symlink() or not workspace.is_dir() or scratch.is_symlink() or not scratch.is_dir() or
            any(scratch.iterdir()) or config.is_symlink() or not config.is_file()):
        raise ValueError('Invalid test stage')
    workspace, scratch = workspace.resolve(strict=True), scratch.resolve(strict=True)
    if workspace == scratch or workspace in scratch.parents or scratch in workspace.parents: raise ValueError('Invalid test stage')
    for node in nodes:
        if (not isinstance(node, str) or len(node) > 400 or not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.:/\[\]-]*', node) or node.startswith('-')):
            raise ValueError('Invalid test stage')
        parts = node.split('::')[0].split('/')
        if any(part in ('', '.', '..') for part in parts): raise ValueError('Invalid test stage')
        path = workspace
        for part in parts:
            path = path / part
            if path.is_symlink(): raise ValueError('Invalid test stage')
        if not path.is_file() or not path.resolve(strict=True).is_relative_to(workspace): raise ValueError('Invalid test stage')
    home, temp = scratch / 'home', scratch / 'tmp'
    home.mkdir(); temp.mkdir()
    allowed = {'PATH', 'SYSTEMROOT', 'WINDIR', 'PATHEXT', 'COMSPEC', 'LANG', 'LC_ALL'}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env.update(HOME=str(home), TEMP=str(temp), TMP=str(temp), TMPDIR=str(temp), PYTHONDONTWRITEBYTECODE='1', PYTEST_DISABLE_PLUGIN_AUTOLOAD='1', HERMES_HOME=str(home), HERMES_DISABLE_LAZY_INSTALLS='1')
    native = workspace / 'public-native'
    if native.is_dir() and not native.is_symlink(): env['HERMES_AGENT_SOURCE'] = str(native)
    report = scratch / 'junit.xml'
    command = [sys.executable, '-I', '-m', 'pytest', '-q', '-s', '-p', 'no:cacheprovider']
    if importlib.util.find_spec('pytest_asyncio') is not None: command.extend(['-p', 'pytest_asyncio.plugin'])
    command.extend(['-c', str(config), '--rootdir=' + str(workspace), '--basetemp=' + str(scratch / 'pytest-temp'), '--junitxml=' + str(report), *nodes])
    process = subprocess.run(command, cwd=workspace, env=env, stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    if (process.returncode not in (0, 1) or report.is_symlink() or not report.is_file() or
            report.stat().st_nlink != 1 or report.stat().st_size > 4 * 1024 * 1024):
        return 2
    try:
        raw = report.read_bytes()
        if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper(): return 2
        root = ET.fromstring(raw)
        cases = list(root.iter('testcase'))
        if not cases or any(not case.get('name') or case.findall('error') for case in cases): return 2
        failures = sum(len(case.findall('failure')) for case in cases)
        if process.returncode == 0 and failures or process.returncode == 1 and not failures: return 2
    except (OSError, ET.ParseError): return 2
    return process.returncode


def main():
    try: return run(sys.argv[1], sys.argv[2:], '/workspace', '/scratch', '/opt/beacon-repair/pytest.ini')
    except (IndexError, OSError, ValueError): return 2


if __name__ == '__main__':
    raise SystemExit(main())
