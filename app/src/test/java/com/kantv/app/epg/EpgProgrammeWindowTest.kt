package com.kantv.app.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgProgrammeWindowTest {
    @Test fun endedInsertDoesNotHideTheStillRunningProgramme() {
        val schedule = schedule(
            programme("长节目", 0, 10_000),
            programme("插播", 4_000, 5_000),
            programme("下一档", 12_000, 15_000),
        )

        val duringInsert = schedule.window(4_500)
        assertEquals("插播", duringInsert.current?.title)
        assertEquals("长节目", duringInsert.previous?.title)
        assertEquals("下一档", duringInsert.next?.title)

        val atInsertEnd = schedule.window(5_000)
        assertEquals("长节目", atInsertEnd.current?.title)

        val afterInsert = schedule.window(6_000)
        assertEquals("长节目", afterInsert.current?.title)
        assertNull(afterInsert.previous)
    }

    @Test fun sameStartOverlapUsesTheLaterEntryStillOnAir() {
        val schedule = schedule(
            programme("长节目", 0, 10_000),
            programme("先插", 4_000, 8_000),
            programme("后插", 4_000, 6_000),
        )

        assertEquals("后插", schedule.window(4_500).current?.title)
        assertEquals("先插", schedule.window(7_000).current?.title)
        assertEquals("长节目", schedule.window(9_000).current?.title)
    }

    @Test fun gapBeforeTheNextProgrammeStaysUpcoming() {
        val schedule = schedule(
            programme("已结束", 0, 10_000),
            programme("下一档", 12_000, 15_000),
        )

        val gap = schedule.window(11_000)
        assertNull(gap.current)
        assertEquals("已结束", gap.previous?.title)
        assertEquals("下一档", gap.next?.title)
    }

    private fun schedule(vararg programmes: EpgProgramme) = EpgChannelSchedule(
        channel = EpgChannelRef("c", null, "测试"),
        feedChannelId = "feed",
        programmes = programmes.toList(),
    )

    private fun programme(title: String, startAtMs: Long, endAtMs: Long) = EpgProgramme(
        id = title,
        startAtMs = startAtMs,
        endAtMs = endAtMs,
        title = title,
    )
}
