import hashlib
import importlib.util
import json
import re
from pathlib import Path

import pytest


ROOT=Path(__file__).resolve().parents[3]
SCRIPT=next(path/'tools'/'release-metadata.py' for path in ROOT.glob('*-android')
            if (path/'tools'/'release-metadata.py').is_file())


def _script():
    spec=importlib.util.spec_from_file_location('beacon_release_metadata',SCRIPT)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def test_obsolete_review_disposition_file_is_removed():
    assert not (ROOT/'hermes-plugin'/'beacon'/'REVIEW-DISPOSITIONS.md').exists()
    assert 'REVIEW-DISPOSITIONS.md' not in (ROOT/'hermes-plugin'/'beacon'/'README.md').read_text(encoding='utf-8')


def _plugin_manifest_version():
    return next(line.split(':',1)[1].strip() for line in (ROOT/'hermes-plugin'/'beacon'/'plugin.yaml').read_text(encoding='utf-8').splitlines() if line.startswith('version:'))


def test_plugin_and_android_app_share_one_release_version():
    gradle=(ROOT/'beacon-android'/'app'/'build.gradle.kts').read_text(encoding='utf-8')
    app_version=re.search(r'versionName\s*=\s*"([^"]+)"',gradle)[1]

    assert app_version==_plugin_manifest_version()


def test_plugin_metadata_remains_version_1_0_0():
    manifest=json.loads((ROOT/'hermes-plugin'/'beacon'/'dashboard'/'manifest.json').read_text(encoding='utf-8'))
    readme=(ROOT/'hermes-plugin'/'beacon'/'README.md').read_text(encoding='utf-8')

    assert manifest['version']=='1.0.0'
    assert '2.1.0' not in readme


def test_release_script_writes_canonical_stable_asset_and_feed_fields(tmp_path,monkeypatch):
    module=_script()
    apk=tmp_path/'app-stable-release.apk';contents=b'signed stable apk bytes';apk.write_bytes(contents)
    aapt=tmp_path/'aapt.exe';signer=tmp_path/'apksigner.exe';aapt.touch();signer.touch()
    certificate='a'*64
    plugin_version=_plugin_manifest_version()
    def run(args):
        if args[0]==str(aapt):
            return f"package: name='dev.hermes.android.stable' versionCode='100' versionName='{plugin_version}'"
        return 'Signer #1 certificate SHA-256 digest: '+certificate
    monkeypatch.setattr(module,'_run',run)

    artifact,manifest,metadata=module.write_release_metadata(apk,tmp_path/'release',aapt=str(aapt),apksigner=str(signer))

    assert artifact.name==f'Beacon-{plugin_version}.apk' and artifact.read_bytes()==contents
    assert manifest.name==f'Beacon-{plugin_version}.json'
    assert json.loads(manifest.read_text())=={
        'artifact_filename':f'Beacon-{plugin_version}.apk',
        'package_name':'dev.hermes.android.stable',
        'version_code':100,
        'version_name':plugin_version,
        'plugin_version':plugin_version,
        'certificate_sha256':certificate,
        'sha256':hashlib.sha256(contents).hexdigest(),
        'size_bytes':len(contents),
    }
    assert metadata['sha256']==hashlib.sha256(contents).hexdigest()


def test_release_script_refuses_an_apk_whose_version_differs_from_the_plugin(tmp_path,monkeypatch):
    module=_script()
    apk=tmp_path/'app-stable-release.apk';apk.write_bytes(b'signed stable apk bytes')
    aapt=tmp_path/'aapt.exe';signer=tmp_path/'apksigner.exe';aapt.touch();signer.touch()
    monkeypatch.setattr(module,'_run',lambda args:"package: name='dev.hermes.android.stable' versionCode='132' versionName='1.1.22'" if args[0]==str(aapt) else 'Signer #1 certificate SHA-256 digest: '+'a'*64)
    monkeypatch.setattr(module,'_plugin_version',lambda:'1.1.19')

    with pytest.raises(module.ReleaseMetadataError,match='differs from the Beacon plugin version'):
        module.write_release_metadata(apk,tmp_path/'release',aapt=str(aapt),apksigner=str(signer))
    assert not (tmp_path/'release').exists() or not any((tmp_path/'release').iterdir())


def test_release_script_refuses_a_production_apk(tmp_path,monkeypatch):
    module=_script()
    apk=tmp_path/'production.apk';apk.write_bytes(b'not a stable package')
    aapt=tmp_path/'aapt.exe';signer=tmp_path/'apksigner.exe';aapt.touch();signer.touch()
    monkeypatch.setattr(module,'_run',lambda args:"package: name='dev.hermes.android' versionCode='100' versionName='1.0.0'" if args[0]==str(aapt) else 'Signer #1 certificate SHA-256 digest: '+'a'*64)

    with pytest.raises(module.ReleaseMetadataError,match='not the Beacon stable package'):
        module.metadata_for_apk(apk,str(aapt),str(signer))


def test_release_script_finds_the_windows_apksigner_batch_file(tmp_path,monkeypatch):
    module=_script()
    tools=tmp_path/'sdk'/'build-tools'/'36.0.0';tools.mkdir(parents=True);(tools/'apksigner.bat').touch()
    monkeypatch.setattr(module.os,'name','nt')
    monkeypatch.setenv('ANDROID_SDK_ROOT',str(tmp_path/'sdk'));monkeypatch.delenv('ANDROID_HOME',raising=False);monkeypatch.delenv('APKSIGNER',raising=False)

    assert module.find_tool('apksigner')==str(tools/'apksigner.bat')
