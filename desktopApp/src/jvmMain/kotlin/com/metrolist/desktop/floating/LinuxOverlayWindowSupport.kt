/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import com.metrolist.desktop.utils.LinuxWindowMove
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import java.awt.Component
import java.awt.Window

// Adapted from Metrolist Desktop's platform/linux/LinuxOverlayWindowSupport.kt,
// LinuxClickThrough.kt and LinuxWindowMover.kt (requestSkipTaskbar), which port lyrics-float
// (912333d). Move and resize go through v14's LinuxWindowMove instead of their own mover.

/**
 * X11 implementation for Compose under XWayland (and plain X11 sessions): EWMH
 * `_NET_WM_MOVERESIZE` for move and resize, `_NET_WM_STATE` skip flags to stay out of the
 * taskbar, and an empty X Shape input region for click pass-through.
 */
internal object LinuxOverlayWindowSupport : OverlayWindowSupport {
    override fun warmUp() {
        LinuxOverlayX11.warmUp()
    }

    // Under XWayland a per-event `setLocation` loop jitters badly; the WM's own move/resize
    // tracks the pointer per frame, exactly like dragging a real title bar or edge.
    override fun requestInteractiveMove(window: Window): Boolean = runCatching { LinuxWindowMove.start(window) }.getOrDefault(false)

    override fun requestInteractiveResize(
        window: Window,
        edge: ResizeEdge,
    ): Boolean = runCatching { LinuxWindowMove.start(window, edge.ewmhDirection) }.getOrDefault(false)

    override fun setClickThrough(
        window: Window,
        enabled: Boolean,
    ): Boolean = LinuxOverlayX11.setInputShapeEmpty(window, enabled)

    override val supportsClickThrough: Boolean = true

    override fun hideFromTaskbar(window: Window): Boolean = LinuxOverlayX11.requestSkipTaskbar(window)
}

// The Shape extension lives in libXext; jna-platform's X11 binding does not carry XShape*.
// `mask` is declared Pointer so null maps to None, which JNA would refuse for an IntegerType
// parameter.
@Suppress("FunctionName")
private interface X11ShapeExt : X11 {
    fun XShapeCombineMask(
        display: X11.Display,
        window: X11.Window,
        destKind: Int,
        xOff: Int,
        yOff: Int,
        mask: Pointer?,
        op: Int,
    ): Int

    fun XShapeCombineRectangles(
        display: X11.Display,
        window: X11.Window,
        destKind: Int,
        xOff: Int,
        yOff: Int,
        rectangles: X11.XRectangle?,
        nRects: Int,
        op: Int,
        ordering: Int,
    ): Int
}

// Shape extension constants: ShapeInput kind, ShapeSet op, YXBanded ordering.
private const val SHAPE_INPUT = 2
private const val SHAPE_SET = 0
private const val YX_BANDED = 3

// SubstructureRedirectMask | SubstructureNotifyMask: the root window must forward client
// messages to the window manager.
private const val ROOT_MESSAGE_MASK = (1L shl 20) or (1L shl 21)

private object LinuxOverlayX11 {
    private val lock = Any()
    private var x11: X11? = null
    private var xext: X11ShapeExt? = null
    private var display: X11.Display? = null

    // Loading JNA's native side is slow; doing it on the UI thread during the first drag or
    // toggle leaves the window stuttering. Call this from a background thread.
    fun warmUp() {
        runCatching { connection() }
    }

    /**
     * Click pass-through via the X Shape extension: an empty input region makes XWayland
     * deliver clicks on the window to the windows below (KWin forwards them to the Wayland
     * surface underneath). With [passThrough] off, the input shape is removed (mask None +
     * ShapeSet restores the default region). Returns false when X11 is unavailable.
     */
    fun setInputShapeEmpty(
        window: Window,
        passThrough: Boolean,
    ): Boolean =
        try {
            val (_, conn) = connection() ?: return false
            val link = xext ?: return false
            // JAWT reports Compose's content window, while the window manager maps the peer's
            // shell around it. The shell's input region bounds its children, so shape both.
            for (id in windowIds(window)) {
                val target = X11.Window(id)
                if (passThrough) {
                    link.XShapeCombineRectangles(conn, target, SHAPE_INPUT, 0, 0, null, 0, SHAPE_SET, YX_BANDED)
                } else {
                    link.XShapeCombineMask(conn, target, SHAPE_INPUT, 0, 0, null, SHAPE_SET)
                }
            }
            link.XFlush(conn)
            true
        } catch (_: Throwable) {
            false
        }

    // Adds the EWMH skip-taskbar and skip-pager states, plus KWin's skip-switcher, to a mapped
    // window. Setting `Window.type = UTILITY` alone leaves KDE's taskbar listing it. The WM
    // drops these states when the window is unmapped, so callers send this again each time the
    // window is shown.
    fun requestSkipTaskbar(window: Window): Boolean =
        try {
            val (link, conn) = connection() ?: return false
            val target = X11.Window(windowIds(window).first())
            val root = link.XDefaultRootWindow(conn)
            val state = link.XInternAtom(conn, "_NET_WM_STATE", false) ?: return false
            val skipTaskbar = link.XInternAtom(conn, "_NET_WM_STATE_SKIP_TASKBAR", false) ?: return false
            val skipPager = link.XInternAtom(conn, "_NET_WM_STATE_SKIP_PAGER", false) ?: return false
            val skipSwitcher = link.XInternAtom(conn, "_KDE_NET_WM_STATE_SKIP_SWITCHER", false)
            sendState(link, conn, root, target, state, skipTaskbar, skipPager)
            if (skipSwitcher != null) sendState(link, conn, root, target, state, skipSwitcher, null)
            link.XFlush(conn)
            true
        } catch (_: Throwable) {
            false
        }

    // _NET_WM_STATE client message: data.l = [action (1 = add), first, second, source (1 =
    // application), 0].
    private fun sendState(
        link: X11,
        conn: X11.Display,
        root: X11.Window,
        target: X11.Window,
        state: X11.Atom,
        first: X11.Atom,
        second: X11.Atom?,
    ) {
        val event = X11.XEvent()
        val client = X11.XClientMessageEvent()
        client.type = X11.ClientMessage
        client.window = target
        client.display = conn
        client.message_type = state
        client.format = 32
        client.data.setType(Array<NativeLong>::class.java)
        client.data.l =
            arrayOf(
                NativeLong(1),
                NativeLong(first.toLong()),
                NativeLong(second?.toLong() ?: 0L),
                NativeLong(1),
                NativeLong(0),
            )
        event.xclient = client
        event.setType(X11.XClientMessageEvent::class.java)
        link.XSendEvent(conn, root, 0, NativeLong(ROOT_MESSAGE_MASK), event)
    }

    /** The peer's shell window first (the one the window manager manages), then JAWT's window. */
    private fun windowIds(window: Window): List<Long> = listOfNotNull(peerShellWindow(window), Native.getWindowID(window)).distinct()

    private fun peerShellWindow(window: Window): Long? =
        runCatching {
            val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
            val peer =
                Class
                    .forName("sun.awt.AWTAccessor\$ComponentAccessor")
                    .getMethod("getPeer", Component::class.java)
                    .invoke(accessor, window) ?: return null
            Class.forName("sun.awt.X11.XBaseWindow").getMethod("getWindow").invoke(peer) as Long
        }.getOrNull()?.takeIf { it != 0L }

    // Opens one display connection and keeps it: JNA caches the libraries, and the X server
    // cleans up the socket when the process exits.
    private fun connection(): Pair<X11, X11.Display>? {
        synchronized(lock) {
            val link = x11 ?: X11.INSTANCE.also { x11 = it }
            if (xext == null) xext = Native.load("Xext", X11ShapeExt::class.java)
            val conn = display ?: link.XOpenDisplay(null)?.also { display = it } ?: return null
            return link to conn
        }
    }
}
