package com.ps2gamepad.ui

import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ConnectionState
import com.ps2gamepad.model.HidState
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.PermissionState
import com.ps2gamepad.model.RegistrationState

/** The single thing the user should do next on the simple home screen. */
enum class SimpleAction { ALLOW_PERMISSION, TURN_ON_BLUETOOTH, NONE_WAIT, RETRY_SETUP, MAKE_VISIBLE, CONNECT_PC, OPEN_CONTROLLER, CONNECT }

/** One plain-language status: a short title, one sentence of help, a colour and one main action. */
data class SimpleStatus(
    val title: String,
    val help: String,
    val severity: Severity,
    val action: SimpleAction,
    val actionLabel: String,
)

/**
 * Pure mapping from the real state to what the simple home screen shows. "Connected" appears only
 * when the Bluetooth stack has confirmed the connection.
 */
fun simpleStatus(
    bt: BluetoothState,
    perm: PermissionState,
    hid: HidState,
    knownPc: HostDevice?,
    phoneName: String,
    /** The user tapped Connect in this session (3.3.0: nothing starts before that). */
    started: Boolean = true,
    /** Connect was tapped and the connection is not there yet. */
    connecting: Boolean = false,
): SimpleStatus = when {
    bt == BluetoothState.UNAVAILABLE -> SimpleStatus(
        "This phone has no Bluetooth", "The app cannot work on this device.", Severity.BAD, SimpleAction.NONE_WAIT, "")
    perm != PermissionState.GRANTED -> SimpleStatus(
        "Allow Bluetooth access", "Tap the button below and choose Allow (Nearby devices).",
        Severity.WARN, SimpleAction.ALLOW_PERMISSION, "Allow")
    bt != BluetoothState.ENABLED -> SimpleStatus(
        "Bluetooth is off", "Turn on Bluetooth on the phone.", Severity.BAD, SimpleAction.TURN_ON_BLUETOOTH, "Turn on Bluetooth")
    hid.connection is ConnectionState.Connected -> {
        val c = hid.connection as ConnectionState.Connected
        SimpleStatus(
            "Connected to ${c.host.displayName} ✓", "You can play now. Keep this app open while playing.",
            Severity.GOOD, SimpleAction.OPEN_CONTROLLER, "Open Controller")
    }
    connecting -> SimpleStatus(
        if (knownPc != null) "Connecting to ${knownPc.displayName}…" else "Waiting for the laptop…",
        if (knownPc != null) "Make sure Bluetooth is on on the laptop."
        else "On the laptop: Settings → Bluetooth & devices → Add device → Bluetooth → choose \"$phoneName\".",
        Severity.INFO, SimpleAction.NONE_WAIT, "")
    !started && hid.registration !is RegistrationState.Failed -> SimpleStatus(
        "Not connected",
        if (knownPc != null) "Turn on Bluetooth on ${knownPc.displayName}, then tap Connect. Another laptop? Tap \"Choose another laptop\"."
        else "Tap Connect to choose your laptop or pair a new one.",
        Severity.WARN, SimpleAction.CONNECT, "Connect")
    hid.registration is RegistrationState.Failed -> SimpleStatus(
        "Setup failed", "The phone refused to start the gamepad (${(hid.registration as RegistrationState.Failed).reason}). Tap Try again.",
        Severity.BAD, SimpleAction.RETRY_SETUP, "Try again")
    hid.registration != RegistrationState.Registered -> SimpleStatus(
        "Getting ready…", "Starting the gamepad. This takes a second.", Severity.INFO, SimpleAction.NONE_WAIT, "")
    else -> when (val c = hid.connection) {
        is ConnectionState.Connected -> SimpleStatus(
            "Connected to ${c.host.displayName} ✓", "You can play now. Keep this app open while playing.",
            Severity.GOOD, SimpleAction.OPEN_CONTROLLER, "Open Controller")
        is ConnectionState.Connecting -> SimpleStatus(
            "Connecting to ${c.host.displayName}…", "Make sure Bluetooth is on on the laptop.", Severity.INFO, SimpleAction.NONE_WAIT, "")
        else -> if (knownPc != null) SimpleStatus(
            "Not connected",
            "Turn on Bluetooth on ${knownPc.displayName} and tap Connect. If it keeps failing, remove \"$phoneName\" in the laptop's Bluetooth settings and pair again.",
            Severity.WARN, SimpleAction.CONNECT, "Connect")
        else SimpleStatus(
            "Ready to pair",
            "Tap Connect, then on the laptop: Settings → Bluetooth & devices → Add device → Bluetooth → choose \"$phoneName\".",
            Severity.WARN, SimpleAction.CONNECT, "Connect")
    }
}
