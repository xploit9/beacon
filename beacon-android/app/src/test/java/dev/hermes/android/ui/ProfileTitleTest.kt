package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * UX re-check of F9, NEW-P1-2, as corrected by review R29 Should 1. An empty chat titled "default" or "coder" reads like a placeholder, and another
 * profile's chat titled with the assistant's name claims to be the assistant: the Beacon-off page read "Beacon" over "Beacon isn't turned on for this
 * profile.". Hermes' display name is optional and empty unless someone renames the profile (hermes_cli/profiles.py:671, :881). So the title is that
 * name when Hermes gives one other than the id; else, on the assistant's own profile only, the assistant's name; else the id made readable. The rule
 * keys off what each Hermes says and the user's own assistant setting, never off a particular profile.
 */
class ProfileTitleTest {
    private val profiles = listOf(
        json("name" to "default"),
        json("name" to "coder", "display_name" to " "),
        json("name" to "work", "display_name" to "Work"),
        json("name" to "research", "display_name" to "research"),
    )

    @Test fun aDisplayNameOtherThanTheIdIsTheTitle() {
        assertEquals("Work", profileTitle("work", profiles, "Beacon", "default"))
        assertEquals("Work", profileTitle("work", profiles, "Beacon", "work"))
        assertEquals("Personal", profileTitle("default", listOf(json("name" to "default", "display_name" to "Personal")), "Beacon", "default"))
    }

    @Test fun theAssistantsNameTitlesOnlyTheAssistantsOwnProfile() {
        assertEquals("Maya", profileTitle("default", profiles, "Maya", "default"))
        assertEquals("Maya", profileTitle("coder", profiles, "Maya", "coder"))
        assertEquals("Coder", profileTitle("coder", profiles, "Maya", "default"))
        assertEquals("Default", profileTitle("default", profiles, "Beacon", "coder"))
    }

    @Test fun anyOtherProfileIsTitledWithItsIdMadeReadableNeverTheBareId() {
        assertEquals("Research", profileTitle("research", profiles, "Beacon", "default"))
        assertEquals("My work 2", profileTitle("my-work_2", emptyList(), "Beacon", "default"))
        for (id in listOf("default", "coder", "research", "my-work_2")) assertNotEquals(id, id, profileTitle(id, profiles, "Beacon", "another"))
        // No profile known yet: the assistant's name, as before.
        assertEquals("Beacon", profileTitle("", emptyList(), "Beacon", "default"))
    }

    /** With no fallback name the profile is named by itself, never by the assistant's name; nothing is left for a missing id. */
    @Test fun withNoFallbackTheProfileIsNamedByItself() {
        assertEquals("Coder", profileTitle("coder", profiles, "", ""))
        assertEquals("Work", profileTitle("work", profiles, "", ""))
        assertEquals("", profileTitle("", emptyList(), "", ""))
    }
}
