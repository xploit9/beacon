import argparse
from contextlib import redirect_stdout
import io
import json
from datetime import datetime
from pathlib import Path
import time
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

from test_connect_info import set_public_url
from test_mobile import harness, load_private


PAIRING_ERROR = "That code didn't work. Make a new one on your PC."


def mint(client, *, profile="default", name="Pixel"):
    response = client.post(
        "/api/plugins/cypher/pairing",
        json={"profile": profile, "name": name},
    )
    assert response.status_code == 200, response.text
    return response.json()


def redeem(client, pairing, *, name="Pixel"):
    return client.post(
        "/api/plugins/cypher/pairing/redeem",
        json={"code": pairing["code"].lower(), "device_name": name},
    )


def test_pairing_mints_hashed_code_redeems_once_and_links_owner(harness, monkeypatch, caplog):
    api, client, _, _, _ = harness
    set_public_url(monkeypatch, "https://hermes.example.test/cypher")

    pairing = mint(client)
    code = pairing["code"]
    assert len(code) == 7 and code[3] == "-"
    assert not set(code.replace("-", "")) & set("ILO01")
    assert set(code.replace("-", "")) <= set(api.mobile.PAIRING_ALPHABET)
    assert pairing["dashboard_url"] == "https://hermes.example.test/cypher"
    assert parse_qs(urlsplit(pairing["qr_link"]).query)["code"] == [code]
    assert pairing["device_id"]
    assert pairing["expires_at"]
    expires_in = datetime.fromisoformat(pairing["expires_at"]).timestamp() - time.time()
    assert 590 <= expires_in <= 600

    with api.mobile.connect() as conn:
        rows = [dict(row) for row in conn.execute("SELECT * FROM device_pairings")]
    assert code not in json.dumps(rows)
    assert code not in caplog.text

    redeemed = redeem(client, pairing)
    assert redeemed.status_code == 200, redeemed.text
    result = redeemed.json()
    assert result["url"] == "https://hermes.example.test/cypher"
    assert result["name"] == "hermes.example.test"
    assert result["device_id"] == pairing["device_id"]
    ticket = result["link_ticket"]
    assert ticket

    linked = client.post(
        "/api/plugins/cypher/devices/link",
        json={"device_id": pairing["device_id"], "link_ticket": ticket},
    )
    assert linked.status_code == 200, linked.text
    assert linked.json()["linked"] is True
    assert linked.json()["device_id"] == pairing["device_id"]
    assert linked.json()["name"] == "Pixel" and linked.json()["profile"] == "default"
    assert linked.json()["created_at"]
    devices = client.get("/api/plugins/cypher/devices").json()["devices"]
    assert len(devices) == 1
    assert devices[0]["device_id"] == pairing["device_id"]
    assert devices[0]["name"] == "Pixel" and devices[0]["profile"] == "default"
    assert devices[0]["created_at"] <= devices[0]["last_seen"]

    reused = client.post(
        "/api/plugins/cypher/devices/link",
        json={"device_id": pairing["device_id"], "link_ticket": ticket},
    )
    assert reused.status_code == 400


def test_wrong_expired_and_reused_pairing_codes_share_one_plain_error(harness, monkeypatch):
    api, client, _, _, _ = harness
    set_public_url(monkeypatch, "https://hermes.example.test")
    wrong = client.post(
        "/api/plugins/cypher/pairing/redeem",
        json={"code": "NOT-A-CODE", "device_name": "Pixel"},
    )
    assert wrong.status_code == 400
    assert wrong.json() == {"detail": PAIRING_ERROR}

    expired = mint(client)
    with api.mobile.connect() as conn:
        conn.execute(
            "UPDATE device_pairings SET code_expires=0 WHERE device_id=?",
            (expired["device_id"],),
        )
    expired_result = redeem(client, expired)
    assert expired_result.status_code == 400
    assert expired_result.json() == {"detail": PAIRING_ERROR}

    used = mint(client)
    assert redeem(client, used).status_code == 200
    reused = redeem(client, used)
    assert reused.status_code == 400
    assert reused.json() == {"detail": PAIRING_ERROR}


def test_pairing_attempt_limit_locks_client(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api.mobile, "PAIRING_MAX_CLIENT_ATTEMPTS", 2, raising=False)
    monkeypatch.setattr(api.mobile, "PAIRING_MAX_GLOBAL_ATTEMPTS", 10, raising=False)
    for _ in range(2):
        response = client.post(
            "/api/plugins/cypher/pairing/redeem",
            json={"code": "BAD001", "device_name": "Pixel"},
        )
        assert response.status_code == 400
    locked = client.post(
        "/api/plugins/cypher/pairing/redeem",
        json={"code": "BAD002", "device_name": "Pixel"},
    )
    assert locked.status_code == 429


def test_pairing_attempt_limit_locks_global_bucket(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api.mobile, "PAIRING_MAX_CLIENT_ATTEMPTS", 10, raising=False)
    monkeypatch.setattr(api.mobile, "PAIRING_MAX_GLOBAL_ATTEMPTS", 2, raising=False)
    for _ in range(2):
        response = client.post(
            "/api/plugins/cypher/pairing/redeem",
            json={"code": "BAD001", "device_name": "Pixel"},
        )
        assert response.status_code == 400
    locked = client.post(
        "/api/plugins/cypher/pairing/redeem",
        json={"code": "BAD002", "device_name": "Pixel"},
    )
    assert locked.status_code == 429


def test_mint_and_device_management_require_owner_and_profile(harness, monkeypatch):
    _, client, cfg, _, _ = harness
    cfg["profiles"] = ["default", "work"]
    set_public_url(monkeypatch, "https://hermes.example.test")

    assert client.post("/api/plugins/cypher/pairing", json={}).status_code == 403
    client.headers["x-fixture-owner"] = "foreign"
    assert client.post(
        "/api/plugins/cypher/pairing", json={"profile": "default"}
    ).status_code == 403
    assert client.get("/api/plugins/cypher/devices").status_code == 403

    client.headers["x-fixture-owner"] = "owner"
    pairing = mint(client, profile="work")
    redeemed = redeem(client, pairing)
    assert redeemed.status_code == 200
    client.headers["x-fixture-owner"] = "foreign"
    refused = client.post(
        "/api/plugins/cypher/devices/link",
        json={"device_id": pairing["device_id"], "link_ticket": redeemed.json()["link_ticket"]},
    )
    assert refused.status_code == 403


def test_removing_device_revokes_push_and_blocks_its_next_request(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    set_public_url(monkeypatch, "https://hermes.example.test")
    pairing = mint(client)
    redeemed = redeem(client, pairing)
    assert redeemed.status_code == 200
    assert client.post(
        "/api/plugins/cypher/devices/link",
        json={"device_id": pairing["device_id"], "link_ticket": redeemed.json()["link_ticket"]},
    ).status_code == 200

    target = client.post(
        "/api/plugins/cypher/mobile/notifications/targets",
        json={"id": pairing["device_id"], "token": "f" * 40},
    )
    assert target.status_code == 200, target.text
    removed = client.delete(
        f"/api/plugins/cypher/devices/{pairing['device_id']}"
    )
    assert removed.status_code == 200
    with api.mobile.connect() as conn:
        assert conn.execute(
            "SELECT 1 FROM targets WHERE id=?", (pairing["device_id"],)
        ).fetchone() is None

    cfg["profiles"] = ["default", "work"]
    next_request = client.get(
        "/api/plugins/cypher/mobile/bootstrap",
        headers={"X-Cypher-Device-ID": pairing["device_id"]},
    )
    assert next_request.status_code == 403
    assert next_request.json() == {"detail": "device_removed"}


def test_install_prompts_request_pairing_code_and_allow_qr_image():
    root = Path(__file__).resolve().parents[3]
    readme = (root / "README.md").read_text(encoding="utf-8")
    guide = (root / "docs" / "install-with-agent.md").read_text(encoding="utf-8")

    assert 'Run `hermes -p <profile> cypher pair --name "<my phone>"` and show me the dashboard address and pairing code.' in readme
    assert "Each phone gets its own code" in readme
    assert "Scan the QR, choose a screenshot of it, or type the address and code." in readme
    assert "Scan the QR, choose a screenshot of it, or type the address and code." in guide


def test_cli_and_slash_pair_commands_share_one_copy_friendly_block(harness, monkeypatch):
    plugin = load_private("__init__")
    cli = {}
    slash = {}
    minted = []
    monkeypatch.setattr(plugin.mobile, "settings", lambda profile=None: {
        "owner_id": "owner", "owner_provider": "basic", "profiles": ["default", "work"],
    })
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "work")
    monkeypatch.setattr(plugin.mobile, "dashboard_connect_info", lambda: (
        "https://hermes.example.test", "hermes.example.test",
    ))

    def mint_pairing(owner, profile, name, dashboard_url, dashboard_name):
        minted.append((owner, profile, name, dashboard_url, dashboard_name))
        return {"dashboard_url": dashboard_url, "code": "ABC-723"}

    monkeypatch.setattr(plugin.mobile, "mint_device_pairing", mint_pairing)
    monkeypatch.setattr(plugin.native, "verify", lambda *args: {"messages": []})
    ctx = SimpleNamespace(
        register_tool=lambda **kwargs: None,
        register_hook=lambda *args: None,
        register_cli_command=lambda **kwargs: cli.update({kwargs["name"]: kwargs}),
        register_command=lambda name, **kwargs: slash.update({name: kwargs}),
        session_key=lambda: "owned-session",
    )

    plugin.register(ctx)

    parser = argparse.ArgumentParser()
    parser.add_argument("-p", "--profile")
    cli["cypher"]["setup_fn"](parser)
    args = parser.parse_args(["-p", "work", "pair", "--name", "Pixel"])
    with redirect_stdout(io.StringIO()) as output:
        args.func(args)
    command_block = output.getvalue()
    slash_block = slash["cypher-pair"]["handler"]("")

    for block in (command_block, slash_block):
        assert "https://hermes.example.test" in block
        assert "ABC-723" in block
        assert "expires in 10 min" in block
    assert minted[0][:3] == ("owner", "work", "Pixel")
    assert minted[1][0:2] == ("owner", "work")
    assert minted[1][2] == "Cypher phone"


def test_a_code_made_before_the_first_sign_in_links_to_the_owner_who_signs_in(harness, monkeypatch):
    api, _, _, _, _ = harness
    mobile = api.mobile
    early = mobile.mint_device_pairing("", "default", "Pixel", "https://hermes.example.test", "hermes.example.test")
    theirs = mobile.mint_device_pairing("someone-else", "default", "Tab", "https://hermes.example.test", "hermes.example.test")
    for pairing in (early, theirs):
        pairing["ticket"] = mobile.redeem_device_pairing(pairing["code"], None, "phone")["link_ticket"]

    linked = mobile.link_paired_device(early["device_id"], early["ticket"], "owner", "basic")
    assert linked and linked["linked"] and linked["device_id"] == early["device_id"]
    assert [device["device_id"] for device in mobile.list_devices("owner")] == [early["device_id"]]
    # A code another owner made is never taken over.
    assert mobile.link_paired_device(theirs["device_id"], theirs["ticket"], "owner", "basic") is None


def test_cli_pairs_on_a_fresh_install_before_anyone_signed_in(harness, monkeypatch):
    plugin = load_private("__init__")
    minted = []
    monkeypatch.setattr(plugin.mobile, "settings", lambda profile=None: {})
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.mobile, "dashboard_connect_info", lambda: ("https://hermes.example.test", "hermes.example.test"))
    monkeypatch.setattr(plugin.mobile, "mint_device_pairing",
                        lambda *args: minted.append(args) or {"dashboard_url": args[3], "code": "ABC-723"})

    assert "Pairing code: ABC-723" in plugin._pairing_block(None, "Pixel")
    assert minted[0][:3] == ("", "default", "Pixel")


def test_a_paired_phone_works_on_every_profile_and_only_removal_refuses_it(harness, monkeypatch):
    api, _, _, _, _ = harness
    mobile = api.mobile
    pairing = mobile.mint_device_pairing("owner", "default", "Pixel", "https://hermes.example.test", "hermes.example.test")
    ticket = mobile.redeem_device_pairing(pairing["code"], None, "phone")["link_ticket"]
    assert mobile.link_paired_device(pairing["device_id"], ticket, "owner", "basic")
    # Paired on "default", the same phone keeps working on the owner's other profiles.
    mobile.touch_device("owner", "basic", "work", pairing["device_id"])
    # An ID this Hermes never linked (for example after its plugin data was reset) is ignored, not a lockout.
    mobile.touch_device("owner", "basic", "default", "f" * 32)
    assert mobile.remove_device("owner", pairing["device_id"])
    try:
        mobile.touch_device("owner", "basic", "work", pairing["device_id"])
        raise AssertionError("a removed phone must be refused")
    except LookupError as exc:
        assert str(exc) == "device_removed"
