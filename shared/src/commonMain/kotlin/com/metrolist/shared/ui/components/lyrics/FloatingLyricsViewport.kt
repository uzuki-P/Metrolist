package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

/** How the floating lyrics draw their lines; the main lyrics view has no equivalent and is not affected. */
internal data class FloatingLyricsStyle(
    val mainSp: Float,
    val romanizationSp: Float,
    val translationSp: Float,
    val lineSpacingDp: Float,
    /** Share of the window height above the active line. */
    val activeLinePosition: Float,
    val outline: Boolean,
    /** The main lyrics' text color, so both views follow the same palette and neutral-color settings. */
    val textColor: Color,
    val wordAnimation: FloatingLyricsWordAnimation,
    val activeLineZoom: Boolean,
)

/** How sung words light up in the active line and its romanization. */
enum class FloatingLyricsWordAnimation {
    SWEEP,
    GLOW,
    LIFT,
    NONE,
    ;

    companion object {
        fun from(name: String): FloatingLyricsWordAnimation = entries.firstOrNull { it.name == name } ?: SWEEP
    }
}

/**
 * The floating window's lyric stack. The active line sits near the top so the lines after it stay in view, each
 * line carries its own romanization and translation sizes, and text is outlined so it reads on any background.
 * Lines scroll by themselves when the active line changes and can still be scrolled by hand.
 */
@Composable
internal fun FloatingLyricsViewport(
    lyrics: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    currentPositionFlow: StateFlow<Long>?,
    positionReader: (() -> Long)?,
    onSeek: ((Long, PlaybackSeekSource) -> Unit)?,
    style: FloatingLyricsStyle,
    modifier: Modifier = Modifier,
) {
    val offsetMs = LyricsUtils.combinedLyricsOffsetMs(timingOffsetMs, Settings.shared.globalLyricsOffsetMs)
    val position = remember { mutableLongStateOf(0L) }
    val positionFlow = remember(currentPositionFlow) { currentPositionFlow ?: MutableStateFlow(0L) }
    val flowPosition by positionFlow.collectAsState()
    val reader by rememberUpdatedState(positionReader)
    LaunchedEffect(songId, isPlaying, positionReader == null) {
        if (reader == null) return@LaunchedEffect
        position.longValue = reader?.invoke() ?: 0L
        while (isPlaying) {
            withFrameMillis { }
            position.longValue = reader?.invoke() ?: 0L
        }
    }
    val lyricsPosition = { (if (positionReader != null) position.longValue else flowPosition) + offsetMs }

    val isSynced = remember(lyrics) { LyricsUtils.hasPlaybackTiming(lyrics) }
    val active by remember(lyrics, offsetMs) {
        derivedStateOf { if (isSynced) LyricsUtils.findCurrentLineIndex(lyrics, lyricsPosition()) else -1 }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(songId, lyrics, active) {
        if (isSynced) listState.animateScrollToItem(active.coerceAtLeast(0))
    }

    val romanization = Settings.shared.isLyricsRomanizationActive
    val romanizeAsMain = Settings.shared.lyricsRomanizeAsMain
    val showOriginal = Settings.shared.lyricsShowOriginalLyrics
    val showTranslations = Settings.shared.lyricsShowTranslations
    val measurer = rememberTextMeasurer()
    val fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val topPadding = if (isSynced) maxHeight * style.activeLinePosition else 0.dp
        val bottomPadding = if (isSynced) maxHeight - topPadding else 0.dp
        val fadePx = with(density) { (style.mainSp * 1.5f).sp.toPx() }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(style.lineSpacingDp.dp),
            modifier =
                Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // Past lines fade out under the top edge; the stack fades at the bottom edge.
                        val fade = (fadePx / size.height).coerceAtMost(0.4f)
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    fade to Color.Black,
                                    1f - fade to Color.Black,
                                    1f to Color.Transparent,
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
        ) {
            itemsIndexed(lyrics, key = { index, entry -> "$index:${entry.time}" }) { index, entry ->
                val view =
                    LineView.of(
                        entry = entry,
                        romanization = romanization,
                        romanizeAsMain = romanizeAsMain,
                        showOriginal = showOriginal,
                        showTranslations = showTranslations,
                    )
                val distance = if (active < 0) 0 else index - active
                val seek = onSeek
                FloatingLyricLine(
                    view = view,
                    distance = distance,
                    isActive = isSynced && distance == 0,
                    measurer = measurer,
                    fontFamily = fontFamily,
                    style = style,
                    positionProvider = lyricsPosition,
                    modifier =
                        if (seek != null && entry.text.isNotBlank()) {
                            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                seek(
                                    LyricsUtils.lyricsSeekPositionForOffset(entry.time, offsetMs),
                                    PlaybackSeekSource("lyrics_tap", mapOf("lineIndex" to index.toString(), "floating" to "true")),
                                )
                            }
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

@Composable
private fun FloatingLyricLine(
    view: LineView,
    distance: Int,
    isActive: Boolean,
    measurer: TextMeasurer,
    fontFamily: FontFamily?,
    style: FloatingLyricsStyle,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
) {
    if (view.main.isBlank()) {
        Spacer(modifier.height((style.mainSp * BLANK_LINE_RATIO).dp))
        return
    }
    val lineColor by animateColorAsState(lineColor(distance, style.textColor), tween(COLOR_FADE_MS))
    val zoom by animateFloatAsState(
        if (isActive && style.activeLineZoom) ACTIVE_LINE_ZOOM else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    )
    val outlinePx = with(LocalDensity.current) { (style.mainSp * OUTLINE_RATIO).sp.toPx() }.coerceAtLeast(MIN_OUTLINE_PX)
    val animate = isActive && style.wordAnimation != FloatingLyricsWordAnimation.NONE
    val mainRanges = remember(view, animate) { if (animate) floatingWordRanges(view.main, view.mainWords) else null }
    val subRanges =
        remember(view, animate) { view.romanization?.takeIf { animate }?.let { floatingWordRanges(it, view.romanizationWords) } }
    val unsung = lerp(Color.Black, style.textColor, UNSUNG_STRENGTH)
    val subColor = lerp(Color.Black, lineColor, SUB_LINE_STRENGTH)

    Column(
        modifier =
            modifier.fillMaxWidth().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((style.mainSp * SUB_LINE_GAP_RATIO).dp),
    ) {
        OutlinedLyricText(
            text = view.main,
            textStyle = lyricTextStyle(style.mainSp, fontFamily, FontWeight.Bold),
            color = if (mainRanges != null) unsung else lineColor,
            litColor = style.textColor,
            outline = style.outline,
            outlinePx = outlinePx,
            measurer = measurer,
            animation = style.wordAnimation,
            ranges = mainRanges,
            positionProvider = positionProvider,
        )
        view.romanization?.let {
            OutlinedLyricText(
                text = it,
                textStyle = lyricTextStyle(style.romanizationSp, fontFamily, FontWeight.SemiBold),
                color = if (subRanges != null) lerp(Color.Black, unsung, SUB_LINE_STRENGTH) else subColor,
                litColor = lerp(Color.Black, style.textColor, SUB_LINE_STRENGTH),
                outline = style.outline,
                outlinePx = outlinePx * SUB_OUTLINE_RATIO,
                measurer = measurer,
                animation = style.wordAnimation,
                ranges = subRanges,
                positionProvider = positionProvider,
            )
        }
        view.translation?.let {
            OutlinedLyricText(
                text = it,
                textStyle = lyricTextStyle(style.translationSp, fontFamily, FontWeight.Medium),
                color = subColor,
                litColor = subColor,
                outline = style.outline,
                outlinePx = outlinePx * SUB_OUTLINE_RATIO,
                measurer = measurer,
                animation = style.wordAnimation,
            )
        }
    }
}

/**
 * Draws [text] centered and wrapped to the available width, optionally outlined. With [ranges], each word lights
 * up as it is sung in the way [animation] chooses.
 */
@Composable
private fun OutlinedLyricText(
    text: String,
    textStyle: TextStyle,
    color: Color,
    litColor: Color,
    outline: Boolean,
    outlinePx: Float,
    measurer: TextMeasurer,
    animation: FloatingLyricsWordAnimation,
    modifier: Modifier = Modifier,
    ranges: List<FloatingWordRange>? = null,
    positionProvider: (() -> Long)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val layout = remember(text, textStyle, widthPx) { measurer.measure(text, textStyle, constraints = Constraints.fixedWidth(widthPx)) }
        val wordBounds = remember(layout, ranges) { ranges?.map { layout.getPathForRange(it.start, it.end).getBounds() } }
        val stroke = remember(outlinePx) { Stroke(width = outlinePx, join = StrokeJoin.Round) }
        val fontPx = with(LocalDensity.current) { textStyle.fontSize.toPx() }
        Canvas(Modifier.fillMaxWidth().height(with(LocalDensity.current) { layout.size.height.toDp() })) {
            val position = positionProvider?.invoke()
            if (ranges == null || wordBounds == null || position == null) {
                drawLyricLayer(layout, color, outline, stroke)
                return@Canvas
            }
            val pad = outlinePx + fontPx * GLOW_BLUR_RATIO
            val words =
                ranges.mapIndexed { index, range ->
                    val progress = ((position - range.startMs).toFloat() / (range.endMs - range.startMs)).coerceIn(0f, 1f)
                    val lift = if (animation == FloatingLyricsWordAnimation.LIFT) fontPx * LIFT_RATIO * sin(PI.toFloat() * progress) else 0f
                    val glow =
                        if (animation == FloatingLyricsWordAnimation.GLOW && progress > 0f) {
                            if (progress < 1f) 1f else (1f - (position - range.endMs).toFloat() / GLOW_FADE_MS).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    SungWord(wordBounds[index], progress, lift, glow)
                }
            val lifted = words.filter { it.lift > 0f }
            // Lifted words are cut out of the line and drawn again higher up, so nothing shows twice.
            val cutout = Path().apply { lifted.forEach { addRect(it.bounds.inflateVertically(pad)) } }
            clipPath(cutout, ClipOp.Difference) {
                drawLyricLayer(layout, color, outline, stroke)
                words.filter { it.lift == 0f }.forEach { drawSungWord(layout, it, litColor, fontPx, pad) }
            }
            lifted.forEach { word ->
                translate(top = -word.lift) {
                    clipRect(word.bounds.left, word.bounds.top - pad, word.bounds.right, word.bounds.bottom + pad) {
                        drawLyricLayer(layout, color, outline, stroke)
                    }
                    drawSungWord(layout, word, litColor, fontPx, pad)
                }
            }
        }
    }
}

private class SungWord(
    val bounds: Rect,
    val progress: Float,
    val lift: Float,
    val glow: Float,
)

private fun Rect.inflateVertically(by: Float) = Rect(left, top - by, right, bottom + by)

private fun DrawScope.drawLyricLayer(
    layout: TextLayoutResult,
    color: Color,
    outline: Boolean,
    stroke: Stroke,
) {
    if (outline) drawText(layout, color = OUTLINE_COLOR, drawStyle = stroke)
    drawText(layout, color = color, shadow = if (outline) null else TEXT_SHADOW)
}

/** Lights [word] up to its progress with a soft leading edge, plus its glow while it is sung. */
private fun DrawScope.drawSungWord(
    layout: TextLayoutResult,
    word: SungWord,
    litColor: Color,
    fontPx: Float,
    pad: Float,
) {
    if (word.progress <= 0f) return
    val bounds = word.bounds
    if (word.glow > 0f) {
        clipRect(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad) {
            drawText(
                layout,
                color = Color.Transparent,
                shadow = Shadow(litColor.copy(alpha = GLOW_ALPHA * word.glow), Offset.Zero, fontPx * GLOW_BLUR_RATIO),
            )
        }
    }
    val edgeX = bounds.left + bounds.width * word.progress
    clipRect(bounds.left, bounds.top - pad, edgeX, bounds.bottom + pad) {
        if (word.progress >= 1f) {
            drawText(layout, color = litColor)
        } else {
            val softness = fontPx * SWEEP_EDGE_RATIO
            drawText(
                layout,
                brush = Brush.horizontalGradient(listOf(litColor, litColor.copy(alpha = 0f)), startX = edgeX - softness, endX = edgeX),
            )
        }
    }
}

private fun lyricTextStyle(
    sizeSp: Float,
    fontFamily: FontFamily?,
    weight: FontWeight,
) = TextStyle(
    fontSize = sizeSp.sp,
    fontWeight = weight,
    lineHeight = (sizeSp * LINE_HEIGHT_RATIO).sp,
    textAlign = TextAlign.Center,
    textDirection = TextDirection.Content,
    fontFamily = fontFamily,
)

/**
 * The line's own color: the main lyrics' text color for the active line, then darker for the lines after it.
 * Fills stay opaque because the outline straddles each glyph's edge and would show through.
 */
private fun lineColor(
    distance: Int,
    textColor: Color,
): Color {
    val strength =
        when {
            distance == 0 -> 1f
            distance > 0 -> (UPCOMING_STRENGTH - UPCOMING_FALLOFF * (distance - 1)).coerceAtLeast(MIN_STRENGTH)
            else -> PAST_STRENGTH
        }
    return lerp(Color.Black, textColor, strength)
}

/** What a line shows after the romanization and translation settings are applied. */
internal class LineView(
    val main: String,
    val mainWords: List<WordTimestamp>?,
    val romanization: String?,
    val romanizationWords: List<WordTimestamp>?,
    val translation: String?,
) {
    companion object {
        fun of(
            entry: LyricsEntry,
            romanization: Boolean,
            romanizeAsMain: Boolean,
            showOriginal: Boolean,
            showTranslations: Boolean,
        ): LineView {
            val romanized =
                entry.romanizedText
                    ?.takeIf { romanization }
                    ?.replace('\u00A0', ' ')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(entry.text.trim(), ignoreCase = true) }
            val asMain = romanizeAsMain && romanized != null
            val main = if (asMain) romanized.orEmpty() else entry.text
            val sub = if (asMain) entry.text.takeIf { showOriginal } else romanized
            val translation =
                entry.translatedText
                    ?.takeIf { showTranslations }
                    ?.replace('\u00A0', ' ')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(main.trim(), ignoreCase = true) && !it.equals(sub?.trim(), ignoreCase = true) }
            return LineView(
                main = main,
                mainWords = if (asMain) entry.romanizedWords else entry.words,
                romanization = sub,
                romanizationWords = if (asMain) entry.words else entry.romanizedWords,
                translation = translation,
            )
        }
    }
}

/** A word's span in the line text and the time it is sung, in milliseconds. */
internal data class FloatingWordRange(
    val start: Int,
    val end: Int,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Finds each timed word in [text], in order. Returns null when the words do not spell the line, so the caller
 * lights nothing rather than the wrong span.
 */
internal fun floatingWordRanges(
    text: String,
    words: List<WordTimestamp>?,
): List<FloatingWordRange>? {
    val timed =
        words?.filter { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime }?.takeIf { it.isNotEmpty() } ?: return null
    var cursor = 0
    return timed.map { word ->
        val piece = word.text.trim()
        val at = if (piece.isEmpty()) -1 else text.indexOf(piece, cursor)
        if (at < 0) return null
        cursor = at + piece.length
        FloatingWordRange(at, cursor, (word.startTime * 1000).toLong(), (word.endTime * 1000).toLong())
    }
}

const val FLOATING_LYRICS_DEFAULT_TEXT_SP = 20
const val FLOATING_LYRICS_DEFAULT_ROMANIZATION_SP = 11
const val FLOATING_LYRICS_DEFAULT_TRANSLATION_SP = 13
const val FLOATING_LYRICS_DEFAULT_LINE_SPACING_DP = 14
const val FLOATING_LYRICS_DEFAULT_ACTIVE_LINE_POSITION = 0.2f
const val FLOATING_LYRICS_DEFAULT_BACKGROUND_OPACITY = 0.45f
const val FLOATING_LYRICS_MIN_ACTIVE_LINE_POSITION = 0.05f
const val FLOATING_LYRICS_MAX_ACTIVE_LINE_POSITION = 0.5f
val FLOATING_LYRICS_TEXT_SP_RANGE = 12..48
val FLOATING_LYRICS_SUB_TEXT_SP_RANGE = 8..30
val FLOATING_LYRICS_LINE_SPACING_DP_RANGE = 0..40

private val OUTLINE_COLOR = Color.Black.copy(alpha = 0.85f)
private val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), 8f)
private const val UNSUNG_STRENGTH = 0.72f
private const val SUB_LINE_STRENGTH = 0.88f
private const val UPCOMING_STRENGTH = 0.85f
private const val UPCOMING_FALLOFF = 0.1f
private const val MIN_STRENGTH = 0.6f
private const val PAST_STRENGTH = 0.55f
private const val SUB_LINE_GAP_RATIO = 0.12f
private const val BLANK_LINE_RATIO = 0.8f
private const val LINE_HEIGHT_RATIO = 1.3f
private const val OUTLINE_RATIO = 0.08f
private const val SUB_OUTLINE_RATIO = 0.6f
private const val MIN_OUTLINE_PX = 1.2f
private const val COLOR_FADE_MS = 250
private const val ACTIVE_LINE_ZOOM = 1.06f
private const val LIFT_RATIO = 0.12f
private const val SWEEP_EDGE_RATIO = 0.5f
private const val GLOW_ALPHA = 0.75f
private const val GLOW_BLUR_RATIO = 0.45f
private const val GLOW_FADE_MS = 400f
