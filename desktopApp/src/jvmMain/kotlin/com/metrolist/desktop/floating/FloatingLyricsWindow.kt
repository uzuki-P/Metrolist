/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.floating

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.theme.MetrolistTheme
import com.metrolist.desktop.utils.ComposeTrayManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.floating_lyrics_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsBar
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsContent
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsFrame
import com.metrolist.shared.ui.settings.SettingsPageId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Color as AwtColor
import java.awt.Window as AwtWindow

// Adapted from Metrolist Desktop's floating/FloatingLyricsWindow.kt, itself from the overlay
// window in lyrics-float Main.kt (912333d). It reads v14's player and lyrics state instead of
// its own lyrics engine and keeps its geometry in Settings.shared.

/**
 * The floating lyrics window: transparent, undecorated, always on top, not focusable and out
 * of the taskbar, resizable from every edge and corner. Hovering shows the control bar, which
 * is also the move handle, since the lyrics take drags and the wheel for scrolling. It sits in
 * the application scope next to the main window, so lyrics keep loading while the main window
 * is hidden to the tray, and shows while floating lyrics are on and a track is loaded (and
 * playing, when it hides while paused).
 */
@Composable
fun FloatingLyricsWindow() {
    val settings = Settings.shared
    val enabled = settings.floatingLyricsEnabled
    val track = PlayerState.currentTrack
    LaunchedEffect(enabled, track?.id, settings.lyricsProviderOrder, settings.lyricsEnabledProviders) {
        if (enabled && track != null) LyricsState.fetchLyrics(track)
    }
    // A pause only counts once it lasts, so skipping between songs does not flash the window.
    var pausedLongEnough by remember { mutableStateOf(false) }
    val playing = PlayerState.isPlaying
    LaunchedEffect(playing) {
        if (playing) {
            pausedLongEnough = false
        } else {
            delay(PAUSE_HIDE_DELAY_MS)
            pausedLongEnough = true
        }
    }
    if (!enabled || track == null || (settings.floatingLyricsHideWhenPaused && pausedLongEnough)) return

    val support = OverlayWindowSupport.current
    LaunchedEffect(support) {
        // Load JNA's native side before the first drag so the window does not lag behind the
        // cursor.
        withContext(Dispatchers.IO) { support.warmUp() }
    }

    val title = stringResource(Res.string.floating_lyrics_title)
    SwingWindow(
        create = {
            val bounds =
                resolveFloatingLyricsBounds(
                    savedX = settings.floatingLyricsX,
                    savedY = settings.floatingLyricsY,
                    savedWidth = settings.floatingLyricsWidth,
                    savedHeight = settings.floatingLyricsHeight,
                    screens = floatingLyricsScreens(),
                )
            ComposeWindow().apply {
                this.title = title
                // The type only applies before the window is displayable, so it is set here. Under
                // XWayland KWin still reports _NET_WM_WINDOW_TYPE_NORMAL, so the taskbar skip
                // states sent after mapping are what keep it out of the taskbar there.
                type = AwtWindow.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                background = AwtColor(0, 0, 0, 0)
                isAlwaysOnTop = true
                isResizable = true
                focusableWindowState = false
                isAutoRequestFocus = false
                minimumSize = Dimension(FLOATING_LYRICS_MIN_WIDTH, FLOATING_LYRICS_MIN_HEIGHT)
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
                            settings.floatingLyricsHeight = window.height
                        }
                }
            }
        window.addComponentListener(listener)
        onDispose {
            window.removeComponentListener(listener)
            persistJob?.cancel()
        }
    }

    // Reset from the settings page: put the window back at its default place and size.
    LaunchedEffect(window) {
        snapshotFlow {
            listOf(settings.floatingLyricsX, settings.floatingLyricsY, settings.floatingLyricsWidth, settings.floatingLyricsHeight)
        }.collect { saved ->
            if (saved.all { it == FLOATING_LYRICS_UNSET }) {
                window.bounds = resolveFloatingLyricsBounds(-1, -1, -1, -1, floatingLyricsScreens())
            }
        }
    }

    var hovered by remember { mutableStateOf(false) }
    val playbackSpeed by PlayerState.player.playbackSpeed.collectAsState()
    val lyrics = LyricsState.currentLyricsEntries
    val dragHandle = Modifier.windowDragHandle(window, support)
    MetrolistTheme(seedColor = UiState.seedColor) {
        FloatingLyricsFrame(
            barVisible = hovered && !clickThrough,
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                when (awaitPointerEvent().type) {
                                    PointerEventType.Enter -> hovered = true
                                    PointerEventType.Exit -> hovered = false
                                    else -> Unit
                                }
                            }
                        }
                    },
            content = {
                FloatingLyricsContent(
                    lyrics = lyrics,
                    agents = LyricsState.currentLyricsAgents,
                    songId = PlayerState.currentTrack?.id,
                    timingOffsetMs = LyricsState.currentLyricsOffsetMs,
                    isPlaying = PlayerState.isPlaying,
                    isLoading = LyricsState.isLyricsLoading,
                    currentPositionFlow = PlayerState.playbackPosition,
                    positionReader = PlayerState::currentMediaPositionMs,
                    playbackSpeed = playbackSpeed,
                    onSeek = { positionMs, source -> if (settings.lyricsClickToSeek) PlayerState.seekToPosition(positionMs, source) },
                    // With nothing to scroll, the whole window moves it.
                    modifier = Modifier.fillMaxSize().then(if (lyrics.isEmpty()) dragHandle else Modifier),
                )
                if (!clickThrough) WindowResizeZones(window, support)
            },
            bar = {
                FloatingLyricsBar(
                    title = PlayerState.currentTrack?.title,
                    artists = PlayerState.currentTrack?.artists,
                    onOpenLyricsSettings = { openSettingsPage(SettingsPageId.LYRICS) },
                    onOpenSettings = { openSettingsPage(SettingsPageId.FLOATING_LYRICS) },
                    onLock = if (support.supportsClickThrough) ({ settings.floatingLyricsClickThrough = true }) else null,
                    onHide = { settings.floatingLyricsEnabled = false },
                    buttonSize = 32.dp,
                    modifier = Modifier.widthIn(max = BAR_MAX_WIDTH).then(dragHandle),
                )
            },
        )
    }
}

/** Brings the main window back from the tray, open on [page] of the settings. */
private fun openSettingsPage(page: SettingsPageId) {
    UiState.selectedSettingsPage = page
    UiState.showSettings = true
    ComposeTrayManager.showWindow()
}

private val BAR_MAX_WIDTH = 520.dp
private const val PAUSE_HIDE_DELAY_MS = 1_500L
private const val PLACEMENT_FRAMES = 5
private const val SHOW_POLL_MS = 50L
private const val PERSIST_DEBOUNCE_MS = 500L
