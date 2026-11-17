package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.floating_lyrics_hide
import com.metrolist.shared.generated.resources.floating_lyrics_lock
import com.metrolist.shared.generated.resources.floating_lyrics_lock_icon
import com.metrolist.shared.generated.resources.floating_lyrics_no_lyrics
import com.metrolist.shared.generated.resources.floating_lyrics_resize
import com.metrolist.shared.generated.resources.floating_lyrics_settings
import com.metrolist.shared.generated.resources.floating_lyrics_title
import com.metrolist.shared.generated.resources.lyrics_auto_scroll_btn
import com.metrolist.shared.generated.resources.lyrics_settings
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The floating lyrics window's lyrics: a [FloatingLyricsViewport] on the window's background, so a taller window
 * shows more lines, while romanization and translation follow the main lyrics settings. Android draws it in an
 * overlay window and desktop in an always-on-top window; both pass the player's lyrics and clock. A null
 * [onSeek] leaves taps on the lines to the window.
 */
@Composable
fun FloatingLyricsContent(
    lyrics: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    currentPositionFlow: StateFlow<Long>?,
    positionReader: (() -> Long)?,
    onSeek: ((Long, PlaybackSeekSource) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val settings = Settings.shared
    val noLyrics = stringResource(Res.string.floating_lyrics_no_lyrics)
    val textColor = resolveLyricsTextColor(Color.White)
    Box(
        modifier =
            modifier
                .clip(FLOATING_LYRICS_SHAPE)
                .background(Color.Black.copy(alpha = settings.floatingLyricsBackgroundOpacity.coerceIn(0f, 1f))),
        contentAlignment = Alignment.Center,
    ) {
        if (lyrics.isEmpty() && !isLoading) {
            Text(
                text = noLyrics,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = textColor.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            FloatingLyricsViewport(
                lyrics = lyrics,
                songId = songId,
                timingOffsetMs = timingOffsetMs,
                isPlaying = isPlaying,
                currentPositionFlow = currentPositionFlow,
                positionReader = positionReader,
                onSeek = onSeek,
                style =
                    FloatingLyricsStyle(
                        mainSp = settings.floatingLyricsTextSize.coerceIn(FLOATING_LYRICS_TEXT_SP_RANGE).toFloat(),
                        romanizationSp = settings.floatingLyricsRomanizationTextSize.coerceIn(FLOATING_LYRICS_SUB_TEXT_SP_RANGE).toFloat(),
                        translationSp = settings.floatingLyricsTranslationTextSize.coerceIn(FLOATING_LYRICS_SUB_TEXT_SP_RANGE).toFloat(),
                        lineSpacingDp = settings.floatingLyricsLineSpacing.coerceIn(FLOATING_LYRICS_LINE_SPACING_DP_RANGE).toFloat(),
                        activeLinePosition =
                            settings.floatingLyricsActiveLinePosition
                                .coerceIn(FLOATING_LYRICS_MIN_ACTIVE_LINE_POSITION, FLOATING_LYRICS_MAX_ACTIVE_LINE_POSITION),
                        outline = settings.floatingLyricsTextOutline,
                        textColor = textColor,
                        wordAnimation = FloatingLyricsWordAnimation.from(settings.floatingLyricsWordAnimation),
                        activeLineZoom = settings.floatingLyricsActiveLineZoom,
                    ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Lays the control [bar] over the lyrics [content] at the top or bottom edge, as the floating lyrics settings
 * choose, so showing it does not push the lines around.
 */
@Composable
fun FloatingLyricsFrame(
    barVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    bar: @Composable () -> Unit,
) {
    val atTop = Settings.shared.floatingLyricsBarAtTop
    Box(modifier) {
        content()
        AnimatedVisibility(
            visible = barVisible,
            enter = fadeIn(tween(BAR_FADE_MS)),
            exit = fadeOut(tween(BAR_FADE_MS)),
            modifier = Modifier.align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter).padding(6.dp),
        ) {
            bar()
        }
    }
}

/**
 * The floating window's control bar: the playing song, then lyrics settings, floating lyrics settings, lock
 * (click-through, when the platform has it) and hide. Each platform makes the bar its move handle.
 */
@Composable
fun FloatingLyricsBar(
    title: String?,
    artists: List<ArtistTiny>?,
    onOpenLyricsSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onLock: (() -> Unit)?,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 40.dp,
) {
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = BAR_BACKGROUND_ALPHA))
                .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = title?.takeIf(String::isNotBlank) ?: stringResource(Res.string.floating_lyrics_title),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            artists
                ?.joinToString(", ") { it.name }
                ?.takeIf(String::isNotBlank)
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
        Spacer(Modifier.width(6.dp))
        BarButton(
            rememberVectorPainter(MetrolistIcons.Outlined.Lyrics),
            stringResource(Res.string.lyrics_settings),
            buttonSize,
            onOpenLyricsSettings,
        )
        BarButton(
            rememberVectorPainter(MetrolistIcons.Outlined.Tune),
            stringResource(Res.string.floating_lyrics_settings),
            buttonSize,
            onOpenSettings,
        )
        if (onLock != null) {
            BarButton(
                painterResource(Res.drawable.floating_lyrics_lock_icon),
                stringResource(Res.string.floating_lyrics_lock),
                buttonSize,
                onLock,
            )
        }
        BarButton(rememberVectorPainter(MetrolistIcons.Filled.Close), stringResource(Res.string.floating_lyrics_hide), buttonSize, onHide)
    }
}

@Composable
private fun BarButton(
    icon: Painter,
    description: String,
    size: Dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(painter = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Diagonal grip lines for a bottom-end corner; [atTop] flips them for a top-end corner. */
@Composable
fun FloatingLyricsResizeGrip(
    atTop: Boolean,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.floating_lyrics_resize)
    Box(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = BAR_BACKGROUND_ALPHA))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 2.dp.toPx()
            for (fraction in floatArrayOf(0.35f, 0.7f, 1f)) {
                val start = size.width * (1f - fraction)
                val y0 = if (atTop) 0f else size.height
                val y1 = if (atTop) size.height * fraction else size.height * (1f - fraction)
                drawLine(Color.White, Offset(start, y0), Offset(size.width, y1), stroke, StrokeCap.Round)
            }
        }
    }
}

val FLOATING_LYRICS_SHAPE = RoundedCornerShape(20.dp)
private const val BAR_BACKGROUND_ALPHA = 0.75f
private const val BAR_FADE_MS = 150
