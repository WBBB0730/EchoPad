package com.wbbb.echopad.mobile.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class ChangelogTest {
    private fun notes(vararg versions: String) =
        json.encodeToString(versions.map { ChangelogEntry(it, body = "- $it") })

    private fun versions(entries: List<ChangelogEntry>) = entries.map { it.version }

    @Test
    fun picksEntriesAfterTheCurrentVersionUpToTheTargetNewestFirst() {
        val notes = notes("1.3.0", "1.0.0", "1.2.0", "1.1.0", "0.9.0")
        assertEquals(listOf("1.2.0", "1.1.0"), versions(pickChangelog(notes, "1.0.0", "1.2.0")))
    }

    @Test
    fun versionsWithoutEntriesAreSkipped() {
        val notes = notes("1.3.0", "1.1.0")
        assertEquals(listOf("1.3.0", "1.1.0"), versions(pickChangelog(notes, "1.0.0", "1.3.0")))
        assertTrue(pickChangelog(notes, "1.1.0", "1.2.0").isEmpty())
    }

    @Test
    fun malformedNotesOrVersionsGiveNoEntries() {
        assertTrue(pickChangelog("", "1.0.0", "1.1.0").isEmpty())
        assertTrue(pickChangelog("- 不是 JSON", "1.0.0", "1.1.0").isEmpty())
        assertTrue(pickChangelog(notes("1.1.0"), "not a version", "1.1.0").isEmpty())
    }
}
