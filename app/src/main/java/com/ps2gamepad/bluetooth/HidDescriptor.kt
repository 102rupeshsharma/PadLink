package com.ps2gamepad.bluetooth

/**
 * HID report descriptor for a generic gamepad. Windows binds its built-in HID game controller
 * driver (HidGame / DirectInput / Raw Input) to it; no custom driver is needed.
 *
 * The descriptor is fixed from Phase 1 onwards. Windows caches it at pairing time, so changing it
 * later would force a re-pair. Phase 1 already declares all 12 buttons that Phase 2 needs.
 *
 * Input report, Report ID 1. 7 bytes on the air: the report ID plus the 6 data bytes below.
 * `BluetoothHidDevice.sendReport(device, 1, data)` adds the ID, so [REPORT_LENGTH] = 6.
 *
 *  byte 0  bit0..7  Buttons 1-8   (Square, Cross, Circle, Triangle, L1, R1, L2, R2)
 *  byte 1  bit0..3  Buttons 9-12  (Select, Start, L3, R3)
 *  byte 1  bit4..7  Hat switch    0..7 = N,NE,E,SE,S,SW,W,NW; 8 = null (centred)
 *  byte 2           X   left stick horizontal   int8 -127..127 (0 = centre, - = left)
 *  byte 3           Y   left stick vertical     int8 -127..127 (- = up)
 *  byte 4           Z   right stick horizontal  int8 -127..127
 *  byte 5           Rz  right stick vertical    int8 -127..127
 */
object HidDescriptor {
    const val REPORT_ID: Int = 1
    const val REPORT_LENGTH: Int = 6
    const val BUTTON_COUNT: Int = 12
    const val HAT_NULL: Int = 8

    const val SDP_NAME = "PadLink Gamepad"
    const val SDP_DESCRIPTION = "PadLink: phone as a Bluetooth controller"
    const val SDP_PROVIDER = "PadLink"

    /** Each line is one HID item: prefix byte (tag/type/size) followed by its data bytes. */
    val REPORT_DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x05,             // Usage (Game Pad)
        0xA1, 0x01,             // Collection (Application)
        0x85, REPORT_ID,        //   Report ID (1)

        // 12 buttons, 1 bit each
        0x05, 0x09,             //   Usage Page (Button)
        0x19, 0x01,             //   Usage Minimum (Button 1)
        0x29, BUTTON_COUNT,     //   Usage Maximum (Button 12)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1 bit)
        0x95, BUTTON_COUNT,     //   Report Count (12)
        0x81, 0x02,             //   Input (Data, Variable, Absolute)

        // Hat switch (D-pad), 4 bits, with a null state for "not pressed"
        0x05, 0x01,             //   Usage Page (Generic Desktop)
        0x09, 0x39,             //   Usage (Hat Switch)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x07,             //   Logical Maximum (7)
        0x35, 0x00,             //   Physical Minimum (0)
        0x46, 0x3B, 0x01,       //   Physical Maximum (315 degrees)
        0x65, 0x14,             //   Unit (English Rotation: degrees)
        0x75, 0x04,             //   Report Size (4 bits)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x42,             //   Input (Data, Variable, Absolute, Null State)

        // Reset the unit and physical range so they do not apply to the axes
        0x65, 0x00,             //   Unit (None)
        0x45, 0x00,             //   Physical Maximum (0) -> physical range = logical range

        // Two analog sticks: X/Y (left), Z/Rz (right), signed 8-bit
        0x09, 0x30,             //   Usage (X)
        0x09, 0x31,             //   Usage (Y)
        0x09, 0x32,             //   Usage (Z)
        0x09, 0x35,             //   Usage (Rz)
        0x15, 0x81,             //   Logical Minimum (-127)
        0x25, 0x7F,             //   Logical Maximum (127)
        0x75, 0x08,             //   Report Size (8 bits)
        0x95, 0x04,             //   Report Count (4)
        0x81, 0x02,             //   Input (Data, Variable, Absolute)

        0xC0,                   // End Collection
    )

    // ------------------------------------------------------------------ 3.9.0: diagonal buttons (opt-in)

    /** 8 extra buttons, one per stick diagonal: left ↖ ↗ ↙ ↘ = buttons 13-16, right ↖ ↗ ↙ ↘ = 17-20. */
    const val DIAGONAL_COUNT: Int = 8
    const val FIRST_DIAGONAL_BUTTON: Int = BUTTON_COUNT + 1
    /** Report length with the diagonal byte appended after the axes. */
    const val REPORT_LENGTH_EXTENDED: Int = REPORT_LENGTH + 1

    /**
     * The same descriptor with 8 more buttons APPENDED after the axes. Everything the standard one has
     * keeps its exact place and number (buttons 1-12, hat, X/Y/Z/Rz = axes 0-3), so existing emulator
     * mappings stay. Only used when the user turns on "Diagonal buttons for emulators"; Windows reads a
     * descriptor at pairing, so switching it needs pairing again once.
     */
    val REPORT_DESCRIPTOR_EXTENDED: ByteArray = REPORT_DESCRIPTOR.copyOfRange(0, REPORT_DESCRIPTOR.size - 1) + bytes(
        0x05, 0x09,             //   Usage Page (Button)
        0x19, FIRST_DIAGONAL_BUTTON, //   Usage Minimum (Button 13)
        0x29, BUTTON_COUNT + DIAGONAL_COUNT, // Usage Maximum (Button 20)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1 bit)
        0x95, DIAGONAL_COUNT,   //   Report Count (8)
        0x81, 0x02,             //   Input (Data, Variable, Absolute)
        0xC0,                   // End Collection
    )

    fun descriptor(extended: Boolean): ByteArray = if (extended) REPORT_DESCRIPTOR_EXTENDED else REPORT_DESCRIPTOR
    fun reportLength(extended: Boolean): Int = if (extended) REPORT_LENGTH_EXTENDED else REPORT_LENGTH

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
