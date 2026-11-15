package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidDescriptor
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.ReportSink
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.ExtraButton
import com.ps2gamepad.model.GamepadState
import com.ps2gamepad.model.Hat
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.Stick
import com.ps2gamepad.model.StickDirection
import com.ps2gamepad.settings.DefaultProfiles
import com.ps2gamepad.settings.InMemoryTextStorage
import com.ps2gamepad.settings.ProfileCodec
import com.ps2gamepad.settings.SettingsRepository
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 3.9.0, requested: "each direction of each stick must map to its own input in the emulator". Diagonals get
 * their own buttons (13-20 = emulator "Button 12-19") in an opt-in descriptor; the standard one is untouched.
 */
class DiagonalDescriptorTest {
    private fun inputBits(d: ByteArray): Int {
        var i = 0; var size = 0; var count = 0; var bits = 0
        while (i < d.size) {
            val p = d[i].toInt() and 0xFF
            val n = when (p and 3) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            var v = 0
            for (k in 0 until n) v = v or ((d[i + 1 + k].toInt() and 0xFF) shl (8 * k))
            when (p and 0xFC) { 0x74 -> size = v; 0x94 -> count = v; 0x80 -> bits += size * count }
            i += 1 + n
        }
        assertEquals("ends on an item boundary", d.size, i)
        return bits
    }

    @Test fun standardDescriptorIsUnchanged() {
        assertEquals("6 data bytes, as every paired PC knows it", 48, inputBits(HidDescriptor.REPORT_DESCRIPTOR))
        assertEquals(6, HidDescriptor.REPORT_LENGTH)
        assertEquals(0xC0.toByte(), HidDescriptor.REPORT_DESCRIPTOR.last())
    }

    @Test fun extendedDescriptorOnlyAppendsEightButtons() {
        val std = HidDescriptor.REPORT_DESCRIPTOR
        val ext = HidDescriptor.REPORT_DESCRIPTOR_EXTENDED
        assertArrayEquals("every existing item keeps its place (buttons 1-12, hat, axes 0-3)", std.copyOfRange(0, std.size - 1), ext.copyOfRange(0, std.size - 1))
        assertEquals(56, inputBits(ext))
        assertEquals(7, HidDescriptor.REPORT_LENGTH_EXTENDED)
        val tail = ext.copyOfRange(std.size - 1, ext.size).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x05, 0x09, 0x19, 13, 0x29, 20, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 8, 0x81, 0x02, 0xC0), tail)
    }

    @Test fun encodingStandardAndExtended() {
        val s = GamepadState(buttons = ControllerButton.CROSS.mask, leftX = -127).withDiagonal(0, true).withDiagonal(7, true)
        val std = HidReportManager.encode(s)
        assertEquals("diagonals are never in the standard report", 6, std.size)
        val ext = HidReportManager.encode(s, extended = true)
        assertEquals(7, ext.size)
        assertArrayEquals("first 6 bytes identical", std, ext.copyOf(6))
        val d = HidReportManager.decode(ext)
        assertEquals(0b1000_0001, d.diagonals)
        assertEquals(ControllerButton.CROSS.mask, d.buttons)
        assertEquals(-127, d.x)
    }

    @Test fun reportsAlwaysFitTheRegisteredDescriptor() {
        val ext = HidReportManager.encode(GamepadState().withDiagonal(3, true), extended = true)
        assertEquals(6, HidReportManager.fit(ext, extended = false).size)
        val std = HidReportManager.encode(GamepadState())
        val grown = HidReportManager.fit(std, extended = true)
        assertEquals(7, grown.size); assertEquals(0, grown[6].toInt())
    }

    @Test fun aDiagonalChangeIsADigitalChange() {
        val a = HidReportManager.encode(GamepadState(), extended = true)
        val b = HidReportManager.encode(GamepadState().withDiagonal(2, true), extended = true)
        assertFalse(HidReportManager.sameDigitalState(a, b))
        assertTrue(HidReportManager.sameDigitalState(a, a.copyOf()))
    }

    @Test fun eachDiagonalHasItsOwnButton() {
        val names = listOf(Stick.LEFT, Stick.RIGHT).flatMap { st ->
            listOf(Hat.UP_LEFT, Hat.UP_RIGHT, Hat.DOWN_LEFT, Hat.DOWN_RIGHT).map { StickDirection(st, it).emulatorButtonName }
        }
        assertEquals((12..19).map { "Button $it" }, names)
        assertEquals((0..7).toList(), listOf(Stick.LEFT, Stick.RIGHT).flatMap { st ->
            listOf(Hat.UP_LEFT, Hat.UP_RIGHT, Hat.DOWN_LEFT, Hat.DOWN_RIGHT).map { StickDirection(st, it).diagonalIndex }
        })
        for (h in listOf(Hat.UP, Hat.DOWN, Hat.LEFT, Hat.RIGHT)) assertNull(StickDirection(Stick.LEFT, h).diagonalIndex)
    }
}

class DiagonalInputTest {
    private val sent = mutableListOf<ByteArray>()
    private val m = ControllerInputManager(ReportSink { r, _ -> sent += r.copyOf(); true })

    @Test fun extendedReportsCarryTheDiagonalAndNothingElse() {
        m.extendedReports = true
        m.setDiagonalFrom("a", 5, true) // right ↗
        val d = HidReportManager.decode(sent.last())
        assertEquals(7, sent.last().size)
        assertEquals(1 shl 5, d.diagonals)
        assertEquals("no stick moves", listOf(0, 0, 0, 0), listOf(d.x, d.y, d.z, d.rz))
        assertEquals(0, d.buttons)
        m.setDiagonalFrom("a", 5, false)
        assertEquals(0, HidReportManager.decode(sent.last()).diagonals)
    }

    @Test fun standardReportsStaySixBytes() {
        m.setButton(ControllerButton.L1, true)
        assertEquals(6, sent.last().size)
    }

    @Test fun twoHoldersKeepItPressedAndReleaseAllClearsIt() {
        m.extendedReports = true
        m.setDiagonalFrom("a", 0, true); m.setDiagonalFrom("b", 0, true)
        m.setDiagonalFrom("a", 0, false)
        assertEquals(1, HidReportManager.decode(sent.last()).diagonals)
        m.releaseAll()
        assertEquals(0, HidReportManager.decode(sent.last()).diagonals)
    }

    @Test fun outOfRangeIndexIsIgnored() {
        m.extendedReports = true
        val before = sent.size
        m.setDiagonalFrom("a", 8, true); m.setDiagonalFrom("a", -1, true)
        assertEquals(before, sent.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagonalSettingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun offByDefaultSoEveryPairedPcKeepsWorking() = runTest(dispatcher) {
        val hid = FakeHid()
        val vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        runCurrent()
        vm.onPermissionsChanged(true); vm.register(); runCurrent()
        assertTrue(hid.registeredExtended.isNotEmpty() && hid.registeredExtended.none { it })
        assertFalse(vm.input.extendedReports)
    }

    @Test fun switchingOnStopsTheGamepadSoTheNextConnectUsesIt() = runTest(dispatcher) {
        val hid = FakeHid()
        val vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        runCurrent()
        vm.onPermissionsChanged(true); vm.register(); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        vm.setDiagonalButtons(true); runCurrent()
        assertTrue(vm.profiles.value.diagonalButtons)
        assertTrue(hid.extended); assertTrue(vm.input.extendedReports)
        assertEquals("old registration removed", 1, hid.unregisterCalls)
        assertTrue(vm.log.lines.value.any { it.contains("pair again") })
        vm.register(); runCurrent()
        assertEquals(false, hid.registeredExtended.first())
        assertEquals("the next registration has the diagonal buttons", true, hid.registeredExtended.last())
    }

    @Test fun theSavedChoiceIsUsedBeforeTheFirstRegistration() = runTest(dispatcher) {
        val saved = ProfileCodec.encode(DefaultProfiles.store().copy(diagonalButtons = true))
        val hid = FakeHid()
        val vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L },
            settingsRepository = SettingsRepository(InMemoryTextStorage(saved), this))
        vm.onPermissionsChanged(true); vm.register()
        advanceUntilIdle()
        assertTrue("registered", hid.registeredExtended.isNotEmpty())
        assertTrue("every registration had the diagonal buttons", hid.registeredExtended.all { it })
        assertTrue(vm.input.extendedReports)
    }
}

/** Real UI (landscape), MOCK Bluetooth: switch it on, make L↖ its own button, press it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class DiagonalButtonsUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle {
            vm.onPermissionsChanged(true); vm.onForeground()
            vm.setDiagonalButtons(true) // as if switched on and paired again
            vm.register()
            hid.emit(HidEvent.AppStatusChanged(true)); hid.emit(HidEvent.ConnectionChanged(HostDevice("1", "PC"), HidStateReducer.STATE_CONNECTED))
            vm.navigate(Screen.CONTROLLER)
        }
    }

    private fun press(tag: String): HidReportManager.Decoded {
        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(0, Offset(b.center.x - r.left, b.center.y - r.top)) }
        rule.waitForIdle()
        val d = HidReportManager.decode(hid.sent.last())
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        return d
    }

    @Test fun theSettingIsInTheSettingsPanel() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("chip_Settings").performClick()
        rule.onNodeWithTag("set_diagonals").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("diagonalsHint").performScrollTo().assertIsDisplayed()
    }

    @Test fun ownButtonInTheEditorThenPressingSendsOnlyThatButton() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("chip_Edit").performClick()
        rule.onNodeWithTag("chip_+ Stick ↗").performClick() // left ↖ by default
        rule.onNodeWithTag("dir_mode_button").performClick()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        val e = vm.activeProfile.value.extraButtons.single()
        assertTrue(e.stickDirection!!.ownButton)

        val d = press("extra_${e.id}")
        assertEquals("left ↖ = diagonal 0 (emulator Button 12)", 1, d.diagonals)
        assertEquals("the stick does not move, so the emulator binds the button", listOf(0, 0), listOf(d.x, d.y))
        assertEquals(0, HidReportManager.decode(hid.sent.last()).diagonals)
    }

    @Test fun withTheSettingOffAnOwnButtonStillMovesTheStick() {
        rule.runOnIdle {
            vm.saveLayout(emptyMap(), listOf(ExtraButton("d", ControllerButton.CROSS, 0.5f, 0.4f, stickDirection = StickDirection(Stick.RIGHT, Hat.DOWN_RIGHT, ownButton = true))))
            vm.setDiagonalButtons(false)
            vm.register(); hid.emit(HidEvent.AppStatusChanged(true)); hid.emit(HidEvent.ConnectionChanged(HostDevice("1", "PC"), HidStateReducer.STATE_CONNECTED))
            vm.navigate(Screen.CONTROLLER)
        }
        val d = press("extra_d")
        assertEquals(127, d.z); assertEquals(127, d.rz)
        assertEquals(6, hid.sent.last().size)
    }
}
