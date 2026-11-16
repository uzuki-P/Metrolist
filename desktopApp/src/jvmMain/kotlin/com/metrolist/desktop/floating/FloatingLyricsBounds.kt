/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

internal const val FLOATING_LYRICS_DEFAULT_WIDTH = 560
internal const val FLOATING_LYRICS_MIN_WIDTH = 240
internal const val FLOATING_LYRICS_DEFAULT_HEIGHT = 170
internal const val FLOATING_LYRICS_MIN_HEIGHT = 96
internal const val FLOATING_LYRICS_BOTTOM_MARGIN = 96

/** Saved coordinates use -1 until the user moves or resizes the window. */
internal const val FLOATING_LYRICS_UNSET = -1

/**
 * Where the floating lyrics window opens, in AWT screen coordinates. A saved position is kept
 * when it still overlaps one of [screens], moved inside that screen if it hangs off an edge.
 * With no saved position, or one on a screen that is gone, the window goes to the bottom center
 * of [primary] (the first screen when null). An unset height opens at the default height.
 */
internal fun resolveFloatingLyricsBounds(
    savedX: Int,
    savedY: Int,
    savedWidth: Int,
    savedHeight: Int,
    screens: List<Rectangle>,
    primary: Rectangle? = null,
): Rectangle {
    val height = (if (savedHeight > 0) savedHeight else FLOATING_LYRICS_DEFAULT_HEIGHT).coerceAtLeast(FLOATING_LYRICS_MIN_HEIGHT)
    val fallbackScreen = primary ?: screens.firstOrNull() ?: Rectangle(0, 0, FLOATING_LYRICS_DEFAULT_WIDTH, height)
    val requestedWidth = if (savedWidth > 0) savedWidth else FLOATING_LYRICS_DEFAULT_WIDTH
    val hasPosition = savedX != FLOATING_LYRICS_UNSET || savedY != FLOATING_LYRICS_UNSET
    val requested = Rectangle(savedX, savedY, requestedWidth, height)
    val screen =
        screens
            .takeIf { hasPosition }
            ?.map { it to it.intersection(requested) }
            ?.filter { (_, overlap) -> !overlap.isEmpty }
            ?.maxByOrNull { (_, overlap) -> overlap.width.toLong() * overlap.height }
            ?.first
    val target = screen ?: fallbackScreen
    val width = fitWidth(requestedWidth, target.width)
    val boundedHeight = height.coerceAtMost(target.height)
    if (screen == null) {
        return Rectangle(
            target.x + (target.width - width) / 2,
            (target.y + target.height - boundedHeight - FLOATING_LYRICS_BOTTOM_MARGIN).coerceAtLeast(target.y),
            width,
            boundedHeight,
        )
    }
    return Rectangle(
        savedX.coerceIn(target.x, target.x + target.width - width),
        savedY.coerceIn(target.y, target.y + target.height - boundedHeight),
        width,
        boundedHeight,
    )
}

private fun fitWidth(
    width: Int,
    screenWidth: Int,
): Int = width.coerceAtLeast(FLOATING_LYRICS_MIN_WIDTH).coerceAtMost(screenWidth.coerceAtLeast(1))

/** The usable area (without panels) of every screen, primary first; empty when headless. */
internal fun floatingLyricsScreens(): List<Rectangle> =
    runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val toolkit = Toolkit.getDefaultToolkit()
        val primary = environment.defaultScreenDevice
        (listOf(primary) + environment.screenDevices.filter { it != primary }).map { device ->
            val configuration = device.defaultConfiguration
            val bounds = configuration.bounds
            val insets = runCatching { toolkit.getScreenInsets(configuration) }.getOrNull()
            if (insets == null) {
                bounds
            } else {
                Rectangle(
                    bounds.x + insets.left,
                    bounds.y + insets.top,
                    bounds.width - insets.left - insets.right,
                    bounds.height - insets.top - insets.bottom,
                )
            }
        }
    }.getOrDefault(emptyList())
