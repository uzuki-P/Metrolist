package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.flow.StateFlow

/**
 * The floating lyrics window's content: the line being sung, and the next one below it.
 * Android draws it in an overlay window and desktop in an always-on-top window; both pass the
 * player's lyrics and clock.
 */
@Composable
fun FloatingLyricsContent(
    lyrics: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    currentPositionFlow: StateFlow<Long>?,
    positionReader: (() -> Long)?,
    playbackSpeed: Float,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val settings = Settings.shared
    val scale = settings.floatingLyricsTextScale.coerceIn(FLOATING_LYRICS_MIN_TEXT_SCALE, FLOATING_LYRICS_MAX_TEXT_SCALE)
    val lineStyle =
        MaterialTheme.typography.titleLarge.let { base ->
            base.copy(
                fontSize = base.fontSize * scale,
                lineHeight = base.lineHeight * scale,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                shadow = TEXT_SHADOW,
            )
        }
    val nextStyle =
        lineStyle.copy(
            fontSize = lineStyle.fontSize * NEXT_LINE_SCALE,
            lineHeight = lineStyle.lineHeight * NEXT_LINE_SCALE,
            fontWeight = FontWeight.SemiBold,
        )
    val synced = remember(lyrics) { LyricsUtils.hasPlaybackTiming(lyrics) }
    val positionState =
        rememberCaptionLyricsPositionState(
            lyrics = lyrics,
            isPlaying = isPlaying,
            songId = songId,
            timingOffsetMs = timingOffsetMs,
            isCasting = false,
            currentPositionFlow = currentPositionFlow,
            positionReader = positionReader,
            enabled = synced,
            finalLineDurationMs = LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS,
            playbackSpeed = playbackSpeed,
        )
    val currentIndex by remember(lyrics, positionState) {
        derivedStateOf(structuralEqualityPolicy()) { LyricsUtils.findCurrentLineIndex(lyrics, positionState.value) }
    }
    val current = if (synced) lyrics.getOrNull(currentIndex) else null
    val next = if (synced) lyrics.getOrNull(currentIndex + 1) else null
    val lineEnd =
        current?.let { LyricsUtils.getLineEndTime(it, next, LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS) } ?: 0L
    val reservedHeight = with(LocalDensity.current) { lineStyle.lineHeight.toDp() * 2 }

    Column(
        modifier =
            modifier
                .background(
                    Color.Black.copy(alpha = settings.floatingLyricsBackgroundOpacity.coerceIn(0f, 1f)),
                    RoundedCornerShape(20.dp),
                ).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedContent(
            targetState = current to lineEnd,
            transitionSpec = { fadeIn(tween(LINE_FADE_MS)) togetherWith fadeOut(tween(LINE_FADE_MS)) },
            contentAlignment = Alignment.Center,
            label = "floatingLyricsLine",
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = reservedHeight),
        ) { (line, end) ->
            if (line == null) {
                FloatingText(if (synced) next?.text.orEmpty() else placeholder, nextStyle, Color.White.copy(alpha = 0.7f))
            } else {
                val active by remember(line, end, positionState) {
                    derivedStateOf(structuralEqualityPolicy()) { positionState.value in line.time..end }
                }
                ActiveLyricsLineContent(
                    entry = line,
                    isActive = active,
                    positionProvider = { positionState.value },
                    lineEndTimeMs = end,
                    isPlaying = isPlaying,
                    textColor = Color.White,
                    lyricStyle = lineStyle,
                    textMeasurer = rememberTextMeasurer(),
                    alignment = TextAlign.Center,
                    maxLines = 2,
                    useFixedTone = false,
                )
            }
        }
        if (settings.floatingLyricsShowNextLine && current != null && next != null) {
            FloatingText(next.text, nextStyle, Color.White.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun FloatingText(
    text: String,
    style: TextStyle,
    color: Color,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

const val FLOATING_LYRICS_MIN_TEXT_SCALE = 0.6f
const val FLOATING_LYRICS_MAX_TEXT_SCALE = 2f
private const val NEXT_LINE_SCALE = 0.75f
private const val LINE_FADE_MS = 300
private val TEXT_SHADOW = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 6f)
