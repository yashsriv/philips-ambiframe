package com.ambiframe.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ScreensaverStatusTest {
    private val ours = "com.ambiframe.tv/com.ambiframe.tv.dream.ArtDreamService"

    @Test
    fun `short and full component names both count as ours`() {
        assertEquals(ScreensaverStatus.Active, screensaverStatus("com.ambiframe.tv/.dream.ArtDreamService", "1", ours))
        assertEquals(ScreensaverStatus.Active, screensaverStatus(ours, null, ours))
    }

    @Test
    fun `another screensaver, the off switch and unreadable settings`() {
        val backdrop = "com.google.android.backdrop/com.google.android.backdrop.Backdrop"
        assertEquals(ScreensaverStatus.OtherScreensaver, screensaverStatus(backdrop, "1", ours))
        assertEquals(ScreensaverStatus.OtherScreensaver, screensaverStatus("", "1", ours))
        assertEquals(ScreensaverStatus.Disabled, screensaverStatus(ours, "0", ours))
        assertEquals(ScreensaverStatus.Unknown, screensaverStatus(null, null, ours))
    }

    @Test
    fun `only the first of several screensavers counts`() {
        assertEquals(
            ScreensaverStatus.OtherScreensaver,
            screensaverStatus("com.other/.Dream,com.ambiframe.tv/.dream.ArtDreamService", "1", ours),
        )
    }

    @Test
    fun `intervals cycle round and unknown values reset to one minute`() {
        assertEquals(30.seconds, nextInterval(15.seconds))
        assertEquals(15.seconds, nextInterval(30.minutes))
        assertEquals(1.minutes, nextInterval(42.seconds))
        assertEquals(listOf("15 seconds", "1 minute", "10 minutes"),
            listOf(intervalLabel(15.seconds), intervalLabel(1.minutes), intervalLabel(10.minutes)))
    }
}
