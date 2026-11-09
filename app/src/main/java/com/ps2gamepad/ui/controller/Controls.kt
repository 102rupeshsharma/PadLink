package com.ps2gamepad.ui.controller

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.StickMath
import com.ps2gamepad.controller.StickVector
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.Dpad
import com.ps2gamepad.model.Hat
import com.ps2gamepad.model.Stick
import com.ps2gamepad.model.StickSettings
import com.ps2gamepad.model.ButtonStyle
import com.ps2gamepad.model.ComboStep
import com.ps2gamepad.model.ExtraButton
import com.ps2gamepad.model.FaceLabels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min

/** Look-and-feel shared by all controls (from the active profile's appearance). */
data class ControlStyle(
    val showLabels: Boolean = true,
    val haptics: Boolean = true,
    val faceSpacing: Float = 1f,
    /** False in the layout editor and in previews: the control draws but never touches HID state. */
    val interactive: Boolean = true,
    val buttonStyle: ButtonStyle = ButtonStyle.REAL,
    val faceLabels: FaceLabels = FaceLabels.XBOX,
)

val LocalControlStyle = compositionLocalOf { ControlStyle() }

/** Haptic tick on press only (never on release: release must never wait for anything). */
@Composable
private fun rememberHaptic(): () -> Unit {
    val view = LocalView.current
    val style = LocalControlStyle.current
    return remember(view, style.haptics) {
        if (style.haptics) { { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } } else { {} }
    }
}

/** Pointer tracking only when the control is interactive. */
@Composable
private fun Modifier.inputIfInteractive(resetKey: Int, onChange: (List<TrackedPointer>, Long) -> Unit): Modifier =
    if (LocalControlStyle.current.interactive) this.systemGestureExclusion().trackPointers(resetKey, onChange) else this

enum class KeyShape { ROUNDED, PILL, CIRCLE }

/** Any single digital button: held while at least one of its own fingers is down. */
@Composable
fun DigitalButton(
    button: ControllerButton,
    input: ControllerInputManager,
    resetKey: Int,
    label: String,
    shape: KeyShape,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    /** Which control is pressing (extra buttons use their own id so they combine with the built-in one). */
    source: String = ControllerInputManager.BASE_SOURCE,
    /** 0..3 = face button position (top, right, bottom, left): coloured Y/B/A/X or PlayStation symbol. */
    faceIndex: Int = -1,
    /** Ring colour override (combo buttons). */
    accent: Color? = null,
) {
    var pressed by remember(resetKey) { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val style = LocalControlStyle.current
    val skin = LocalSkin.current
    val neu = LocalNeu.current
    val faceColor = if (faceIndex >= 0) FaceColors.color(style.faceLabels, faceIndex) else null
    // REAL keys stay neutral with a coloured symbol, like a physical pad; GLOSSY uses the colour only as
    // a glow while pressed; the other styles colour the ring
    val ringColor = accent ?: faceColor?.takeIf { style.buttonStyle != ButtonStyle.REAL }
    Box(
        modifier.inputIfInteractive(resetKey) { pointers, t ->
            val now = pointers.isNotEmpty()
            input.reportPointers(if (source == ControllerInputManager.BASE_SOURCE) button.name else source, pointers.size)
            if (now != pressed) {
                pressed = now
                input.setButtonFrom(source, button, now, t)
                if (now) haptic()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val d = neuDepth(size)
            val inset = d * 0.8f
            val path = when (shape) {
                KeyShape.CIRCLE -> circlePath(center, size.minDimension / 2 - inset)
                KeyShape.PILL, KeyShape.ROUNDED -> {
                    val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
                    roundRectPath(Offset(inset, inset), sz, if (shape == KeyShape.PILL) sz.height / 2 else sz.height * 0.3f)
                }
            }
            neuPath(path, neu, pressed, d, accent = ringColor, style = style.buttonStyle)
            if (style.showLabels && faceIndex >= 0 && style.faceLabels == FaceLabels.PLAYSTATION) {
                drawFaceMark(faceIndex, center, size.minDimension / 2 - inset, faceColor!!)
            }
        }
        if (style.showLabels && !(faceIndex >= 0 && style.faceLabels == FaceLabels.PLAYSTATION)) {
            val text = if (faceIndex >= 0) FaceColors.text(style.faceLabels, faceIndex) else label
            Text(text, color = faceColor ?: if (pressed) skin.labelPressed else skin.label, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * D-pad as one control: the direction comes from the finger position relative to the centre,
 * so sliding between arrows and diagonals works. Several fingers are combined.
 */
@Composable
fun DpadControl(input: ControllerInputManager, resetKey: Int, modifier: Modifier = Modifier) {
    var dpad by remember(resetKey) { mutableStateOf(Dpad.NEUTRAL) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val haptic = rememberHaptic()
    val labels = LocalControlStyle.current.showLabels
    val buttonStyle = LocalControlStyle.current.buttonStyle
    val skin = LocalSkin.current
    val neu = LocalNeu.current
    Canvas(
        modifier
            .onSizeChanged { area = it }
            .inputIfInteractive(resetKey) { pointers, t ->
                val c = Offset(area.width / 2f, area.height / 2f)
                val r = min(area.width, area.height) / 2f
                val next = StickMath.combine(pointers.map { StickMath.dpadFromOffset(it.position.x - c.x, it.position.y - c.y, r) })
                input.reportPointers("DPAD", pointers.size)
                if (next != dpad) {
                    if (next.hat != Hat.NEUTRAL && next.hat != dpad.hat) haptic()
                    dpad = next
                    input.setDpad(next, t)
                }
            }
    ) {
        val s = size.minDimension
        val c = center
        val depth = (s * 0.03f).coerceIn(2f, 8f)
        // base plate (outline style: just a faint ring, nothing else lit)
        if (buttonStyle != ButtonStyle.OUTLINE) drawCircle(neu.key.copy(alpha = 0.55f), radius = s * 0.48f, center = c)
        drawCircle(neu.ring.copy(alpha = 0.35f), radius = s * 0.48f, center = c, style = Stroke(depth * 0.5f))
        val off = s * 0.29f
        val r = s * 0.155f
        fun dir(dx: Float, dy: Float, angleDeg: Float, active: Boolean) {
            val bc = Offset(c.x + dx * off, c.y + dy * off)
            neuPath(circlePath(bc, r), neu, active, depth, style = buttonStyle)
            if (labels) rotate(angleDeg, bc) {
                val g = r * 0.42f
                val chevron = Path().apply { moveTo(bc.x - g, bc.y + g * 0.45f); lineTo(bc.x, bc.y - g * 0.55f); lineTo(bc.x + g, bc.y + g * 0.45f) }
                drawPath(chevron, if (active) skin.labelPressed else skin.label, style = Stroke(width = r * 0.16f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        val h = dpad.hat
        dir(0f, -1f, 0f, h == Hat.UP || h == Hat.UP_LEFT || h == Hat.UP_RIGHT)
        dir(1f, 0f, 90f, h == Hat.RIGHT || h == Hat.UP_RIGHT || h == Hat.DOWN_RIGHT)
        dir(0f, 1f, 180f, h == Hat.DOWN || h == Hat.DOWN_LEFT || h == Hat.DOWN_RIGHT)
        dir(-1f, 0f, 270f, h == Hat.LEFT || h == Hat.UP_LEFT || h == Hat.DOWN_LEFT)
    }
}

/** Geometry of the face-button diamond for a given spacing (fractions of the cluster size). */
data class FaceGeometry(val offset: Float, val buttonRadius: Float, val hitRadius: Float) {
    companion object {
        /**
         * spacing 1.0 = Phase 2 geometry. The button radius shrinks when spacing grows so buttons
         * stay inside the cluster box; the hit radius keeps the same small overlap between
         * neighbours (adjacent centres are offset*sqrt(2) apart; 2*0.735*offset > that).
         */
        fun of(spacing: Float): FaceGeometry {
            val offset = 0.32f * spacing.coerceIn(0.8f, 1.3f)
            val r = min(0.165f, 0.49f - offset)
            return FaceGeometry(offset, r, offset * 0.735f)
        }
    }
}

/** PlayStation face symbol [i] (0 triangle, 1 circle, 2 cross, 3 square) on a button of radius [r]. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFaceMark(i: Int, c: Offset, r: Float, color: Color) {
    val stroke = Stroke(width = r * 0.13f, cap = StrokeCap.Round)
    val g = r * 0.46f
    when (i) {
        0 -> drawPath(Path().apply {
            moveTo(c.x, c.y - g); lineTo(c.x + g * 0.95f, c.y + g * 0.62f); lineTo(c.x - g * 0.95f, c.y + g * 0.62f); close()
        }, color, style = stroke)
        1 -> drawCircle(color, radius = g * 0.9f, center = c, style = stroke)
        2 -> {
            drawLine(color, Offset(c.x - g * 0.8f, c.y - g * 0.8f), Offset(c.x + g * 0.8f, c.y + g * 0.8f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(c.x + g * 0.8f, c.y - g * 0.8f), Offset(c.x - g * 0.8f, c.y + g * 0.8f), stroke.width, StrokeCap.Round)
        }
        3 -> drawRect(color, topLeft = Offset(c.x - g * 0.75f, c.y - g * 0.75f), size = Size(g * 1.5f, g * 1.5f), style = stroke)
    }
}

/**
 * Analog stick. The first finger to land in the area owns it; other fingers in the same area are
 * ignored until the owner lifts. Fixed mode: the centre is the middle of the area plus the
 * calibrated offset. Floating mode: the centre is where the finger landed (kept so the base stays
 * inside the area). Dragging outside the area keeps tracking and saturates at full deflection.
 * Release returns exactly to (0, 0).
 *
 * [onRaw] (calibration screen) gets the finger position relative to the area centre, as a fraction
 * of travel (before dead zone / curve), and the processed output; null when released.
 */
@Composable
fun AnalogStick(
    stick: Stick,
    input: ControllerInputManager,
    resetKey: Int,
    settings: StickSettings,
    modifier: Modifier = Modifier,
    onRaw: ((raw: Offset?, out: StickVector) -> Unit)? = null,
) {
    var area by remember { mutableStateOf(IntSize.Zero) }
    var owner by remember(resetKey) { mutableStateOf<Long?>(null) }
    var origin by remember(resetKey) { mutableStateOf<Offset?>(null) }
    var knob by remember(resetKey) { mutableStateOf(Offset.Zero) } // displacement from origin, px
    var last by remember(resetKey) { mutableStateOf(StickVector.ZERO) }
    val neu = LocalNeu.current
    val buttonStyle = LocalControlStyle.current.buttonStyle

    fun fixedCentre(travel: Float) = Offset(
        area.width / 2f + settings.centerOffsetX * travel,
        area.height / 2f + settings.centerOffsetY * travel,
    )

    Canvas(
        modifier
            .onSizeChanged { area = it }
            .inputIfInteractive(resetKey) { pointers, t ->
                val s = min(area.width, area.height).toFloat()
                val travel = s * STICK_TRAVEL
                input.reportPointers("STICK_$stick", pointers.size)
                var p = pointers.firstOrNull { it.id == owner }
                if (p == null) {
                    p = pointers.firstOrNull()
                    owner = p?.id
                    origin = p?.let {
                        // Current position, not the touch-down point: when a second finger takes
                        // over after the first lifts, the stick must not jump (audit L2).
                        if (settings.floating) Offset(
                            it.position.x.coerceIn(travel, area.width - travel),
                            it.position.y.coerceIn(travel, area.height - travel),
                        ) else fixedCentre(travel)
                    }
                }
                val o = origin
                val v = if (p == null || o == null) {
                    knob = Offset.Zero
                    onRaw?.invoke(null, StickVector.ZERO)
                    StickVector.ZERO
                } else {
                    val dx = p.position.x - o.x
                    val dy = p.position.y - o.y
                    val d = hypot(dx, dy)
                    knob = if (d > travel) Offset(dx / d * travel, dy / d * travel) else Offset(dx, dy)
                    val out = StickMath.process(dx, dy, travel, settings)
                    onRaw?.invoke(Offset((p.position.x - area.width / 2f) / travel, (p.position.y - area.height / 2f) / travel), out)
                    out
                }
                if (p == null) origin = null
                if (v != last) { last = v; input.setStick(stick, v, t) }
            }
    ) {
        val s = size.minDimension
        val travel = s * STICK_TRAVEL
        val c = origin ?: if (settings.floating) center else fixedCentre(travel)
        drawNeuStick(neu, c, travel, s, knob, owner != null, settings.deadZone, buttonStyle)
    }
}

/** Stick: a well and a knob, in the chosen button style. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeuStick(
    neu: NeuTheme, c: Offset, travel: Float, s: Float, knob: Offset, held: Boolean, deadZone: Float = 0f,
    style: ButtonStyle = ButtonStyle.SIMPLE,
) {
    val depth = (s * 0.03f).coerceIn(2f, 8f)
    val wellR = travel + s * 0.07f
    val kr = s * 0.17f
    val k = c + knob
    when (style) {
        ButtonStyle.REAL -> {
            // recessed well: dark centre, lighter lip
            drawCircle(Brush.radialGradient(listOf(Color(0xFF07080A), Color(0xFF202329)), center = c, radius = wellR), radius = wellR, center = c)
            drawCircle(Color.White.copy(alpha = 0.10f), radius = wellR, center = c, style = Stroke(depth * 0.5f))
            // domed knob lit from the top-left, with grip rings
            drawCircle(Color.Black.copy(alpha = 0.6f), radius = kr * 1.06f, center = k + Offset(0f, depth))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF5A606C), Color(0xFF1C1F25)), center = k - Offset(kr * 0.35f, kr * 0.35f), radius = kr * 1.5f), radius = kr, center = k)
            drawCircle(Color.Black.copy(alpha = 0.45f), radius = kr * 0.72f, center = k, style = Stroke(s * 0.01f))
            drawCircle(Color.White.copy(alpha = 0.10f), radius = kr * 0.5f, center = k, style = Stroke(s * 0.006f))
            if (held) drawCircle(Color.White.copy(alpha = 0.10f), radius = kr, center = k)
        }
        ButtonStyle.SIMPLE -> {
            neuPath(circlePath(c, wellR), neu, pressed = false, depth = depth, fill = neu.key.copy(alpha = 0.6f))
            drawCircle(neu.ring.copy(alpha = 0.25f), radius = travel * 0.62f, center = c, style = Stroke(s * 0.006f))
            neuPath(circlePath(k, kr), neu, pressed = held, depth = depth, fill = Color(0xFF262A32))
            drawCircle(neu.ring.copy(alpha = 0.5f), radius = kr * 0.6f, center = k, style = Stroke(s * 0.008f))
        }
        ButtonStyle.OUTLINE -> {
            drawCircle(neu.ring.copy(alpha = 0.55f), radius = wellR, center = c, style = Stroke(depth * 0.6f))
            neuPath(circlePath(k, kr), neu, pressed = held, depth = depth, style = ButtonStyle.OUTLINE)
        }
        ButtonStyle.GLOSSY -> {
            // dark well with a soft rim, glossy knob
            drawCircle(Brush.radialGradient(listOf(Color(0xFF0A0B0D), Color(0xFF1B1D22)), center = c, radius = wellR), radius = wellR, center = c)
            drawCircle(Color(0xFF6B707B).copy(alpha = 0.55f), radius = wellR, center = c, style = Stroke(depth * 0.45f))
            neuPath(circlePath(k, kr), neu, pressed = held, depth = depth, style = ButtonStyle.GLOSSY)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = kr * 0.62f, center = k, style = Stroke(s * 0.008f))
        }
    }
    if (deadZone > 0f && style != ButtonStyle.OUTLINE) {
        drawCircle(neu.labelDim.copy(alpha = 0.25f), radius = travel * deadZone, center = c, style = Stroke(s * 0.006f))
    }
}

/**
 * A combo button made in the layout editor ([ExtraButton] with steps).
 *  - One step: all its buttons (and direction) are held together while the finger is down.
 *  - Several steps: each tap plays the steps in order, each held [ExtraButton.stepMs]. A step that
 *    repeats a button of the previous one gets a short release first, so the PC sees a new press.
 * The combo releases everything it holds when the screen changes or inputs are reset.
 */
@Composable
fun ComboButton(
    combo: ExtraButton,
    input: ControllerInputManager,
    resetKey: Int,
    label: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember(resetKey) { mutableStateOf(false) }
    var playing by remember(resetKey) { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val style = LocalControlStyle.current
    val neu = LocalNeu.current
    val scope = rememberCoroutineScope()
    val source = "combo_${combo.id}"
    val job = remember(resetKey) { arrayOfNulls<Job>(1) }
    DisposableEffect(resetKey, combo) {
        onDispose {
            job[0]?.cancel()
            if (style.interactive) input.setComboFrom(source, emptySet(), Dpad.NEUTRAL)
        }
    }
    fun hold(step: ComboStep?) = input.setComboFrom(source, step?.buttons ?: emptySet(), step?.let { Dpad.of(it.hat) } ?: Dpad.NEUTRAL)
    Box(
        modifier.inputIfInteractive(resetKey) { pointers, _ ->
            val now = pointers.isNotEmpty()
            input.reportPointers(source, pointers.size)
            if (now == pressed) return@inputIfInteractive
            pressed = now
            if (combo.steps.isEmpty()) return@inputIfInteractive
            if (!combo.isSequence) {
                hold(if (now) combo.steps.first() else null)
                if (now) haptic()
            } else if (now) {
                haptic()
                job[0]?.cancel()
                job[0] = scope.launch {
                    playing = true
                    try {
                        val gap = (combo.stepMs / 3).coerceAtLeast(MIN_COMBO_GAP_MS).toLong()
                        combo.steps.forEachIndexed { i, step ->
                            val prev = combo.steps.getOrNull(i - 1)
                            if (prev != null && (prev.buttons.any { it in step.buttons } || (prev.hat == step.hat && step.hat != Hat.NEUTRAL))) {
                                hold(null); delay(gap) // release so the repeated button counts as a new press
                            }
                            hold(step)
                            delay(combo.stepMs.toLong())
                        }
                    } finally {
                        hold(null)
                        playing = false
                    }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val d = neuDepth(size)
            val inset = d * 0.8f
            val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
            neuPath(roundRectPath(Offset(inset, inset), sz, sz.height * 0.3f), neu, pressed || playing, d, accent = COMBO_COLOR, style = style.buttonStyle)
        }
        if (style.showLabels) {
            Text(label, color = if (style.buttonStyle == ButtonStyle.REAL || style.buttonStyle == ButtonStyle.GLOSSY) COMBO_COLOR else neu.label, fontSize = fontSize,
                fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Ring colour of combo buttons (so they stand out from normal buttons). */
val COMBO_COLOR = Color(0xFFB794F6)
private const val MIN_COMBO_GAP_MS = 20

const val STICK_TRAVEL = 0.27f // knob travel radius, fraction of the stick area
