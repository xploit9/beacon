"""REVIEW-121B FU-P2-1: the relay's confirmations run on workers of their own, one per canonical socket, so up to MAX_SOCKETS of them can
write the mobile store at once. Real native creations (test_native_owner_compat's harness, synthetic owner, private Hermes home), confirmed
together through the plugin's own confirmation pool: none is lost to a locked database, and every chat becomes the owner's."""
import asyncio
import sys

from test_mobile import harness  # noqa: F401  (fixture used by bound)
from test_native_owner_compat import bound, native_creation  # noqa: F401


def test_concurrent_relay_confirmations_all_settle_their_chats(bound):
    b = bound
    transport = sys.modules['beacon_canonical_transport']
    created = [native_creation(b, request=f'concurrent-confirm-{n}') for n in range(transport.MAX_SOCKETS)]
    for _, _, pending, response in created:  # nothing is the owner's before its confirmation
        assert not b.native.owns_row('owner', 'basic', 'default', response['session_id'], b.db.get_session(response['session_id']), b.login)
    confirmations = transport.BoundedWorkers(transport.MAX_SOCKETS)

    async def confirm_all():
        return await asyncio.gather(*(transport.bounded_call(confirmations, b.native.confirm_created, b.api.mobile, pending, response)
                                      for _, _, pending, response in created))

    try:
        results = asyncio.run(confirm_all())
    finally:
        confirmations.close()
    assert results == [True] * transport.MAX_SOCKETS
    for _, _, _, response in created:
        stored = response['session_id']
        assert b.native.owns_row('owner', 'basic', 'default', stored, b.db.get_session(stored), b.login)
        assert b.native.verify('owner', 'basic', 'default', stored, principal=b.login)['id'] == stored
