package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.ReportSink
import com.ps2gamepad.controller.StickMapping
import com.ps2gamepad.controller.StickVector
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.Dpad
import com.ps2gamepad.model.Hat
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.Stick
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/** Vector at [deg] screen degrees (0 = right, 90 = down) with strength [s]. */
private fun at(deg: Float, s: Float = 1f): StickVector {
    val r = Math.toRadians(deg.toDouble())
    return StickVector((cos(r) * s).toFloat(), (sin(r) * s).toFloat())
}

/** 3.10.0: which of the 8 directions a stick is in (pure, with hysteresis). */
class StickMappingTest {
    private val centres = mapOf(
        Hat.RIGHT to 0f, Hat.DOWN_RIGHT to 45f, Hat.DOWN to 90f, Hat.DOWN_LEFT to 135f,
        Hat.LEFT to 180f, Hat.UP_LEFT to 225f, Hat.UP to 270f, Hat.UP_RIGHT to 315f,
    )

    @Test fun everyDirectionAtItsCentre() {
        for ((h, d) in centres) assertEquals(h.name, h, StickMapping.direction(at(d), Hat.NEUTRAL))
    }

    @Test fun everyDirectionAcrossItsWholeSectorFromNeutral() {
        for ((h, d) in centres) for (off in listOf(-22f, -15f, -5f, 0f, 5f, 15f, 22f)) {
            assertEquals("${h.name} at ${d + off}", h, StickMapping.direction(at(d + off), Hat.NEUTRAL))
        }
    }

    @Test fun wrapAroundRight() {
        assertEquals(Hat.RIGHT, StickMapping.direction(at(355f), Hat.NEUTRAL))
        assertEquals(Hat.RIGHT, StickMapping.direction(at(-10f), Hat.NEUTRAL))
        assertEquals(Hat.RIGHT, StickMapping.direction(at(359.9f), Hat.NEUTRAL))
    }

    @Test fun aLightTouchSendsNothing() {
        assertEquals(Hat.NEUTRAL, StickMapping.direction(StickVector.ZERO, Hat.NEUTRAL))
        assertEquals(Hat.NEUTRAL, StickMapping.direction(at(225f, StickMapping.ENGAGE - 0.01f), Hat.NEUTRAL))
        assertEquals(Hat.UP_LEFT, StickMapping.direction(at(225f, StickMapping.ENGAGE + 0.01f), Hat.NEUTRAL))
    }

    @Test fun strengthHysteresisKeepsADirectionUntilClearlyLetGo() {
        val mid = (StickMapping.ENGAGE + StickMapping.RELEASE) / 2
        assertEquals("not yet engaged", Hat.NEUTRAL, StickMapping.direction(at(270f, mid), Hat.NEUTRAL))
        assertEquals("engaged: stays", Hat.UP, StickMapping.direction(at(270f, mid), Hat.UP))
        assertEquals(Hat.NEUTRAL, StickMapping.direction(at(270f, StickMapping.RELEASE - 0.01f), Hat.UP))
    }

    @Test fun angleHysteresisAtASectorEdge() {
        // UP is 270; the sector edge with UP_RIGHT is 292.5; hysteresis keeps UP until 302.5
        assertEquals(Hat.UP, StickMapping.direction(at(300f), Hat.UP))
        assertEquals(Hat.UP_RIGHT, StickMapping.direction(at(305f), Hat.UP))
        // and back: UP_RIGHT is kept down to 282.5
        assertEquals(Hat.UP_RIGHT, StickMapping.direction(at(285f), Hat.UP_RIGHT))
        assertEquals(Hat.UP, StickMapping.direction(at(280f), Hat.UP_RIGHT))
    }

    @Test fun wobbleAtAnEdgeNeverFlips() {
        var h = StickMapping.direction(at(225f), Hat.NEUTRAL) // UP_LEFT
        val noise = listOf(-8f, 6f, -7f, 8f, -5f, 7f, -8f, 4f)
        for (n in noise) h = StickMapping.direction(at(247.5f + n), h) // around the UP_LEFT / UP edge
        assertEquals(Hat.UP_LEFT, h)
    }

    @Test fun badInputIsNeutral() {
        assertEquals(Hat.NEUTRAL, StickMapping.direction(StickVector(Float.NaN, 0f), Hat.UP))
        assertEquals(Hat.NEUTRAL, StickMapping.direction(StickVector(Float.POSITIVE_INFINITY, 0f), Hat.NEUTRAL))
    }

    @Test fun straightDirectionsUseOneAxisDiagonalsNone() {
        assertEquals(StickVector(-1f, 0f), StickMapping.axes(Hat.LEFT))
        assertEquals(StickVector(1f, 0f), StickMapping.axes(Hat.RIGHT))
        assertEquals(StickVector(0f, -1f), StickMapping.axes(Hat.UP))
        assertEquals(StickVector(0f, 1f), StickMapping.axes(Hat.DOWN))
        for (h in listOf(Hat.UP_LEFT, Hat.UP_RIGHT, Hat.DOWN_LEFT, Hat.DOWN_RIGHT, Hat.NEUTRAL)) assertEquals(StickVector.ZERO, StickMapping.axes(h))
    }
}

/** 3.10.0: what the PC receives in Mapping mode (the emulator's binding boxes record these). */
class MappingModeInputTest {
    private val sent = mutableListOf<ByteArray>()
    // the ramp's timing is tested in MappingRampTest; here its steps run at once, so every report is deterministic
    private val m = ControllerInputManager(ReportSink { r, _ -> sent += r.copyOf(); true }, delayed = { _, task -> task() }).apply { extendedReports = true }
    private fun last() = HidReportManager.decode(sent.last())

    /** The code an emulator would record for the current report. */
    private fun code(): String {
        val d = last()
        val parts = mutableListOf<String>()
        if (d.x != 0) parts += "Axis 0" + if (d.x < 0) "-" else "+"
        if (d.y != 0) parts += "Axis 1" + if (d.y < 0) "-" else "+"
        if (d.z != 0) parts += "Axis 2" + if (d.z < 0) "-" else "+"
        if (d.rz != 0) parts += "Axis 3" + if (d.rz < 0) "-" else "+"
        for (i in 0 until 8) if (d.diagonals and (1 shl i) != 0) parts += "Button ${12 + i}"
        if (d.buttons != 0) parts += "buttons=${d.buttons}"
        return parts.joinToString(" & ").ifEmpty { "nothing" }
    }

    private val expectedLeft = linkedMapOf(
        180f to "Axis 0-", 0f to "Axis 0+", 270f to "Axis 1-", 90f to "Axis 1+",
        225f to "Button 12", 315f to "Button 13", 135f to "Button 14", 45f to "Button 15",
    )
    private val expectedRight = linkedMapOf(
        180f to "Axis 2-", 0f to "Axis 2+", 270f to "Axis 3-", 90f to "Axis 3+",
        225f to "Button 16", 315f to "Button 17", 135f to "Button 18", 45f to "Button 19",
    )

    @Test fun all16DirectionsOfBothSticksGiveExactlyOneDifferentCode() {
        m.setMappingMode(true)
        val seen = mutableListOf<String>()
        for ((stick, expected) in listOf(Stick.LEFT to expectedLeft, Stick.RIGHT to expectedRight)) {
            for ((deg, want) in expected) {
                m.setStick(stick, at(deg))
                val got = code()
                assertEquals("${stick.name} at $deg", want, got)
                seen += got
                m.setStick(stick, StickVector.ZERO)
                assertEquals("lifting the thumb sends nothing", "nothing", code())
            }
        }
        assertEquals("16 different codes", 16, seen.toSet().size)
    }

    @Test fun movingFromADiagonalToStraightIsOneCleanReport() {
        m.setMappingMode(true)
        m.setStick(Stick.LEFT, at(225f))
        assertEquals("Button 12", code())
        val before = sent.size
        m.setStick(Stick.LEFT, at(270f))
        assertEquals("Axis 1-", code())
        val first = HidReportManager.decode(sent[before])
        assertEquals("the first report already has the diagonal released", 0, first.diagonals)
        assertTrue("…and the up axis set (one clean change)", first.y < 0 && first.x == 0)
    }

    @Test fun sweepingAroundTheStickNeverSendsTwoCodesAtOnce() {
        m.setMappingMode(true)
        var deg = 0f
        while (deg < 720f) {
            m.setStick(Stick.RIGHT, at(deg))
            val c = code()
            assertFalse("at $deg: $c", c.contains("&"))
            deg += 3f
        }
    }

    @Test fun bothSticksAtOnceAreIndependent() {
        m.setMappingMode(true)
        m.setStick(Stick.LEFT, at(225f))
        m.setStick(Stick.RIGHT, at(45f))
        assertEquals("Button 12 & Button 19", code())
        m.setStick(Stick.LEFT, StickVector.ZERO)
        assertEquals("Button 19", code())
    }

    @Test fun otherButtonsAndTheDpadAreUnaffected() {
        m.setMappingMode(true)
        m.setButton(ControllerButton.CROSS, true)
        m.setDpad(Dpad.of(Hat.LEFT))
        m.setStick(Stick.LEFT, at(315f))
        val d = last()
        assertEquals(ControllerButton.CROSS.mask, d.buttons)
        assertEquals(Hat.LEFT.value, d.hat)
        assertEquals(1 shl 1, d.diagonals)
        m.setButton(ControllerButton.CROSS, false)
        assertEquals("the diagonal stays held", 1 shl 1, last().diagonals)
    }

    @Test fun offMeansExactlyTheNormalAnalogSticks() {
        val normal = ControllerInputManager(ReportSink { r, _ -> sent += r.copyOf(); true })
        for (v in listOf(at(225f, 0.7f), StickVector(0.3f, -0.2f), at(10f, 0.95f))) {
            normal.setStick(Stick.LEFT, v)
            val expected = sent.last().copyOf()
            m.setStick(Stick.LEFT, v)
            assertEquals(expected.toList(), sent.last().copyOf(6).toList())
            assertEquals(0, last().diagonals)
        }
    }

    @Test fun withoutDiagonalButtonsMappingStaysOff() {
        m.extendedReports = false
        m.setMappingMode(true)
        m.setStick(Stick.LEFT, at(225f, 0.8f))
        val d = HidReportManager.decode(sent.last())
        assertTrue("normal analog diagonal", d.x < 0 && d.y < 0)
        assertEquals(6, sent.last().size)
    }

    @Test fun switchingWhileTheStickIsHeldAppliesAtOnce() {
        m.setStick(Stick.LEFT, at(135f, 0.9f))
        assertTrue(last().x < 0 && last().y > 0)
        m.setMappingMode(true)
        assertEquals("Button 14", code())
        m.setMappingMode(false)
        assertTrue("analog again", last().x < 0 && last().y > 0)
        assertEquals(0, last().diagonals)
    }

    @Test fun theGyroCannotProduceCodesWhileMapping() {
        m.setMappingMode(true)
        m.setGyroStick(StickVector(1f, 1f))
        assertEquals("nothing", code())
        m.setStick(Stick.RIGHT, at(270f))
        assertEquals("the thumb still works", "Axis 3-", code())
        m.setStick(Stick.RIGHT, StickVector.ZERO)
        m.setMappingMode(false)
        assertTrue("gyro back after mapping", last().z > 0)
    }

    @Test fun anOwnDiagonalButtonAndMappingShareTheBitSafely() {
        m.setMappingMode(true)
        m.setDiagonalFrom("own", 0, true) // an Own-button L↖ is held
        m.setStick(Stick.LEFT, at(225f)) // and the stick is up-left too
        assertEquals(1, last().diagonals)
        m.setStick(Stick.LEFT, StickVector.ZERO)
        assertEquals("still held by the button", 1, last().diagonals)
        m.setDiagonalFrom("own", 0, false)
        assertEquals(0, last().diagonals)
    }

    @Test fun aMovesTheStickDirectionButtonAlsoGivesItsCode() {
        m.setMappingMode(true)
        m.setStickPushFrom("btn", Stick.RIGHT, StickVector(-1f, 1f)) // R↙
        assertEquals("Button 18", code())
        m.setStickPushFrom("btn", Stick.RIGHT, null)
        assertEquals("nothing", code())
    }

    @Test fun releaseAllClearsEverythingAndTheNextTouchStartsFresh() {
        m.setMappingMode(true)
        m.setStick(Stick.LEFT, at(225f))
        m.releaseAll()
        assertEquals("nothing", code())
        val mid = (StickMapping.ENGAGE + StickMapping.RELEASE) / 2
        m.setStick(Stick.LEFT, at(225f, mid))
        assertEquals("a light touch after the reset is not a held direction", "nothing", code())
        assertTrue("mapping mode itself stays on", m.isMappingMode)
    }

    @Test fun lightTouchesNeverTriggerACode() {
        m.setMappingMode(true)
        for (d in 0 until 360 step 15) {
            m.setStick(Stick.LEFT, at(d.toFloat(), 0.3f))
            assertEquals("at $d", "nothing", code())
        }
    }
}

/** Real UI (landscape), MOCK Bluetooth: Mapping mode in the ☰ menu, and dragging the real joysticks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class MappingModeUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private lateinit var vm: MainViewModel

    private fun start(diagonals: Boolean) {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle {
            vm.onPermissionsChanged(true); vm.onForeground()
            if (diagonals) vm.setDiagonalButtons(true)
            vm.register()
            hid.emit(HidEvent.AppStatusChanged(true)); hid.emit(HidEvent.ConnectionChanged(HostDevice("1", "PC"), HidStateReducer.STATE_CONNECTED))
            vm.navigate(Screen.CONTROLLER)
        }
    }

    private fun toggleMapping() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("chip_Mapping").performClick()
    }

    /** Drags [tag] like a thumb: lands in the middle, slides 120 px towards [deg]. Returns the report while held. */
    private fun drag(tag: String, deg: Float): HidReportManager.Decoded {
        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val start = Offset(b.center.x - r.left, b.center.y - r.top)
        val dir = at(deg)
        root.performTouchInput {
            down(0, start)
            for (k in 1..6) moveTo(0, start + Offset(dir.x * 20f * k, dir.y * 20f * k))
        }
        rule.waitForIdle()
        Thread.sleep(250) // hold: the ramp (on its own timer) reaches full
        rule.waitForIdle()
        val d = HidReportManager.decode(hid.sent.last())
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        return d
    }

    @Test fun mappingModeTurnsOnShowsTheBannerAndTurnsOff() {
        start(diagonals = true)
        toggleMapping()
        rule.runOnIdle { assertTrue(vm.mappingMode.value) }
        rule.onNodeWithTag("mappingBanner").assertIsDisplayed()
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithText("Map ON").assertIsDisplayed()
        rule.onNodeWithTag("chip_Mapping").performClick()
        rule.runOnIdle { assertFalse(vm.mappingMode.value) }
        rule.onNodeWithTag("mappingBanner").assertDoesNotExist()
    }

    @Test fun draggingTheLeftJoystickUpLeftGivesButton12Only() {
        start(diagonals = true)
        toggleMapping()
        val d = drag("stick_left", 225f)
        assertEquals(1, d.diagonals)
        assertEquals("no axis movement, so the emulator binds the button", listOf(0, 0, 0, 0), listOf(d.x, d.y, d.z, d.rz))
        assertEquals(0, HidReportManager.decode(hid.sent.last()).diagonals)
    }

    @Test fun draggingBothJoysticksInAll8DirectionsGives16DifferentCodes() {
        start(diagonals = true)
        toggleMapping()
        val codes = mutableSetOf<String>()
        for (tag in listOf("stick_left", "stick_right")) for (deg in listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)) {
            val d = drag(tag, deg)
            // exactly ONE signal: one full axis, or one diagonal button (what an emulator box needs)
            val signals = listOf(d.x, d.y, d.z, d.rz).count { it != 0 } + Integer.bitCount(d.diagonals)
            assertEquals("$tag $deg sends one signal", 1, signals)
            assertTrue("axes are full or zero", listOf(d.x, d.y, d.z, d.rz).all { it == 0 || it == 127 || it == -127 })
            val c = "x${d.x}y${d.y}z${d.z}rz${d.rz}d${d.diagonals}"
            assertTrue("$tag $deg duplicated: $c", codes.add(c))
        }
        assertEquals(16, codes.size)
    }

    @Test fun withoutDiagonalButtonsItExplainsAndStaysOff() {
        start(diagonals = false)
        toggleMapping()
        rule.runOnIdle { assertFalse(vm.mappingMode.value) }
        rule.onNodeWithTag("mappingUnavailable").assertIsDisplayed()
        rule.onNodeWithTag("mappingBanner").assertDoesNotExist()
        val d = drag("stick_left", 225f)
        assertTrue("normal analog stick", d.x < 0 && d.y < 0)
    }

    @Test fun leavingTheControllerTurnsMappingOff() {
        start(diagonals = true)
        toggleMapping()
        rule.runOnIdle { vm.navigate(Screen.HOME) }
        rule.runOnIdle { assertFalse(vm.mappingMode.value); assertFalse(vm.input.isMappingMode) }
    }

    @Test fun turningDiagonalButtonsOffTurnsMappingOff() {
        start(diagonals = true)
        toggleMapping()
        rule.runOnIdle { vm.setDiagonalButtons(false) }
        rule.runOnIdle { assertFalse(vm.mappingMode.value); assertFalse(vm.input.isMappingMode) }
    }

    @Test fun otherButtonsStillWorkInMappingMode() {
        start(diagonals = true)
        toggleMapping()
        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag("btn_CROSS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(0, Offset(b.center.x - r.left, b.center.y - r.top)) }
        rule.waitForIdle()
        assertEquals(ControllerButton.CROSS.mask, HidReportManager.decode(hid.sent.last()).buttons)
        root.performTouchInput { up(0) }
    }
}
