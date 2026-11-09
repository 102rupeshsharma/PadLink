package com.ps2gamepad

import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.StickMath
import com.ps2gamepad.controller.StickVector
import com.ps2gamepad.model.GyroAim
import com.ps2gamepad.model.Stick
import com.ps2gamepad.motion.GyroAimEngine
import com.ps2gamepad.motion.MotionSample
import com.ps2gamepad.motion.SensorKind
import com.ps2gamepad.motion.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlin.math.abs
import kotlin.math.sign

/**
 * "Play-testing" gyro aim without a phone (3.1.0).
 *
 * A simulated HAND turns a phone held in landscape at 45° (like playing on a sofa); the phone's
 * gyroscope + accelerometer readings come out at 200 Hz, exactly as Android delivers them, and go
 * through the real [GyroAimEngine] and the 8-bit HID axis. A simulated SHOOTER then turns its
 * camera from the right stick the way most games do: a 20 % inner dead zone, then camera speed
 * proportional to the stick, 180°/s at full stick.
 *
 * These are simulations of the maths, not a substitute for playing on the real phone.
 */
class GyroAimTest {
    /** Phone in landscape (display rotation 1), screen tilted 45° towards the face. */
    private val upScreen = Vec3(0f, 0.7071f, 0.7071f)
    private fun toDevice(s: Vec3) = Vec3(s.y, -s.x, s.z) // inverse of ScreenRemap rotation 1
    private val e1: Vec3 = (Vec3.X - upScreen * (Vec3.X dot upScreen)).normalized()

    /** The game: inner dead zone, then linear camera speed. */
    private class Shooter(val deadZone: Float = 0.2f, val maxTurnDps: Float = 180f) {
        var yaw = 0f; var pitch = 0f
        private fun response(v: Float) = if (abs(v) <= deadZone) 0f else sign(v) * (abs(v) - deadZone) / (1f - deadZone)
        fun frame(stick: StickVector, dt: Float) {
            // what the game receives is the 8-bit HID value
            val x = StickMath.toAxis(stick.x) / 127f
            val y = StickMath.toAxis(stick.y) / 127f
            yaw += response(x) * maxTurnDps * dt
            pitch -= response(y) * maxTurnDps * dt // stick up (negative y) = look up
        }
    }

    private inner class Session(settings: GyroAim = GyroAim(enabled = true), val game: Shooter = Shooter(), val biasRadS: Float = 0f,
                                /** Hand tremor: peak °/s of a real hand's 8-12 Hz shake (0 = robot hand). */
                                val tremorDps: Float = 0f) {
        private val rnd = java.util.Random(42)
        val engine = GyroAimEngine().also { it.settings = settings }
        var t = 0L
        var stick = StickVector.ZERO
        val rad = (Math.PI / 180.0).toFloat()

        /** Hand turns the phone at [yawDps] (right +) and [pitchDps] (look up +) for [seconds]. */
        fun turn(yawDps: Float, pitchDps: Float, seconds: Float) {
            val steps = (seconds * 200).toInt()
            repeat(steps) {
                t += 5_000_000L
                val ts = t / 1e9
                val shakeYaw = tremorDps * (0.6 * kotlin.math.sin(2 * Math.PI * 9.0 * ts) + 0.4 * kotlin.math.sin(2 * Math.PI * 11.3 * ts + 1.0) + 0.3 * rnd.nextGaussian()).toFloat()
                val shakePitch = tremorDps * (0.6 * kotlin.math.sin(2 * Math.PI * 10.1 * ts + 2.0) + 0.4 * kotlin.math.sin(2 * Math.PI * 8.4 * ts) + 0.3 * rnd.nextGaussian()).toFloat()
                val wScreen = upScreen * (-(yawDps + shakeYaw) * rad) + e1 * (-(pitchDps + shakePitch) * rad)
                val w = toDevice(wScreen)
                engine.onSample(MotionSample(SensorKind.ACCELEROMETER, toDevice(upScreen).x * 9.81f, toDevice(upScreen).y * 9.81f, toDevice(upScreen).z * 9.81f, t, 1))
                engine.onSample(MotionSample(SensorKind.GYROSCOPE, w.x + biasRadS, w.y, w.z, t, 1))?.let { stick = it }
                game.frame(stick, 0.005f)
            }
        }
        fun hold(seconds: Float) = turn(0f, 0f, seconds)
    }

    // ------------------------------------------------------------------ the original complaint

    @Test fun oldTiltGyroIgnoredSmallAimTheNewOneDoesNot() {
        // Old 1.3 behaviour: stick = (tilt - 2° dead zone) / 25° -> a 2° correction gives stick 0,
        // a 5° tilt gives 0.12, still inside the game's 20 % dead zone: the camera does not move.
        fun oldStick(tiltDeg: Float) = ((tiltDeg - 2f) / 23f).coerceIn(0f, 1f)
        assertEquals(0f, oldStick(2f), 0f)
        assertTrue("old: a 5° tilt is still inside the game's dead zone", oldStick(5f) < 0.2f)

        // New: turn the phone 2° to the right over half a second (a typical small correction).
        val s = Session()
        s.hold(1f)
        s.turn(4f, 0f, 0.5f)
        val expected = 2f * 180f / GyroAim.FULL_SPEED_DPS // = 3° of camera for 2° of phone
        assertTrue("2° of phone moved the camera ${s.game.yaw}°, expected about $expected°", s.game.yaw in expected * 0.7f..expected * 1.3f)
    }

    // ------------------------------------------------------------------ feel while playing

    @Test fun slowTrackingMovesTheCameraSmoothlyFromTheFirstDegree() {
        for (speed in listOf(1.5f, 3f, 6f)) { // very slow tracking of a walking enemy
            val s = Session()
            s.hold(1f)
            s.turn(speed, 0f, 1f)
            val expected = speed * 180f / GyroAim.FULL_SPEED_DPS
            assertTrue("$speed°/s for 1 s -> camera ${s.game.yaw}° (expected ~$expected°)", s.game.yaw in expected * 0.6f..expected * 1.3f)
        }
    }

    @Test fun cameraFollowsThePhoneTheSameAtAnySpeed() {
        // Turning the phone 10° should turn the camera the same amount whether done slowly or fast:
        // that is what makes it feel like pointing, not like steering.
        val results = listOf(10f, 30f, 80f).map { speed ->
            val s = Session()
            s.hold(1f)
            s.turn(speed, 0f, 10f / speed)
            s.hold(0.3f)
            s.game.yaw
        }
        val mean = results.average().toFloat()
        assertEquals("10° of phone -> 15° of camera", 15f, mean, 2f)
        for (r in results) assertEquals("same result at every speed: $results", mean, r, mean * 0.12f)
    }

    @Test fun cameraStopsTheMomentThePhoneStops() {
        val s = Session()
        s.hold(1f)
        s.turn(40f, 0f, 0.5f)
        val atStop = s.game.yaw
        s.hold(0.05f)
        val after50ms = s.game.yaw
        s.hold(2f)
        assertTrue("overshoot after stopping: ${after50ms - atStop}°", after50ms - atStop < 0.6f)
        assertEquals("no drift once still", after50ms, s.game.yaw, 0.05f)
        assertEquals(StickVector.ZERO, s.stick)
    }

    @Test fun flickTurnsFastAndSaturatesCleanly() {
        val s = Session()
        s.hold(1f)
        s.turn(400f, 0f, 0.2f) // a quick 80° flick
        assertTrue("full stick during a flick", abs(s.stick.x) > 0.99f)
        assertTrue("camera turned at full speed: ${s.game.yaw}°", s.game.yaw > 30f)
    }

    @Test fun upAndDownFollowTheTopEdge() {
        val s = Session()
        s.hold(1f)
        s.turn(0f, 20f, 0.5f) // tilt the top edge towards you = look up
        assertTrue("look up -> camera pitch up: ${s.game.pitch}", s.game.pitch > 5f)
        assertEquals("no sideways drift", 0f, s.game.yaw, 0.3f)
        val inverted = Session(GyroAim(enabled = true, invertY = true))
        inverted.hold(1f)
        inverted.turn(0f, 20f, 0.5f)
        assertTrue(inverted.game.pitch < -5f)
    }

    @Test fun aRestingPhoneWithSensorDriftDoesNotMoveTheCamera() {
        // real gyroscopes report a small constant rotation at rest (bias): learned automatically
        val s = Session(biasRadS = 0.008f) // 0.46 °/s, typical leftover bias of a calibrated phone gyroscope
        s.hold(2f)
        val settled = s.game.yaw
        s.hold(5f)
        assertTrue("drift while learning the bias: $settled°", abs(settled) < 2f)
        assertEquals("no drift after the bias is learned", settled, s.game.yaw, 0.1f)
    }

    @Test fun deadZoneFixMatchesTheGame() {
        // With the fix at the game's dead zone, the game's response is linear from zero speed.
        val off = Session(GyroAim(enabled = true, gameDeadZone = 0f))
        off.hold(1f); off.turn(4f, 0f, 0.5f)
        val on = Session(GyroAim(enabled = true, gameDeadZone = 0.2f))
        on.hold(1f); on.turn(4f, 0f, 0.5f)
        assertEquals("without the fix a small correction is swallowed by the game", 0f, off.game.yaw, 0.01f)
        assertTrue("with the fix it moves: ${on.game.yaw}°", on.game.yaw > 2f)
    }

    @Test fun sensitivityScalesTheCameraTurn() {
        val normal = Session(); normal.hold(1f); normal.turn(10f, 0f, 1f)
        val fast = Session(GyroAim(enabled = true, sensitivity = 2f)); fast.hold(1f); fast.turn(10f, 0f, 1f)
        assertEquals(normal.game.yaw * 2f, fast.game.yaw, normal.game.yaw * 0.15f)
    }

    // ------------------------------------------------------------------ a real (shaky) hand

    @Test fun holdingAimOnATargetWithARealShakyHandKeepsTheCrosshairSteady() {
        // A real hand never holds perfectly still: ~1.5 °/s of 8-12 Hz shake. The camera must not
        // wander or jitter around the target while the player tries to hold aim.
        val s = Session(tremorDps = 1.5f)
        s.hold(1f)
        val yaw0 = s.game.yaw; val pitch0 = s.game.pitch
        var maxDev = 0f
        repeat(300) { s.hold(0.01f); maxDev = maxOf(maxDev, abs(s.game.yaw - yaw0), abs(s.game.pitch - pitch0)) }
        println("PLAY | holding aim 3 s, shaky hand 1.5 deg/s | crosshair wandered at most %.2f deg".format(maxDev))
        assertTrue("crosshair wandered $maxDev°", maxDev < 1.5f)
    }

    @Test fun slowTrackingStillWorksWithAShakyHand() {
        val s = Session(tremorDps = 1.5f)
        s.hold(1f)
        val y0 = s.game.yaw
        s.turn(5f, 0f, 2f) // follow a walking enemy: 10° of phone over 2 s
        val moved = s.game.yaw - y0
        println("PLAY | slow tracking 5 deg/s for 2 s, shaky hand | camera %.1f deg (steady hand: 15 deg)".format(moved))
        assertTrue("tracking with a shaky hand: $moved° (expected about 15°)", moved in 11f..19f)
    }

    /** Prints what a player would see, for the release notes (no assertion). */
    @Test fun printPlaySummary() {
        fun run(yaw: Float, secs: Float): Pair<Float, Float> { val s = Session(); s.hold(1f); s.turn(yaw, 0f, secs); val stop = s.game.yaw; s.hold(0.5f); return stop to s.game.yaw }
        for ((name, yaw, secs) in listOf(Triple("tiny correction 2 deg", 4f, 0.5f), Triple("slow tracking 3 deg/s", 3f, 1f),
            Triple("normal turn 10 deg", 30f, 0.333f), Triple("fast turn 10 deg", 80f, 0.125f), Triple("flick 80 deg", 400f, 0.2f))) {
            val (atStop, after) = run(yaw, secs)
            println("PLAY | %-24s | phone %.1f deg | camera %.1f deg | after stop +%.2f deg".format(name, yaw * secs, after, after - atStop))
        }
    }

    // ------------------------------------------------------------------ touch + gyro together

    @Test fun touchRightStickWinsWhileTouchedThenGyroContinues() {
        val sent = mutableListOf<ByteArray>()
        val input = ControllerInputManager({ r, _ -> sent += r; true })
        input.setGyroStick(StickVector(0.5f, 0f))
        assertEquals(StickMath.toAxis(0.5f), input.state.value.rightX)
        input.setStick(Stick.RIGHT, StickVector(-1f, 0f))
        input.setGyroStick(StickVector(0.6f, 0f))
        assertEquals("thumb on the stick wins", -127, input.state.value.rightX)
        input.setStick(Stick.RIGHT, StickVector.ZERO)
        assertEquals("thumb lifted: gyro again", StickMath.toAxis(0.6f), input.state.value.rightX)
        input.releaseAll()
        assertEquals(0, input.state.value.rightX)
    }
}

/** Real UI, MOCK Bluetooth + MOCK sensors: the Gyro chip on the controller's top bar. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class GyroChipUiTest {
    @get:org.junit.Rule val rule = androidx.compose.ui.test.junit4.createComposeRule()
    private val hid = FakeHid()
    private val motion = FakeMotionSource()
    private lateinit var vm: com.ps2gamepad.ui.MainViewModel

    @org.junit.Before fun setUp() {
        vm = com.ps2gamepad.ui.MainViewModel(hid, kotlinx.coroutines.flow.MutableStateFlow(com.ps2gamepad.model.BluetoothState.ENABLED),
            { emptyList() }, com.ps2gamepad.model.DiagnosticLog(), emptyList(), { 0L }, motionSource = motion)
        rule.setContent { com.ps2gamepad.ui.App(vm, com.ps2gamepad.ui.AppActions({}, {}, {}, {})) }
        rule.runOnIdle {
            vm.onPermissionsChanged(true); vm.onForeground(); vm.register()
            hid.emit(com.ps2gamepad.bluetooth.HidEvent.AppStatusChanged(true))
            hid.emit(com.ps2gamepad.bluetooth.HidEvent.ConnectionChanged(com.ps2gamepad.model.HostDevice("1", "PC"), com.ps2gamepad.bluetooth.HidStateReducer.STATE_CONNECTED))
            vm.navigate(com.ps2gamepad.ui.Screen.CONTROLLER)
        }
    }

    private fun turnRight(n: Int) {
        val up = Vec3(0f, 0.7071f, 0.7071f)
        val dev = { s: Vec3 -> Vec3(s.y, -s.x, s.z) }
        var t = 0L
        repeat(n) {
            t += 5_000_000L
            val a = dev(up)
            motion.listener?.invoke(MotionSample(SensorKind.ACCELEROMETER, a.x * 9.81f, a.y * 9.81f, a.z * 9.81f, t, 1))
            val w = dev(up * -0.5f) // ~29 °/s to the right
            motion.listener?.invoke(MotionSample(SensorKind.GYROSCOPE, w.x, w.y, w.z, t, 1))
        }
    }

    @Test fun gyroChipTurnsGyroAimOnAndOff() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Gyro ON/OFF is in the menu (it stays open)
        rule.onNodeWithTag("chip_gyro").assertTextEquals("Gyro OFF")
        assertTrue(!motion.running)
        rule.onNodeWithTag("chip_gyro").performClick()
        rule.runOnIdle { assertTrue(vm.activeProfile.value.gyroAim.enabled); assertTrue("sensors on", motion.running) }
        turnRight(40)
        rule.waitForIdle()
        val r = com.ps2gamepad.bluetooth.HidReportManager.decode(hid.sent.last())
        assertTrue("turning right pushes the right stick right: z=${r.z}", r.z > 20)
        rule.onNodeWithTag("chip_gyro").performClick()
        rule.runOnIdle { assertTrue("sensors off", !motion.running) }
        rule.waitForIdle()
        assertEquals("right stick centred when gyro turns off", 0, com.ps2gamepad.bluetooth.HidReportManager.decode(hid.sent.last()).z)
    }

    @Test fun sensorsStopInEditModeAndOutsideTheController() {
        rule.runOnIdle { vm.setGyroEnabled(true) }
        rule.runOnIdle { assertTrue(motion.running) }
        rule.runOnIdle { vm.setEditMode(true) }
        rule.runOnIdle { assertTrue("edit mode: off", !motion.running) }
        rule.runOnIdle { vm.setEditMode(false) }
        rule.runOnIdle { assertTrue(motion.running) }
        rule.runOnIdle { vm.navigate(com.ps2gamepad.ui.Screen.HOME) }
        rule.runOnIdle { assertTrue("home screen: off (battery)", !motion.running) }
        rule.runOnIdle { vm.onBackground() }
        rule.runOnIdle { vm.navigate(com.ps2gamepad.ui.Screen.CONTROLLER) }
        rule.runOnIdle { assertTrue("app in background: off", !motion.running) }
    }

}
