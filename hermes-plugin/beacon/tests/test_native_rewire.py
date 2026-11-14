"""Beacon uses Hermes' own routes and methods where Hermes already has them (Lane NR, rows 2-5)."""
from test_mobile import harness


def test_the_plugin_no_longer_owns_session_pin_or_project_create(harness):
    api, client, _, _, _ = harness
    for path in ('/api/plugins/beacon/sessions/pin', '/api/plugins/beacon/mobile/projects'):
        assert client.post(path, json={}).status_code in (404, 405), path
    assert not hasattr(api.native, 'create_project')
    assert not hasattr(api.native, 'PROJECT_CREATION_LOCK')


def test_the_admin_router_keeps_only_what_hermes_has_no_route_for(harness):
    api, _, _, _, _ = harness
    admin = api.admin
    router = admin.build_router(lambda *args, **kwargs: None)
    served = {(method, route.path) for route in router.routes for method in route.methods}
    assert served == {
        ('POST', '/admin/plugin/restart'), ('GET', '/admin/plugin/restart/status'),
        ('POST', '/admin/dashboard/restart'), ('GET', '/admin/dashboard/restart/status'),
        ('GET', '/admin/model-capabilities'), ('GET', '/admin/bots'),
        # Hermes has no profile-scoped plugin-enable route (its dashboard enable acts on the dashboard's own profile and ignores ?profile=): the owner's one-tap
        # "Turn on Beacon for this profile" is the plugin's own route, which runs Hermes' own activation inside that profile's scope (test_profile_plugin_enable.py).
        ('POST', '/admin/profile-plugin/enable'),
    }
    for name in ('native_update', 'native_update_status', 'current_receipt', 'save_model', '_update_gate', 'snapshot', '_snapshot_locked', 'revision'):
        assert not hasattr(admin, name), name
    # What the dashboard restart and the health check still need.
    assert callable(admin.update_in_progress) and admin.UPDATING
