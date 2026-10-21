package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationChannelsTest {
    @Test fun onlyIdsOutsideTheCurrentSetAreDeleted() {
        assertEquals(
            setOf("stale-one", "stale-two"),
            notificationChannelIdsToDelete(
                existingIds = setOf("hermes-messages", "beacon_keep_connected", "stale-one", "stale-two"),
                currentIds = setOf("hermes-messages", "beacon_keep_connected"),
            ),
        )
    }

    @Test fun noCurrentIdsAreDeleted() {
        assertEquals(
            emptySet<String>(),
            notificationChannelIdsToDelete(
                existingIds = setOf("xara-calls", "hermes-updates"),
                currentIds = setOf("xara-calls", "hermes-updates"),
            ),
        )
    }
}
