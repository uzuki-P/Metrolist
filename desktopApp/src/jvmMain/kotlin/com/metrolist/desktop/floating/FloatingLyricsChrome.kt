/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
// ui/WindowChrome.kt (912333d).

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
 * Invisible edge and corner targets with resize cursors. A drag hands off to the WM's interactive
 * resize; when the WM refuses, the target tracks the drag with setBounds, clamped to the window's
 * AWT minimum size. Like the move, it follows the pointer's screen position.
 */
@Composable
internal fun BoxScope.WindowResizeZones(
    window: Window,
    support: OverlayWindowSupport,
) {
    val corner = Modifier.size(CORNER_SIZE)
    ResizeHandle(window, support, ResizeEdge.TOP_LEFT, corner.align(Alignment.TopStart))
    ResizeHandle(window, support, ResizeEdge.TOP_RIGHT, corner.align(Alignment.TopEnd))
    ResizeHandle(window, support, ResizeEdge.BOTTOM_LEFT, corner.align(Alignment.BottomStart))
    ResizeHandle(window, support, ResizeEdge.BOTTOM_RIGHT, corner.align(Alignment.BottomEnd))
    val horizontal = Modifier.fillMaxWidth(EDGE_FRACTION).height(EDGE_THICKNESS)
    ResizeHandle(window, support, ResizeEdge.TOP, horizontal.align(Alignment.TopCenter))
    ResizeHandle(window, support, ResizeEdge.BOTTOM, horizontal.align(Alignment.BottomCenter))
    val vertical = Modifier.width(EDGE_THICKNESS).fillMaxHeight(EDGE_FRACTION)
    ResizeHandle(window, support, ResizeEdge.LEFT, vertical.align(Alignment.CenterStart))
    ResizeHandle(window, support, ResizeEdge.RIGHT, vertical.align(Alignment.CenterEnd))
}

@Composable
private fun ResizeHandle(
    window: Window,
    support: OverlayWindowSupport,
    edge: ResizeEdge,
    modifier: Modifier,
) {
    val cursor =
        when (edge) {
            ResizeEdge.TOP_LEFT, ResizeEdge.BOTTOM_RIGHT -> Cursor.NW_RESIZE_CURSOR
            ResizeEdge.TOP_RIGHT, ResizeEdge.BOTTOM_LEFT -> Cursor.NE_RESIZE_CURSOR
            ResizeEdge.TOP, ResizeEdge.BOTTOM -> Cursor.N_RESIZE_CURSOR
            ResizeEdge.LEFT, ResizeEdge.RIGHT -> Cursor.E_RESIZE_CURSOR
        }
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
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
                        val dy = now.y - from.y
                        val width =
                            (base.width + (if (edge.isRight) dx else 0) - (if (edge.isLeft) dx else 0))
                                .coerceAtLeast(window.minimumSize.width)
                        val height =
                            (base.height + (if (edge.isBottom) dy else 0) - (if (edge.isTop) dy else 0))
                                .coerceAtLeast(window.minimumSize.height)
                        val x = if (edge.isLeft) base.x + base.width - width else base.x
                        val y = if (edge.isTop) base.y + base.height - height else base.y
                        window.setBounds(x, y, width, height)
                    },
                    onDragEnd = { manualBase = null },
                    onDragCancel = { manualBase = null },
                )
            },
    )
}

private val CORNER_SIZE = 16.dp
private val EDGE_THICKNESS = 8.dp
private const val EDGE_FRACTION = 0.8f
