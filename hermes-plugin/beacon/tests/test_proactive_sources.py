"""The owner picks which sources the Proactive watch reads: stored per profile, applied to the watch job, shown to the app."""
import json

import pytest

from test_mobile import harness  # noqa: F401  (fixture)
from test_proactive import _fake_cron, _native_profile_scope  # noqa: F401  (fixtures and helper)

PATH = "/api/plugins/beacon/mobile/proactive"
# What the call catalogue holds: Hermes' own tools carry a toolset, an MCP server's tools carry the server's configured name.
ROWS = [
    {"name": "read_file", "_raw": "read_file", "_toolset": "file"},
    {"name": "web_search", "_raw": "web_search", "_toolset": "web"},
    {"name": "mcp_gmail_search", "_raw": "search", "_server": "gmail"},
    {"name": "mcp_google_calendar_list", "_raw": "list", "_server": "google-calendar"},
    {"name": "mcp_github_issues", "_raw": "issues", "_server": "github"},
]
CATALOGUE = "Hermes: file (1); web (1)\ngithub: issues\ngmail: search\ngoogle calendar: list"


@pytest.fixture
def catalogue(harness, monkeypatch):
    """The profile's sources as the plugin sees them; `state` lets a test remove one or make the catalogue unsettled."""
    api = harness[0]
    state = {"rows": list(ROWS), "text": CATALOGUE, "settled": True}

    async def rows(profile):
        return list(state["rows"]), state["settled"]

    monkeypatch.setattr(api.voice_context, "tool_catalogue", lambda profile, wait=None: state["text"])
    monkeypatch.setattr(api, "_proactive_source_rows", rows)
    return state


def _watch(jobs):
    return next(job for job in jobs if job["name"] == "Beacon: what needs you")


def _put(client, names):
    return client.put(PATH, json={"sources": names})


def test_source_options_map_each_name_to_the_toolsets_hermes_uses(harness):
    options = harness[0].proactive.watch_source_options(ROWS)

    assert list(options) == ["hermes", "gmail", "google calendar", "github"]
    assert options["hermes"] == {"name": "Hermes", "native": ["file", "web"], "servers": []}
    # A server keeps the name Hermes has it under ("google-calendar"); the label is only its words.
    assert options["google calendar"] == {"name": "google calendar", "native": [], "servers": ["google-calendar"]}
    assert harness[0].proactive.watch_source_options([{"name": "x"}, "x", None]) == {}


def test_no_choice_leaves_the_watch_with_every_tool(harness):
    api = harness[0]
    watch = api.proactive._specs(api.proactive.default_setup())[0]
    assert "enabled_toolsets" not in watch
    assert api.proactive.watch_toolsets(api.mobile, "owner", "default") is None


def test_a_watch_spec_carries_the_toolsets_it_is_given_and_nightly_memory_keeps_its_own(harness):
    proactive = harness[0].proactive
    watch = proactive._specs(proactive.default_setup(), (), ["gmail", "memory"])[0]
    assert watch["enabled_toolsets"] == ["gmail", "memory"]
    assert proactive._specs(proactive.default_setup(), (), ["gmail"])[1]["enabled_toolsets"] == ["memory", "no_mcp"]


def test_a_choice_updates_the_watch_in_place_and_clearing_it_restores_every_source(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    jobs, calls = _fake_cron(api, monkeypatch, home)
    api.proactive.configure(api.mobile, "owner", "default", {"enabled": True, **api.proactive.default_setup()})
    assert calls["create"] == 2 and not _watch(jobs).get("enabled_toolsets")
    nightly = next(job for job in jobs if job["name"].endswith("Nightly memory upkeep"))

    saved = _put(client, ["Gmail", "google calendar"])
    assert saved.status_code == 200, saved.text
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "gmail", "google-calendar", "memory"]
    assert (calls["create"], calls["update"]) == (2, 1), "the same job is updated; none is added"
    assert nightly["enabled_toolsets"] == ["memory", "no_mcp"]

    # Hermes' own tools are a source too; only servers, or only Hermes, never both lists at once.
    assert _put(client, ["Hermes", "github"]).status_code == 200
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "file", "github", "memory", "web"]
    assert _put(client, ["Hermes"]).status_code == 200
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "file", "memory", "no_mcp", "web"]
    assert calls["create"] == 2

    assert _put(client, []).status_code == 200
    assert not _watch(jobs).get("enabled_toolsets")
    assert api.proactive.watch_toolsets(api.mobile, "owner", "default") is None
    assert calls["create"] == 2


def test_a_list_of_every_connected_source_still_names_exactly_those(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    jobs, _ = _fake_cron(api, monkeypatch, home)
    api.proactive.configure(api.mobile, "owner", "default", {"enabled": True, **api.proactive.default_setup()})

    saved = _put(client, ["Hermes", "github", "gmail", "google calendar"])

    assert saved.status_code == 200, saved.text
    assert saved.json()["sources_all"] is False and all(row["watched"] for row in saved.json()["source_choices"])
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "file", "github", "gmail", "google-calendar", "memory", "web"]
    # Only "All sources" (an empty list) takes in a source connected later.
    assert _put(client, []).json()["sources_all"] is True and not _watch(jobs).get("enabled_toolsets")


def test_a_choice_made_while_the_watch_is_off_waits_for_it(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    jobs, calls = _fake_cron(api, monkeypatch, home)

    saved = _put(client, ["gmail"])

    assert saved.status_code == 200, saved.text
    assert saved.json()["enabled"] is False and jobs == [] and calls["create"] == 0
    api.proactive.set_enabled(api.mobile, "owner", "default", True)
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "gmail", "memory"]


def test_the_choice_belongs_to_one_owner_and_profile(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    _fake_cron(api, monkeypatch, home)
    assert _put(client, ["gmail"]).status_code == 200
    toolsets = api.proactive.watch_toolsets
    assert toolsets(api.mobile, "owner", "default") == ["beacon-mailbox", "gmail", "memory"]
    assert toolsets(api.mobile, "owner", "other") is None
    assert toolsets(api.mobile, "someone", "default") is None


def test_status_lists_every_connected_source_and_whether_it_is_watched(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    _fake_cron(api, monkeypatch, home)

    body = client.get(PATH).json()
    # The plain label list stays as it was, for an app that does not know the choice.
    assert body["sources"] == ["Hermes", "github", "gmail", "google calendar"]
    assert body["sources_all"] is True
    assert body["source_choices"] == [{"name": name, "watched": True} for name in body["sources"]]

    saved = _put(client, ["gmail"]).json()
    assert saved["sources"] == body["sources"] and saved["sources_all"] is False
    assert saved["source_choices"] == [{"name": "Hermes", "watched": False}, {"name": "github", "watched": False},
                                       {"name": "gmail", "watched": True}, {"name": "google calendar", "watched": False}]
    assert client.get(PATH).json()["source_choices"] == saved["source_choices"]
    # The same names read back in any case or spelling, as a rule's source does.
    assert _put(client, ["Google-Calendar", "GMAIL", "gmail"]).json()["source_choices"][2:] == [
        {"name": "gmail", "watched": True}, {"name": "google calendar", "watched": True}]


@pytest.mark.parametrize("value", [
    "gmail", {"gmail": True}, 7, 0, "", False, {}, [7], [None], [["gmail"]], ["gmail", ""], ["unknown source"], ["gmail", "nope"],
    ["x" * 81], ["gmail"] * 41, [f"source {index}" for index in range(41)], ["gmail\nsend"], ["a: b"],
])
def test_a_choice_with_names_the_catalogue_lacks_is_refused_and_nothing_changes(harness, catalogue, monkeypatch, value):
    api, client, _, home, _ = harness
    _fake_cron(api, monkeypatch, home)
    assert _put(client, ["github"]).status_code == 200

    refused = _put(client, value)

    assert refused.status_code == 400, refused.text
    assert api.proactive.watch_toolsets(api.mobile, "owner", "default") == ["beacon-mailbox", "github", "memory"]


def test_the_choice_travels_alone_and_an_older_field_set_is_still_refused(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    _fake_cron(api, monkeypatch, home)
    assert client.put(PATH, json={"sources": ["gmail"], "enabled": True}).status_code == 400
    assert client.put(PATH, json={"sources": ["gmail"], "delivery": "notify"}).status_code == 400
    assert client.put(PATH, json={"sources": None}).status_code == 200


def test_the_choice_needs_the_catalogue_to_name_the_toolsets(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    _fake_cron(api, monkeypatch, home)
    catalogue["rows"] = []

    refused = _put(client, ["gmail"])

    assert refused.status_code == 503, refused.text
    assert api.proactive.watch_toolsets(api.mobile, "owner", "default") is None
    assert _put(client, []).status_code == 200, "going back to every source needs no catalogue"


def test_a_source_that_goes_away_leaves_the_choice_quietly(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    jobs, calls = _fake_cron(api, monkeypatch, home)
    api.proactive.configure(api.mobile, "owner", "default", {"enabled": True, **api.proactive.default_setup()})
    assert _put(client, ["gmail", "github"]).status_code == 200
    updates = calls["update"]

    catalogue["rows"] = [row for row in ROWS if row.get("_server") != "github"]
    catalogue["text"] = "Hermes: file (1); web (1)\ngmail: search\ngoogle calendar: list"
    body = client.get(PATH).json()

    assert [row["name"] for row in body["source_choices"] if row["watched"]] == ["gmail"]
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "gmail", "memory"]
    assert calls["update"] == updates + 1 and calls["create"] == 2
    client.get(PATH)
    assert calls["update"] == updates + 1, "nothing changes again once it is dropped"

    # With none of the chosen sources left, the watch reads every source again.
    catalogue["rows"] = [row for row in ROWS if row.get("_server") not in ("gmail", "github")]
    catalogue["text"] = "Hermes: file (1); web (1)\ngoogle calendar: list"
    body = client.get(PATH).json()
    assert body["sources_all"] is True and all(row["watched"] for row in body["source_choices"])
    assert not _watch(jobs).get("enabled_toolsets")
    assert api.proactive.watch_toolsets(api.mobile, "owner", "default") is None


def test_a_catalogue_that_is_still_filling_never_drops_a_choice(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    jobs, calls = _fake_cron(api, monkeypatch, home)
    api.proactive.configure(api.mobile, "owner", "default", {"enabled": True, **api.proactive.default_setup()})
    assert _put(client, ["gmail", "github"]).status_code == 200
    updates = calls["update"]

    # Right after a restart a server can be missing for a moment; what the app sees is accurate, what is saved is untouched.
    catalogue.update(settled=False, rows=[row for row in ROWS if row.get("_server") != "github"],
                     text="Hermes: file (1); web (1)\ngmail: search\ngoogle calendar: list")
    body = client.get(PATH).json()
    assert [row["name"] for row in body["source_choices"] if row["watched"]] == ["gmail"]
    assert calls["update"] == updates
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "github", "gmail", "memory"]

    catalogue.update(settled=True, rows=list(ROWS), text=CATALOGUE)
    assert [row["name"] for row in client.get(PATH).json()["source_choices"] if row["watched"]] == ["github", "gmail"]


def test_no_catalogue_means_no_choices_to_show_and_the_choice_is_kept(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    _fake_cron(api, monkeypatch, home)
    assert _put(client, ["gmail"]).status_code == 200

    catalogue.update(rows=[], text="")
    body = client.get(PATH).json()

    assert body["sources"] == [] and body["source_choices"] == [] and body["sources_all"] is False
    assert api.proactive.watch_toolsets(api.mobile, "owner", "default") == ["beacon-mailbox", "gmail", "memory"]


def test_a_failed_job_update_puts_the_old_choice_back(harness, catalogue, monkeypatch):
    api, client, _, home, _ = harness
    jobs, _ = _fake_cron(api, monkeypatch, home)
    api.proactive.configure(api.mobile, "owner", "default", {"enabled": True, **api.proactive.default_setup()})
    assert _put(client, ["gmail"]).status_code == 200
    cron = __import__("sys").modules["hermes_cli.web_server_cron"]
    mutate = cron._mutate_cron_for_profile
    monkeypatch.setattr(cron, "_mutate_cron_for_profile",
                        lambda profile, action, job_id, changes=None: None if action == "update_job" else mutate(profile, action, job_id, changes))

    failed = _put(client, ["github"])

    assert failed.status_code >= 500
    assert api.proactive.watch_toolsets(api.mobile, "owner", "default") == ["beacon-mailbox", "gmail", "memory"]
    assert _watch(jobs)["enabled_toolsets"] == ["beacon-mailbox", "gmail", "memory"]
    assert json.loads(json.dumps(api.proactive.status(api.mobile, "owner", "default"))).get("_error") is not True
