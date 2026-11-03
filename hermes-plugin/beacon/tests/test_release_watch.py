"""Owner, 3 Nov: a published Beacon release reaches the owner's phone as a notice, every time. Hermes (always on, one stable address)
watches GitHub and tells the phone over Keep connected; the phone checks the release itself and posts the update notice."""
import json
import sys

from test_mobile import harness  # noqa: F401  (fixture)


def release(tag='v1.1.28', draft=False, prerelease=False):
    return json.dumps({'tag_name': tag, 'draft': draft, 'prerelease': prerelease, 'published_at': '2026-11-04T09:00:00Z', 'body': 'Notes',
                       'assets': [{'name': 'Beacon-1.1.28.json', 'state': 'uploaded', 'size': 900,
                                   'browser_download_url': 'https://github.com/xploit9/beacon/releases/download/v1.1.28/Beacon-1.1.28.json'}]}).encode()


class GitHub:
    """GitHub's latest-release answers in order; records the ETag each request sent."""
    def __init__(self, *answers):
        self.answers, self.sent = list(answers), []

    def __call__(self, etag):
        self.sent.append(etag)
        return self.answers.pop(0)


def notices(mobile):
    with mobile.connect() as conn:
        return [dict(row) for row in conn.execute("SELECT owner,profile,kind,conversation,push FROM notices WHERE kind='app_update'")]


def test_a_new_release_becomes_one_notice_for_the_owner(harness):
    mobile = sys.modules['beacon_mobile']
    github = GitHub((200, '"one"', release()), (304, None, b''), (200, '"two"', release()))
    assert mobile.check_release('owner', 'default', fetch=github) == '1.1.28'
    assert notices(mobile) == [{'owner': 'owner', 'profile': 'default', 'kind': 'app_update', 'conversation': 'release-1-1-28', 'push': 'none'}]
    # GitHub says nothing changed (the stored ETag), and the same release seen again is never announced twice.
    assert mobile.check_release('owner', 'default', fetch=github) == ''
    assert mobile.check_release('owner', 'default', fetch=github) == ''
    assert github.sent == [None, '"one"', '"one"']
    assert len(notices(mobile)) == 1
    # The phone reads GitHub's own release document from Hermes, exactly as GitHub gave it.
    assert json.loads(mobile.latest_release_document())['tag_name'] == 'v1.1.28'


def test_drafts_prereleases_odd_tags_and_failures_announce_nothing(harness):
    mobile = sys.modules['beacon_mobile']
    for answer in ((200, '"a"', release(draft=True)), (200, '"b"', release(prerelease=True)), (200, '"c"', release(tag='nightly')),
                   (403, None, b''), (500, None, b''), (200, '"d"', b'not json'), (200, '"e"', b'[]')):
        assert mobile.check_release('owner', 'default', fetch=GitHub(answer)) == ''
    assert notices(mobile) == []
    assert mobile.latest_release_document() is None


def test_a_github_that_cannot_be_reached_never_stops_the_watch(harness):
    mobile = sys.modules['beacon_mobile']

    def offline(etag):
        raise OSError('offline')
    assert mobile.check_release('owner', 'default', fetch=offline) == ''


def test_the_phone_reads_the_relayed_release_only_with_the_owners_sign_in(harness):
    mobile = sys.modules['beacon_mobile']
    _, client, _, _, _ = harness
    assert client.get('/api/plugins/beacon/mobile/release').status_code == 404
    mobile.check_release('owner', 'default', fetch=GitHub((200, '"one"', release())))
    response = client.get('/api/plugins/beacon/mobile/release')
    assert response.status_code == 200 and response.json()['tag_name'] == 'v1.1.28'
