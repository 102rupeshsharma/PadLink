package com.ps2gamepad.bluetooth

import android.content.Context
import androidx.core.content.edit

/**
 * Two (up to four) phones on one PC: each phone is one player.
 *
 * Player 1 keeps the names every earlier version used ("PadLink" / "PadLink Gamepad"), so a phone that
 * is already paired keeps working without pairing again. Players 2-4 get their own names, so the PC,
 * PCSX2 and PadLink Bridge can tell the phones apart:
 *
 *   player 1 → Bluetooth "PadLink",    controller "PadLink Gamepad"
 *   player 2 → Bluetooth "PadLink P2", controller "PadLink Gamepad P2"
 *
 * Windows remembers the names from the moment of pairing, so a phone switched to another player after
 * pairing must be removed on the PC and paired again to show its new name.
 */
object PlayerSlot {
    const val MAX = 4

    fun clamp(player: Int): Int = player.coerceIn(1, MAX)

    fun phoneName(player: Int): String = if (clamp(player) == 1) PhoneNameControl.GAMEPAD_NAME else "${PhoneNameControl.GAMEPAD_NAME} P${clamp(player)}"

    fun controllerName(player: Int): String = if (clamp(player) == 1) HidDescriptor.SDP_NAME else "${HidDescriptor.SDP_NAME} P${clamp(player)}"

    private val NUMBERED = Regex("""PadLink P[1-9]""")

    /** True for any name PadLink itself gives the phone (never saved as the phone's own name). */
    fun isGamepadName(name: String?): Boolean =
        name != null && (name == PhoneNameControl.GAMEPAD_NAME || name in PhoneNameControl.LEGACY_NAMES || NUMBERED.matches(name))
}

/** Remembers which player this phone is. */
interface PlayerPrefs {
    fun load(): Int
    fun save(player: Int)
}

class InMemoryPlayerPrefs(private var value: Int = 1) : PlayerPrefs {
    override fun load(): Int = value
    override fun save(player: Int) { value = player }
}

class AndroidPlayerPrefs(context: Context) : PlayerPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)
    override fun load(): Int = PlayerSlot.clamp(prefs.getInt(KEY, 1))
    override fun save(player: Int) = prefs.edit { putInt(KEY, PlayerSlot.clamp(player)) }
    private companion object { const val KEY = "player" }
}
