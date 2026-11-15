package com.ps2gamepad.controller

import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.Dpad
import com.ps2gamepad.model.GamepadState
import com.ps2gamepad.model.Stick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Software timing of report generation (state -> encode -> hand to the sender queue), in ns.
 * Written under the manager's lock, read by the diagnostics screen. Does NOT include Bluetooth
 * transmission (see SenderStats) or anything on the PC.
 */
class ReportTiming {
    @Volatile var count: Long = 0; private set
    @Volatile var totalNanos: Long = 0; private set
    @Volatile var maxNanos: Long = 0; private set
    fun record(ns: Long) { count++; totalNanos += ns; if (ns > maxNanos) maxNanos = ns }
    val avgMicros: Double get() = if (count == 0L) 0.0 else totalNanos / count / 1000.0
    val maxMicros: Double get() = maxNanos / 1000.0
    fun reset() { count = 0; totalNanos = 0; maxNanos = 0 }
}

/** Where reports go. Returns false when nothing is connected and the report was dropped. */
fun interface ReportSink {
    fun send(report: ByteArray, eventUptimeMs: Long): Boolean
}

/**
 * The single source of truth for the controller state. Every UI control (and the Phase 1 test
 * buttons) changes state only through this class. Each change produces at most one HID report.
 *
 * Controls are responsible for their own pointers and call in with their aggregated result,
 * e.g. "D-pad = up+right" or "Cross pressed". [releaseAll] bumps [resetEpoch]; controls key their
 * pointer tracking on it, so fingers that were down during a reset are ignored until lifted.
 *
 * Thread-safe: all mutations take one lock (touch input arrives on the main thread, while
 * resets can come from Bluetooth callbacks).
 */
class ControllerInputManager(
    private val sink: ReportSink,
    /** Minimum change in axis counts before an analog-only report is sent. 1 = every change. */
    private val axisThreshold: Int = 1,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()

    private val _state = MutableStateFlow(GamepadState.NEUTRAL)
    val state: StateFlow<GamepadState> = _state.asStateFlow()

    private val _resetEpoch = MutableStateFlow(0)
    val resetEpoch: StateFlow<Int> = _resetEpoch.asStateFlow()

    private val pointerCounts = HashMap<String, Int>()
    private val _activePointers = MutableStateFlow(0)
    val activePointers: StateFlow<Int> = _activePointers.asStateFlow()

    private val _lastReport = MutableStateFlow<ByteArray?>(null)
    /** Last report the sink accepted. */
    val lastReport: StateFlow<ByteArray?> = _lastReport.asStateFlow()

    val timing = ReportTiming()

    /** State the host last received; null = unknown, so the next change is always sent. */
    private var lastSent: GamepadState? = null

    /** 3.9.0: send the extended report (diagonal byte) - set from the "Diagonal buttons" setting. */
    @Volatile var extendedReports: Boolean = false

    /** Which controls hold each diagonal button (index 0-7). */
    private val diagonalHolders = HashMap<Int, MutableSet<String>>()

    /** A diagonal button (0 = left ↖ … 7 = right ↘) pressed / released by [source]. */
    fun setDiagonalFrom(source: String, index: Int, pressed: Boolean, eventUptimeMs: Long = clock()) {
        if (index !in 0 until com.ps2gamepad.bluetooth.HidDescriptor.DIAGONAL_COUNT) return
        synchronized(lock) {
            val set = diagonalHolders.getOrPut(index) { HashSet() }
            if (pressed) set.add(source) else set.remove(source)
            mutate(eventUptimeMs) { it.withDiagonal(index, set.isNotEmpty()) }
        }
    }

    /** Which controls currently hold each button (the built-in control and any extra buttons). */
    private val holders = HashMap<ControllerButton, MutableSet<String>>()

    fun setButton(button: ControllerButton, pressed: Boolean, eventUptimeMs: Long = clock()) =
        setButtonFrom(BASE_SOURCE, button, pressed, eventUptimeMs)

    /**
     * Press/release [button] on behalf of one control. The button stays down while ANY control
     * holds it, so releasing an extra "Cross" never cuts a Cross still held on the face buttons.
     */
    fun setButtonFrom(source: String, button: ControllerButton, pressed: Boolean, eventUptimeMs: Long = clock()) {
        synchronized(lock) {
            val set = holders.getOrPut(button) { HashSet() }
            if (pressed) set.add(source) else set.remove(source)
            val held = set.isNotEmpty()
            mutate(eventUptimeMs) { it.withButton(button, held) }
        }
    }

    /**
     * A combo button's current step: exactly [buttons] and [dpad] held by [source], everything else
     * it held is released. One state change, so all buttons of a step reach the PC in ONE report.
     */
    fun setComboFrom(source: String, buttons: Set<ControllerButton>, dpad: Dpad, eventUptimeMs: Long = clock()) {
        synchronized(lock) {
            for (b in ControllerButton.entries) {
                val set = holders.getOrPut(b) { HashSet() }
                if (b in buttons) set.add(source) else set.remove(source)
            }
            if (dpad.isNeutral) dpadHolders.remove(source) else dpadHolders[source] = dpad
            val effective = StickMath.combine(dpadHolders.values)
            mutate(eventUptimeMs) { st ->
                var n = st
                for (b in ControllerButton.entries) n = n.withButton(b, holders[b]?.isNotEmpty() == true)
                n.copy(dpad = effective)
            }
        }
    }

    /** D-pad directions held per control (the D-pad itself and combo buttons); combined like fingers. */
    private val dpadHolders = HashMap<String, Dpad>()

    fun setDpad(dpad: Dpad, eventUptimeMs: Long = clock()) = setDpadFrom(BASE_SOURCE, dpad, eventUptimeMs)

    /** Direction held by one control; the effective D-pad combines all controls. */
    fun setDpadFrom(source: String, dpad: Dpad, eventUptimeMs: Long = clock()) {
        synchronized(lock) {
            if (dpad.isNeutral) dpadHolders.remove(source) else dpadHolders[source] = dpad
            val effective = StickMath.combine(dpadHolders.values)
            mutate(eventUptimeMs) { it.copy(dpad = effective) }
        }
    }

    /** Right stick from touch and from the gyro: touch wins while the thumb is on the stick. */
    private var touchRight = StickVector.ZERO
    private var gyroRight = StickVector.ZERO
    private var touchLeft = StickVector.ZERO

    /**
     * 3.8.0: stick directions held by direction buttons (e.g. "L↖"), per button. While any is held it
     * wins over the thumb (and the gyro); the button pressed last counts. Releasing it returns the stick
     * to the thumb, exactly as before.
     */
    private val stickPushes = mapOf(Stick.LEFT to LinkedHashMap<String, StickVector>(), Stick.RIGHT to LinkedHashMap<String, StickVector>())

    /** [vector] components in [-1, 1], already through the dead zone. */
    fun setStick(stick: Stick, vector: StickVector, eventUptimeMs: Long = clock()) {
        if (stick == Stick.LEFT) {
            synchronized(lock) { touchLeft = vector; applyLeft(eventUptimeMs) }
            return
        }
        synchronized(lock) { touchRight = vector; applyRight(eventUptimeMs) }
    }

    /** A direction button held ([vector]) or released (null) on behalf of [source]. */
    fun setStickPushFrom(source: String, stick: Stick, vector: StickVector?, eventUptimeMs: Long = clock()) {
        synchronized(lock) {
            val m = stickPushes.getValue(stick)
            m.remove(source)
            if (vector != null) m[source] = vector
            if (stick == Stick.LEFT) applyLeft(eventUptimeMs) else applyRight(eventUptimeMs)
        }
    }

    private fun applyLeft(t: Long) {
        val v = stickPushes.getValue(Stick.LEFT).values.lastOrNull() ?: touchLeft
        mutate(t) { it.withStick(Stick.LEFT, StickMath.toAxis(v.x), StickMath.toAxis(v.y)) }
    }

    /** Gyro aiming output for the right stick (any thread). */
    fun setGyroStick(vector: StickVector, eventUptimeMs: Long = clock()) {
        synchronized(lock) { gyroRight = vector; applyRight(eventUptimeMs) }
    }

    private fun applyRight(t: Long) {
        val v = stickPushes.getValue(Stick.RIGHT).values.lastOrNull()
            ?: if (touchRight != StickVector.ZERO) touchRight else gyroRight
        mutate(t) { it.withStick(Stick.RIGHT, StickMath.toAxis(v.x), StickMath.toAxis(v.y)) }
    }

    /** Releases every input, sends a neutral report and invalidates all active touches. */
    fun releaseAll() {
        synchronized(lock) {
            pointerCounts.clear()
            holders.clear()
            dpadHolders.clear()
            diagonalHolders.clear()
            touchRight = StickVector.ZERO
            gyroRight = StickVector.ZERO
            touchLeft = StickVector.ZERO
            stickPushes.values.forEach { it.clear() }
            _activePointers.value = 0
            _state.value = GamepadState.NEUTRAL
            _resetEpoch.value = _resetEpoch.value + 1
            lastSent = null // force the neutral report out even if we think the host has it
            trySend(GamepadState.NEUTRAL, clock())
        }
    }

    /** Host connection is gone: forget what it had, so it gets a full report after reconnecting. */
    fun onHostLost() = synchronized(lock) { lastSent = null }

    /** Diagnostic only: number of fingers currently owned by the control [source]. */
    fun reportPointers(source: String, count: Int) = synchronized(lock) {
        if (count <= 0) pointerCounts.remove(source) else pointerCounts[source] = count
        _activePointers.value = pointerCounts.values.sum()
    }

    companion object { const val BASE_SOURCE = "base" }

    private inline fun mutate(eventUptimeMs: Long, f: (GamepadState) -> GamepadState) {
        synchronized(lock) {
            val next = f(_state.value)
            if (next == _state.value && lastSent == next) return
            _state.value = next
            if (shouldSend(lastSent, next)) trySend(next, eventUptimeMs)
        }
    }

    private fun shouldSend(prev: GamepadState?, next: GamepadState): Boolean {
        if (prev == null) return true
        if (prev.buttons != next.buttons || prev.dpad.hat != next.dpad.hat || prev.diagonals != next.diagonals) return true
        return StickMath.axisChangedSignificantly(prev.leftX, next.leftX, axisThreshold) ||
            StickMath.axisChangedSignificantly(prev.leftY, next.leftY, axisThreshold) ||
            StickMath.axisChangedSignificantly(prev.rightX, next.rightX, axisThreshold) ||
            StickMath.axisChangedSignificantly(prev.rightY, next.rightY, axisThreshold)
    }

    private fun trySend(state: GamepadState, eventUptimeMs: Long) {
        val t0 = System.nanoTime()
        val report = HidReportManager.encode(state, extendedReports)
        val accepted = sink.send(report, eventUptimeMs)
        if (accepted) timing.record(System.nanoTime() - t0)
        if (accepted) {
            lastSent = state
            _lastReport.value = report
        } else {
            lastSent = null
        }
    }
}
