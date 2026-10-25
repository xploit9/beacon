from contextlib import closing
import hmac
import secrets
import sys
import time
from types import ModuleType, SimpleNamespace

import pytest

from test_connect_info import set_public_url
from test_mobile import harness, load_private
from test_owner_claim import _native_profiles, _use_real_settings, _write_config


LINK = "/api/plugins/beacon/devices/link"
DEVICES = "/api/plugins/beacon/devices"
TARGETS = "/api/plugins/beacon/mobile/notifications/targets"


def link(client, name="Pixel", **extra):
    return client.post(LINK, json={"device_name": name, **extra})


def test_link_creates_a_device_for_the_signed_in_owner_without_a_code(harness):
    api, client, cfg, _, _ = harness
    cfg["profiles"] = ["default", "work"]

    response = link(client)

    assert response.status_code == 200, response.text
    assert response.headers["cache-control"] == "no-store"
    result = response.json()
    assert set(result) == {"device_id", "name", "profiles"}
    assert result["name"] == "Pixel" and result["profiles"] == ["default", "work"]
    assert api.mobile.DEVICE_ID.fullmatch(result["device_id"])
    devices = client.get(DEVICES).json()["devices"]
    assert [(device["device_id"], device["name"]) for device in devices] == [(result["device_id"], "Pixel")]
    assert devices[0]["created_at"] <= devices[0]["last_seen"]
    with closing(api.mobile.connect()) as conn:
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert not {"device_pairings", "pairing_attempts"} & tables


def test_link_without_a_name_uses_the_default_label(harness):
    _, client, _, _, _ = harness

    response = client.post(LINK, json={})

    assert response.status_code == 200, response.text
    assert response.json()["name"] == "Beacon phone"


def test_link_reuses_the_owners_own_device_and_refreshes_it(harness):
    api, client, _, _, _ = harness
    first = link(client).json()
    with api.mobile.connect() as conn:
        conn.execute("UPDATE beacon_devices SET last_seen=1 WHERE id=?", (first["device_id"],))

    again = link(client, "Pixel 9", device_id=first["device_id"])

    assert again.status_code == 200, again.text
    assert again.json()["device_id"] == first["device_id"] and again.json()["name"] == "Pixel 9"
    devices = client.get(DEVICES).json()["devices"]
    assert len(devices) == 1 and devices[0]["name"] == "Pixel 9" and devices[0]["last_seen"] > 1


def test_link_never_adopts_another_owners_or_providers_device(harness):
    api, client, _, _, _ = harness
    mobile = api.mobile
    theirs = mobile.link_device("someone-else", "basic", "Their phone")
    other_provider = mobile.link_device("owner", "other-provider", "Other provider")

    for foreign in (theirs, other_provider):
        result = link(client, "Mine", device_id=foreign["device_id"])
        assert result.status_code == 200, result.text
        assert result.json()["device_id"] != foreign["device_id"]

    with mobile.connect() as conn:
        rows = conn.execute("SELECT id,name FROM beacon_devices WHERE id IN (?,?)",
                            (theirs["device_id"], other_provider["device_id"])).fetchall()
    assert {row["id"]: row["name"] for row in rows} == {
        theirs["device_id"]: "Their phone", other_provider["device_id"]: "Other provider"}


def test_link_never_reuses_a_removed_device(harness):
    _, client, _, _, _ = harness
    removed = link(client).json()["device_id"]
    assert client.delete(f"{DEVICES}/{removed}").status_code == 200

    fresh = link(client, device_id=removed)

    assert fresh.status_code == 200, fresh.text
    assert fresh.json()["device_id"] != removed
    assert [device["device_id"] for device in client.get(DEVICES).json()["devices"]] == [fresh.json()["device_id"]]


def test_removing_a_linked_device_revokes_push_and_blocks_its_next_request(harness):
    api, client, cfg, _, _ = harness
    device_id = link(client).json()["device_id"]
    target = client.post("/api/plugins/beacon/mobile/notifications/targets", json={"id": device_id, "token": "f" * 40})
    assert target.status_code == 200, target.text

    assert client.delete(f"{DEVICES}/{device_id}").status_code == 200

    with api.mobile.connect() as conn:
        assert conn.execute("SELECT 1 FROM targets WHERE id=?", (device_id,)).fetchone() is None
    cfg["profiles"] = ["default", "work"]
    blocked = client.get("/api/plugins/beacon/mobile/bootstrap", headers={"X-Beacon-Device-ID": device_id})
    assert blocked.status_code == 403
    assert blocked.json() == {"detail": "device_removed"}


def test_a_linked_phone_works_on_every_profile_and_only_removal_refuses_it(harness):
    mobile = harness[0].mobile
    device = mobile.link_device("owner", "basic", "Pixel")
    mobile.touch_device("owner", "basic", "work", device["device_id"])
    # An ID this Hermes never linked (for example after its plugin data was reset) is ignored, not a lockout.
    mobile.touch_device("owner", "basic", "default", "f" * 32)
    assert mobile.remove_device("owner", device["device_id"])
    with pytest.raises(LookupError, match="device_removed"):
        mobile.touch_device("owner", "basic", "work", device["device_id"])


def test_link_stops_at_32_active_phones_and_a_removal_frees_a_slot(harness):
    api, client, _, _, _ = harness
    mobile = api.mobile
    assert mobile.MAX_DEVICES == 32
    ids = [mobile.link_device("owner", "basic", f"Phone {number}")["device_id"] for number in range(mobile.MAX_DEVICES)]

    full = link(client, "One too many")

    assert full.status_code == 409
    assert full.json() == {"detail": "Too many phones. Remove one in Devices."}
    assert len(client.get(DEVICES).json()["devices"]) == 32
    assert link(client, "Renamed", device_id=ids[0]).json()["device_id"] == ids[0]
    assert mobile.link_device("someone-else", "basic", "Their phone")
    assert client.delete(f"{DEVICES}/{ids[1]}").status_code == 200
    assert link(client, "Fits now").status_code == 200


@pytest.mark.parametrize("payload", [
    {"device_name": "Pixel", "profile": "default"},
    {"device_name": "Pixel", "link_ticket": "x" * 40},
    {"device_name": "Pixel", "code": "ABC-723"},
    {"device_name": "Pixel", "device_id": "bad id!"},
    {"device_name": "Pixel", "device_id": "a" * 129},
    {"device_name": "Pixel", "device_id": ""},
    {"device_name": "Pixel", "device_id": 7},
    {"device_name": ""},
    {"device_name": "   "},
    {"device_name": "x" * 81},
    {"device_name": "Pi\nxel"},
    {"device_name": 5},
    ["device_name"],
])
def test_link_rejects_unknown_fields_and_invalid_values(harness, payload):
    _, client, _, _, _ = harness

    assert client.post(LINK, json=payload).status_code == 400
    assert client.get(DEVICES).json()["devices"] == []


def test_link_needs_the_owners_hermes_sign_in(harness):
    api, client, cfg, _, _ = harness
    del client.headers["x-fixture-owner"]
    assert link(client).status_code == 401
    client.headers["x-fixture-owner"] = "foreign"
    assert link(client).status_code == 403
    client.headers["x-fixture-owner"] = "owner"
    cfg["owner_provider"] = "other-provider"
    assert link(client).status_code == 403

    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM beacon_devices").fetchone()[0] == 0


def test_link_on_a_no_auth_dashboard_needs_its_loopback_session(harness, monkeypatch):
    api, client, cfg, _, app = harness
    cfg.update(owner_id="local", owner_provider="loopback")
    app.state.auth_required = False
    del client.headers["x-fixture-owner"]
    set_public_url(monkeypatch, "https://hermes.example.test")
    token = secrets.token_urlsafe(32)
    server = ModuleType("hermes_cli.web_server")
    server._has_valid_session_token = lambda request: hmac.compare_digest(request.headers.get("x-hermes-session-token", ""), token)
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server", server)

    assert link(client).status_code == 401
    client.headers["x-hermes-session-token"] = token
    response = link(client)

    assert response.status_code == 200, response.text
    assert response.json()["profiles"] == ["default"]
    cfg["owner_id"] = "different-owner"
    assert link(client).status_code == 403


def test_first_link_on_a_fresh_install_binds_the_signed_in_owner(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ["default", "work"])
    assert not api.mobile.settings().get("owner_id")

    response = link(client)

    assert response.status_code == 200, response.text
    assert response.json()["profiles"] == ["default", "work"]
    assert api.mobile.settings()["owner_id"] == "owner"
    assert api.mobile.settings()["owner_provider"] == "basic"


@pytest.mark.parametrize("path", ["/pairing", "/pairing/redeem"])
def test_code_pairing_routes_are_gone(harness, path):
    _, client, _, _, _ = harness

    response = client.post("/api/plugins/beacon" + path, json={"code": "ABC-723", "device_name": "Pixel", "profile": "default"})

    assert response.status_code in (404, 405)


def test_plugin_registers_no_pairing_cli_or_slash_command(harness):
    plugin = load_private("__init__")
    registered = []
    ctx = SimpleNamespace(
        register_tool=lambda **kwargs: None,
        register_hook=lambda *args: None,
        register_cli_command=lambda **kwargs: registered.append(kwargs),
        register_command=lambda *args, **kwargs: registered.append((args, kwargs)),
    )

    plugin.register(ctx)

    assert registered == []
    assert not hasattr(plugin, "_pairing_block")


def test_removing_a_phone_still_revokes_when_another_target_payload_is_unreadable(harness):
    # Strix afd1b07 S6: one malformed payload used to abort the whole revocation.
    api, client, _, _, _ = harness
    device_id = link(client).json()["device_id"]
    registered = client.post(TARGETS, json={"id": device_id, "token": "t" * 40}, headers={"x-beacon-device-id": device_id})
    assert registered.status_code == 200, registered.text
    with api.mobile.connect() as conn:
        conn.execute("INSERT INTO targets VALUES(?,?,?,?,?,?)", ("legacy", "owner", "default", "l" * 40, time.time() + 3600, "not json"))

    assert client.delete(f"{DEVICES}/{device_id}").status_code == 200

    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM targets").fetchone()[0] == 0
    assert client.get(DEVICES).json()["devices"] == []


def test_removing_a_removed_phone_again_revokes_the_push_it_registered_without_its_id(harness):
    # Strix afd1b07 S7: the Hermes sign-in is the credential, so a removed phone can register push with no device header.
    api, client, _, _, _ = harness
    device_id = link(client).json()["device_id"]
    assert client.delete(f"{DEVICES}/{device_id}").status_code == 200
    again = client.post(TARGETS, json={"id": "lost-phone-push", "token": "r" * 40})
    assert again.status_code == 200, again.text

    assert client.delete(f"{DEVICES}/{device_id}").status_code == 200

    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM targets").fetchone()[0] == 0
    assert client.delete(f"{DEVICES}/{'f' * 32}").status_code == 404
