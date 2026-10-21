import hmac
import secrets
import sys
from types import ModuleType

from test_mobile import harness


def assert_session_matches(actual, expected):
    if not isinstance(actual, str) or not hmac.compare_digest(actual, expected):
        raise AssertionError('Loopback session token mismatch')


def assert_token_absent(value, token):
    if token in value:
        raise AssertionError('Loopback session token leaked in an error response')


def set_public_url(monkeypatch, value):
    hermes_cli = ModuleType('hermes_cli'); hermes_cli.__path__ = []
    dashboard_auth = ModuleType('hermes_cli.dashboard_auth'); dashboard_auth.__path__ = []
    prefix = ModuleType('hermes_cli.dashboard_auth.prefix')
    prefix.resolve_public_url = lambda: value
    hermes_cli.dashboard_auth = dashboard_auth
    dashboard_auth.prefix = prefix
    monkeypatch.setitem(sys.modules, 'hermes_cli', hermes_cli)
    monkeypatch.setitem(sys.modules, 'hermes_cli.dashboard_auth', dashboard_auth)
    monkeypatch.setitem(sys.modules, 'hermes_cli.dashboard_auth.prefix', prefix)


def test_connect_info_requires_owner_auth_but_no_profile_selection(harness, monkeypatch):
    _, client, cfg, _, _ = harness
    cfg['profiles'] = ['default', 'second']
    set_public_url(monkeypatch, 'https://hermes.example.test/beacon')

    response = client.get('/api/plugins/beacon/connect-info')

    assert response.status_code == 200
    assert response.json() == {
        'url': 'https://hermes.example.test/beacon',
        'name': 'hermes.example.test',
    }
    assert response.headers['cache-control'] == 'no-store'
    del client.headers['x-fixture-owner']
    assert client.get('/api/plugins/beacon/connect-info').status_code == 401


def test_connect_info_returns_loopback_token_only_to_the_local_owner(harness, monkeypatch):
    _, client, cfg, _, app = harness
    cfg.update(owner_id='local', owner_provider='loopback')
    app.state.auth_required = False
    set_public_url(monkeypatch, 'https://hermes.example.test/beacon')
    token = secrets.token_urlsafe(32)
    server = ModuleType('hermes_cli.web_server')
    server._has_valid_session_token = lambda request: hmac.compare_digest(request.headers.get('x-hermes-session-token', ''), token)
    server._SESSION_TOKEN = token
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server', server)
    client.headers['x-hermes-session-token'] = token

    response = client.get('/api/plugins/beacon/connect-info')

    assert response.status_code == 200
    payload = response.json()
    assert payload['url'] == 'https://hermes.example.test/beacon'
    assert payload['name'] == 'hermes.example.test'
    assert set(payload) == {'url', 'name', 'session'}
    assert_session_matches(payload.get('session'), token)
    assert response.headers['cache-control'] == 'no-store'

    cfg['owner_id'] = 'different-owner'
    denied = client.get('/api/plugins/beacon/connect-info')
    assert denied.status_code == 403
    assert_token_absent(denied.text, token)


def test_connect_info_does_not_issue_loopback_token_without_dashboard_auth(harness, monkeypatch):
    _, client, cfg, _, app = harness
    cfg.update(owner_id='local', owner_provider='loopback')
    app.state.auth_required = False
    set_public_url(monkeypatch, 'https://hermes.example.test/beacon')
    token = secrets.token_urlsafe(32)
    server = ModuleType('hermes_cli.web_server')
    server._has_valid_session_token = lambda request: False
    server._SESSION_TOKEN = token
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server', server)

    response = client.get('/api/plugins/beacon/connect-info')

    assert response.status_code == 401
    assert_token_absent(response.text, token)


def test_connect_info_fails_closed_for_non_https_loopback_and_secret_urls(harness, monkeypatch):
    _, client, _, _, _ = harness
    secret = secrets.token_urlsafe(32)
    unsafe_urls = (
        'http://hermes.example.test',
        'https://localhost:8443',
        'https://127.0.0.1:8443',
        'https://[::1]:8443',
        'https://0x7f000001',
        'https://0.0.0.0:8443',
        f'https://user:{secret}@hermes.example.test',
        f'https://hermes.example.test/?token={secret}',
    )
    for url in unsafe_urls:
        set_public_url(monkeypatch, url)
        response = client.get('/api/plugins/beacon/connect-info')
        assert response.status_code == 409
        assert_token_absent(response.text, secret)
