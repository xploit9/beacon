from test_mobile import harness


def test_plugin_version_reports_imported_and_current_manifest_versions(harness,monkeypatch):
    api,client,_,tmp,_=harness
    (tmp/'plugin.yaml').write_text('name: beacon\nversion: 2.5.0\n',encoding='utf-8')
    monkeypatch.setattr(api,'ROOT',tmp)
    monkeypatch.setattr(api,'_RUNNING_PLUGIN_VERSION','2.4.0')

    response=client.get('/api/plugins/beacon/mobile/plugin/version')

    assert response.status_code==200
    assert response.json()=={'running':'2.4.0','installed':'2.5.0'}
    client.headers['x-fixture-owner']='foreign'
    assert client.get('/api/plugins/beacon/mobile/plugin/version').status_code==403
    client.headers.pop('x-fixture-owner')
    assert client.get('/api/plugins/beacon/mobile/plugin/version').status_code==401
