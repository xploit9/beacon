import hashlib
import importlib.util
import json
from pathlib import Path

import pytest


SCRIPT=Path(__file__).resolve().parents[3]/'cypher-android'/'tools'/'release-metadata.py'


def _script():
    spec=importlib.util.spec_from_file_location('cypher_release_metadata',SCRIPT)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


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

    artifact,manifest,metadata=module.write_release_metadata(apk,tmp_path/'release',aapt=str(aapt),apksigner=str(signer))

    assert artifact.name=='Cypher-1.0.0.apk' and artifact.read_bytes()==contents
    assert manifest.name=='Cypher-1.0.0.json'
    assert json.loads(manifest.read_text())=={
        'artifact_filename':'Cypher-1.0.0.apk',
        'package_name':'dev.hermes.android.stable',
        'version_code':100,
        'version_name':'1.0.0',
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

    with pytest.raises(module.ReleaseMetadataError,match='not the Cypher stable package'):
        module.metadata_for_apk(apk,str(aapt),str(signer))
