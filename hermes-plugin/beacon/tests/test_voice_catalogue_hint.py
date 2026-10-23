import sys
import threading
import time
from types import SimpleNamespace

from test_mobile import harness


def test_a_cold_tool_catalogue_never_holds_the_call_context(harness, monkeypatch):
    api, _, _, _, _ = harness
    voice = api.voice_context
    release = threading.Event()
    builds = []

    def compact(profile):
        builds.append(profile)
        release.wait(10)
        return 'Gmail: Search messages'

    gateway = SimpleNamespace(Gateway=lambda mobile, scoped: SimpleNamespace(warm_catalogue=lambda profile: None, compact_catalogue=compact))
    monkeypatch.setitem(sys.modules, 'beacon_gateway_tools', gateway)
    monkeypatch.setattr(voice, 'HINT_WAIT', 0.2)
    monkeypatch.setattr(voice, 'HINTS', {})

    started = time.monotonic()
    assert voice.tool_catalogue('cold') == ''
    assert voice.tool_catalogue('cold') == ''
    assert time.monotonic() - started < 2
    assert builds == ['cold']  # the second call waited on the same discovery instead of starting another

    release.set()
    deadline = time.monotonic() + 5
    while not voice.HINTS['cold'].done() and time.monotonic() < deadline:
        time.sleep(0.01)
    assert voice.tool_catalogue('cold') == 'Gmail: Search messages'
    assert builds == ['cold', 'cold']


def test_a_failing_tool_catalogue_gives_an_empty_hint(harness, monkeypatch):
    api, _, _, _, _ = harness
    voice = api.voice_context

    def broken(profile):
        raise RuntimeError('discovery failed')

    monkeypatch.setitem(sys.modules, 'beacon_gateway_tools', SimpleNamespace(Gateway=lambda mobile, scoped: SimpleNamespace(warm_catalogue=broken, compact_catalogue=broken)))
    monkeypatch.setattr(voice, 'HINTS', {})
    assert voice.tool_catalogue('work') == ''


def test_settings_can_wait_longer_for_a_cold_catalogue(harness, monkeypatch):
    api, _, _, _, _ = harness
    voice = api.voice_context

    def compact(profile):
        time.sleep(0.5)
        return 'Gmail: Search messages'

    monkeypatch.setitem(sys.modules, 'beacon_gateway_tools', SimpleNamespace(Gateway=lambda mobile, scoped: SimpleNamespace(warm_catalogue=lambda profile: None, compact_catalogue=compact)))
    monkeypatch.setattr(voice, 'HINT_WAIT', 0.1)
    monkeypatch.setattr(voice, 'HINTS', {})
    assert voice.tool_catalogue('cold', 3.0) == 'Gmail: Search messages'
