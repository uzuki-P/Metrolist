package com.metrolist.desktop.floating

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class FloatingLyricsBoundsTest {
    private val primary = Rectangle(0, 0, 1920, 1040)
    private val right = Rectangle(1920, 0, 2560, 1440)

    @Test
    fun unsetPositionOpensAtBottomCenterOfPrimaryScreen() {
        val bounds = resolveFloatingLyricsBounds(-1, -1, -1, 120, listOf(primary, right))

        assertEquals(Rectangle(680, 1040 - 120 - FLOATING_LYRICS_BOTTOM_MARGIN, 560, 120), bounds)
    }

    @Test
    fun savedPositionInsideAScreenIsKept() {
        val bounds = resolveFloatingLyricsBounds(2200, 300, 700, 120, listOf(primary, right))

        assertEquals(Rectangle(2200, 300, 700, 120), bounds)
    }

    @Test
    fun windowHangingOffAnEdgeMovesBackInside() {
        val bounds = resolveFloatingLyricsBounds(1700, 1000, 560, 120, listOf(primary))

        assertEquals(Rectangle(1920 - 560, 1040 - 120, 560, 120), bounds)
    }

    @Test
    fun windowAcrossTwoScreensStaysOnTheOneHoldingMostOfIt() {
        val bounds = resolveFloatingLyricsBounds(1800, 200, 560, 120, listOf(primary, right))

        assertEquals(Rectangle(1920, 200, 560, 120), bounds)
    }

    @Test
    fun positionOnAnUnpluggedScreenFallsBackToTheDefault() {
        val bounds = resolveFloatingLyricsBounds(3000, 400, 600, 120, listOf(primary))

        assertEquals(Rectangle(660, 1040 - 120 - FLOATING_LYRICS_BOTTOM_MARGIN, 600, 120), bounds)
    }

    @Test
    fun widthIsKeptBetweenTheMinimumAndTheScreenWidth() {
        val narrow = resolveFloatingLyricsBounds(10, 10, 50, 120, listOf(primary))
        val wide = resolveFloatingLyricsBounds(10, 10, 5000, 120, listOf(primary))

        assertEquals(FLOATING_LYRICS_MIN_WIDTH, narrow.width)
        assertEquals(Rectangle(0, 10, 1920, 120), wide)
    }

    @Test
    fun screenOffsetsAreRespected() {
        val leftOfPrimary = Rectangle(-1280, 0, 1280, 1024)
        val bounds = resolveFloatingLyricsBounds(-1500, 50, 560, 120, listOf(primary, leftOfPrimary))

        assertEquals(Rectangle(-1280, 50, 560, 120), bounds)
    }
}
