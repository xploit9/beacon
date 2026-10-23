package dev.hermes.android.updates

import org.junit.Assert.*
import org.junit.Test

class AuditR8aUpdateTest {
    @Test fun ignoredPendingVersionIsReusedUntilANewerReleaseArrives() {
        assertTrue(retainPendingUpdate(true, 42, 42))
        assertTrue(retainPendingUpdate(true, 43, 42))
        assertFalse(retainPendingUpdate(true, 42, 43))
        assertFalse(retainPendingUpdate(false, 43, 42))
    }
}
