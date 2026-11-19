"""The agent phone's read guard (EMU-DESIGN §6.5, spike S12): masks in the tree and the image, and the refusal.

Trees are synthetic uiautomator dumps shaped like the spike's fixture shop (hint attribute, the field's hint as text when empty).
Every absence check runs after a positive control shows the value really was in the unmasked capture.
"""
import importlib.util
import json
import os
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parents[1]
PASSWORD, CARD, UNREGISTERED_CARD, NOT_A_CARD = 'Test-Pass-4711', '4242424242424242', '5555555555554444', '4242424242424241'
IDENTIFIER = 'agent.kanto'
SHOP = 'dev.beacon.fixture.shop'


def load():
    spec = importlib.util.spec_from_file_location('fixture_agent_phone_redact', PLUGIN / 'agent_phone_redact.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def node(text='', hint='', rid='', desc='', password=False, package='dev.beacon.fixture.shop', bounds='[63,344][1017,470]'):
    attrs = {'index': '0', 'text': text, 'resource-id': rid, 'class': 'android.widget.EditText', 'package': package,
             'content-desc': desc, 'password': 'true' if password else 'false', 'bounds': bounds, 'hint': hint}
    from xml.sax.saxutils import quoteattr
    return '<node ' + ' '.join(f'{k}={quoteattr(v)}' for k, v in attrs.items()) + ' />'


def tree(*nodes):
    title = node('Kanto Foods · Sign in', bounds='[63,252][1017,344]')
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
            + title + ''.join(nodes) + '</hierarchy>')


@pytest.fixture
def r():
    return load()


@pytest.fixture
def masks(r):
    m = r.MaskSet()
    m.register(PASSWORD, SHOP)
    m.register(CARD)
    return m


def test_a_revealed_password_is_masked_and_the_identifier_stays(r, masks):
    raw = tree(node(IDENTIFIER, hint='Email'), node(PASSWORD, hint='Password', password=True, bounds='[63,470][1017,596]'))
    assert PASSWORD in raw and IDENTIFIER in raw  # positive control
    masked, boxes, counts = r.mask_tree(raw, masks)
    assert PASSWORD not in masked and IDENTIFIER in masked
    assert counts == {'registered': 1} and boxes == [(63, 470, 1017, 596)]


def test_the_password_flag_masks_a_value_beacon_did_not_fill(r):
    raw = tree(node('typed-by-hand-1', hint='Password', password=True))
    masked, _, counts = r.mask_tree(raw, r.MaskSet())
    assert 'typed-by-hand-1' not in masked and counts == {'password': 1}
    # An empty password field shows its hint as text: nothing to mask there.
    empty, _, none = r.mask_tree(tree(node('Password', hint='Password', password=True)), r.MaskSet())
    assert none == {} and 'Password' in empty


def test_a_registered_value_is_masked_anywhere_also_without_line_breaks(r, masks):
    m = r.MaskSet()
    m.register('Line-One\nLine-Two')
    raw = tree(node('notes: ' + PASSWORD, hint='Notes'), node('Line-OneLine-Two', hint='Notes'))
    for value in (PASSWORD, 'Line-OneLine-Two'):
        assert value in raw
    masked, _, counts = r.mask_tree(raw, masks)
    assert PASSWORD not in masked and counts['registered'] == 1
    masked2, _, counts2 = r.mask_tree(raw, m)
    assert 'Line-OneLine-Two' not in masked2 and counts2 == {'registered': 1}


def test_an_unregistered_card_number_is_masked_by_luhn_and_a_failing_number_is_not(r):
    raw = tree(node(UNREGISTERED_CARD, hint='Notes'), node('5555 5555 5555 4444', hint='Notes'), node(NOT_A_CARD, hint='Order'))
    masked, _, counts = r.mask_tree(raw, r.MaskSet())
    assert UNREGISTERED_CARD not in masked and '5555 5555 5555 4444' not in masked and counts == {'card': 2}
    assert NOT_A_CARD in masked  # the control: 16 digits that fail Luhn stay
    assert r.luhn(UNREGISTERED_CARD) and r.luhn(CARD) and not r.luhn(NOT_A_CARD) and not r.luhn('4242')


def test_expiry_and_security_code_fields_are_masked_by_their_label(r):
    raw = tree(node('12/29', hint='MM/YY'), node('737', hint='CVC', rid='dev.beacon.fixture.shop:id/cvc'),
               node('4471', hint='Security code'), node('MM/YY', hint='MM/YY'), node('737', hint='Quantity'))
    masked, _, counts = r.mask_tree(raw, r.MaskSet())
    assert '12/29' not in masked and counts == {'expiry': 1, 'cvc': 2}
    assert 'MM/YY' in masked and masked.count('737') == 1  # the empty expiry field and an unrelated 737 stay


def test_a_code_or_password_in_the_notification_shade_is_masked(r):
    shade = 'com.android.systemui'
    raw = tree(node('Kanto Foods: your code is 482913', package=shade), node('Temporary password Tmp-AAAA-1111', package=shade),
               node('Kanto Foods: your order shipped', package=shade), node('your code is 482913', hint='Notes'))
    masked, _, counts = r.mask_tree(raw, r.MaskSet())
    assert counts == {'notification': 2} and 'Tmp-AAAA-1111' not in masked
    assert 'your order shipped' in masked
    assert masked.count('482913') == 1  # the app's own text is not the shade: only the registered/Luhn rules apply there


def test_a_tree_less_screen_in_the_app_of_a_fill_is_refused(r, masks):
    blank = "<?xml version='1.0' ?><hierarchy rotation=\"0\">" + node('', bounds='[0,0][1080,2400]') + '</hierarchy>'
    assert r.mask_capture(blank, None, masks, package='dev.beacon.fixture.shop') == {'refused': r.REFUSED}
    assert r.mask_capture('', None, masks, package='dev.beacon.fixture.shop') == {'refused': r.REFUSED}
    assert r.mask_capture('<not xml', None, masks, package='dev.beacon.fixture.shop') == {'refused': r.REFUSED}
    # Another app with an empty tree, and the same app with a readable one, are not refused.
    assert 'refused' not in r.mask_capture(blank, None, masks, package='com.example.other')
    assert 'refused' not in r.mask_capture(tree(node('hello', hint='x')), None, masks, package='dev.beacon.fixture.shop')
    # The caller didn't say which app is in front: after any fill, an unreadable screen is refused (fail closed).
    assert r.mask_capture(blank, None, masks) == {'refused': r.REFUSED}
    assert 'refused' not in r.mask_capture(blank, None, r.MaskSet())


def test_the_image_is_blacked_out_where_the_tree_was_masked(r, masks):
    from PIL import Image
    image = Image.new('RGB', (720, 1600), (255, 255, 255))
    raw = tree(node(PASSWORD, hint='Password', password=True, bounds='[63,470][1017,596]'))
    out = r.mask_capture(raw, image, masks)
    assert out['masked'] == {'registered': 1}
    sx, sy = 720 / 1080, 1600 / 2400
    assert out['image'].getpixel((int(500 * sx), int(530 * sy))) == (0, 0, 0)  # inside the field
    assert out['image'].getpixel((int(500 * sx), int(300 * sy))) == (255, 255, 255)  # the title above it
    assert out['image'].getpixel((int(500 * sx), int(700 * sy))) == (255, 255, 255)


def test_the_mask_set_keeps_the_newest_values_and_clears(r):
    m = r.MaskSet()
    for i in range(r.MAX_VALUES + 10):
        m.register(f'value-{i:04d}')
    assert len(m) <= r.MAX_VALUES and m.found_in('value-0265') and not m.found_in('value-0000')
    m.register('ab')  # too short to mask without masking everything
    assert not m.found_in('xaby')
    m.clear()
    assert len(m) == 0 and not m.filled_packages


def test_no_masked_capture_holds_any_registered_value(r, masks):
    raw = tree(node(IDENTIFIER, hint='Email'), node(PASSWORD, hint='Password', password=True),
               node('pay with ' + CARD, hint='Notes'), node(CARD[:4] + ' ' + CARD[4:8] + ' ' + CARD[8:12] + ' ' + CARD[12:], hint='Card'))
    assert raw.count(PASSWORD) == 1 and CARD in raw  # positive control
    out = r.mask_capture(raw, None, masks)
    for value in (PASSWORD, CARD, CARD[:4] + ' ' + CARD[4:8]):
        assert value not in out['tree']
    assert IDENTIFIER in out['tree']


# -- PHONEFIX-121 (REVIEW-PHONE-121 P3-2): the masks outlive one process, as digests ---------------------------------------------

def kept(tmp_path):
    (tmp_path / 'run').mkdir(exist_ok=True)
    return tmp_path / 'run' / 'masks.json'


def show_password(value=PASSWORD):
    """The password field with "Show" on: its text is the value and the password flag is off."""
    return tree(node(IDENTIFIER, hint='Email'), node(value, hint='Password', bounds='[63,470][1017,596]'))


def test_p3_2_a_gateway_restart_still_masks_a_revealed_password_and_the_file_holds_no_value(r, tmp_path):
    store = kept(tmp_path)
    first = r.MaskSet(store=store)
    first.register(PASSWORD, SHOP)
    first.register(CARD)
    text = store.read_text(encoding='utf-8')
    assert PASSWORD not in text and CARD not in text  # digests only, never the values
    restarted = r.MaskSet(store=store)  # the gateway came back: a new process, an empty memory
    assert len(restarted) == 0 and SHOP in restarted.filled_packages
    raw = show_password()
    assert PASSWORD in raw  # positive control
    masked, boxes, counts = r.mask_tree(raw, restarted, SHOP)
    assert PASSWORD not in masked and IDENTIFIER in masked and counts == {'registered': 1} and boxes == [(63, 470, 1017, 596)]
    again, _, none = r.mask_tree(show_password('some other text'), restarted, SHOP)
    assert 'some other text' in again and none == {}  # a different text is shown
    inside, _, counts = r.mask_tree(show_password('Password: ' + PASSWORD), restarted, SHOP)
    assert PASSWORD not in inside and counts == {'registered': 1}  # the value as a word of a longer text


def test_p3_2_a_tree_less_screen_in_a_filled_app_is_refused_after_a_restart(r, tmp_path):
    store = kept(tmp_path)
    r.MaskSet(store=store).register(PASSWORD, SHOP)
    blank = '<hierarchy><node class="android.view.View" package="dev.beacon.fixture.shop" bounds="[0,0][1080,2400]"/></hierarchy>'
    assert 'refused' in r.mask_tree(blank, r.MaskSet(store=store), SHOP)[2]
    assert r.mask_tree(blank, r.MaskSet(), SHOP)[2] == {}  # without the kept set, nothing is known (the old behaviour)


def test_p3_2_the_setup_sessions_fills_reach_the_gateways_set(r, tmp_path):
    store = kept(tmp_path)
    gateway = r.MaskSet(store=store)  # long-lived, started before the owner's setup session
    assert not gateway.found_in('Typed-In-Setup-77') and not gateway.filled_packages
    setup = r.MaskSet(store=store)  # the dashboard's own object, for the setup session's channel
    setup.register('Typed-In-Setup-77', SHOP)
    assert gateway.found_in('Typed-In-Setup-77') and SHOP in gateway.filled_packages
    masked, _, counts = r.mask_tree(show_password('Typed-In-Setup-77'), gateway, SHOP)
    assert 'Typed-In-Setup-77' not in masked and counts == {'registered': 1}


def test_p3_2_a_dropped_snapshot_clears_the_kept_masks_and_the_running_gateways_too(r, tmp_path):
    store = kept(tmp_path)
    gateway = r.MaskSet(store=store)
    gateway.register(PASSWORD, SHOP)
    assert gateway.found_in(PASSWORD) and store.exists()
    store.unlink()  # the snapshot was dropped (a stop with no save): the host deleted the file
    assert not gateway.found_in(PASSWORD) and not gateway.filled_packages and len(gateway) == 0
    assert not r.MaskSet(store=store).filled_packages


def test_p3_2_clear_deletes_the_file(r, tmp_path):
    store = kept(tmp_path)
    masks = r.MaskSet(store=store)
    masks.register(PASSWORD, SHOP)
    masks.clear()
    assert not store.exists() and not masks.filled_packages


def test_p3_2_a_code_fill_the_helper_did_itself_keeps_its_app_too(r, tmp_path):
    store = kept(tmp_path)
    r.MaskSet(store=store).watch_fields(SHOP, [{'view_id': 'x'}])
    assert SHOP in r.MaskSet(store=store).filled_packages


def test_p3_2_nothing_is_written_without_a_phone_folder_or_a_store(r, tmp_path):
    masks = r.MaskSet()
    masks.register(PASSWORD, SHOP)
    assert masks.found_in(PASSWORD) and not list(tmp_path.iterdir())
    gone = r.MaskSet(store=tmp_path / 'removed-phone' / 'run' / 'masks.json')  # Remove deleted the folder: it is not made again
    gone.register(PASSWORD, SHOP)
    assert gone.found_in(PASSWORD) and not (tmp_path / 'removed-phone').exists()


@pytest.mark.skipif(__import__('os').name == 'nt', reason='POSIX file modes')
def test_p3_2_the_kept_file_is_owner_only(r, tmp_path):
    store = kept(tmp_path)
    r.MaskSet(store=store).register(PASSWORD, SHOP)
    assert store.stat().st_mode & 0o077 == 0


# ---- PHONEFIX-121 follow-up 4 (REVIEW-FIX-PHONE-121 F9, F10) ---------------------------------------------------------------------

def test_f9_a_failed_write_is_written_at_the_next_register(r, tmp_path, monkeypatch):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    setup = r.MaskSet(store=store)  # the dashboard's set
    real, calls = r.os.replace, []

    def flaky(src, dst):
        calls.append(dst)
        if len(calls) == 1:
            raise PermissionError(13, 'The process cannot access the file')  # Windows: the gateway had the file open
        return real(src, dst)
    setup.register('Setup-Login-1', SHOP)  # the file exists now: written once, as in any session
    monkeypatch.setattr(r.os, 'replace', flaky)
    setup.register('Setup-Pass-31', SHOP)  # this write fails
    setup.register('Setup-Pass-31', SHOP)  # the same fill again: it is not "nothing new" while the file lacks it
    assert gateway.found_in('Setup-Login-1')  # positive control: the first value crossed
    assert len(calls) == 2, 'the failed write was never retried'
    assert gateway.found_in('Setup-Pass-31'), 'the gateway never learns the value'


@pytest.mark.parametrize('text', ['Your code is 482913', 'Your Kanto code is 482913.', 'Code: "482913"', '(482913)', '482913,', 'code=482913;'])
def test_f10_after_a_restart_a_value_next_to_punctuation_is_still_masked(r, tmp_path, text):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    r.MaskSet(store=store).register('482913', SHOP)  # a code the vault filled
    restarted = r.MaskSet(store=store)
    if text == 'code=482913;':  # a value glued to a word by "=" is inside a longer word: the documented limit
        assert not restarted.found_in(text)
    else:
        assert restarted.found_in(text), text


def test_r2_4_the_digest_cap_keeps_the_newest_not_the_smallest(r, tmp_path, monkeypatch):
    monkeypatch.setattr(r, 'MAX_DIGESTS', 5)
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    writer = r.MaskSet(store=store)
    values = [f'Secret-Value-{i:02d}' for i in range(10)]
    for value in values:
        writer.register(value, SHOP)
    later = r.MaskSet(store=store)  # another process, or the gateway after a restart: only the file
    assert all(later.found_in(v) for v in values[-5:]), 'a newer value was dropped from the file'
    assert not any(later.found_in(v) for v in values[:5])  # the oldest went, in order


# ---- PHONEFIX-121 follow-up 6: a record that cannot be written, tentative reads, another app's tree ----------------------------------

def test_fu6_register_says_whether_its_record_is_on_disk(r, tmp_path, monkeypatch):
    assert r.MaskSet().register('Value-One-11', SHOP) is True  # no store: an in-memory set has no record to lose
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    masks = r.MaskSet(store=store)
    assert masks.register('Value-One-11', SHOP) is True and store.exists()
    assert masks.register('Value-One-11', SHOP) is True  # nothing new: the record is there
    gone = r.MaskSet(store=tmp_path / 'removed-phone' / 'run' / 'masks.json')  # run/ is missing: nothing can be recorded
    assert gone.register('Value-Two-22', SHOP) is False and gone.found_in('Value-Two-22') and not (tmp_path / 'removed-phone').exists()
    monkeypatch.setattr(r.os, 'replace', lambda *a: (_ for _ in ()).throw(PermissionError(13, 'in use')))
    assert r.MaskSet(store=tmp_path / 'run' / 'other.json').register('Value-Three-33', SHOP) is False  # a write that fails


def test_fu6_watching_says_whether_a_code_the_helper_filled_is_watched(r):
    masks = r.MaskSet()
    assert masks.watching(SHOP) is False
    masks.watch_fields(SHOP, [{'view_id': 'x'}])
    assert masks.watching(SHOP) is True and masks.watching('com.android.systemui') is False


def _code_tree(text='482913'):
    return tree(node('Enter the code we sent', hint='Info'), node(text, hint='Code', bounds='[63,470][1017,596]'))


def test_fu6_a_tentative_read_places_nothing_registers_nothing_and_ends_nothing(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [])  # every text field of the app is watched
    masked, boxes, counts = r.mask_tree(_code_tree(), masks, SHOP, commit=False)
    assert '482913' not in masked  # the code is masked in this read
    assert masks._watches[0].fields is None  # ... but the watch was not placed against it
    assert not masks.found_in('482913')  # and nothing was registered
    r.mask_tree(tree(node('Loading', hint='x'), node('Please wait', hint='y', bounds='[63,470][1017,596]')), masks, SHOP, commit=False)
    assert masks.watching(SHOP) is True  # a tree without the field ended nothing
    assert '482913' not in r.mask_tree(_code_tree(), masks, SHOP)[0]  # and the committed read still masks it


def test_fu6_another_apps_tree_neither_masks_nor_ends_a_watch(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [])
    bar = ('<hierarchy rotation="0">' + node('12:30', hint='clock', package='com.android.systemui') + node('Wi-Fi', hint='wifi', package='com.android.systemui', bounds='[63,470][1017,596]') + '</hierarchy>')
    r.mask_tree(bar, masks, 'com.android.systemui')
    r.mask_tree(bar, masks, None)
    assert masks.watching(SHOP) is True  # R3-1: the status bar's window ended the watch of the app in front
    assert '482913' not in r.mask_tree(_code_tree(), masks, SHOP)[0]


def test_fu6_field_boxes_lists_every_text_field(r):
    boxes = r.field_boxes(tree(node('a', hint='A'), node('b', hint='B', bounds='[63,470][1017,596]'),
                               node('c', hint='C', password=True, bounds='[63,600][1017,700]')))
    assert (63, 344, 1017, 470) in boxes and (63, 470, 1017, 596) in boxes and (63, 600, 1017, 700) in boxes
    assert r.field_boxes('') == [] and r.field_boxes('<hierarchy') == []


# ---- PHONEFIX-121 follow-up 7 (REVIEW-FIX-PHONE-121 R4): a watch placed against a sheet, and the fill record ahead of a code -------------

def _no_field_screen():
    """A sheet of the shop's own app: the same package, no text field."""
    return ('<hierarchy rotation="0"><node index="0" text="Checking the code" resource-id="" class="android.widget.TextView" '
            f'package="{SHOP}" content-desc="" password="false" bounds="[63,1700][1017,1800]" />'
            f'<node index="1" text="Cancel" resource-id="" class="android.widget.Button" package="{SHOP}" content-desc="" '
            'password="false" bounds="[63,2200][1017,2330]" /></hierarchy>')


def test_fu7_a_committed_placement_that_finds_no_field_leaves_the_watch_unplaced(r):
    """R4-1: the first shown read can be a sheet of the same app. It places nothing, which is no word on the watch: it stays, unplaced,
    and the code page that comes back is placed and masked. A watch ends when the fields it had placed are gone from its own app's tree
    (the app stays a code app: every text field of it is masked until the stop)."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 470, 1017, 596]}])
    r.mask_tree(_no_field_screen(), masks, SHOP)
    assert masks._watches and masks._watches[0].fields is None
    r.mask_tree(_no_field_screen(), masks, SHOP)  # and again: still no word
    assert masks._watches and masks._watches[0].fields is None
    code = tree(node('482913', hint='Code', rid=SHOP + ':id/code', bounds='[63,470][1017,596]'))
    assert '482913' not in r.mask_tree(code, masks, SHOP)[0] and masks.found_in('482913')  # placed, and registered by value
    r.mask_tree(_no_field_screen(), masks, SHOP)  # the placed field is gone from its own app's tree
    assert masks._watches == [] and masks.watching(SHOP) is True  # the watch ended; the code app did not
    assert '482913' not in r.mask_tree(code, masks, SHOP)[0]  # the code itself stays masked anywhere


def test_fu7_a_watch_with_no_targets_also_survives_a_screen_with_no_text_field(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [])  # a helper from before fu1: every text field of the app
    r.mask_tree(_no_field_screen(), masks, SHOP)
    assert masks.watching(SHOP) is True and masks._watches[0].fields is None
    assert '482913' not in r.mask_tree(_code_tree(), masks, SHOP)[0]


def test_fu7_mark_filled_writes_the_apps_record_and_says_whether_it_is_on_disk(r, tmp_path):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    masks = r.MaskSet(store=store)
    assert masks.mark_filled(SHOP) is True and store.exists() and SHOP in masks.filled_packages
    assert masks.mark_filled(SHOP) is True  # nothing new: the record is there
    assert r.MaskSet(store=tmp_path / 'removed-phone' / 'run' / 'masks.json').mark_filled(SHOP) is False  # run/ is missing
    assert r.MaskSet().mark_filled(SHOP) is True and r.MaskSet().mark_filled('') is True


# ---- PHONEFIX-121 follow-up 8: a watch placed by digest ends only at the stop or by the cap --------------------------------------------

def _sheet_screen():
    """A sheet of the shop's own app with no text field (the reviewer's "Checking the code" dialog)."""
    return _no_field_screen()


def test_fu8_a_watch_placed_by_digest_survives_a_screen_with_no_text_field(r):
    """Probe (b) of follow-up 7: the helper's reported field could not be placed (the keyboard moved the screen), so every text field of
    the app is watched by digest. A later shown capture of a sheet of the same app, with no text field, ended the watch (its fields
    were "gone"), and the code page that came back showed the code as text."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    assert '482913' not in r.mask_tree(_code_tree(), masks, SHOP)[0]  # the page: placed by digest, masked
    r.mask_tree(_sheet_screen(), masks, SHOP)  # a sheet of the same app comes up: no text field in it
    assert masks.watching(SHOP) is True
    assert '482913' not in r.mask_tree(_code_tree(), masks, SHOP)[0]  # the page comes back: still masked
    assert not masks.found_in('482913')  # a digest field is never registered by value: the field is what stays hidden


def test_fu8_a_digest_field_stays_hidden_after_its_text_changes_and_whatever_the_clock_says(r):
    now = [100.0]
    masks = r.MaskSet(clock=lambda: now[0])
    masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    assert '482913' not in r.mask_tree(_code_tree(), masks, SHOP)[0]
    now[0] += 3600  # long after the fill has settled
    assert 'hello' not in r.mask_tree(_code_tree('hello'), masks, SHOP)[0]  # a changed text is still hidden: nothing but the stop ends it
    assert masks.watching(SHOP) is True


def test_fu8_a_digest_field_comes_back_masked_at_its_own_place_only(r):
    """A field absent from one capture is masked again where a same-class node sits at its path; another node at that path, of another
    class, is no field of the watch."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    r.mask_tree(_code_tree(), masks, SHOP)
    r.mask_tree(_sheet_screen(), masks, SHOP)
    other = ('<hierarchy rotation="0"><node index="0" text="Kanto Foods" resource-id="" class="android.widget.TextView" '
             f'package="{SHOP}" content-desc="" password="false" bounds="[63,252][1017,344]" />'
             f'<node index="1" text="Welcome back" resource-id="" class="android.widget.TextView" package="{SHOP}" content-desc="" '
             'password="false" bounds="[63,344][1017,470]" /></hierarchy>')
    assert 'Welcome back' in r.mask_tree(other, masks, SHOP)[0]  # text views are no text fields: the watch masks fields, not words


def test_fu8_a_watch_placed_by_digest_ends_only_at_the_stop_or_by_the_cap(r):
    """The watch itself goes with the newest-8 cap or the stop; since R5-2 the app stays a code app (every text field masked) until the stop."""
    masks = r.MaskSet()
    for i in range(r.MAX_WATCHES + 1):
        masks.watch_fields(f'dev.beacon.app{i}', [{'bounds': [0, 0, 10, 10]}])
    assert len(masks._watches) == r.MAX_WATCHES and masks._watches[0].package == 'dev.beacon.app1'  # the oldest watch went
    assert all(masks.watching(f'dev.beacon.app{i}') for i in range(r.MAX_WATCHES + 1))  # but app0 is still a code app
    masks.clear()  # the stop
    assert not any(masks.watching(f'dev.beacon.app{i}') for i in range(r.MAX_WATCHES + 1))


def test_fu8_a_field_placed_by_its_view_id_keeps_the_rule_of_v7(r):
    """It is registered by value, so it stays masked wherever it shows; the watch ends when the field it had placed is gone from its
    own app's tree (the code is no longer there to hide)."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 470, 1017, 596]}])
    code = tree(node('482913', hint='Code', rid=SHOP + ':id/code', bounds='[63,470][1017,596]'))
    assert '482913' not in r.mask_tree(code, masks, SHOP)[0] and masks.found_in('482913')
    r.mask_tree(_sheet_screen(), masks, SHOP)
    assert masks._watches == []
    assert '482913' not in r.mask_tree(code, masks, SHOP)[0]  # and the code stays masked anywhere


def test_fu8_the_other_text_fields_of_a_bounds_placed_watch_stay_hidden(r):
    """A target found by its bounds alone may be the wrong field (the keyboard moved the screen), so every other text field of the app is
    watched by digest too: those, and the watch with them, stay until the stop."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'bounds': [63, 470, 1017, 596]}])
    page = tree(node('482913', hint='Code', bounds='[63,470][1017,596]'), node('a note', hint='Note', bounds='[63,600][1017,726]'))
    masked = r.mask_tree(page, masks, SHOP)[0]
    assert '482913' not in masked and 'a note' not in masked
    r.mask_tree(_sheet_screen(), masks, SHOP)
    assert masks.watching(SHOP) is True
    assert 'a note' not in r.mask_tree(page, masks, SHOP)[0]


# ---- PHONEFIX-121 follow-up 9: a watch that has a digest field adds every text field of its app seen in a later shown capture ----------

def _doc(*parts):
    return '<hierarchy rotation="0">' + ''.join(parts) + '</hierarchy>'


def _part(index, text, cls, rid='', bounds='[0,0][10,10]'):
    return (f'<node index="{index}" text="{text}" resource-id="{rid}" class="{cls}" package="{SHOP}" content-desc="" '
            f'password="false" bounds="{bounds}" />')


CODE_PAGE_XML = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                     _part(1, '482913', 'android.widget.EditText', SHOP + ':id/code', '[63,600][1017,726]'),
                     _part(2, 'Verify', 'android.widget.Button', bounds='[63,900][1017,1026]'))
SHEET_WITH_A_FIELD_XML = _doc(_part(0, 'Add a note', 'android.widget.TextView', bounds='[63,1700][1017,1800]'),
                              _part(1, 'Cancel', 'android.widget.Button', bounds='[63,1800][1017,1850]'),
                              _part(2, 'hello', 'android.widget.EditText', SHOP + ':id/note', '[63,1850][1017,1950]'))


@pytest.mark.parametrize('targets', [[{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}], [{'bounds': [0, 0, 10, 10]}], []],
                         ids=['view id not on the sheet', 'bounds that place nothing', 'no targets'])
def test_fu9_a_sheet_with_a_text_field_of_its_own_does_not_leave_the_code_page_unwatched(r, targets):
    """The sibling probe of follow-up 8: the FIRST shown capture after the fill is a sheet of the same app that has a text field of
    its own. The watch is placed (by digest) against that field, the page's code field was never in the watched set, and the code page
    that came back showed the code. A watch that has a digest field now takes in every text field of its app that a later shown
    capture shows."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, targets)
    masked_sheet = r.mask_tree(SHEET_WITH_A_FIELD_XML, masks, SHOP)[0]
    assert 'hello' not in masked_sheet  # the sheet's own field is the one the watch is placed on
    assert '482913' not in r.mask_tree(CODE_PAGE_XML, masks, SHOP)[0], 'the code the helper filled is shown as text'


def test_fu9_every_text_field_the_app_shows_later_stays_hidden_including_boxes_the_agent_types_into(r):
    """The cost, pinned: a field that is first shown after the watch was placed joins it, whatever it holds."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    r.mask_tree(CODE_PAGE_XML, masks, SHOP)
    search = _doc(_part(0, 'Search the menu', 'android.widget.TextView', bounds='[63,300][1017,380]'),
                  _part(1, 'Cancel', 'android.widget.Button', bounds='[63,2200][1017,2330]'),
                  _part(2, 'rice crackers', 'android.widget.EditText', SHOP + ':id/search', '[63,400][1017,500]'))
    assert 'rice crackers' not in r.mask_tree(search, masks, SHOP)[0]
    assert 'Search the menu' in r.mask_tree(search, masks, SHOP)[0]  # words are not boxes: only text fields are hidden


def test_fu9_a_tentative_read_masks_a_code_apps_fields_but_places_and_registers_nothing(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}])
    assert 'hello' not in r.mask_tree(SHEET_WITH_A_FIELD_XML, masks, SHOP, commit=False)[0]  # masked in this read: the app is a code app
    assert masks._watches[0].fields is None and not masks.found_in('hello')  # but a read that is not shown placed and registered nothing
    assert 'hello' not in r.mask_tree(CODE_PAGE_XML.replace('482913', 'hello'), masks, SHOP)[0]
    assert masks._watches[0].fields and masks.found_in('hello')  # the shown read did


def test_fu9_a_watch_whose_field_was_found_by_view_id_makes_a_code_app_too(r):
    """R6-1: the code is registered by value, but a sheet of the same app can carry the same view id and take the placement, so the app is a
    code app whatever the helper named: a later text field is hidden too."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}])
    page = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                _part(1, '482913', 'android.widget.EditText', SHOP + ':id/code', '[63,600][1017,726]'))
    assert '482913' not in r.mask_tree(page, masks, SHOP)[0] and masks.found_in('482913')
    assert all(f['placed'] for f in masks._watches[0].fields.values())
    assert 'hello' not in r.mask_tree(SHEET_WITH_A_FIELD_XML, masks, SHOP)[0]


def test_fu9_a_code_app_stays_the_watched_app(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    r.mask_tree(CODE_PAGE_XML, masks, SHOP)
    other = _doc(_part(0, 'Notes', 'android.widget.TextView', bounds='[63,300][1017,380]').replace(SHOP, 'com.example.other'),
                 _part(1, 'my note', 'android.widget.EditText', bounds='[63,400][1017,500]').replace(SHOP, 'com.example.other'))
    assert 'my note' in r.mask_tree(other, masks, 'com.example.other')[0]


def test_fu9_the_extended_watch_still_ends_at_the_stop_and_by_the_cap(r):
    masks = r.MaskSet()
    for i in range(r.MAX_WATCHES + 1):
        masks.watch_fields(f'dev.beacon.app{i}', [{'bounds': [0, 0, 10, 10]}])
    assert len(masks._watches) == r.MAX_WATCHES  # the cap still drops the oldest watch (its app stays a code app: R5-2)
    masks.clear()
    assert masks._watches == [] and not any(masks.watching(f'dev.beacon.app{i}') for i in range(r.MAX_WATCHES + 1))


def test_fu9_a_field_placed_by_position_alone_with_other_text_fields_on_the_page_hides_them_all(r):
    """A code filled into a field with no resource id, on a page that has another text field: both are masked, and a later sheet with a
    text field of its own is masked too (the app is a code app)."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': None, 'bounds': [63, 600, 1017, 726]}])
    page = _doc(_part(0, 'Name', 'android.widget.EditText', bounds='[63,400][1017,500]'),
                _part(1, '482913', 'android.widget.EditText', bounds='[63,600][1017,726]'))
    masked = r.mask_tree(page, masks, SHOP)[0]
    assert '482913' not in masked and 'text="Name"' not in masked and masks.found_in('482913')  # registered by value
    assert [f['placed'] for f in masks._watches[0].fields.values()] == [False]  # the position-found field: never dropped
    assert 'hello' not in r.mask_tree(SHEET_WITH_A_FIELD_XML, masks, SHOP)[0]


def test_fu9_a_field_placed_by_position_alone_is_a_digest_field_even_when_it_is_the_only_text_field(r):
    """R5-1: a field found by position alone may be the wrong box (a sheet's only box can lie over the code field), so it is a digest
    field, registered by value as before; the watch is extended, and the app is a code app."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': None, 'bounds': [63, 600, 1017, 726]}])
    page = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                _part(1, '482913', 'android.widget.EditText', bounds='[63,600][1017,726]'))
    assert '482913' not in r.mask_tree(page, masks, SHOP)[0] and masks.found_in('482913')
    assert [f['placed'] for f in masks._watches[0].fields.values()] == [False]
    assert 'hello' not in r.mask_tree(SHEET_WITH_A_FIELD_XML, masks, SHOP)[0]


# ---- PHONEFIX-121 follow-up 10 (REVIEW-FIX-PHONE-121 R5): position alone is a guess, and the code apps -------------------------------------

def test_fu10_a_field_found_by_position_alone_is_a_digest_field_that_is_never_dropped_and_still_registered_by_value(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/gone', 'bounds': [63, 600, 1017, 726]}])  # the view id is not on the page: by position
    page = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                _part(1, '482913', 'android.widget.EditText', bounds='[63,600][1017,726]'))
    assert '482913' not in r.mask_tree(page, masks, SHOP)[0] and masks.found_in('482913')
    (field,) = masks._watches[0].fields.values()
    assert field['placed'] is False and field['value'] is True
    r.mask_tree(_no_field_screen(), masks, SHOP)  # a screen of the same app with no text field: says nothing
    assert masks.watching(SHOP) is True
    assert '482913' not in r.mask_tree(page, masks, SHOP)[0]


def test_fu10_a_field_found_by_its_view_id_is_placed_and_keeps_the_rule_of_v7(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}])
    page = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                _part(1, '482913', 'android.widget.EditText', SHOP + ':id/code', '[63,600][1017,726]'))
    r.mask_tree(page, masks, SHOP)
    (field,) = masks._watches[0].fields.values()
    assert field['placed'] is True and field['value'] is True


@pytest.mark.parametrize('targets', [[], [{'bounds': [0, 0, 10, 10]}], [{'view_id': 'x:id/c', 'bounds': [0, 0, 10, 10]}, {'bounds': [1, 1, 2, 2]}],
                                     [{'view_id': 'x:id/c', 'bounds': [0, 0, 10, 10]}]],
                         ids=['no targets', 'bounds only', 'one target without a view id', 'a view id'])
def test_fu10_watch_fields_makes_a_code_app_whatever_the_helper_reported(r, targets):
    """R6-1: every helper code fill, a view id included (a sheet of the same app can carry the same id)."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, targets)
    assert SHOP in masks._code_apps and masks.watching(SHOP) is True


def test_fu10_the_app_is_a_code_app_before_any_capture_so_a_restart_before_the_first_shown_read_loses_nothing(r, tmp_path):
    """R6-2: the helper named a view id, so v10 made no code app until a shown read; a Hermes restart in that window showed the code."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    assert gateway.mark_filled(SHOP) is True
    assert gateway.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}]) is True
    assert json.loads(store.read_text(encoding='utf-8'))['code_apps'] == [SHOP]
    restarted = r.MaskSet(store=store)  # Hermes restarted before the agent's first shown capture; the phone kept running
    assert '482913' not in r.mask_tree(CODE_PAGE_XML, restarted, SHOP)[0]


def test_fu10_every_text_field_of_a_code_app_is_masked_whatever_watch_the_cap_kept(r):
    """R5-2 at the redact level: the code page's field was never registered by value, and the cap dropped its watch."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    for i in range(r.MAX_WATCHES):
        masks.watch_fields(f'dev.beacon.app{i}', [{'bounds': [0, 0, 10, 10]}])
    assert all(w.package != SHOP for w in masks._watches)  # the shop's watch is gone
    typed = _doc(_part(0, 'Search', 'android.widget.TextView', bounds='[63,300][1017,380]'),
                 _part(1, 'rice crackers', 'android.widget.EditText', bounds='[63,400][1017,500]'))
    masked = r.mask_tree(typed, masks, SHOP)[0]
    assert 'rice crackers' not in masked and 'Search' in masked  # a text field, not a word


def test_fu10_a_code_app_does_not_hide_another_apps_text_fields(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    other = _doc(_part(0, 'my note', 'android.widget.EditText', bounds='[63,400][1017,500]').replace(SHOP, 'com.example.other'))
    assert 'my note' in r.mask_tree(other, masks, 'com.example.other')[0]


def test_fu10_the_code_apps_are_kept_in_the_masks_file_and_every_process_masks_them(r, tmp_path):
    """The reviewer's restart check, flipped: Hermes restarts while the phone runs. The watch lived in the old process's memory; the app is in
    the file as a code app, so the new process masks every text field of it."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    assert gateway.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}]) is True
    data = json.loads(store.read_text(encoding='utf-8'))
    assert data['code_apps'] == [SHOP] and SHOP in data['packages']
    assert '482913' not in r.mask_tree(_code_tree(), gateway, SHOP)[0]
    restarted = r.MaskSet(store=store)  # Hermes restarted; the phone kept running
    assert '482913' not in r.mask_tree(_code_tree(), restarted, SHOP)[0]
    assert restarted.watching(SHOP) is True and restarted._watches == []  # no watch, but the app is a code app


def test_fu10_a_code_app_is_kept_in_the_file_when_the_watch_is_made_not_when_a_read_places_it(r, tmp_path):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    gateway.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}])
    assert json.loads(store.read_text(encoding='utf-8'))['code_apps'] == [SHOP]


def test_fu10_the_stop_deletes_the_code_apps_with_the_masks(r, tmp_path):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    gateway.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    other = r.MaskSet(store=store)  # the dashboard's setup session, or a restarted Hermes
    assert '482913' not in r.mask_tree(_code_tree(), other, SHOP)[0] and other.watching(SHOP) is True
    store.unlink()  # the stop (and every cold boot) deletes the masks file
    assert '482913' in r.mask_tree(_code_tree(), other, SHOP)[0]  # nothing is hidden now: the screen the code was on is gone
    assert other.watching(SHOP) is False and other._code_apps == set()
    gateway.clear()
    assert gateway._code_apps == set() and gateway._watches == []


def test_fu10_a_code_app_whose_record_cannot_be_written_says_so(r, tmp_path):
    """`watch_fields` returns False when the record can't be written. The vault only logs that (the code is already in, there is nothing to
    refuse: `test_fu11_a_code_app_whose_record_cannot_be_written_is_logged_not_refused`), and the app is masked in this process all the same."""
    masks = r.MaskSet(store=tmp_path / 'removed-phone' / 'run' / 'masks.json')  # run/ is missing
    assert masks.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}]) is False
    assert SHOP in masks._code_apps


# ---- PHONEFIX-121 follow-up 11 (REVIEW-FIX-PHONE-121 R6): a restart before the first shown capture, and the phone session -------------------

def test_r6_2_a_hermes_restart_before_the_first_shown_capture_does_not_show_a_code_the_helper_was_sure_of(r, tmp_path):
    """The helper names the code field by a view id. The code is registered by value (kept in the file as a digest) only when the first
    shown capture places the watch; until then it lived in the gateway's memory alone, so a Hermes restart in that window (Windows: the phone
    keeps running) left nothing that masked it. The app is a code app from the fill, and the file says so."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    assert gateway.mark_filled(SHOP) is True  # the vault's record, right before arm_code
    assert gateway.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}]) is True
    assert json.loads(store.read_text(encoding='utf-8'))['code_apps'] == [SHOP]
    page = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                _part(1, '482913', 'android.widget.EditText', SHOP + ':id/code', '[63,600][1017,726]'))
    assert '482913' not in r.mask_tree(page, gateway, SHOP, commit=False)[0]  # this process masks it
    restarted = r.MaskSet(store=store)  # Hermes restarted before the agent's next shown capture; the phone kept running
    assert '482913' not in r.mask_tree(page, restarted, SHOP)[0], 'after a Hermes restart the code the helper filled went to the model'


def _start_a_session(store, name):
    """What `PhoneHost.new_session` does at every start: a new id in run/session, written to a temporary file and moved over it."""
    tmp = store.parent / 'session.tmp'
    tmp.write_text(name, encoding='utf-8')
    os.replace(tmp, store.parent / 'session')


R6_SEARCH = ('<hierarchy rotation="0"><node index="0" text="rice crackers" resource-id="" class="android.widget.EditText" package="com.android.chrome" '
             'content-desc="" password="false" bounds="[63,300][1017,400]" /><node index="1" text="Search" resource-id="" '
             'class="android.widget.TextView" package="com.android.chrome" content-desc="" password="false" bounds="[63,100][1017,200]" /></hierarchy>')


def _stale_gateway(r, store):
    """The gateway's set from a session with a Chrome code app; the stop deleted the file; the next start wrote a new session id; the
    dashboard's setup session wrote the next session's file before the gateway looked again."""
    chrome = 'com.android.chrome'
    _start_a_session(store, 'session-1')
    gateway = r.MaskSet(store=store)
    gateway.watch_fields(chrome, [{'bounds': [0, 0, 10, 10]}])  # a code filled in Chrome: a code app
    assert gateway.watching(chrome) is True
    store.unlink()  # the stop (PhoneHost.cleanup_masks); the gateway's set is not told
    _start_a_session(store, 'session-2')  # the next start
    setup = r.MaskSet(store=store)  # the owner signs in to a site during setup, before the agent's next look
    assert setup.register('Setup-Pass-4711', 'dev.example.mail') is True and setup.watching(chrome) is False
    assert 'rice crackers' in r.mask_tree(R6_SEARCH, setup, chrome)[0]  # control: the new session hides nothing in Chrome
    return gateway


def test_r6_3_a_code_app_does_not_outlive_the_stop_when_the_setup_session_writes_the_next_file_first(r, tmp_path):
    """Fails closed: the last session's code app (and its watches, fill records and values) stayed in the gateway's set."""
    (tmp_path / 'run').mkdir()
    gateway = _stale_gateway(r, tmp_path / 'run' / 'masks.json')
    assert 'rice crackers' in r.mask_tree(R6_SEARCH, gateway, 'com.android.chrome')[0], 'a code app of the last phone session still hides every Chrome box'


def test_r6_3_a_new_code_app_in_the_next_session_is_kept_in_the_file(r, tmp_path):
    """Fails open: the stale `_kept`/`_code_kept` made `_persist` return early, so a new session's code app in the same app never reached
    the file, and a Hermes restart while that code was on its page showed it."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = _stale_gateway(r, store)
    assert gateway.watch_fields('com.android.chrome', [{'bounds': [0, 0, 10, 10]}]) is True  # the next session's code
    assert json.loads(store.read_text(encoding='utf-8'))['code_apps'] == ['com.android.chrome'], "the new session's code app is not in the masks file"


def test_fu11_a_new_session_forgets_the_session_sets_and_keeps_the_values_digested_again(r, tmp_path):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    _start_a_session(store, 'session-1')
    gateway = r.MaskSet(store=store)
    assert gateway.register('Old-Value-4711', SHOP) is True
    gateway.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    assert gateway._watches and gateway._code_apps == {SHOP} and gateway._filled == {SHOP}
    store.unlink()
    _start_a_session(store, 'session-2')
    r.MaskSet(store=store).register('Setup-Pass-4711', 'dev.example.mail')
    assert gateway.found_in('Old-Value-4711')  # a value is kept: more is hidden, never less
    assert gateway._watches == [] and gateway._code_apps == set() and SHOP not in gateway._filled and SHOP not in gateway._kept
    assert gateway.register('Newer-Value-9', 'dev.example.mail') is True  # and it is written to the new session's file ...
    fresh = r.MaskSet(store=store)
    assert fresh.found_in('Old-Value-4711') and fresh.found_in('Newer-Value-9') and fresh.found_in('Setup-Pass-4711')  # ... digested under its key


def test_fu11_without_a_session_file_or_with_the_same_id_nothing_is_forgotten(r, tmp_path):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)  # no run/session: a phone from before this change, nothing to compare
    gateway.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    assert gateway.watching(SHOP) is True
    _start_a_session(store, 'session-1')  # the first id this process sees: it adopts it
    assert gateway.watching(SHOP) is True
    _start_a_session(store, 'session-1')  # rewritten with the same id: the same session
    assert gateway.watching(SHOP) is True and SHOP in gateway._code_apps
    store.unlink()  # the stop
    _start_a_session(store, 'session-2')  # the next start
    assert gateway.watching(SHOP) is False


def test_fu11_a_masks_file_kept_across_a_quick_boot_start_still_hides_the_code_app_in_the_next_session(r, tmp_path):
    """A start with a snapshot keeps the masks file (§5c item 13) but writes a new session id: a process forgets what it kept in memory and
    re-reads the file, whose code apps stay (more hidden, never less) until a stop deletes it."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    _start_a_session(store, 'session-1')
    gateway = r.MaskSet(store=store)
    gateway.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    _start_a_session(store, 'session-2')  # a quick-boot start: the file stays
    assert gateway.watching(SHOP) is True and '482913' not in r.mask_tree(_code_tree(), gateway, SHOP)[0]
    gateway.clear()  # the next normal stop
    assert gateway.watching(SHOP) is False and not store.exists()


def test_fu11_two_starts_in_one_clock_tick_are_two_sessions(r, tmp_path):
    """The first full-suite run caught this: a stamp of the file's mtime alone can be the same for two starts on a coarse clock, and the second
    session was missed. An atomic replace is a new file, so its identity differs even with the same mtime and size."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    _start_a_session(store, 'session-1')
    gateway = r.MaskSet(store=store)
    gateway.watch_fields(SHOP, [{'bounds': [0, 0, 10, 10]}])
    assert gateway.watching(SHOP) is True
    session = store.parent / 'session'
    tick = session.stat().st_mtime_ns
    store.unlink()
    _start_a_session(store, 'session-2')
    os.utime(session, ns=(tick, tick))  # the same clock tick as the first start
    r.MaskSet(store=store).register('Setup-Pass-4711', 'dev.example.mail')  # the next session's first writer
    assert gateway.watching(SHOP) is False


# ---- PHONEFIX-121 follow-up 12 (REVIEW-FIX-PHONE-121 R7) ----------------------------------------------------------------------------------

R7_CELLS = [_part(2 + i, d, 'android.widget.TextView', bounds=f'[{63 + 150 * i},600][{183 + 150 * i},726]') for i, d in enumerate('482913')]
R7_OTP_PAGE = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                   _part(1, '482913', 'android.widget.EditText', SHOP + ':id/code', '[63,600][1017,726]'), *R7_CELLS)


def test_r7_4_a_code_drawn_one_digit_per_text_view_is_masked_in_a_code_app(r):
    """The reviewer's proof (assumed dump shape: a hidden EditText takes the code and six TextViews draw one digit each). The code app masked
    EditTexts only, and a value is registered only from 3 characters, so no single digit was ever masked."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}])
    masked, boxes, counts = r.mask_tree(R7_OTP_PAGE, masks, SHOP)
    shown = [d for d in '482913' if f'text="{d}"' in masked]
    assert not shown, f'the code is shown one digit per cell: {shown}'
    assert 'Enter the code we sent' in masked  # words are not digits
    assert all((63 + 150 * i, 600, 183 + 150 * i, 726) in boxes for i in range(6))  # and every cell is boxed in the picture


@pytest.mark.parametrize('text,masked', [('4', True), ('482913', True), ('482 913', True), ('48-29-13', True), ('4 8 2 9 1 3 7 0', True), ('12345678', True),
                                         ('٤٨٢٩١٣', True), ('123456789', False), ('12.40', False), ('£12', False), ('Verify 2', False), ('', False), ('-', False),
                                         (' - ', False)])
def test_r7_4_which_text_counts_as_only_digits(r, text, masked):
    """1 to 8 digits, spaces and dashes ignored, in the whole text or the whole content description."""
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [])
    page = _doc(_part(0, 'Menu', 'android.widget.TextView', bounds='[63,300][1017,380]'), _part(1, text, 'android.widget.TextView', bounds='[63,400][1017,500]'),
                _part(2, 'Next', 'android.widget.TextView', bounds='[63,600][1017,700]'))
    out, boxes, _ = r.mask_tree(page, masks, SHOP)
    assert (f'text="{text}"' not in out) is masked if text.strip(' -') else True
    assert ((63, 400, 1017, 500) in boxes) is masked


def test_r7_4_a_digits_only_content_description_is_masked_too(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [])
    cell = ('<node index="1" text="" resource-id="" class="android.view.View" package="%s" content-desc="7" password="false" bounds="[63,400][183,500]" />' % SHOP)
    page = _doc(_part(0, 'Menu', 'android.widget.TextView', bounds='[63,300][1017,380]'), cell, _part(2, 'Next', 'android.widget.TextView', bounds='[63,600][1017,700]'))
    out, boxes, _ = r.mask_tree(page, masks, SHOP)
    assert 'content-desc="7"' not in out and (63, 400, 183, 500) in boxes


def test_r7_4_nothing_changes_outside_a_code_app(r):
    """The page of an app that is not a code app keeps its digits (a count, a price, a page number), and so does another app's tree."""
    masks = r.MaskSet()
    assert 'text="482913"' in r.mask_tree(_doc(_part(0, 'Menu', 'android.widget.TextView', bounds='[63,300][1017,380]'),
                                              _part(1, '482913', 'android.widget.TextView', bounds='[63,400][1017,500]'),
                                              _part(2, 'Next', 'android.widget.TextView', bounds='[63,600][1017,700]')), masks, SHOP)[0]
    masks.watch_fields(SHOP, [])  # now the shop is a code app; another app's digits stay
    other = _doc(_part(0, 'Menu', 'android.widget.TextView', bounds='[63,300][1017,380]').replace(SHOP, 'com.example.other'),
                 _part(1, '482913', 'android.widget.TextView', bounds='[63,400][1017,500]').replace(SHOP, 'com.example.other'),
                 _part(2, 'Next', 'android.widget.TextView', bounds='[63,600][1017,700]').replace(SHOP, 'com.example.other'))
    assert 'text="482913"' in r.mask_tree(other, masks, 'com.example.other')[0]


def test_r7_4_a_tentative_read_masks_the_digits_too(r):
    masks = r.MaskSet()
    masks.watch_fields(SHOP, [])
    assert 'text="4"' not in r.mask_tree(R7_OTP_PAGE, masks, SHOP, commit=False)[0]


def test_r7_4_field_boxes_also_box_the_digit_views_of_a_window_behind_the_active_one(r):
    boxes = r.field_boxes(R7_OTP_PAGE)
    assert all((63 + 150 * i, 600, 183 + 150 * i, 726) in boxes for i in range(6)) and (63, 600, 1017, 726) in boxes
    words = _doc(_part(0, 'Enter the code we sent', 'android.widget.TextView', bounds='[63,400][1017,500]'))
    assert r.field_boxes(words) == []


# R7-1: the masks file's own stamp

R7_MAIL = 'dev.example.mail'
R7_SHOWN_PASSWORD = _doc(_part(0, 'Menu', 'android.widget.TextView', bounds='[63,300][1017,380]').replace(SHOP, R7_MAIL),
                         _part(1, 'Setup-Pass-4711', 'android.widget.TextView', bounds='[63,400][1017,500]').replace(SHOP, R7_MAIL))


def test_r7_1_an_owner_value_the_setup_session_writes_in_the_gateways_clock_tick_is_still_masked(r, tmp_path):
    """The reviewer's proof: `_sync` re-read masks.json only when its mtime differed from the last one it saw; two writes in one file-clock
    tick have the same mtime, and the gateway never read the second (the owner's password then went to the model in a capture)."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    assert gateway.register('Agent-Pass-1', R7_MAIL) is True  # the gateway's own last write: its `_seen`
    tick = gateway._seen
    setup = r.MaskSet(store=store)
    assert setup.register('Setup-Pass-4711', R7_MAIL) is True  # the owner's fill during setup
    os.utime(store, ns=(tick, tick))  # it landed in the same clock tick
    assert r.MaskSet(store=store).found_in('Setup-Pass-4711')  # control: it is in the file
    assert 'Setup-Pass-4711' not in r.mask_tree(R7_SHOWN_PASSWORD, gateway, R7_MAIL)[0], "the owner's value went to the model"


def test_r7_1_measure_the_gateway_never_misses_a_write_in_its_own_tick(r, tmp_path):
    """The reviewer's unforced measurement (back-to-back writes of two processes): 5, 9 and 6 of 40 runs missed the second write on v11."""
    missed = 0
    for i in range(40):
        (tmp_path / str(i) / 'run').mkdir(parents=True)
        store = tmp_path / str(i) / 'run' / 'masks.json'
        gateway = r.MaskSet(store=store)
        gateway.register('Agent-Pass-1', R7_MAIL)
        r.MaskSet(store=store).register('Setup-Pass-4711', R7_MAIL)
        missed += 'Setup-Pass-4711' in r.mask_tree(R7_SHOWN_PASSWORD, gateway, R7_MAIL)[0]
    assert missed == 0, f'the gateway missed the setup write in {missed} of 40 runs'


def test_r7_1_a_write_is_recorded_with_the_identity_of_the_file_it_wrote(r, tmp_path):
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    masks = r.MaskSet(store=store)
    masks.register('Agent-Pass-1', R7_MAIL)
    info = store.stat()
    assert masks._seen == info.st_mtime_ns and masks._seen_id == (info.st_ino, info.st_size)  # its own write is not read again
    assert masks._seen_id is not None


def test_r7_1_another_process_replacing_the_file_right_after_our_replace_is_not_recorded_as_read(r, tmp_path, monkeypatch):
    """`_persist` took its stamp from the store after its own replace, so a replace by another process between the two was recorded as
    read and its content never was. The stamp is now taken from the file written, before the replace."""
    (tmp_path / 'run').mkdir()
    store = tmp_path / 'run' / 'masks.json'
    gateway = r.MaskSet(store=store)
    other = r.MaskSet(store=store)
    real, busy = os.replace, []

    def replace_then_another_process_writes(src, dst):
        real(src, dst)
        if not busy:
            busy.append(1)
            other.register('Other-Value-9', R7_MAIL)  # the other process's replace lands right after ours

    monkeypatch.setattr(r.os, 'replace', replace_then_another_process_writes)
    assert gateway.register('Mine-Value-1', R7_MAIL) is True
    monkeypatch.setattr(r.os, 'replace', real)
    assert gateway.found_in('Other-Value-9') and gateway.found_in('Mine-Value-1')


# R7-3: pinned (doc only, by Root's call): a code Beacon filled can still show in the message it came in

def test_r7_3_a_code_beacon_filled_can_still_show_in_the_message_it_came_in(r, tmp_path):
    """The reviewer's R7-3 case, pinned as it is: the code app masks the code app's text boxes; another app's text is masked only by value,
    and the code is registered by value only when a shown capture of its app finds the helper's field. §5c item 19 says so."""
    (tmp_path / 'run').mkdir()
    masks = r.MaskSet(store=tmp_path / 'run' / 'masks.json')
    assert masks.mark_filled(SHOP) is True and masks.watch_fields(SHOP, [{'view_id': SHOP + ':id/code', 'bounds': [63, 600, 1017, 726]}]) is True
    messages = 'com.google.android.apps.messaging'
    sms = _doc(_part(0, 'Kanto: your sign-in code is 482913', 'android.widget.TextView', bounds='[63,600][1017,726]').replace(SHOP, messages))
    assert '482913' in r.mask_tree(sms, masks, messages)[0]
