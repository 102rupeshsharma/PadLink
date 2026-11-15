package com.ps2gamepad.controller

import com.ps2gamepad.model.Hat
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 3.10.0 Mapping mode: a stick becomes 8 separate directions, so an emulator's binding boxes get a different
 * code for each one (left/right/up/down = one axis each, diagonals = their own button).
 *
 * Pure function, with two kinds of hysteresis so a real thumb (which always wobbles) cannot flip the code
 * while the emulator is listening:
 *  - strength: a direction starts at [ENGAGE] of full travel and is only let go below [RELEASE];
 *  - angle: the current direction is kept until the stick is [ANGLE_HYSTERESIS_DEG] past its 45° sector edge.
 */
object StickMapping {
    const val ENGAGE = 0.5f
    const val RELEASE = 0.35f
    const val ANGLE_HYSTERESIS_DEG = 10f

    /** Sector centres in screen degrees (0 = right, 90 = down, y positive = down). */
    private val CENTRES = mapOf(
        Hat.RIGHT to 0f, Hat.DOWN_RIGHT to 45f, Hat.DOWN to 90f, Hat.DOWN_LEFT to 135f,
        Hat.LEFT to 180f, Hat.UP_LEFT to 225f, Hat.UP to 270f, Hat.UP_RIGHT to 315f,
    )
    private val BY_SECTOR = listOf(Hat.RIGHT, Hat.DOWN_RIGHT, Hat.DOWN, Hat.DOWN_LEFT, Hat.LEFT, Hat.UP_LEFT, Hat.UP, Hat.UP_RIGHT)

    /** Which of the 8 directions (or NEUTRAL) the stick [v] is in, given the direction it was in before. */
    fun direction(v: StickVector, current: Hat): Hat {
        val strength = hypot(v.x, v.y)
        if (!strength.isFinite()) return Hat.NEUTRAL
        if (current == Hat.NEUTRAL) {
            if (strength < ENGAGE) return Hat.NEUTRAL
        } else if (strength < RELEASE) {
            return Hat.NEUTRAL
        }
        val deg = angle(v)
        val keep = CENTRES[current]
        if (keep != null && angularDistance(deg, keep) <= 22.5f + ANGLE_HYSTERESIS_DEG) return current
        return sector(deg)
    }

    /**
     * 3.10.1: a straight direction rises in steps instead of jumping to full. Emulators like Azahar take the
     * FIRST value they see on an axis as its rest position: a jump 0 → full made them think the axis rests at
     * full, and they recorded the RELEASE (back to 0) as the opposite direction ("Right" became "Axis 0-").
     * Step 1 (20 %) is clearly near the middle, step 2 (80 %) is clearly pressed but not at the very end (so
     * it cannot look like a digital trigger), step 3 is full.
     */
    val RAMP = floatArrayOf(0.2f, 0.8f, 1f)
    const val RAMP_STEP_MS = 50L

    fun isStraight(h: Hat) = h == Hat.LEFT || h == Hat.RIGHT || h == Hat.UP || h == Hat.DOWN

    fun isDiagonal(h: Hat) = h == Hat.UP_LEFT || h == Hat.UP_RIGHT || h == Hat.DOWN_LEFT || h == Hat.DOWN_RIGHT

    /** Axis values for a STRAIGHT direction (diagonals and neutral send no axis movement). */
    fun axes(h: Hat): StickVector = when (h) {
        Hat.LEFT -> StickVector(-1f, 0f)
        Hat.RIGHT -> StickVector(1f, 0f)
        Hat.UP -> StickVector(0f, -1f)
        Hat.DOWN -> StickVector(0f, 1f)
        else -> StickVector.ZERO
    }

    internal fun angle(v: StickVector): Float {
        val d = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
        return if (d < 0f) d + 360f else d
    }

    private fun sector(deg: Float): Hat = BY_SECTOR[(((deg + 22.5f) / 45f).toInt()) % 8]

    private fun angularDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
