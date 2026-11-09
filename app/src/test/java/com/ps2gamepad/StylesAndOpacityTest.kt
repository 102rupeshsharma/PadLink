package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.model.Appearance
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.settings.DefaultProfiles
import com.ps2gamepad.settings.ProfileCodec
import com.ps2gamepad.settings.ProfileManager
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

class OpacityModelTest {
    @Test fun controlOpacityIsClampedAndFullOpacityIsNotStored() {
        val a = Appearance(controlOpacity = mapOf(ControlId.L1 to 0f, ControlId.FACE to 0.5f, ControlId.START to 1f, ControlId.R1 to Float.NaN)).sanitized()
        assertEquals(Appearance.MIN_CONTROL_OPACITY, a.opacityOf(ControlId.L1))
        assertEquals(0.5f, a.opacityOf(ControlId.FACE))
        assertEquals(1f, a.opacityOf(ControlId.START))
        assertEquals(setOf(ControlId.L1, ControlId.FACE), a.controlOpacity.keys)
    }

    @Test fun opacityRoundTripsThroughStorage() {
        val s = ProfileManager.updateActive(DefaultProfiles.store()) {
            it.copy(appearance = it.appearance.copy(buttonOpacity = 0.6f, controlOpacity = mapOf(ControlId.DPAD to 0.4f)))
        }
        val back = ProfileCodec.decode(ProfileCodec.encode(s))
        assertEquals(0.6f, back.active.appearance.buttonOpacity)
        assertEquals(0.4f, back.active.appearance.opacityOf(ControlId.DPAD))
    }

    @Test fun settingsSavedWithAControllerStyleFromAnOlderVersionStillLoad() {
        // 1.2-1.5 stored "skin"; the single-controller version ignores it and keeps everything else.
        val old = """{"activeId":"standard","profiles":[{"id":"standard","name":"Standard PS2","builtIn":true,
            "appearance":{"buttonScale":1.2,"skin":"XBOX_STYLE","buttonOpacity":0.7}}]}"""
        val s = ProfileCodec.decode(old)
        assertEquals(1.2f, s.active.appearance.buttonScale)
        assertEquals(0.7f, s.active.appearance.buttonOpacity)
    }
}

/** Real UI, MOCK Bluetooth: the in-controller Settings panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class ControllerSettingsUiTest {
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

    @Test fun settingsPanelChangesOpacityAndSizeLive() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").performClick()
        rule.onNodeWithTag("controllerSettings").assertIsDisplayed()
        rule.onNodeWithTag("set_opacity").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.onNodeWithTag("set_buttonSize").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.2f) }
        rule.onNodeWithTag("set_stickSize").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        rule.waitForIdle()
        rule.runOnIdle {
            val a = vm.activeProfile.value.appearance
            assertEquals(0.5f, a.buttonOpacity, 0.01f); assertEquals(1.2f, a.buttonScale, 0.01f); assertEquals(0.8f, a.stickScale, 0.01f)
        }
        rule.onNodeWithTag("chip_Reset size & opacity").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1f, vm.activeProfile.value.appearance.buttonOpacity) }
    }

    @Test fun buttonStyleAndFaceLabelsAreChosenInSettingsAndSaved() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").performClick()
        for (st in com.ps2gamepad.model.ButtonStyle.entries) {
            rule.onNodeWithTag("style_${st.name}").performScrollTo().performClick()
            rule.runOnIdle { assertEquals(st, vm.activeProfile.value.appearance.buttonStyle) }
        }
        rule.onNodeWithTag("faces_PLAYSTATION").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(com.ps2gamepad.model.FaceLabels.PLAYSTATION, vm.activeProfile.value.appearance.faceLabels) }
        val back = ProfileCodec.decode(ProfileCodec.encode(vm.profiles.value)).active.appearance
        assertEquals("the last style tapped is saved", com.ps2gamepad.model.ButtonStyle.entries.last(), back.buttonStyle)
        assertEquals(com.ps2gamepad.model.FaceLabels.PLAYSTATION, back.faceLabels)
    }

    @Test fun everyStyleAndLabelSetStillSendsTheSameButtons() {
        val root = rule.onNodeWithTag("controller")
        for (st in com.ps2gamepad.model.ButtonStyle.entries) for (fl in com.ps2gamepad.model.FaceLabels.entries) {
            rule.runOnIdle { vm.updateAppearance { it.copy(buttonStyle = st, faceLabels = fl) } }
            rule.waitForIdle()
            val r = root.fetchSemanticsNode().boundsInRoot
            val a = rule.onNodeWithTag("btn_CROSS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            root.performTouchInput { down(0, Offset(a.center.x - r.left, a.center.y - r.top)) }
            rule.waitForIdle()
            assertEquals("$st/$fl: bottom face button is Cross", ControllerButton.CROSS.mask, HidReportManager.decode(hid.sent.last()).buttons)
            root.performTouchInput { up(0) }
            rule.waitForIdle()
        }
    }

    @Test fun faceButtonsAreYbaxAndStillSendTheSameButtons() {
        rule.onNodeWithText("Y", useUnmergedTree = true).assertExists()
        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val a = rule.onNodeWithTag("btn_CROSS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(0, Offset(a.center.x - r.left, a.center.y - r.top)) }
        rule.waitForIdle()
        assertEquals("A (bottom) is still Cross / HID button 2", ControllerButton.CROSS.mask, HidReportManager.decode(hid.sent.last()).buttons)
        root.performTouchInput { up(0) }
    }

    @Test fun touchesOnThePanelNeverPressControlsBehindIt() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").performClick()
        val before = hid.sent.size
        rule.onNodeWithTag("controllerSettings").performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0, vm.gamepad.value.buttons)
            assertEquals(before, hid.sent.size)
        }
    }

    @Test fun controlsStillSendTheSameButtonsAfterResizing() {
        rule.runOnIdle { vm.updateAppearance { it.copy(buttonScale = 1.3f, buttonOpacity = 0.4f) } }
        rule.waitForIdle()
        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val l1 = rule.onNodeWithTag("btn_L1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(0, Offset(l1.center.x - r.left, l1.center.y - r.top)) }
        rule.waitForIdle()
        assertEquals(ControllerButton.L1.mask, HidReportManager.decode(hid.sent.last()).buttons)
        root.performTouchInput { up(0) }
    }
}

/** Real UI, MOCK Bluetooth: per-control opacity in Customize (unchanged feature). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class StylesUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        vm = MainViewModel(FakeHid(), MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
    }

    @Test fun perControlOpacitySliderInCustomize() {
        rule.runOnIdle { vm.navigate(Screen.CUSTOMIZE) }
        rule.onNodeWithTag("op_DPAD").performScrollTo().performClick()
        rule.onNodeWithTag("s_controlOpacity").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.35f) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0.35f, vm.activeProfile.value.appearance.opacityOf(ControlId.DPAD), 0.01f)
            assertEquals(1f, vm.activeProfile.value.appearance.opacityOf(ControlId.FACE))
        }
    }
}
