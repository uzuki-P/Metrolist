/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Window
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.theme.MetrolistTheme
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.floating_lyrics_no_lyrics
import com.metrolist.shared.generated.resources.floating_lyrics_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.ceil
import java.awt.Color as AwtColor
import java.awt.Window as AwtWindow

// Adapted from Metrolist Desktop's floating/FloatingLyricsWindow.kt, itself from the overlay
// window in lyrics-float Main.kt (912333d). It reads v14's player and lyrics state instead of
// its own lyrics engine and keeps its geometry in Settings.shared.

/**
 * The floating lyrics window: transparent, undecorated, always on top, not focusable and out
 * of the taskbar, draggable anywhere and resizable from its side edges. It sits in the
 * application scope next to the main window, so lyrics keep loading while the main window is
 * hidden to the tray, and shows while floating lyrics are on and a track is loaded.
 */
@Composable
fun FloatingLyricsWindow() {
    val settings = Settings.shared
    val enabled = settings.floatingLyricsEnabled
    val track = PlayerState.currentTrack
    LaunchedEffect(enabled, track?.id, settings.lyricsProviderOrder, settings.lyricsEnabledProviders) {
        if (enabled && track != null) LyricsState.fetchLyrics(track)
    }
    if (!enabled || track == null) return

    val support = OverlayWindowSupport.current
    LaunchedEffect(support) {
        // Load JNA's native side before the first drag so the window does not lag behind the
        // cursor.
        withContext(Dispatchers.IO) { support.warmUp() }
    }

    val title = stringResource(Res.string.floating_lyrics_title)
    Window(
        create = {
            val bounds =
                resolveFloatingLyricsBounds(
                    savedX = settings.floatingLyricsX,
                    savedY = settings.floatingLyricsY,
                    savedWidth = settings.floatingLyricsWidth,
                    height = INITIAL_HEIGHT,
                    screens = floatingLyricsScreens(),
                )
            ComposeWindow().apply {
                this.title = title
                // Only applies while the window is not displayable yet, which is why the window
                // is created here instead of through Window's parameters. The taskbar skip
                // states below cover window managers that still list utility windows.
                type = AwtWindow.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                background = AwtColor(0, 0, 0, 0)
                isAlwaysOnTop = true
                isResizable = true
                focusableWindowState = false
                isAutoRequestFocus = false
                minimumSize = Dimension(FLOATING_LYRICS_MIN_WIDTH, 1)
                setBounds(bounds)
            }
        },
        dispose = ComposeWindow::dispose,
    ) {
        FloatingLyricsWindowContent(window, support)
    }
}

@Composable
private fun FloatingLyricsWindowContent(
    window: ComposeWindow,
    support: OverlayWindowSupport,
) {
    val settings = Settings.shared
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var mappingSettled by remember { mutableStateOf(false) }

    // Mapping-time placement: keep overriding for a few frames because AWT/KWin re-place the
    // window while it is being mapped. The window manager also drops the skip states whenever
    // the window is hidden, and ignores requests until it manages the newly mapped window, so
    // repeat them over the first second.
    LaunchedEffect(window) {
        val target = window.location
        repeat(PLACEMENT_FRAMES) {
            window.setLocation(target)
            withFrameNanos { }
        }
        mappingSettled = true
        for (delayMs in longArrayOf(0, 150, 350, 500)) {
            delay(delayMs)
            if (window.isShowing) withContext(Dispatchers.IO) { support.hideFromTaskbar(window) }
        }
    }

    // Click pass-through removes the window's entire input region so XWayland sends pointer
    // events to the windows below. A window that is not showing has no X window to shape yet.
    val clickThrough = settings.floatingLyricsClickThrough && support.supportsClickThrough
    LaunchedEffect(window, clickThrough) {
        while (!window.isShowing) delay(SHOW_POLL_MS)
        support.setClickThrough(window, clickThrough)
    }

    // The WM's interactive move/resize consumes the pointer release, so Compose's drag-end never
    // fires for native gestures. Persist geometry from AWT events instead, debounced, and only
    // once mapping has settled so the placement loop above is not recorded as a custom spot.
    DisposableEffect(window) {
        var persistJob: Job? = null
        val listener =
            object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = persist()

                override fun componentResized(e: ComponentEvent) = persist()

                private fun persist() {
                    if (!mappingSettled) return
                    persistJob?.cancel()
                    persistJob =
                        scope.launch {
                            delay(PERSIST_DEBOUNCE_MS)
                            if (!window.isShowing) return@launch
                            settings.floatingLyricsX = window.x
                            settings.floatingLyricsY = window.y
                            settings.floatingLyricsWidth = window.width
                        }
                }
            }
        window.addComponentListener(listener)
        onDispose {
            window.removeComponentListener(listener)
            persistJob?.cancel()
        }
    }

    val placeholder = if (LyricsState.isLyricsLoading) "" else stringResource(Res.string.floating_lyrics_no_lyrics)
    val playbackSpeed by PlayerState.player.playbackSpeed.collectAsState()
    MetrolistTheme(seedColor = UiState.seedColor) {
        Box(Modifier.fillMaxSize()) {
            FloatingLyricsContent(
                lyrics = LyricsState.currentLyricsEntries,
                songId = PlayerState.currentTrack?.id,
                timingOffsetMs = LyricsState.currentLyricsOffsetMs,
                isPlaying = PlayerState.isPlaying,
                currentPositionFlow = PlayerState.playbackPosition,
                positionReader = PlayerState::currentMediaPositionMs,
                playbackSpeed = playbackSpeed,
                placeholder = placeholder,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .onSizeChanged { size ->
                            // The window's height follows its content: AWT sizes in scaled units.
                            val height = ceil(size.height / density.density).toInt().coerceAtLeast(1)
                            if (window.height != height) window.setSize(window.width, height)
                        }.windowDragHandle(window, support),
            )
            if (!clickThrough) WindowWidthResizeEdges(window, support)
        }
    }
}

private const val INITIAL_HEIGHT = 120
private const val PLACEMENT_FRAMES = 5
private const val SHOW_POLL_MS = 50L
private const val PERSIST_DEBOUNCE_MS = 500L
