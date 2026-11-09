package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ButtonStyle
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.FaceLabels
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug (reported 3.1.0): while playing, Settings did nothing: chips ignored taps and the panel did not
 * scroll. Synthetic test taps (down + up at one point) worked; a real finger always moves a few
 * pixels between down and up, and those moves were swallowed by the panel's touch blocker, which
 * cancelled every tap and drag. These tests touch like a real thumb: a little wobble while pressing,
 * and real swipes for scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class RealFingerSettingsTest {
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
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").performClick()
        rule.waitForIdle()
    }

    /** A thumb tap: down, two tiny wobbles (well under the touch slop), up. */
    private fun SemanticsNodeInteraction.thumbTap() = performTouchInput {
        down(center); moveBy(Offset(1.5f, 1f)); moveBy(Offset(-1f, 1.5f)); up()
    }

    @Test fun thumbTapsChangeTheButtonStyle() {
        rule.onNodeWithTag("style_SIMPLE").thumbTap()
        rule.runOnIdle { assertEquals(ButtonStyle.SIMPLE, vm.activeProfile.value.appearance.buttonStyle) }
        rule.onNodeWithTag("style_OUTLINE").thumbTap()
        rule.runOnIdle { assertEquals(ButtonStyle.OUTLINE, vm.activeProfile.value.appearance.buttonStyle) }
    }

    @Test fun thumbTapChangesTheFaceLabels() {
        rule.onNodeWithTag("faces_PLAYSTATION").thumbTap()
        rule.runOnIdle { assertEquals(FaceLabels.PLAYSTATION, vm.activeProfile.value.appearance.faceLabels) }
    }

    private fun scrollPos(): Float = rule.onNodeWithTag("controllerSettings").fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun swipingScrollsThePanelAndLowerSettingsWork() {
        assertEquals(0f, scrollPos())
        rule.onNodeWithTag("controllerSettings").performTouchInput {
            down(Offset(centerX, bottom - 30f)); repeat(6) { moveBy(Offset(0f, -40f)) }; up()
        }
        rule.waitForIdle()
        assertTrue("a thumb swipe scrolled the panel: ${scrollPos()}", scrollPos() > 50f)
        // (swiping itself is proven above; position the switch, then press it with a thumb)
        rule.onNodeWithTag("set_saver").performScrollTo()
        val saverBefore = vm.profiles.value.batterySaver
        rule.onNodeWithTag("set_saver").thumbTap()
        rule.runOnIdle { assertEquals("battery saver switch toggled", !saverBefore, vm.profiles.value.batterySaver) }
    }

    @Test fun draggingASliderChangesIt() {
        rule.onNodeWithTag("set_opacity").performScrollTo()
        rule.onNodeWithTag("set_opacity").performTouchInput {
            down(Offset(left + width * 0.9f, centerY)); moveBy(Offset(-width * 0.2f, 0f)); moveBy(Offset(-width * 0.2f, 0f)); up()
        }
        rule.runOnIdle { assertTrue("opacity lowered: ${vm.activeProfile.value.appearance.buttonOpacity}", vm.activeProfile.value.appearance.buttonOpacity < 0.8f) }
    }

    @Test fun touchesOnThePanelStillNeverReachTheControlsBehindIt() {
        val before = hid.sent.size
        rule.onNodeWithTag("controllerSettings").performTouchInput {
            down(center); moveBy(Offset(2f, 2f)); up()
            down(Offset(left + 20f, bottom - 20f)); moveBy(Offset(30f, 0f)); up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("nothing sent to the PC", before, hid.sent.size)
            assertEquals(0, vm.gamepad.value.buttons)
        }
    }
}
