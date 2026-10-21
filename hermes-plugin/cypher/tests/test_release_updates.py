import hashlib
import fnmatch
import json
from pathlib import Path
from types import SimpleNamespace
import pytest

from test_mobile import harness


def _release(version_code=100, version_name='1.0.0', apk_bytes=b'signed stable apk', certificate='a' * 64):
    digest=hashlib.sha256(apk_bytes).hexdigest()
    return apk_bytes, {
        'artifact_filename': f'Cypher-{version_name}.apk',
        'package_name': 'dev.hermes.android.stable',
        'version_code': version_code,
    'version_name': version_name,
    'plugin_version': '1.0.0',
    'certificate_sha256': certificate,
        'sha256': digest,
        'size_bytes': len(apk_bytes),
    }


def _verification_tools(cfg, tmp):
    certificate='a' * 64
    aapt=tmp/'aapt.exe';signer=tmp/'apksigner.exe'
    aapt.touch();signer.touch()
    cfg.update(android_certificate_sha256=certificate,aapt=str(aapt),apksigner=str(signer))
    return certificate,aapt,signer


def _fake_github(api, monkeypatch, cfg, assets, versions):
    monkeypatch.setattr(api.shutil,'which',lambda name:'gh')
    patterns=[]
    def run(args,**kwargs):
        if args[0]=='gh':
            assert args[1:5]==['release','download','--repo','xploit9/beacon']
            requested=[args[index+1] for index,value in enumerate(args[:-1]) if value=='--pattern']
            patterns.extend(requested)
            assert requested and kwargs['shell'] is False and kwargs['timeout']==120
            target=Path(args[args.index('--dir')+1])
            for name,content in assets.items():
                if any(fnmatch.fnmatchcase(name,pattern) for pattern in requested): (target/name).write_bytes(content)
            return SimpleNamespace(returncode=0,stdout='',stderr='')
        apk=Path(args[-1]);identity=versions[apk.name]
        version_code,version_name=identity[:2]
        package_name=identity[2] if len(identity)>2 else 'dev.hermes.android.stable'
        if args[0]==cfg['aapt']:
            return SimpleNamespace(stdout=f"package: name='{package_name}' versionCode='{version_code}' versionName='{version_name}'")
        return SimpleNamespace(stdout='Signer #1 certificate SHA-256 digest: '+'a'*64)
    monkeypatch.setattr(api.subprocess,'run',run)
    return patterns


def test_auto_update_and_stable_metadata_work_without_local_android_tools(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    cfg.pop('aapt',None);cfg.pop('apksigner',None)
    apk,manifest=_release()
    manifest['plugin_version']='1.1.0'
    patterns=_fake_github(api,monkeypatch,cfg,
        {manifest['artifact_filename']:apk,'Cypher-1.0.0.json':json.dumps(manifest).encode()},
        {manifest['artifact_filename']:(100,'1.0.0')})
    installed=['1.0.0'];updates=[]
    monkeypatch.setattr(api,'_installed_plugin_version',lambda:installed[0])
    monkeypatch.setattr(api,'_hermes_busy',lambda owner:False)
    monkeypatch.setattr(api,'_update_cypher_plugin',lambda:updates.append('cypher') or installed.__setitem__(0,'1.1.0') or {'ok':True})
    release_checks=[]
    release=api.release
    def track_release(*args,**kwargs):
        release_checks.append(list(updates))
        return release(*args,**kwargs)
    monkeypatch.setattr(api,'release',track_release)

    result=api.auto_update_check(cfg,now=2_000_000)
    metadata=client.get('/api/plugins/cypher/mobile/releases/latest?package=dev.hermes.android.stable')

    assert updates==['cypher'] and result['result']=='restart_required'
    assert release_checks and release_checks[0]==['cypher']
    assert patterns==['Cypher-*.json','Cypher-1.0.0.apk']
    assert metadata.status_code==200,metadata.text
    assert metadata.json()['plugin_version']=='1.1.0'
    assert metadata.json()['sha256']==manifest['sha256']


def test_release_check_keeps_stable_feed_separate_from_production(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    apk,manifest=_release()
    certificate,_,_=_verification_tools(cfg,tmp)
    api.mobile.protect=lambda path:path.mkdir(parents=True,exist_ok=True)
    production=tmp/'production-releases';production.mkdir()
    production_apk,production_manifest=_release(21,'1.0-prod',b'production apk')
    production_manifest['package_name']='dev.hermes.android'
    (production/production_manifest['artifact_filename']).write_bytes(production_apk)
    (production/(production_manifest['sha256']+'.json')).write_text(json.dumps(production_manifest))
    production_latest=json.dumps(production_manifest).encode()
    (production/'latest.json').write_bytes(production_latest)
    cfg['release_root']=str(production)
    patterns=_fake_github(api,monkeypatch,cfg,{manifest['artifact_filename']:apk,'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                          {manifest['artifact_filename']:(100,'1.0.0'),production_manifest['artifact_filename']:(21,'1.0-prod','dev.hermes.android')})

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==200,response.text
    assert response.json()=={'version_name':'1.0.0','version_code':100,'plugin_version':'1.0.0','available':True}
    root=tmp/'plugin-data'/'cypher'/'releases-stable'
    digest=manifest['sha256']
    assert (root/manifest['artifact_filename']).read_bytes()==apk
    assert json.loads((root/(digest+'.json')).read_text())==manifest
    assert json.loads((root/'latest.json').read_text())==manifest
    assert (production/'latest.json').read_bytes()==production_latest
    stable=client.get('/api/plugins/cypher/mobile/releases/latest?package=dev.hermes.android.stable')
    assert stable.status_code==200 and stable.json()['package_name']=='dev.hermes.android.stable'
    assert '?' not in stable.json()['artifact_path']
    downloaded=client.get(stable.json()['artifact_path']+'?package=dev.hermes.android.stable')
    assert downloaded.status_code==200 and downloaded.content==apk
    latest=client.get('/api/plugins/cypher/mobile/releases/latest')
    assert latest.status_code==200 and latest.json()['package_name']=='dev.hermes.android'
    assert latest.json()['artifact_path'].endswith('/download')
    assert patterns[0]=='Cypher-*.json'


def test_new_stable_release_keeps_only_itself_and_the_release_it_replaces(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    _verification_tools(cfg,tmp)
    root=tmp/'plugin-data'/'cypher'/'releases-stable';root.mkdir(parents=True)
    for code,name,content in ((90,'0.9.0',b'oldest apk'),(95,'0.9.5',b'previous apk')):
        old_apk,old=_release(code,name,content)
        (root/old['artifact_filename']).write_bytes(old_apk)
        (root/(old['sha256']+'.json')).write_text(json.dumps(old))
    (root/'latest.json').write_text(json.dumps(old))
    apk,manifest=_release()
    _fake_github(api,monkeypatch,cfg,{manifest['artifact_filename']:apk,'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                 {manifest['artifact_filename']:(100,'1.0.0'),'Cypher-0.9.5.apk':(95,'0.9.5'),'Cypher-0.9.0.apk':(90,'0.9.0')})

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==200 and response.json()['available'] is True,response.text
    assert sorted(path.name for path in root.iterdir())==sorted(
        ['latest.json','Cypher-1.0.0.apk',manifest['sha256']+'.json','Cypher-0.9.5.apk',old['sha256']+'.json'])


@pytest.mark.parametrize(('pin','expected_status'),[(None,200),('b'*64,503)])
def test_stable_certificate_pin_is_optional_but_enforced_when_set(harness,monkeypatch,pin,expected_status):
    api,client,cfg,tmp,_=harness
    apk,manifest=_release()
    _verification_tools(cfg,tmp)
    cfg.pop('android_stable_certificate_sha256',None)
    if pin is not None: cfg['android_stable_certificate_sha256']=pin
    _fake_github(api,monkeypatch,cfg,{manifest['artifact_filename']:apk,'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                 {manifest['artifact_filename']:(100,'1.0.0')})

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==expected_status,response.text


def test_release_check_skips_apk_download_when_unchanged(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    apk,manifest=_release()
    _verification_tools(cfg,tmp)
    root=tmp/'plugin-data'/'cypher'/'releases-stable';root.mkdir(parents=True)
    (root/manifest['artifact_filename']).write_bytes(apk)
    (root/(manifest['sha256']+'.json')).write_text(json.dumps(manifest))
    (root/'latest.json').write_text(json.dumps(manifest))
    patterns=_fake_github(api,monkeypatch,cfg,{'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                          {manifest['artifact_filename']:(100,'1.0.0')})

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==200,response.text
    assert response.json()=={'version_name':'1.0.0','version_code':100,'plugin_version':'1.0.0','available':False}
    assert patterns==['Cypher-*.json']


def test_release_check_refreshes_plugin_metadata_without_redownloading_same_apk(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    apk,manifest=_release()
    _verification_tools(cfg,tmp)
    root=tmp/'plugin-data'/'cypher'/'releases-stable';root.mkdir(parents=True)
    old=dict(manifest,plugin_version='1.0.0')
    (root/old['artifact_filename']).write_bytes(apk)
    (root/(old['sha256']+'.json')).write_text(json.dumps(old))
    (root/'latest.json').write_text(json.dumps(old))
    manifest['plugin_version']='1.1.0'
    patterns=_fake_github(api,monkeypatch,cfg,{'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                          {manifest['artifact_filename']:(100,'1.0.0')})

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==200,response.text
    assert response.json()['plugin_version']=='1.1.0'
    assert response.json()['available'] is False
    assert json.loads((root/'latest.json').read_text())['plugin_version']=='1.1.0'
    assert patterns==['Cypher-*.json']


def test_release_check_rejects_metadata_without_plugin_version(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    apk,manifest=_release()
    _verification_tools(cfg,tmp)
    manifest.pop('plugin_version')
    patterns=_fake_github(api,monkeypatch,cfg,{'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                          {manifest['artifact_filename']:(100,'1.0.0')})

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==503
    assert 'plugin version' in response.json()['detail'].lower()
    assert patterns==['Cypher-*.json']


def test_release_check_reports_missing_github_cli_login_without_exposing_cli_output(harness,monkeypatch):
    api,client,_,_,_=harness
    monkeypatch.setattr(api.shutil,'which',lambda name:'gh')
    monkeypatch.setattr(api.subprocess,'run',lambda *args,**kwargs:SimpleNamespace(returncode=1,stdout='',stderr='You are not logged into any GitHub hosts. private-token-value'))

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==503
    assert response.json()['detail']=='GitHub CLI is not signed in on your PC. Sign in with gh auth login.'
    assert 'private-token-value' not in response.text


def test_release_check_rejects_bad_artifact_before_touching_existing_feed(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    apk,manifest=_release()
    _,_,_=_verification_tools(cfg,tmp)
    root=tmp/'plugin-data'/'cypher'/'releases-stable';root.mkdir(parents=True)
    old_apk,old_manifest=_release(99,'0.9.0',b'old signed apk')
    (root/old_manifest['artifact_filename']).write_bytes(old_apk)
    (root/(old_manifest['sha256']+'.json')).write_text(json.dumps(old_manifest))
    old_latest=json.dumps(old_manifest).encode()
    (root/'latest.json').write_bytes(old_latest)
    manifest['sha256']='0'*64
    _fake_github(api,monkeypatch,cfg,{manifest['artifact_filename']:apk,'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                 {manifest['artifact_filename']:(100,'1.0.0')})

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==503
    assert response.json()['detail']=='The GitHub APK checksum does not match its metadata.'
    assert (root/'latest.json').read_bytes()==old_latest


def test_release_check_replaces_latest_only_after_all_immutable_files_are_saved(harness,monkeypatch):
    api,client,cfg,tmp,_=harness
    root=tmp/'plugin-data'/'cypher'/'releases-stable';root.mkdir(parents=True)
    old_apk,old_manifest=_release(99,'0.9.0',b'old signed apk')
    (root/old_manifest['artifact_filename']).write_bytes(old_apk)
    (root/(old_manifest['sha256']+'.json')).write_text(json.dumps(old_manifest))
    old_latest=json.dumps(old_manifest).encode()
    (root/'latest.json').write_bytes(old_latest)
    apk,manifest=_release()
    _verification_tools(cfg,tmp)
    _fake_github(api,monkeypatch,cfg,{manifest['artifact_filename']:apk,'Cypher-1.0.0.json':json.dumps(manifest).encode()},
                 {old_manifest['artifact_filename']:(99,'0.9.0'),manifest['artifact_filename']:(100,'1.0.0')})
    replace=api.os.replace
    def fail_latest(source,destination):
        if Path(destination).name=='latest.json' and Path(destination).parent==root:
            raise OSError('fixture replacement failure')
        return replace(source,destination)
    monkeypatch.setattr(api.os,'replace',fail_latest)

    response=client.post('/api/plugins/cypher/mobile/releases/check')

    assert response.status_code==503
    assert response.json()['detail']=='The verified Cypher release could not be saved on Hermes.'
    assert (root/'latest.json').read_bytes()==old_latest
    assert (root/(manifest['sha256']+'.json')).is_file()
    assert (root/manifest['artifact_filename']).read_bytes()==apk


def test_release_check_is_owner_only_and_rejects_repository_option_injection(harness,monkeypatch):
    api,client,cfg,_,_=harness
    cfg['release_repo']='--help'
    calls=[]
    monkeypatch.setattr(api.shutil,'which',lambda name:calls.append(name) or 'gh')
    client.headers['x-fixture-owner']='foreign'
    assert client.post('/api/plugins/cypher/mobile/releases/check').status_code==403
    client.headers['x-fixture-owner']='owner'
    response=client.post('/api/plugins/cypher/mobile/releases/check')
    assert response.status_code==503 and response.json()['detail']=='The configured GitHub release repository is invalid.'
    assert calls==[]


def test_plugin_version_reports_imported_and_current_manifest_versions(harness,monkeypatch):
    api,client,_,tmp,_=harness
    (tmp/'plugin.yaml').write_text('name: cypher\nversion: 2.5.0\n',encoding='utf-8')
    monkeypatch.setattr(api,'ROOT',tmp)
    monkeypatch.setattr(api,'_RUNNING_PLUGIN_VERSION','2.4.0')

    response=client.get('/api/plugins/cypher/mobile/plugin/version')

    assert response.status_code==200
    assert response.json()=={'running':'2.4.0','installed':'2.5.0'}
    client.headers['x-fixture-owner']='foreign'
    assert client.get('/api/plugins/cypher/mobile/plugin/version').status_code==403
    client.headers.pop('x-fixture-owner')
    assert client.get('/api/plugins/cypher/mobile/plugin/version').status_code==401
