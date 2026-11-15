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
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.metrolist.android.ui.theme.getAppTypography
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.floating_lyrics_no_lyrics
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsContent
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Owns the floating lyrics overlay window. It lives in the playback service, so it keeps showing while the
 * app is in the background. The window is attached only while floating lyrics are on, the app may draw
 * overlays and a song is loaded; it reads lyrics and the clock from the app-wide [PlayerViewModel] and asks
 * it to fetch lyrics while shown, since the player screen may never open.
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

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var drag: DragOrigin? = null

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

    private val shouldShow =
        combine(snapshotFlow { Settings.shared.floatingLyricsEnabled }, permissionGranted, hasSong) { enabled, permitted, song ->
            enabled && permitted && song
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
                setViewTreeLifecycleOwner(this@FloatingLyricsHost)
                setViewTreeSavedStateRegistryOwner(this@FloatingLyricsHost)
                setViewTreeViewModelStoreOwner(this@FloatingLyricsHost)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { Content() }
                setOnTouchListener { _, event -> onTouch(event) }
                // The window wraps its content, so keep it on screen when the lyrics grow or shrink.
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyLayout()
                }
            }
        params = layoutParams
        view = composeView
        computeLayout(layoutParams)
        try {
            windowManager.addView(composeView, layoutParams)
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
        drag = null
    }

    @Composable
    private fun Content() {
        val state by viewModel.uiState.collectAsState()
        val playbackSpeed by viewModel.playbackSpeed.collectAsState()
        MaterialTheme(colorScheme = darkColorScheme(), typography = getAppTypography()) {
            FloatingLyricsContent(
                lyrics = state.lyrics,
                songId = state.currentSong?.id,
                timingOffsetMs = state.lyricsOffsetMs,
                isPlaying = state.isPlaying,
                currentPositionFlow = viewModel.currentPosition,
                positionReader = viewModel::currentPositionMs,
                playbackSpeed = playbackSpeed,
                placeholder = if (state.isLyricsLoading) "" else stringResource(Res.string.floating_lyrics_no_lyrics),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /** Drags move the window in screen coordinates, so the moving window does not skew the deltas. */
    private fun onTouch(event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = DragOrigin(event.rawX, event.rawY, p.x, p.y)
            }

            MotionEvent.ACTION_MOVE -> {
                val origin = drag ?: return false
                val screen = screenSize()
                val point =
                    clampOverlayPosition(
                        OverlayPoint(
                            origin.windowX + (event.rawX - origin.touchX).roundToInt(),
                            origin.windowY + (event.rawY - origin.touchY).roundToInt(),
                        ),
                        screen.x,
                        screen.y,
                        view?.width ?: p.width,
                        view?.height ?: 0,
                    )
                if (point.x != p.x || point.y != p.y) {
                    p.x = point.x
                    p.y = point.y
                    updateView()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val origin = drag ?: return false
                drag = null
                if (p.x != origin.windowX || p.y != origin.windowY) {
                    Settings.shared.floatingLyricsX = p.x
                    Settings.shared.floatingLyricsY = p.y
                }
            }
        }
        return true
    }

    private fun applyLayout() {
        val p = params ?: return
        if (drag != null) return
        computeLayout(p)
        updateView()
    }

    private fun computeLayout(p: WindowManager.LayoutParams) {
        val settings = Settings.shared
        val density = context.resources.displayMetrics.density
        val screen = screenSize()
        val width =
            floatingLyricsWidth(
                savedWidth = settings.floatingLyricsWidth,
                screenWidth = screen.x,
                margin = (MARGIN_DP * density).roundToInt(),
                maxWidth = (MAX_DEFAULT_WIDTH_DP * density).roundToInt(),
                minWidth = (MIN_WIDTH_DP * density).roundToInt(),
            )
        val position =
            floatingLyricsPosition(
                savedX = settings.floatingLyricsX,
                savedY = settings.floatingLyricsY,
                screenWidth = screen.x,
                screenHeight = screen.y,
                width = width,
                height = view?.height ?: 0,
            )
        p.width = width
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

    private class DragOrigin(
        val touchX: Float,
        val touchY: Float,
        val windowX: Int,
        val windowY: Int,
    )

    private companion object {
        const val MARGIN_DP = 16f
        const val MAX_DEFAULT_WIDTH_DP = 480f
        const val MIN_WIDTH_DP = 160f
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
