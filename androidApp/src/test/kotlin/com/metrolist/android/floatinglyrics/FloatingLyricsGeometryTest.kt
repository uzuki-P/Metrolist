package com.metrolist.android.floatinglyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLyricsGeometryTest {
    @Test
    fun clampKeepsTheWindowOnScreen() {
        assertEquals(OverlayPoint(0, 0), clampOverlayPosition(OverlayPoint(-50, -10), 1080, 2400, 900, 200))
        assertEquals(OverlayPoint(180, 2200), clampOverlayPosition(OverlayPoint(500, 3000), 1080, 2400, 900, 200))
        assertEquals(OverlayPoint(100, 300), clampOverlayPosition(OverlayPoint(100, 300), 1080, 2400, 900, 200))
    }

    @Test
    fun clampPinsAWindowLargerThanTheScreenToTheTopLeft() {
        assertEquals(OverlayPoint(0, 0), clampOverlayPosition(OverlayPoint(40, 40), 300, 200, 400, 300))
    }

    @Test
    fun defaultWidthFitsTheScreenUpToTheMaximum() {
        assertEquals(1048, floatingLyricsSpan(saved = -1, screen = 1080, margin = 16, maxDefault = 1440, min = 480))
        assertEquals(1440, floatingLyricsSpan(saved = -1, screen = 2400, margin = 48, maxDefault = 1440, min = 480))
    }

    @Test
    fun savedWidthIsKeptWithinTheScreen() {
        assertEquals(600, floatingLyricsSpan(saved = 600, screen = 1080, margin = 16, maxDefault = 1440, min = 480))
        assertEquals(1048, floatingLyricsSpan(saved = 5000, screen = 1080, margin = 16, maxDefault = 1440, min = 480))
        assertEquals(480, floatingLyricsSpan(saved = 10, screen = 1080, margin = 16, maxDefault = 1440, min = 480))
    }

    @Test
    fun unsetPositionCentresNearTheTop() {
        assertEquals(OverlayPoint(90, 288), floatingLyricsPosition(-1, -1, 1080, 2400, 900, 200))
        assertEquals(OverlayPoint(90, 288), floatingLyricsPosition(500, -1, 1080, 2400, 900, 200))
    }

    @Test
    fun savedPositionIsClampedAfterTheScreenShrinks() {
        // Saved on a portrait screen, shown after rotating to landscape.
        assertEquals(OverlayPoint(1500, 880), floatingLyricsPosition(1500, 2000, 2400, 1080, 900, 200))
        assertEquals(OverlayPoint(100, 300), floatingLyricsPosition(100, 300, 2400, 1080, 900, 200))
    }
}
