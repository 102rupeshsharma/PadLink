package com.ps2gamepad.model

/**
 * The 12 digital buttons in the HID report. [hidButton] is the 1-based HID button usage
 * (what joy.cpl and PCSX2 show as "Button N"); bit index in the report is hidButton - 1.
 *
 * The order follows the convention used by PlayStation-style DirectInput pads, so tools such as
 * PCSX2/SDL map the face buttons sensibly without manual binding.
 */
enum class ControllerButton(val hidButton: Int, val label: String) {
    SQUARE(1, "Square"),
    CROSS(2, "Cross"),
    CIRCLE(3, "Circle"),
    TRIANGLE(4, "Triangle"),
    L1(5, "L1"),
    R1(6, "R1"),
    L2(7, "L2"),
    R2(8, "R2"),
    SELECT(9, "Select"),
    START(10, "Start"),
    L3(11, "L3"),
    R3(12, "R3");

    val mask: Int get() = 1 shl (hidButton - 1)
}

enum class Stick { LEFT, RIGHT }

/** HID hat switch value. 0 = up, then clockwise in 45 degree steps; [NEUTRAL] is the null state. */
enum class Hat(val value: Int, val label: String) {
    UP(0, "Up"), UP_RIGHT(1, "Up-Right"), RIGHT(2, "Right"), DOWN_RIGHT(3, "Down-Right"),
    DOWN(4, "Down"), DOWN_LEFT(5, "Down-Left"), LEFT(6, "Left"), UP_LEFT(7, "Up-Left"),
    NEUTRAL(8, "Neutral");

    companion object {
        /** Opposite directions cancel each other (like a physical rocker), so the result is always valid. */
        fun from(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Hat {
            val v = (if (up) -1 else 0) + (if (down) 1 else 0)
            val h = (if (left) -1 else 0) + (if (right) 1 else 0)
            return when {
                v < 0 && h == 0 -> UP
                v < 0 && h > 0 -> UP_RIGHT
                v == 0 && h > 0 -> RIGHT
                v > 0 && h > 0 -> DOWN_RIGHT
                v > 0 && h == 0 -> DOWN
                v > 0 && h < 0 -> DOWN_LEFT
                v == 0 && h < 0 -> LEFT
                v < 0 && h < 0 -> UP_LEFT
                else -> NEUTRAL
            }
        }
    }
}

data class Dpad(
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
) {
    val hat: Hat get() = Hat.from(up, down, left, right)
    val isNeutral: Boolean get() = hat == Hat.NEUTRAL

    companion object {
        val NEUTRAL = Dpad()

        /** The D-pad state that produces [hat]. */
        fun of(hat: Hat) = when (hat) {
            Hat.UP -> Dpad(up = true); Hat.UP_RIGHT -> Dpad(up = true, right = true)
            Hat.RIGHT -> Dpad(right = true); Hat.DOWN_RIGHT -> Dpad(down = true, right = true)
            Hat.DOWN -> Dpad(down = true); Hat.DOWN_LEFT -> Dpad(down = true, left = true)
            Hat.LEFT -> Dpad(left = true); Hat.UP_LEFT -> Dpad(up = true, left = true)
            Hat.NEUTRAL -> NEUTRAL
        }
    }
}

/**
 * Complete logical controller state. Axes are HID values in [-127, 127] with 0 = centre.
 * Y follows the HID/DirectInput convention: negative = up, positive = down.
 */
data class GamepadState(
    val buttons: Int = 0,
    val dpad: Dpad = Dpad.NEUTRAL,
    val leftX: Int = 0,
    val leftY: Int = 0,
    val rightX: Int = 0,
    val rightY: Int = 0,
    /** 3.9.0: the 8 diagonal buttons (bit 0 = left ↖ … bit 7 = right ↘); only sent in the extended report. */
    val diagonals: Int = 0,
) {
    fun isPressed(button: ControllerButton): Boolean = (buttons and button.mask) != 0

    fun withDiagonal(index: Int, pressed: Boolean): GamepadState =
        copy(diagonals = if (pressed) diagonals or (1 shl index) else diagonals and (1 shl index).inv())

    fun withButton(button: ControllerButton, pressed: Boolean): GamepadState =
        copy(buttons = if (pressed) buttons or button.mask else buttons and button.mask.inv())

    fun withStick(stick: Stick, x: Int, y: Int): GamepadState = when (stick) {
        Stick.LEFT -> copy(leftX = clampAxis(x), leftY = clampAxis(y))
        Stick.RIGHT -> copy(rightX = clampAxis(x), rightY = clampAxis(y))
    }

    companion object {
        const val AXIS_MIN = -127
        const val AXIS_MAX = 127
        val NEUTRAL = GamepadState()
        fun clampAxis(v: Int): Int = v.coerceIn(AXIS_MIN, AXIS_MAX)
    }
}
