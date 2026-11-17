/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.floatinglyrics

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.metrolist.android.MainActivity
import com.metrolist.android.ui.theme.getAppTypography
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.floating_lyrics_locked_hint
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsBar
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsContent
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsFrame
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsResizeGrip
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the floating lyrics overlay window. It lives in the playback service, so it keeps showing while the
 * app is in the background. The window is attached only while floating lyrics are on, the app may draw
 * overlays and a song is loaded (and playing, when it hides while paused); it reads lyrics and the clock from
 * the app-wide [PlayerViewModel] and asks it to fetch lyrics while shown, since the player screen may never
 * open.
 *
 * A tap on the lyrics shows the control bar and the resize grip. Dragging the bar moves the window, dragging
 * the grip resizes it, and dragging the lyrics scrolls them.
 */
internal class FloatingLyricsHost(
    serviceContext: Context,
    private val viewModel: PlayerViewModel,
) : LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val context: Context = overlayContext(serviceContext)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private var view: OverlayFrame? = null
    private var params: WindowManager.LayoutParams? = null
    private var gesture: WindowGesture? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var controlsVisible by mutableStateOf(false)
    private var controlsTouchedAt by mutableLongStateOf(0L)

    // In window coordinates, read only while the controls are visible.
    private var barBounds: Rect? = null
    private var gripBounds: Rect? = null

    private val permissionGranted = MutableStateFlow(FloatingLyricsPermission.canDrawOverlays(context))

    // The callback can arrive before the new mode is readable, so read it on the next main-loop turn.
    private val permissionListener =
        AppOpsManager.OnOpChangedListener { _, packageName ->
            if (packageName == context.packageName) mainHandler.post(::recheckPermission)
        }

    private val configCallbacks =
        object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                applyLayout()
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        }

    private val hasSong =
        viewModel.uiState
            .map { it.currentSong != null }
            .distinctUntilChanged()

    // A pause only counts once it lasts, so skipping between songs does not flash the window.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val playingRecently: Flow<Boolean> =
        viewModel.uiState
            .map { it.isPlaying }
            .distinctUntilChanged()
            .transformLatest { playing ->
                if (!playing) delay(PAUSE_HIDE_DELAY_MS)
                emit(playing)
            }.onStart { emit(true) }

    private val shouldShow =
        combine(
            snapshotFlow { Settings.shared.floatingLyricsEnabled },
            permissionGranted,
            hasSong,
            snapshotFlow { Settings.shared.floatingLyricsHideWhenPaused },
            playingRecently,
        ) { enabled, permitted, song, hideWhenPaused, playing ->
            enabled && permitted && song && (playing || !hideWhenPaused)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionListener)
        }
        context.registerComponentCallbacks(configCallbacks)
        scope.launch {
            shouldShow.collect { show -> if (show) attach() else detach() }
        }
        scope.launch {
            snapshotFlow {
                listOf(
                    Settings.shared.floatingLyricsX,
                    Settings.shared.floatingLyricsY,
                    Settings.shared.floatingLyricsWidth,
                    Settings.shared.floatingLyricsHeight,
                    Settings.shared.floatingLyricsClickThrough,
                )
            }.collect { applyLayout() }
        }
        scope.launch {
            combine(
                shouldShow,
                viewModel.uiState.map { it.currentSong }.distinctUntilChangedBy { it?.id },
                snapshotFlow {
                    listOf(
                        Settings.shared.lyricsProviderOrder,
                        Settings.shared.lyricsEnabledProviders,
                        Settings.shared.preferWordSyncedLyrics,
                    )
                },
            ) { show, song, _ -> song.takeIf { show } }
                .collect { song -> song?.let { viewModel.loadLyrics(it) } }
        }
    }

    fun release() {
        detach()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) appOps.stopWatchingMode(permissionListener)
        context.unregisterComponentCallbacks(configCallbacks)
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    fun recheckPermission() {
        permissionGranted.value = FloatingLyricsPermission.canDrawOverlays(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        if (view != null) return
        val layoutParams =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayWindowType(),
                    BASE_FLAGS,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START }
        val composeView =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { OverlayContent() }
            }
        val frame =
            OverlayFrame(context).apply {
                setViewTreeLifecycleOwner(this@FloatingLyricsHost)
                setViewTreeSavedStateRegistryOwner(this@FloatingLyricsHost)
                setViewTreeViewModelStoreOwner(this@FloatingLyricsHost)
                addView(composeView)
            }
        params = layoutParams
        view = frame
        computeLayout(layoutParams)
        try {
            windowManager.addView(frame, layoutParams)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (error: RuntimeException) {
            Timber.w(error, "Could not add the floating lyrics window")
            view = null
            params = null
        }
    }

    private fun detach() {
        val current = view ?: return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCatching { windowManager.removeViewImmediate(current) }
        view = null
        params = null
        gesture = null
        controlsVisible = false
    }

    @Composable
    private fun OverlayContent() {
        val state by viewModel.uiState.collectAsState()
        val lockedHint = stringResource(Res.string.floating_lyrics_locked_hint)
        LaunchedEffect(controlsVisible, controlsTouchedAt) {
            if (controlsVisible) {
                delay(CONTROLS_TIMEOUT_MS)
                controlsVisible = false
            }
        }

        fun openSettings(page: SettingsPageId) {
            controlsVisible = false
            runCatching { context.startActivity(MainActivity.settingsPageIntent(context, page)) }
                .onFailure { Timber.w(it, "Could not open settings page $page") }
        }

        val barAtTop = Settings.shared.floatingLyricsBarAtTop
        MaterialTheme(colorScheme = darkColorScheme(), typography = getAppTypography()) {
            FloatingLyricsFrame(
                barVisible = controlsVisible,
                modifier = Modifier.fillMaxSize(),
                content = {
                    FloatingLyricsContent(
                        lyrics = state.lyrics,
                        songId = state.currentSong?.id,
                        timingOffsetMs = state.lyricsOffsetMs,
                        isPlaying = state.isPlaying,
                        isLoading = state.isLyricsLoading,
                        currentPositionFlow = viewModel.currentPosition,
                        positionReader = viewModel::currentPositionMs,
                        // A tap toggles the controls, so it does not seek as well.
                        onSeek = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (controlsVisible) {
                        FloatingLyricsResizeGrip(
                            atTop = !barAtTop,
                            modifier =
                                Modifier
                                    .align(if (barAtTop) Alignment.BottomEnd else Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(GRIP_SIZE)
                                    .onGloballyPositioned { gripBounds = it.boundsInRoot().toRect() },
                        )
                    }
                },
                bar = {
                    FloatingLyricsBar(
                        title = state.currentSong?.title,
                        artists = state.currentSong?.artists,
                        onOpenLyricsSettings = { openSettings(SettingsPageId.LYRICS) },
                        onOpenSettings = { openSettings(SettingsPageId.FLOATING_LYRICS) },
                        onLock = {
                            controlsVisible = false
                            Settings.shared.floatingLyricsClickThrough = true
                            Toast.makeText(context, lockedHint, Toast.LENGTH_LONG).show()
                        },
                        onHide = {
                            controlsVisible = false
                            Settings.shared.floatingLyricsEnabled = false
                        },
                        modifier = Modifier.onGloballyPositioned { barBounds = it.boundsInRoot().toRect() },
                    )
                },
            )
        }
    }

    private fun androidx.compose.ui.geometry.Rect.toRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    /**
     * The window's root view. It sees each touch before Compose. A drag past the touch slop that starts on the
     * control bar moves the window, and one on the resize grip resizes it; Compose then gets a cancel. Anything
     * else reaches Compose untouched, so the lyrics scroll, and a tap on them toggles the controls. Moves use
     * screen coordinates, so the moving window does not skew the deltas.
     */
    private inner class OverlayFrame(
        context: Context,
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (handleWindowGesture(event)) {
                return true
            }
            // Claim the stream even when Compose ignores the press, so the tap that ends it still arrives.
            return super.dispatchTouchEvent(event) || event.actionMasked == MotionEvent.ACTION_DOWN
        }

        private fun handleWindowGesture(event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x.toInt()
                    val y = event.y.toInt()
                    val kind =
                        when {
                            controlsVisible && gripBounds?.contains(x, y) == true -> GestureKind.RESIZE
                            controlsVisible && barBounds?.contains(x, y) == true -> GestureKind.MOVE
                            else -> GestureKind.CONTENT
                        }
                    gesture = WindowGesture(event.rawX, event.rawY, p.x, p.y, width, height, kind)
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val g = gesture ?: return false
                    val dx = event.rawX - g.touchX
                    val dy = event.rawY - g.touchY
                    if (!g.active) {
                        if (hypot(dx, dy) < touchSlop) return false
                        g.active = true
                        if (g.kind == GestureKind.CONTENT) return false
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    when (g.kind) {
                        GestureKind.CONTENT -> return false
                        GestureKind.RESIZE -> resizeTo(p, g, dx, dy)
                        GestureKind.MOVE -> moveTo(p, g, dx, dy)
                    }
                    controlsTouchedAt = SystemClock.uptimeMillis()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val g = gesture ?: return false
                    gesture = null
                    if (g.kind == GestureKind.CONTENT) {
                        if (!g.active && event.actionMasked == MotionEvent.ACTION_UP) {
                            controlsVisible = !controlsVisible
                            controlsTouchedAt = SystemClock.uptimeMillis()
                        }
                        return false
                    }
                    if (!g.active) return false
                    val settings = Settings.shared
                    if (g.kind == GestureKind.RESIZE) {
                        settings.floatingLyricsWidth = p.width
                        settings.floatingLyricsHeight = p.height
                    }
                    if (p.x != g.windowX || p.y != g.windowY) {
                        settings.floatingLyricsX = p.x
                        settings.floatingLyricsY = p.y
                    }
                    return true
                }
            }
            return gesture?.let { it.active && it.kind != GestureKind.CONTENT } == true
        }
    }

    private fun moveTo(
        p: WindowManager.LayoutParams,
        g: WindowGesture,
        dx: Float,
        dy: Float,
    ) {
        val screen = screenSize()
        val point =
            clampOverlayPosition(
                OverlayPoint(g.windowX + dx.roundToInt(), g.windowY + dy.roundToInt()),
                screen.x,
                screen.y,
                p.width,
                p.height,
            )
        if (point.x != p.x || point.y != p.y) {
            p.x = point.x
            p.y = point.y
            updateView()
        }
    }

    /** Grows the right edge, and the bottom or top edge depending on which corner holds the grip. */
    private fun resizeTo(
        p: WindowManager.LayoutParams,
        g: WindowGesture,
        dx: Float,
        dy: Float,
    ) {
        val density = context.resources.displayMetrics.density
        val minWidth = (MIN_WIDTH_DP * density).roundToInt()
        val minHeight = (MIN_HEIGHT_DP * density).roundToInt()
        val screen = screenSize()
        val width = (g.width + dx.roundToInt()).coerceIn(minOf(minWidth, screen.x - p.x), screen.x - p.x)
        val fromTop = !Settings.shared.floatingLyricsBarAtTop
        val (y, height) =
            if (fromTop) {
                val bottom = g.windowY + g.height
                val h = (g.height - dy.roundToInt()).coerceIn(minOf(minHeight, bottom), bottom)
                bottom - h to h
            } else {
                g.windowY to (g.height + dy.roundToInt()).coerceIn(minOf(minHeight, screen.y - g.windowY), screen.y - g.windowY)
            }
        if (width != p.width || height != p.height || y != p.y) {
            p.width = width
            p.height = height
            p.y = y
            updateView()
        }
    }

    private fun applyLayout() {
        val p = params ?: return
        if (gesture?.let { it.active && it.kind != GestureKind.CONTENT } == true) return
        computeLayout(p)
        updateView()
    }

    private fun computeLayout(p: WindowManager.LayoutParams) {
        val settings = Settings.shared
        val density = context.resources.displayMetrics.density
        val screen = screenSize()
        val margin = (MARGIN_DP * density).roundToInt()
        val width =
            floatingLyricsSpan(
                saved = settings.floatingLyricsWidth,
                screen = screen.x,
                margin = margin,
                maxDefault = (DEFAULT_WIDTH_DP * density).roundToInt(),
                min = (MIN_WIDTH_DP * density).roundToInt(),
            )
        val height =
            floatingLyricsSpan(
                saved = settings.floatingLyricsHeight,
                screen = screen.y,
                margin = margin,
                maxDefault = (DEFAULT_HEIGHT_DP * density).roundToInt(),
                min = (MIN_HEIGHT_DP * density).roundToInt(),
            )
        val position =
            floatingLyricsPosition(
                savedX = settings.floatingLyricsX,
                savedY = settings.floatingLyricsY,
                screenWidth = screen.x,
                screenHeight = screen.y,
                width = width,
                height = height,
            )
        p.width = width
        p.height = height
        p.x = position.x
        p.y = position.y
        val clickThrough = settings.floatingLyricsClickThrough
        p.flags = if (clickThrough) BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else BASE_FLAGS
        // Android 12+ blocks touches that pass through another app's window more opaque than this.
        p.alpha =
            if (clickThrough && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
            } else {
                1f
            }
    }

    private fun screenSize(): OverlayPoint =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            OverlayPoint(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            OverlayPoint(metrics.widthPixels, metrics.heightPixels)
        }

    private fun updateView() {
        val v = view ?: return
        val p = params ?: return
        if (v.isAttachedToWindow) runCatching { windowManager.updateViewLayout(v, p) }
    }

    private enum class GestureKind { MOVE, RESIZE, CONTENT }

    private class WindowGesture(
        val touchX: Float,
        val touchY: Float,
        val windowX: Int,
        val windowY: Int,
        val width: Int,
        val height: Int,
        val kind: GestureKind,
        var active: Boolean = false,
    )

    private companion object {
        const val MARGIN_DP = 16f
        const val DEFAULT_WIDTH_DP = 480f
        const val DEFAULT_HEIGHT_DP = 200f
        const val MIN_WIDTH_DP = 200f
        const val MIN_HEIGHT_DP = 96f
        const val CONTROLS_TIMEOUT_MS = 6_000L
        const val PAUSE_HIDE_DELAY_MS = 1_500L
        val GRIP_SIZE = 36.dp
        const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        fun overlayWindowType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        /** A window context on Android 11+, so window metrics and rotation changes come from the display. */
        fun overlayContext(context: Context): Context =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                context.createDisplayContext(display).createWindowContext(overlayWindowType(), null)
            } else {
                context
            }
    }
}
