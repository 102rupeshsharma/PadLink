package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ComboStep
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.Dpad
import com.ps2gamepad.model.ExtraButton
import com.ps2gamepad.model.FaceLabels
import com.ps2gamepad.model.Hat
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.settings.DefaultProfiles
import com.ps2gamepad.settings.ProfileCodec
import com.ps2gamepad.settings.ProfileManager
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import com.ps2gamepad.ui.controller.comboLabel
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

class ComboModelTest {
    private val l1r1 = ExtraButton("c", ControllerButton.CROSS, steps = listOf(ComboStep(setOf(ControllerButton.L1, ControllerButton.R1))))
    private val hadouken = ExtraButton("h", ControllerButton.CROSS, steps = listOf(
        ComboStep(hat = Hat.DOWN), ComboStep(hat = Hat.DOWN_RIGHT), ComboStep(setOf(ControllerButton.SQUARE), Hat.RIGHT),
    ))

    @Test fun oneStepIsHeldTogetherSeveralStepsAreASequence() {
        assertTrue(l1r1.isCombo); assertFalse(l1r1.isSequence)
        assertTrue(hadouken.isCombo); assertTrue(hadouken.isSequence)
        assertFalse(ExtraButton("p", ControllerButton.CROSS).isCombo)
    }

    @Test fun labelsFollowTheChosenFaceLabels() {
        assertEquals("L1+R1", comboLabel(l1r1, FaceLabels.XBOX))
        assertEquals("↓ ↘ →+X", comboLabel(hadouken, FaceLabels.XBOX))
        assertEquals("↓ ↘ →+□", comboLabel(hadouken, FaceLabels.PLAYSTATION))
    }

    @Test fun combosAreSanitizedAndSurviveSaving() {
        val messy = hadouken.copy(steps = hadouken.steps + ComboStep() + List(20) { ComboStep(setOf(ControllerButton.R2)) }, stepMs = 5)
        val s = ProfileManager.updateActive(DefaultProfiles.store()) { it.copy(extraButtons = listOf(messy, l1r1)) }
        val back = ProfileCodec.decode(ProfileCodec.encode(s)).active.extraButtons
        val h = back.first { it.id == "h" }
        assertEquals("empty steps dropped, at most ${ExtraButton.MAX_STEPS}", ExtraButton.MAX_STEPS, h.steps.size)
        assertEquals(ExtraButton.MIN_STEP_MS, h.stepMs)
        assertEquals(Hat.DOWN_RIGHT, h.steps[1].hat)
        assertEquals(setOf(ControllerButton.L1, ControllerButton.R1), back.first { it.id == "c" }.steps.single().buttons)
    }

    @Test fun aComboStepIsOneReportAndReleasingItDoesNotCutOtherControls() {
        val sent = mutableListOf<ByteArray>()
        val input = ControllerInputManager({ r, _ -> sent += r; true })
        input.setButton(ControllerButton.L1, true) // finger on the real L1
        sent.clear()
        input.setComboFrom("combo_c", setOf(ControllerButton.L1, ControllerButton.R1), Dpad.of(Hat.DOWN))
        assertEquals("one report for the whole step", 1, sent.size)
        val st = HidReportManager.decode(sent.last())
        assertEquals(ControllerButton.L1.mask or ControllerButton.R1.mask, st.buttons)
        assertEquals(Hat.DOWN.value, st.hat)
        input.setComboFrom("combo_c", emptySet(), Dpad.NEUTRAL)
        assertEquals("the real L1 is still held", ControllerButton.L1.mask, input.state.value.buttons)
        assertEquals(Hat.NEUTRAL, input.state.value.dpad.hat)
    }
}

/** Real UI, MOCK Bluetooth: combo buttons in play and in the layout editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class ComboButtonUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle {
            vm.onPermissionsChanged(true); vm.onForeground(); vm.register()
            hid.emit(HidEvent.AppStatusChanged(true)); hid.emit(HidEvent.ConnectionChanged(HostDevice("1", "PC"), HidStateReducer.STATE_CONNECTED))
            vm.navigate(Screen.CONTROLLER)
        }
    }

    private fun centreOf(tag: String): Offset {
        val r = rule.onNodeWithTag("controller").fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return Offset(b.center.x - r.left, b.center.y - r.top)
    }

    @Test fun heldComboPressesAllItsButtonsTogetherWhileHeld() {
        rule.runOnIdle { vm.saveLayout(emptyMap(), listOf(ExtraButton("c", ControllerButton.CROSS, 0.5f, 0.2f, steps = listOf(ComboStep(setOf(ControllerButton.L1, ControllerButton.R1)))))) }
        rule.waitForIdle()
        val root = rule.onNodeWithTag("controller")
        val p = centreOf("extra_c")
        root.performTouchInput { down(0, p) }
        rule.waitForIdle()
        assertEquals(ControllerButton.L1.mask or ControllerButton.R1.mask, HidReportManager.decode(hid.sent.last()).buttons)
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(0, HidReportManager.decode(hid.sent.last()).buttons)
    }

    @Test fun sequenceComboPlaysItsStepsInOrderThenReleases() {
        val seq = ExtraButton("s", ControllerButton.CROSS, 0.5f, 0.2f, stepMs = 50, steps = listOf(
            ComboStep(hat = Hat.DOWN), ComboStep(hat = Hat.DOWN_RIGHT), ComboStep(setOf(ControllerButton.SQUARE), Hat.RIGHT),
            ComboStep(setOf(ControllerButton.SQUARE)), // repeats Square: needs a release in between
        ))
        rule.runOnIdle { vm.saveLayout(emptyMap(), listOf(seq)) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        hid.sent.clear()
        val p = centreOf("extra_s")
        rule.onNodeWithTag("controller").performTouchInput { down(0, p); up(0) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val states = hid.sent.map { HidReportManager.decode(it) }.map { r -> Hat.entries.first { it.value == r.hat } to r.buttons }
        val sq = ControllerButton.SQUARE.mask
        val expected = listOf(Hat.DOWN to 0, Hat.DOWN_RIGHT to 0, Hat.RIGHT to sq, Hat.NEUTRAL to 0, Hat.NEUTRAL to sq, Hat.NEUTRAL to 0)
        assertEquals("steps in order, a release before the repeated Square, then everything released", expected, states)
        rule.mainClock.autoAdvance = true
    }

    @Test fun comboIsMadeInTheLayoutEditor() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Edit is in the menu
        rule.onNodeWithTag("chip_Edit").performClick()
        rule.onNodeWithTag("chip_+ Combo").performClick()
        rule.onNodeWithTag("comboEditor").assertIsDisplayed()
        // step 1: R2 + Cross (default was L1+R1: toggle L1 and R1 off)
        rule.onNodeWithTag("combo_btn_L1").performClick()
        rule.onNodeWithTag("combo_btn_R1").performClick()
        rule.onNodeWithTag("combo_btn_R2").performClick()
        rule.onNodeWithTag("combo_btn_CROSS").performClick()
        // step 2: Down + Circle
        rule.onNodeWithTag("combo_addStep").performClick()
        rule.onNodeWithTag("combo_hat_DOWN").performClick()
        rule.onNodeWithTag("combo_btn_CIRCLE").performClick()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        val c = vm.activeProfile.value.extraButtons.single()
        assertTrue(c.isSequence)
        assertEquals(listOf(
            ComboStep(setOf(ControllerButton.R2, ControllerButton.CROSS)),
            ComboStep(setOf(ControllerButton.CIRCLE), Hat.DOWN),
        ), c.steps)
    }
}
