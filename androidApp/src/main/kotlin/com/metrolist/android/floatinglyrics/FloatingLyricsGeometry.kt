/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.floatinglyrics

import kotlin.math.roundToInt

internal data class OverlayPoint(
    val x: Int,
    val y: Int,
)

/** Keeps the whole window on screen; a window larger than the screen pins to the top-left. */
internal fun clampOverlayPosition(
    point: OverlayPoint,
    screenWidth: Int,
    screenHeight: Int,
    width: Int,
    height: Int,
): OverlayPoint =
    OverlayPoint(
        x = point.x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)),
        y = point.y.coerceIn(0, (screenHeight - height).coerceAtLeast(0)),
    )

/**
 * One side of the window in pixels: the saved length, or the screen less both margins up to [maxDefault] when
 * unset (negative). Always between [min] (or the space available, when smaller) and the space available.
 */
internal fun floatingLyricsSpan(
    saved: Int,
    screen: Int,
    margin: Int,
    maxDefault: Int,
    min: Int,
): Int {
    val available = (screen - 2 * margin).coerceAtLeast(1)
    val length = if (saved < 0) minOf(available, maxDefault) else saved
    return length.coerceIn(minOf(min, available), available)
}

/**
 * The saved position, or centred horizontally a [DEFAULT_TOP_FRACTION] of the way down the screen when
 * either coordinate is unset (negative). Always clamped to the screen.
 */
internal fun floatingLyricsPosition(
    savedX: Int,
    savedY: Int,
    screenWidth: Int,
    screenHeight: Int,
    width: Int,
    height: Int,
): OverlayPoint {
    val point =
        if (savedX < 0 || savedY < 0) {
            OverlayPoint((screenWidth - width) / 2, (screenHeight * DEFAULT_TOP_FRACTION).roundToInt())
        } else {
            OverlayPoint(savedX, savedY)
        }
    return clampOverlayPosition(point, screenWidth, screenHeight, width, height)
}

private const val DEFAULT_TOP_FRACTION = 0.12f
