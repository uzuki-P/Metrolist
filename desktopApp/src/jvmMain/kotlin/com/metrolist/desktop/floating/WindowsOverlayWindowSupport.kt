/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import com.metrolist.desktop.utils.AppLogger
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JComponent
import javax.swing.Timer

// Adapted from Metrolist Desktop's platform/windows/WindowsOverlayWindowSupport.kt.

/**
 * Win32 implementation for the floating lyrics overlay: `WS_EX_LAYERED` with
 * `WS_EX_TRANSPARENT` for click pass-through and `WS_EX_TOOLWINDOW` to stay out of the
 * taskbar and Alt+Tab.
 *
 * Move and resize return false: Windows applies `setLocation` and `setBounds` right away,
 * so the drag fallback in `FloatingLyricsChrome` follows the pointer without the XWayland jitter
 * that the native move works around on Linux.
 */
internal object WindowsOverlayWindowSupport : OverlayWindowSupport {
    /** Windows this class made layered, so turning click-through off can undo only its own change. */
    private val addedLayered: MutableSet<Window> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** The latest click-through request per window, so a pending retry never undoes a newer one. */
    private val requested: MutableMap<Window, Boolean> = Collections.synchronizedMap(WeakHashMap())

    override fun warmUp() {
        runCatching { User32.INSTANCE }
    }

    override fun requestInteractiveMove(window: Window): Boolean = false

    override fun requestInteractiveResize(
        window: Window,
        edge: ResizeEdge,
    ): Boolean = false

    override fun setClickThrough(
        window: Window,
        enabled: Boolean,
    ): Boolean {
        if (hwndOf(window) == null) return false
        requested[window] = enabled
        // Restore the fill before input comes back, so the empty parts of the pill hover again.
        setTransparencyFill(window, !enabled)
        applyClickThrough(window, enabled, LAYERED_RETRIES)
        return true
    }

    /** Compose panels whose transparency fill this class turned off, with their original background. */
    private val fillRemoved: MutableMap<JComponent, Color?> = Collections.synchronizedMap(WeakHashMap())

    /**
     * On Windows, Compose's `JLayeredPaneWithTransparencyHack` (the superclass of
     * `ComposeWindowPanel`) fills every transparent window with `Color(0, 0, 0, 1)`, because
     * Windows sends no mouse events to fully transparent pixels of a layered window. The fill
     * darkens the desktop behind the pill by one level, which shows as a faint box at 0%
     * background. While click-through is on the window takes no input anyway, so drop the
     * fill. The hack paints it only while the panel is not opaque. An opaque panel with a
     * fully transparent background paints nothing over the desktop instead.
     */
    internal fun setTransparencyFill(
        window: Window,
        enabled: Boolean,
    ) {
        runCatching {
            val panel = transparencyHackPanel(window) ?: return
            if (enabled) {
                if (!fillRemoved.containsKey(panel)) return
                val background = fillRemoved.remove(panel)
                panel.isOpaque = false
                panel.background = background
            } else {
                // An opaque window covers the fill with its own background.
                if (window.isOpaque || fillRemoved.containsKey(panel)) return
                fillRemoved[panel] = panel.background
                panel.background = Color(0, 0, 0, 0)
                panel.isOpaque = true
            }
            panel.repaint()
        }.onFailure { AppLogger.warning(TAG, "Changing the transparency fill failed", it) }
    }

    /** The window's Compose panel. The hack class is a Compose internal, so match it by name. */
    private fun transparencyHackPanel(window: Window): JComponent? {
        fun isHack(component: Component): Boolean =
            generateSequence<Class<*>>(component.javaClass) { it.superclass }.any { it.name == TRANSPARENCY_HACK_CLASS }

        fun find(component: Component): JComponent? {
            if (component is JComponent && isHack(component)) return component
            if (component !is Container) return null
            return component.components.firstNotNullOfOrNull(::find)
        }
        return find(window)
    }

    // Ported from lyrics-float platform/WindowsPlatform.kt (912333d).
    private fun applyClickThrough(
        window: Window,
        enabled: Boolean,
        retriesLeft: Int,
    ) {
        // A newer request (or a retry of an older one) won the race.
        if (requested[window] != enabled) return
        val hwnd = hwndOf(window) ?: return
        runCatching {
            val user32 = User32.INSTANCE
            val style = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
            val next =
                if (enabled) {
                    val value = style or WS_EX_TRANSPARENT
                    if (style and WinUser.WS_EX_LAYERED == 0) {
                        if (retriesLeft > 0) {
                            // Forcing the layered style now with constant alpha
                            // would override AWT's per-pixel alpha and paint the
                            // transparent background black. Retry until AWT adds it.
                            Timer(LAYERED_RETRY_MS) { applyClickThrough(window, enabled, retriesLeft - 1) }
                                .apply { isRepeats = false }
                                .start()
                            return
                        }
                        // Opaque windows never become layered on their own. A
                        // fresh layered window stays invisible until it gets
                        // attributes, so give it full opacity.
                        addedLayered += window
                        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, value or WinUser.WS_EX_LAYERED)
                        user32.SetLayeredWindowAttributes(hwnd, 0, 255.toByte(), WinUser.LWA_ALPHA)
                        return
                    }
                    value
                } else {
                    var cleared = style and WS_EX_TRANSPARENT.inv()
                    if (addedLayered.remove(window)) cleared = cleared and WinUser.WS_EX_LAYERED.inv()
                    cleared
                }
            if (next != style) user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, next)
        }.onFailure { AppLogger.warning(TAG, "Click-through failed", it) }
    }

    override val supportsClickThrough: Boolean = true

    override fun hideFromTaskbar(window: Window): Boolean {
        val hwnd = hwndOf(window) ?: return false
        return runCatching {
            val user32 = User32.INSTANCE
            val style = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
            val next = (style or WS_EX_TOOLWINDOW) and WS_EX_APPWINDOW.inv()
            if (next != style) {
                user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, next)
                // The taskbar reads the style when a window is shown, so show it again.
                if (user32.IsWindowVisible(hwnd)) {
                    user32.ShowWindow(hwnd, WinUser.SW_HIDE)
                    user32.ShowWindow(hwnd, SW_SHOWNOACTIVATE)
                }
            }
            true
        }.getOrElse {
            AppLogger.warning(TAG, "Hiding the overlay from the taskbar failed", it)
            false
        }
    }

    private fun hwndOf(window: Window): HWND? {
        if (!window.isDisplayable) return null
        return runCatching { Native.getWindowPointer(window)?.let(::HWND) }.getOrNull()
    }

    private const val TAG = "FloatingLyrics"
    private const val TRANSPARENCY_HACK_CLASS = "androidx.compose.ui.awt.JLayeredPaneWithTransparencyHack"
    private const val LAYERED_RETRY_MS = 50
    private const val LAYERED_RETRIES = 20
    private const val WS_EX_TRANSPARENT = 0x00000020
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val WS_EX_APPWINDOW = 0x00040000
    private const val SW_SHOWNOACTIVATE = 4
}
