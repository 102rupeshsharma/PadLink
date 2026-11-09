package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.GamepadState
import com.ps2gamepad.model.Hat
import com.ps2gamepad.model.HostDevice
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

/**
 * UI tests on the JVM (Robolectric). Real Compose UI and real touch events, MOCK Bluetooth
 * ([FakeHid]): these prove the app turns touches into the right HID reports. They do not prove
 * anything about the phone's Bluetooth stack or Windows.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class ControllerUiTest {
    @get:Rule val rule = createComposeRule()

    private val hid = FakeHid()
    private val bt = MutableStateFlow(BluetoothState.ENABLED)
    private val laptop = HostDevice("11:22:33:44:55:66", "LAPTOP")
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        vm = MainViewModel(hid, bt, { listOf(laptop) }, DiagnosticLog(), listOf("robolectric"), { 0L }, batteryPercent = { 77 })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle { vm.navigate(Screen.DASHBOARD) } // these tests start from the "More" screen
        rule.waitForIdle()
    }

    private fun connectHost() {
        rule.runOnIdle {
            vm.onPermissionsChanged(true)
            vm.register()
            hid.emit(HidEvent.AppStatusChanged(true))
            hid.emit(HidEvent.ConnectionChanged(laptop, HidStateReducer.STATE_CONNECTED))
        }
        rule.waitForIdle()
    }

    private fun openController() {
        rule.onNodeWithTag("dash_controller").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("play_standard").performClick() // loadout picker
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private val root: SemanticsNodeInteraction get() = rule.onNodeWithTag("controller")

    /** Position inside [tag] (fractions of its size), relative to the controller root node. */
    private fun at(tag: String, fx: Float = 0.5f, fy: Float = 0.5f): Offset {
        val b = bounds(tag); val r = bounds("controller")
        return Offset(b.left + b.width * fx - r.left, b.top + b.height * fy - r.top)
    }

    private val state: GamepadState get() = vm.gamepad.value

    // ------------------------------------------------------------------ dashboard & states

    @Test fun dashboardShowsRealStatus() {
        rule.onNodeWithTag("dashboard").assertIsDisplayed()
        rule.onNodeWithText("Standard PS2").assertIsDisplayed()
        rule.onNodeWithText("77 %").assertIsDisplayed()
        rule.onNodeWithText("never").assertIsDisplayed() // no connection yet: nothing fabricated
        connectHost()
        rule.onNodeWithText("Connected: LAPTOP").assertIsDisplayed()
    }

    @Test fun registrationErrorIsShown() {
        rule.runOnIdle { hid.emit(HidEvent.RegisterRejected("HID Device profile not supported by this phone")) }
        rule.onNodeWithText("Registration failed: HID Device profile not supported by this phone. See BLUETOOTH_SETUP.md, troubleshooting.").performScrollTo().assertIsDisplayed()
    }

    @Test fun controllerWarnsWhenNotConnected() {
        openController()
        rule.onNodeWithTag("notConnectedBanner").assertIsDisplayed()
        connectHost()
        rule.onNodeWithTag("notConnectedBanner").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ every button

    @Test fun everyDigitalButtonPressesAndReleases() {
        connectHost(); openController()
        val buttons = mapOf(
            "btn_L1" to ControllerButton.L1, "btn_L2" to ControllerButton.L2, "btn_R1" to ControllerButton.R1, "btn_R2" to ControllerButton.R2,
            "btn_L3" to ControllerButton.L3, "btn_R3" to ControllerButton.R3, "btn_SELECT" to ControllerButton.SELECT, "btn_START" to ControllerButton.START,
        )
        for ((tag, b) in buttons) {
            root.performTouchInput { down(0, at(tag)) }
            rule.waitForIdle()
            assertEquals(tag, b.mask, state.buttons)
            assertTrue(HidReportManager.decode(hid.sent.last()).isPressed(b))
            root.performTouchInput { up(0) }
            rule.waitForIdle()
            assertEquals("$tag released", GamepadState.NEUTRAL, state)
            assertTrue(hid.sent.last().contentEquals(HidReportManager.NEUTRAL_REPORT))
        }
    }

    @Test fun everyFaceButton() {
        connectHost(); openController()
        // 1.9.0: four separate buttons, each with its own tag
        for (b in listOf(ControllerButton.TRIANGLE, ControllerButton.CIRCLE, ControllerButton.CROSS, ControllerButton.SQUARE)) {
            root.performTouchInput { down(0, at("btn_${b.name}")) }
            rule.waitForIdle()
            assertEquals(b.label, b.mask, state.buttons)
            root.performTouchInput { up(0) }
            rule.waitForIdle()
            assertEquals(0, state.buttons)
        }
    }

    @Test fun everyDpadDirectionIncludingDiagonalAndSlide() {
        connectHost(); openController()
        val dirs = mapOf(
            Hat.UP to (0.5f to 0.1f), Hat.DOWN to (0.5f to 0.9f), Hat.LEFT to (0.1f to 0.5f), Hat.RIGHT to (0.9f to 0.5f),
            Hat.UP_RIGHT to (0.85f to 0.15f), Hat.DOWN_LEFT to (0.15f to 0.85f),
        )
        for ((hat, xy) in dirs) {
            root.performTouchInput { down(0, at("dpad", xy.first, xy.second)) }
            rule.waitForIdle()
            assertEquals(hat, state.dpad.hat)
            root.performTouchInput { up(0) }
            rule.waitForIdle()
            assertEquals(Hat.NEUTRAL, state.dpad.hat)
        }
        // slide from Up to Right without lifting
        root.performTouchInput { down(0, at("dpad", 0.5f, 0.1f)); moveTo(0, at("dpad", 0.9f, 0.5f)) }
        rule.waitForIdle()
        assertEquals(Hat.RIGHT, state.dpad.hat)
        root.performTouchInput { up(0) }
    }

    @Test fun bothSticksMoveCorrectlyAndReturnToCentre() {
        connectHost(); openController()
        for ((tag, isLeft) in listOf("stick_left" to true, "stick_right" to false)) {
            val b = bounds(tag)
            val travel = minOf(b.width, b.height) * 0.27f
            val c = at(tag)
            root.performTouchInput { down(0, c); moveTo(0, c + Offset(travel * 2, 0f)) } // beyond the edge -> saturate
            rule.waitForIdle()
            assertEquals(127, if (isLeft) state.leftX else state.rightX)
            root.performTouchInput { moveTo(0, c + Offset(0f, -travel)) } // up -> negative Y
            rule.waitForIdle()
            assertEquals(-127, if (isLeft) state.leftY else state.rightY)
            assertEquals(0, if (isLeft) state.leftX else state.rightX)
            root.performTouchInput { up(0) }
            rule.waitForIdle()
            assertEquals(GamepadState.NEUTRAL, state)
        }
    }

    // ------------------------------------------------------------------ multi-touch

    @Test fun multiTouchStickPlusButtonsPlusShoulders() {
        connectHost(); openController()
        val lb = bounds("stick_left"); val travel = minOf(lb.width, lb.height) * 0.27f
        root.performTouchInput {
            down(0, at("stick_left"))
            moveTo(0, at("stick_left") + Offset(travel, 0f))
            down(1, at("btn_CROSS"))
            down(2, at("btn_L1"))
            down(3, at("btn_R1"))
            down(4, at("stick_right")); moveTo(4, at("stick_right") + Offset(0f, travel))
        }
        rule.waitForIdle()
        assertEquals(127, state.leftX)
        assertEquals(127, state.rightY)
        assertEquals(ControllerButton.CROSS.mask or ControllerButton.L1.mask or ControllerButton.R1.mask, state.buttons)
        assertEquals(5, vm.input.activePointers.value)
        // lifting one finger keeps the others
        root.performTouchInput { up(1) }
        rule.waitForIdle()
        assertEquals(ControllerButton.L1.mask or ControllerButton.R1.mask, state.buttons)
        assertEquals(127, state.leftX)
        root.performTouchInput { up(0); up(2); up(3); up(4) }
        rule.waitForIdle()
        assertEquals(GamepadState.NEUTRAL, state)
        assertEquals(0, vm.input.activePointers.value)
    }

    @Test fun interruptionWhileHoldingReleasesAndStaleFingerIsIgnored() {
        connectHost(); openController()
        root.performTouchInput { down(0, at("btn_R2")) }
        rule.waitForIdle()
        assertTrue(state.isPressed(ControllerButton.R2))
        rule.runOnIdle { vm.onInterrupted("test: focus lost") }
        rule.waitForIdle()
        assertEquals(GamepadState.NEUTRAL, state)
        // the finger is still down: moving it must not re-press anything
        root.performTouchInput { moveBy(0, Offset(2f, 0f)) }
        rule.waitForIdle()
        assertEquals(GamepadState.NEUTRAL, state)
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(GamepadState.NEUTRAL, state)
    }

    @Test fun connectionLossWhileHoldingResetsInputs() {
        connectHost(); openController()
        root.performTouchInput { down(0, at("btn_L2")) }
        rule.waitForIdle()
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptop, HidStateReducer.STATE_DISCONNECTED)) }
        rule.waitForIdle()
        assertEquals(GamepadState.NEUTRAL, state)
        rule.onNodeWithTag("notConnectedBanner").assertIsDisplayed()
        root.performTouchInput { up(0) }
    }

    // ------------------------------------------------------------------ edit layout

    @Test fun editModeBlocksHidAndMovesControls() {
        connectHost(); openController()
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Edit is in the menu
        rule.onNodeWithTag("chip_Edit").performClick()
        rule.waitForIdle()
        val sentBefore = hid.sent.size
        // tapping where L1 is does nothing to the HID state
        root.performTouchInput { down(0, at("btn_L1")); up(0) }
        rule.waitForIdle()
        assertEquals(0, state.buttons)
        assertEquals(sentBefore, hid.sent.size)

        // 1.9.0: controls may overlap, so dragging L2 onto L1 is allowed and stays there
        val l1Before = bounds("edit_L1")
        val l2Before = bounds("edit_L2")
        val toL1 = l1Before.center - l2Before.center
        rule.onNodeWithTag("edit_L2").performTouchInput { down(center); moveBy(toL1 / 2f); moveBy(toL1 / 2f); up() }
        rule.waitForIdle()
        // (the first few px of a drag are touch slop, so it lands a little short of the target)
        assertTrue(bounds("edit_L2").center.y > l2Before.center.y + toL1.y * 0.7f)

        // dragging L1 a little to the right into free space works, and Save persists it
        rule.onNodeWithTag("edit_L1").performTouchInput { down(center); moveBy(Offset(40f, 0f)); moveBy(Offset(40f, 0f)); up() }
        rule.waitForIdle()
        assertTrue(bounds("edit_L1").center.x > l1Before.center.x + 20f)
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        assertFalse(vm.editMode.value)
        assertTrue(vm.activeProfile.value.placements.containsKey(ControlId.L1))
        // and the button still sends L1 at its new place
        root.performTouchInput { down(0, at("btn_L1")) }
        rule.waitForIdle()
        assertEquals(ControllerButton.L1.mask, state.buttons)
        root.performTouchInput { up(0) }
    }

    // ------------------------------------------------------------------ customisation & profiles

    @Test fun customizeChangesApplyImmediately() {
        rule.onNodeWithTag("dash_customize").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("s_buttonOpacity").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.onNodeWithTag("sw_labels").performScrollTo().performClick()
        rule.onNodeWithTag("tab_RIGHT").performScrollTo().performClick()
        rule.onNodeWithTag("sw_invert").performScrollTo().performClick()
        rule.waitForIdle()
        val p = vm.activeProfile.value
        assertEquals(0.5f, p.appearance.buttonOpacity, 0.01f)
        assertFalse(p.appearance.showLabels)
        assertTrue(p.right.invertY)
        assertFalse(p.left.invertY)
        rule.onNodeWithTag("preview").assertIsDisplayed()
    }

    @Test fun profileDeleteThroughDialogAndSwitch() {
        rule.runOnIdle { vm.createProfile("My Pad") }
        rule.waitForIdle()
        val id = vm.activeProfile.value.id
        rule.onNodeWithTag("dash_profiles").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("My Pad  (active)").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()          // only custom profiles have Delete
        rule.onNodeWithTag("confirmDelete").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile_$id").assertDoesNotExist()
        rule.onNodeWithText("Standard PS2  (active)").assertIsDisplayed()
        // switch with the "Use" button of the second profile (God of War)
        rule.onNodeWithTag("profile_${com.ps2gamepad.settings.DefaultProfiles.GOW}").assertIsDisplayed()
        rule.runOnIdle { vm.switchProfile(com.ps2gamepad.settings.DefaultProfiles.GOW) }
        rule.waitForIdle()
        rule.onNodeWithText("God of War  (active)").assertIsDisplayed()
    }

    /**
     * SKIPPED (reported as skipped, not passed): a text field inside a dialog never lets the
     * Robolectric/Compose test harness become idle (AppNotIdleException even with a manual clock).
     * The Delete dialog (no text field) is covered above; create/rename logic is covered by
     * ProfileManagerTest and MainViewModelTest; the dialog itself is on the manual checklist.
     */
    @org.junit.Ignore("Robolectric cannot idle with a TextField inside a Dialog; see TEST_REPORT.md")
    @Test fun newProfileDialogOpensAndCancels() {
        rule.onNodeWithTag("dash_profiles").performClick()
        rule.waitForIdle()
        // Text field cursor blink inside a dialog never lets Robolectric idle: drive the clock by hand.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("New profile").performClick()
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("nameField").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("nameField").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        assertEquals(6, vm.profiles.value.profiles.size)
    }

    @Test fun calibrationScreenShowsLiveHidValues() {
        connectHost()
        rule.runOnIdle { vm.navigate(Screen.CALIBRATION) }
        rule.waitForIdle()
        rule.onNodeWithText("Left stick · floating · HID X=0 Y=0").assertIsDisplayed()
        val b = bounds("calStick_LEFT")
        rule.onNodeWithTag("calStick_LEFT").performTouchInput { down(center); moveBy(Offset(b.width, 0f)) }
        rule.waitForIdle()
        assertEquals(127, state.leftX)
        rule.onNodeWithText("Left stick · floating · HID X=127 Y=0").assertIsDisplayed()
        rule.onNodeWithTag("calStick_LEFT").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, state.leftX)
    }

    @Test fun diagnosticsResetAllInputs() {
        connectHost()
        rule.runOnIdle { vm.input.setButton(ControllerButton.START, true); vm.navigate(Screen.DIAGNOSTICS); vm.input.setButton(ControllerButton.START, true) }
        rule.waitForIdle()
        rule.onNodeWithText("Reset All Inputs").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(GamepadState.NEUTRAL, state)
    }

    @Test fun profilesScreenOpens() {
        rule.onNodeWithTag("dash_profiles").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Standard PS2  (active)").assertIsDisplayed()
    }
}
