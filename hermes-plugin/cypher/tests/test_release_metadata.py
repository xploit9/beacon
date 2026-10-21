import hashlib
import importlib.util
import json
from pathlib import Path

import pytest


SCRIPT=Path(__file__).resolve().parents[3]/'cypher-android'/'tools'/'release-metadata.py'
ROOT=Path(__file__).resolve().parents[3]


def _script():
    spec=importlib.util.spec_from_file_location('cypher_release_metadata',SCRIPT)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def test_obsolete_review_disposition_file_is_removed():
    assert not (ROOT/'hermes-plugin'/'cypher'/'REVIEW-DISPOSITIONS.md').exists()
    assert 'REVIEW-DISPOSITIONS.md' not in (ROOT/'hermes-plugin'/'cypher'/'README.md').read_text(encoding='utf-8')


def test_plugin_metadata_remains_version_1_0_0():
    manifest=json.loads((ROOT/'hermes-plugin'/'cypher'/'dashboard'/'manifest.json').read_text(encoding='utf-8'))
    readme=(ROOT/'hermes-plugin'/'cypher'/'README.md').read_text(encoding='utf-8')

    assert manifest['version']=='1.0.0'
    assert '2.1.0' not in readme


def test_release_script_writes_canonical_stable_asset_and_feed_fields(tmp_path,monkeypatch):
    module=_script()
    apk=tmp_path/'app-stable-release.apk';contents=b'signed stable apk bytes';apk.write_bytes(contents)
    aapt=tmp_path/'aapt.exe';signer=tmp_path/'apksigner.exe';aapt.touch();signer.touch()
    certificate='a'*64
    def run(args):
        if args[0]==str(aapt):
            return "package: name='dev.hermes.android.stable' versionCode='100' versionName='1.0.0'"
        return 'Signer #1 certificate SHA-256 digest: '+certificate
    monkeypatch.setattr(module,'_run',run)
    plugin_version=next(line.split(':',1)[1].strip() for line in (ROOT/'hermes-plugin'/'cypher'/'plugin.yaml').read_text(encoding='utf-8').splitlines() if line.startswith('version:'))

    artifact,manifest,metadata=module.write_release_metadata(apk,tmp_path/'release',aapt=str(aapt),apksigner=str(signer))

    assert artifact.name=='Beacon-1.0.0.apk' and artifact.read_bytes()==contents
    assert manifest.name=='Beacon-1.0.0.json'
    assert json.loads(manifest.read_text())=={
        'artifact_filename':'Beacon-1.0.0.apk',
        'package_name':'dev.hermes.android.stable',
        'version_code':100,
        'version_name':'1.0.0',
        'plugin_version':plugin_version,
        'certificate_sha256':certificate,
        'sha256':hashlib.sha256(contents).hexdigest(),
        'size_bytes':len(contents),
    }
    assert metadata['sha256']==hashlib.sha256(contents).hexdigest()


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
