/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import com.metrolist.desktop.utils.LinuxWindowMove
import com.metrolist.shared.utils.OsUtil
import java.awt.Window

// Adapted from Metrolist Desktop's platform/OverlayWindowSupport.kt. The floating lyrics window
// only resizes its width, so it has two edges instead of eight.

/** Window edge grabbed for an interactive width resize. */
internal enum class ResizeEdge {
    LEFT,
    RIGHT,
}

/**
 * Native window behavior the floating lyrics window needs from the desktop: moving and
 * resizing an undecorated window through the window manager, staying out of the taskbar, and
 * letting clicks pass through. Linux implements it with X11 (Compose runs under XWayland);
 * Windows with Win32 extended window styles.
 *
 * Every call returns false when the platform cannot do it, so callers can fall back to moving
 * the window with `setLocation`.
 */
internal interface OverlayWindowSupport {
    /** Loads native libraries off the UI thread, so the first drag does not lag. */
    fun warmUp() {}

    /** Starts a window-manager move, as if the user dragged a title bar. */
    fun requestInteractiveMove(window: Window): Boolean

    /** Starts a window-manager resize from [edge]. */
    fun requestInteractiveResize(
        window: Window,
        edge: ResizeEdge,
    ): Boolean

    /** Makes the whole window ignore pointer input when [enabled], or restores input. */
    fun setClickThrough(
        window: Window,
        enabled: Boolean,
    ): Boolean

    /** False when [setClickThrough] has no effect on this platform. */
    val supportsClickThrough: Boolean

    /**
     * Keeps the shown [window] out of the taskbar, pager, and window switcher. Call it each time
     * the window is shown: window managers drop the state when a window is hidden.
     */
    fun hideFromTaskbar(window: Window): Boolean = false

    companion object {
        val current: OverlayWindowSupport by lazy {
            when {
                OsUtil.isLinux && LinuxWindowMove.isX11Toolkit() -> LinuxOverlayWindowSupport
                OsUtil.isWindows -> WindowsOverlayWindowSupport
                else -> FallbackOverlayWindowSupport
            }
        }
    }
}

/** Platforms without a native implementation: callers move windows with setLocation. */
internal object FallbackOverlayWindowSupport : OverlayWindowSupport {
    override fun requestInteractiveMove(window: Window): Boolean = false

    override fun requestInteractiveResize(
        window: Window,
        edge: ResizeEdge,
    ): Boolean = false

    override fun setClickThrough(
        window: Window,
        enabled: Boolean,
    ): Boolean = false

    override val supportsClickThrough: Boolean = false
}
