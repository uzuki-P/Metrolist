/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window

// Adapted from Metrolist Desktop's floating/WindowChrome.kt, a port of lyrics-float
// ui/WindowChrome.kt (912333d). Resizing keeps only the left and right edges because the
// window's height follows its content.

/**
 * Move gesture for an undecorated window. setLocation dragging jitters under XWayland; the
 * WM's own move (_NET_WM_MOVERESIZE) tracks the pointer per-frame, so prefer it there and only
 * fall back to accumulated setLocation deltas when the WM refuses.
 *
 * The fallback follows the pointer's screen position rather than summing Compose drag deltas:
 * those are relative to the window that is moving, so events queued before a setLocation
 * overshoot and the window jitters. They are also in pixels while AWT on Windows positions in
 * scaled units.
 *
 * The WM's interactive move consumes the pointer release, so drag-end fires as a cancel (or not
 * at all) for native moves; the window persists its geometry from AWT component events.
 */
internal fun Modifier.windowDragHandle(
    window: Window,
    support: OverlayWindowSupport,
): Modifier =
    pointerInput(window, support) {
        var base: Point? = null
        var pointerStart: Point? = null
        detectDragGestures(
            onDragStart = {
                val native = support.requestInteractiveMove(window)
                pointerStart = if (native) null else pointerOnScreen()
                base = pointerStart?.let { Point(window.x, window.y) }
            },
            onDrag = { change, _ ->
                change.consume()
                val start = base ?: return@detectDragGestures
                val from = pointerStart ?: return@detectDragGestures
                val now = pointerOnScreen() ?: return@detectDragGestures
                window.setLocation(start.x + now.x - from.x, start.y + now.y - from.y)
            },
            onDragEnd = { base = null },
            onDragCancel = { base = null },
        )
    }

/** Pointer position in AWT screen coordinates, the same units as `setLocation`. */
private fun pointerOnScreen(): Point? = MouseInfo.getPointerInfo()?.location

/**
 * Invisible left and right edge targets with resize cursors. A drag hands off to the WM's
 * interactive resize; when the WM refuses, the edge tracks the drag with setBounds, clamped to
 * the window's AWT minimum width. Like the move, it follows the pointer's screen position.
 */
@Composable
internal fun BoxScope.WindowWidthResizeEdges(
    window: Window,
    support: OverlayWindowSupport,
) {
    ResizeEdgeHandle(window, support, ResizeEdge.LEFT, Modifier.align(Alignment.CenterStart))
    ResizeEdgeHandle(window, support, ResizeEdge.RIGHT, Modifier.align(Alignment.CenterEnd))
}

@Composable
private fun ResizeEdgeHandle(
    window: Window,
    support: OverlayWindowSupport,
    edge: ResizeEdge,
    modifier: Modifier,
) {
    Box(
        modifier
            .width(10.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(window, edge, support) {
                var manualBase: Rectangle? = null
                var pointerStart: Point? = null
                detectDragGestures(
                    onDragStart = {
                        val native = support.requestInteractiveResize(window, edge)
                        pointerStart = if (native) null else pointerOnScreen()
                        manualBase = pointerStart?.let { window.bounds }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val base = manualBase ?: return@detectDragGestures
                        val from = pointerStart ?: return@detectDragGestures
                        val now = pointerOnScreen() ?: return@detectDragGestures
                        val dx = now.x - from.x
                        val width =
                            (if (edge == ResizeEdge.RIGHT) base.width + dx else base.width - dx)
                                .coerceAtLeast(window.minimumSize.width)
                        val x = if (edge == ResizeEdge.LEFT) base.x + base.width - width else base.x
                        window.setBounds(x, base.y, width, window.height)
                    },
                    onDragEnd = { manualBase = null },
                    onDragCancel = { manualBase = null },
                )
            },
    )
}
