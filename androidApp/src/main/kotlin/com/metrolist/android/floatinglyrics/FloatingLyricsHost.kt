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
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
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
import com.metrolist.android.R
import com.metrolist.android.ui.theme.getAppTypography
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.floating_lyrics_hide
import com.metrolist.shared.generated.resources.floating_lyrics_larger_text
import com.metrolist.shared.generated.resources.floating_lyrics_lock
import com.metrolist.shared.generated.resources.floating_lyrics_locked_hint
import com.metrolist.shared.generated.resources.floating_lyrics_no_lyrics
import com.metrolist.shared.generated.resources.floating_lyrics_resize
import com.metrolist.shared.generated.resources.floating_lyrics_smaller_text
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.FLOATING_LYRICS_MAX_TEXT_SCALE
import com.metrolist.shared.ui.components.lyrics.FLOATING_LYRICS_MIN_TEXT_SCALE
import com.metrolist.shared.ui.components.lyrics.FloatingLyricsContent
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.viewmodels.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import kotlin.math.hypot
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

    private var view: OverlayFrame? = null
    private var params: WindowManager.LayoutParams? = null
    private var gesture: WindowGesture? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var controlsVisible by mutableStateOf(false)
    private var controlsTouchedAt by mutableLongStateOf(0L)

    // In window coordinates; null while the controls are hidden.
    private var resizeHandleBounds: Rect? = null

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
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { OverlayContent() }
            }
        val frame =
            OverlayFrame(context).apply {
                setViewTreeLifecycleOwner(this@FloatingLyricsHost)
                setViewTreeSavedStateRegistryOwner(this@FloatingLyricsHost)
                setViewTreeViewModelStoreOwner(this@FloatingLyricsHost)
                addView(composeView)
                // The window wraps its content, so keep it on screen when the lyrics grow or shrink.
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyLayout()
                }
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
        resizeHandleBounds = null
    }

    @Composable
    private fun OverlayContent() {
        val state by viewModel.uiState.collectAsState()
        val playbackSpeed by viewModel.playbackSpeed.collectAsState()
        LaunchedEffect(controlsVisible, controlsTouchedAt) {
            if (controlsVisible) {
                delay(CONTROLS_TIMEOUT_MS)
                controlsVisible = false
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(), typography = getAppTypography()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FloatingLyricsContent(
                    lyrics = state.lyrics,
                    songId = state.currentSong?.id,
                    timingOffsetMs = state.lyricsOffsetMs,
                    isPlaying = state.isPlaying,
                    currentPositionFlow = viewModel.currentPosition,
                    positionReader = viewModel::currentPositionMs,
                    playbackSpeed = playbackSpeed,
                    placeholder = if (state.isLyricsLoading) "" else stringResource(Res.string.floating_lyrics_no_lyrics),
                    modifier =
                        Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) {
                            controlsVisible = !controlsVisible
                            controlsTouchedAt = SystemClock.uptimeMillis()
                        },
                )
                if (controlsVisible) OverlayControls()
            }
        }
    }

    @Composable
    private fun OverlayControls() {
        val settings = Settings.shared
        val lockedHint = stringResource(Res.string.floating_lyrics_locked_hint)

        fun touched() {
            controlsTouchedAt = SystemClock.uptimeMillis()
        }

        fun changeTextScale(step: Float) {
            settings.floatingLyricsTextScale =
                ((settings.floatingLyricsTextScale + step) * 10)
                    .roundToInt()
                    .div(10f)
                    .coerceIn(FLOATING_LYRICS_MIN_TEXT_SCALE, FLOATING_LYRICS_MAX_TEXT_SCALE)
            touched()
        }
        DisposableEffect(Unit) { onDispose { resizeHandleBounds = null } }
        Row(
            modifier =
                Modifier
                    .background(Color.Black.copy(alpha = CONTROLS_BACKGROUND_ALPHA), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(
                MetrolistIcons.Filled.Remove,
                stringResource(Res.string.floating_lyrics_smaller_text),
            ) { changeTextScale(-TEXT_SCALE_STEP) }
            ControlButton(
                MetrolistIcons.Filled.Add,
                stringResource(Res.string.floating_lyrics_larger_text),
            ) { changeTextScale(TEXT_SCALE_STEP) }
            ControlButton(MetrolistIcons.Outlined.Settings, stringResource(Res.string.settings)) {
                controlsVisible = false
                runCatching { context.startActivity(MainActivity.settingsPageIntent(context, SettingsPageId.FLOATING_LYRICS)) }
                    .onFailure { Timber.w(it, "Could not open floating lyrics settings") }
            }
            ControlButton(painterResource(R.drawable.ic_notification_lock), stringResource(Res.string.floating_lyrics_lock)) {
                controlsVisible = false
                settings.floatingLyricsClickThrough = true
                Toast.makeText(context, lockedHint, Toast.LENGTH_LONG).show()
            }
            ControlButton(MetrolistIcons.Filled.Close, stringResource(Res.string.floating_lyrics_hide)) {
                controlsVisible = false
                settings.floatingLyricsEnabled = false
            }
            Icon(
                imageVector = MetrolistIcons.Filled.DragHandle,
                contentDescription = stringResource(Res.string.floating_lyrics_resize),
                tint = Color.White,
                modifier =
                    Modifier
                        .size(CONTROL_SIZE)
                        .padding(10.dp)
                        .rotate(90f)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInRoot()
                            resizeHandleBounds = Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
                        },
            )
        }
    }

    @Composable
    private fun ControlButton(
        icon: ImageVector,
        description: String,
        onClick: () -> Unit,
    ) = ControlButton(rememberVectorPainter(icon), description, onClick)

    @Composable
    private fun ControlButton(
        icon: Painter,
        description: String,
        onClick: () -> Unit,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(CONTROL_SIZE)) {
            Icon(painter = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }

    /**
     * The window's root view. It sees each touch before Compose: a drag past the touch slop moves the window, or
     * resizes it when it starts on the resize handle, and Compose gets a cancel; taps reach Compose untouched.
     * Moves use screen coordinates, so the moving window does not skew the deltas.
     */
    private inner class OverlayFrame(
        context: Context,
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (handleWindowGesture(event)) {
                return true
            }
            // Claim the stream even when Compose ignores the press, so a drag that starts on the lyrics still arrives.
            return super.dispatchTouchEvent(event) || event.actionMasked == MotionEvent.ACTION_DOWN
        }

        private fun handleWindowGesture(event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture =
                        WindowGesture(
                            touchX = event.rawX,
                            touchY = event.rawY,
                            windowX = p.x,
                            windowY = p.y,
                            width = width,
                            resizing = resizeHandleBounds?.contains(event.x.toInt(), event.y.toInt()) == true,
                        )
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val g = gesture ?: return false
                    val dx = event.rawX - g.touchX
                    val dy = event.rawY - g.touchY
                    if (!g.active) {
                        if (hypot(dx, dy) < touchSlop) return false
                        g.active = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (g.resizing) resizeTo(p, g, dx) else moveTo(p, g, dx, dy)
                    controlsTouchedAt = SystemClock.uptimeMillis()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val g = gesture ?: return false
                    gesture = null
                    if (!g.active) return false
                    if (g.resizing) {
                        Settings.shared.floatingLyricsWidth = p.width
                    } else if (p.x != g.windowX || p.y != g.windowY) {
                        Settings.shared.floatingLyricsX = p.x
                        Settings.shared.floatingLyricsY = p.y
                    }
                    return true
                }
            }
            return gesture?.active == true
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
                view?.width ?: p.width,
                view?.height ?: 0,
            )
        if (point.x != p.x || point.y != p.y) {
            p.x = point.x
            p.y = point.y
            updateView()
        }
    }

    private fun resizeTo(
        p: WindowManager.LayoutParams,
        g: WindowGesture,
        dx: Float,
    ) {
        val minWidth = (MIN_WIDTH_DP * context.resources.displayMetrics.density).roundToInt()
        val width = (g.width + dx.roundToInt()).coerceIn(minOf(minWidth, screenSize().x - p.x), screenSize().x - p.x)
        if (width != p.width) {
            p.width = width
            updateView()
        }
    }

    private fun applyLayout() {
        val p = params ?: return
        if (gesture?.active == true) return
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

    private class WindowGesture(
        val touchX: Float,
        val touchY: Float,
        val windowX: Int,
        val windowY: Int,
        val width: Int,
        val resizing: Boolean,
        var active: Boolean = false,
    )

    private companion object {
        const val MARGIN_DP = 16f
        const val MAX_DEFAULT_WIDTH_DP = 480f
        const val MIN_WIDTH_DP = 160f
        const val CONTROLS_TIMEOUT_MS = 6_000L
        const val CONTROLS_BACKGROUND_ALPHA = 0.7f
        const val TEXT_SCALE_STEP = 0.1f
        val CONTROL_SIZE = 40.dp
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
