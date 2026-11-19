"""Version skew, 1.2.0 app on the 1.2.1 plugin (CALLS-CONTRACT.md section 8): every route a 1.2.0 app calls is still served and
answers in its 1.2.0 shape. Lane A's test_calls_v3.py covers the grant fields, the contexts' 1.2.0 fields and the 1.2.0 invoke;
this file adds the route table and the 1.2.0 approval decision. Synthetic data only."""
import re
import threading
import time

from test_gateway_api import api, invoke_api  # noqa: F401 -- the gateway route fixture
from test_mobile import harness  # noqa: F401 -- the plugin route fixture

# Contract section 8: kept unchanged until the removal rule (1.3.0, 30 days, 14 days without a 1.2.0 call).
V120_ROUTES = {
    ('POST', '/mobile/calls'), ('POST', '/mobile/callbacks/{}/{}'), ('GET', '/mobile/calls/{}'), ('DELETE', '/mobile/calls/{}'),
    ('GET', '/mobile/voice/context'), ('GET', '/mobile/calls/{}/context'), ('GET', '/mobile/calls/{}/tools'),
    ('POST', '/mobile/calls/{}/tools/invoke'), ('GET', '/mobile/calls/{}/tools/requests/{}'),
    ('POST', '/mobile/calls/{}/tools/requests/{}/decision'), ('POST', '/mobile/calls/{}/tools/requests/{}/cancel'),
    *{('POST', f'/mobile/calls/{{}}/{name}') for name in
      ('events', 'finalize', 'search', 'memory', 'skills', 'profile-context', 'work', 'jobs', 'callback', 'schedule-callback')},
}
V3_ONLY = {'tool', 'ran_on_hermes', 'server_ms', 'read_only', 'error_code', 'expires_at'}


def served(router):
    found = set()
    for route in router.routes:
        path = re.sub(r'\{[^}]+\}', '{}', getattr(route, 'path', ''))
        path = path[path.index('/mobile/'):] if '/mobile/' in path else path
        found |= {(method, path) for method in getattr(route, 'methods', set())}
    return found


def test_every_route_a_120_app_calls_is_still_served(harness):
    api, *_ = harness
    missing = V120_ROUTES - served(api.router)
    assert not missing, sorted(missing)


def test_a_120_approval_decision_answers_at_once_in_its_120_shape(api):
    client, dispatcher, backend, _ = api
    backend.read_only = False
    card = invoke_api(client, request='approval', approval='ask').json()
    assert card['status'] == 'needs_approval' and not V3_ONLY & set(card)
    backend.wait = threading.Event()
    try:
        started = time.perf_counter()
        decided = client.post('/mobile/calls/call/tools/requests/approval/decision',
                              json={'proposal_id': card['proposal']['id'], 'decision': 'approve'})
        elapsed = time.perf_counter() - started
        assert decided.status_code == 200, decided.text
        assert elapsed < 1, 'a 1.2.0 decision has no wait_ms, so it never waits for the tool'
        assert decided.json()['status'] in ('ready', 'running') and not V3_ONLY & set(decided.json())
    finally:
        backend.wait.set()
