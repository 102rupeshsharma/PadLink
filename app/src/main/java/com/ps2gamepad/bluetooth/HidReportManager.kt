package com.ps2gamepad.bluetooth

import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.GamepadState

/** Pure report encoding/decoding for the layout documented in [HidDescriptor]. No Android dependencies. */
object HidReportManager {

    private const val BUTTON_BITS_MASK = (1 shl HidDescriptor.BUTTON_COUNT) - 1

    /** [extended]: append the diagonal-buttons byte (3.9.0 opt-in descriptor). */
    fun encode(state: GamepadState, extended: Boolean = false): ByteArray {
        val buttons = state.buttons and BUTTON_BITS_MASK
        val hat = state.dpad.hat.value and 0x0F
        val base = byteArrayOf(
            (buttons and 0xFF).toByte(),
            (((buttons shr 8) and 0x0F) or (hat shl 4)).toByte(),
            GamepadState.clampAxis(state.leftX).toByte(),
            GamepadState.clampAxis(state.leftY).toByte(),
            GamepadState.clampAxis(state.rightX).toByte(),
            GamepadState.clampAxis(state.rightY).toByte(),
        )
        return if (extended) base + (state.diagonals and 0xFF).toByte() else base
    }

    /** [report] in exactly the length the registered descriptor expects (drops or adds the diagonal byte). */
    fun fit(report: ByteArray, extended: Boolean): ByteArray {
        val want = HidDescriptor.reportLength(extended)
        return when {
            report.size == want -> report
            report.size > want -> report.copyOf(want)
            else -> report.copyOf(want) // missing diagonal byte = nothing pressed
        }
    }

    val NEUTRAL_REPORT: ByteArray get() = encode(GamepadState.NEUTRAL)

    /** Decoded view of a report, used by tests and the diagnostic screen. */
    data class Decoded(val buttons: Int, val hat: Int, val x: Int, val y: Int, val z: Int, val rz: Int, val diagonals: Int = 0) {
        fun isPressed(b: ControllerButton) = (buttons and b.mask) != 0
    }

    fun decode(report: ByteArray): Decoded {
        require(report.size == HidDescriptor.REPORT_LENGTH || report.size == HidDescriptor.REPORT_LENGTH_EXTENDED) {
            "Report must be ${HidDescriptor.REPORT_LENGTH} or ${HidDescriptor.REPORT_LENGTH_EXTENDED} bytes, was ${report.size}"
        }
        val b0 = report[0].toInt() and 0xFF
        val b1 = report[1].toInt() and 0xFF
        return Decoded(
            buttons = b0 or ((b1 and 0x0F) shl 8),
            hat = (b1 shr 4) and 0x0F,
            x = report[2].toInt(), y = report[3].toInt(), z = report[4].toInt(), rz = report[5].toInt(),
            diagonals = if (report.size > HidDescriptor.REPORT_LENGTH) report[6].toInt() and 0xFF else 0,
        )
    }

    /** True when two reports carry the same buttons and hat (they may differ only in analog axes). */
    fun sameDigitalState(a: ByteArray, b: ByteArray): Boolean =
        a[0] == b[0] && a[1] == b[1] && a.getOrElse(6) { 0 } == b.getOrElse(6) { 0 } // diagonal buttons are digital too

    fun toHex(report: ByteArray): String = report.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun describe(state: GamepadState): String {
        val pressed = ControllerButton.entries.filter { state.isPressed(it) }.joinToString("+") { it.label }
        return "buttons=[${pressed.ifEmpty { "none" }}] dpad=${state.dpad.hat.label} " +
            "L=(${state.leftX},${state.leftY}) R=(${state.rightX},${state.rightY})"
    }
}
