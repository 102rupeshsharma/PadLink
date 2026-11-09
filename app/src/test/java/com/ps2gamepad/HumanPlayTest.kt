package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ButtonStyle
import com.ps2gamepad.model.ControllerButton
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
 * Real UI, MOCK Bluetooth, touches like a REAL finger (3.2.0).
 *
 * The older UI tests tapped with a perfectly still synthetic finger. A real thumb always moves a few
 * pixels between landing and lifting, and that is exactly what broke the Settings panel: every tap
 * and swipe on it was cancelled on the phone while all the still-finger tests passed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class HumanPlayTest {
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

    /** A thumb tap: lands, wobbles a few pixels, lifts (~90 ms). [finger] 1 = the other thumb. */
    private fun SemanticsNodeInteraction.thumbTap(finger: Int = 0) = performTouchInput {
        down(finger, center)
        advanceEventTime(30); moveBy(finger, Offset(3f, -2f))
        advanceEventTime(30); moveBy(finger, Offset(-1f, 3f))
        advanceEventTime(30); up(finger)
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    // ------------------------------------------------------------------ bug 1: Settings while playing

    @Test fun settingsWorkWithARealThumbWhilePlaying() {
        // playing: left thumb walking, right thumb taps Settings
        val root = rule.onNodeWithTag("controller")
        val r = bounds("controller"); val ls = bounds("stick_left")
        root.performTouchInput { down(0, Offset(ls.center.x - r.left, ls.center.y - r.top)); moveBy(0, Offset(40f, 0f)) }
        rule.onNodeWithTag("menuButton").thumbTap(finger = 1) // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").thumbTap(finger = 1)
        rule.onNodeWithTag("controllerSettings").assertIsDisplayed()
        root.performTouchInput { up(0) } // left thumb lets go too
        rule.onNodeWithTag("style_OUTLINE").thumbTap()
        rule.runOnIdle { assertEquals("button style changed by a real tap", ButtonStyle.OUTLINE, vm.activeProfile.value.appearance.buttonStyle) }
        rule.onNodeWithTag("faces_PLAYSTATION").thumbTap()
        rule.runOnIdle { assertEquals("face labels changed by a real tap", FaceLabels.PLAYSTATION, vm.activeProfile.value.appearance.faceLabels) }
        rule.onNodeWithTag("style_REAL").thumbTap()
        rule.onNodeWithTag("faces_XBOX").thumbTap()
        rule.runOnIdle {
            assertEquals(ButtonStyle.REAL, vm.activeProfile.value.appearance.buttonStyle)
            assertEquals(FaceLabels.XBOX, vm.activeProfile.value.appearance.faceLabels)
        }
    }

    @Test fun settingsPanelScrollsWithARealSwipe() {
        rule.onNodeWithTag("menuButton").thumbTap() // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").thumbTap()
        fun y() = rule.onNodeWithTag("set_stickSize", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        val before = y()
        rule.onNodeWithTag("controllerSettings").performTouchInput {
            down(Offset(centerX, bottom - 30f))
            repeat(10) { advanceEventTime(16); moveBy(Offset(1f, -40f)) }
            up()
        }
        rule.waitForIdle()
        val after = y()
        assertTrue("the panel scrolled up: $before -> $after", after < before - 100f)
        // and the switch that was down there now works with a real tap
        val saverBefore = vm.profiles.value.batterySaver
        rule.onNodeWithTag("set_saver").performScrollTo().thumbTap()
        rule.runOnIdle { assertEquals(!saverBefore, vm.profiles.value.batterySaver) }
    }

    @Test fun touchesOnTheOpenPanelNeverPressControlsBehindIt() {
        rule.onNodeWithTag("menuButton").thumbTap() // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").thumbTap()
        val sentBefore = hid.sent.size
        rule.onNodeWithTag("controllerSettings").performTouchInput {
            down(center); advanceEventTime(50); moveBy(Offset(5f, 5f)); advanceEventTime(50); up()
        }
        rule.waitForIdle()
        assertEquals(0, vm.gamepad.value.buttons)
        assertEquals("nothing sent to the PC", sentBefore, hid.sent.size)
    }

    // ------------------------------------------------------------------ bug 2: letting go of the sticks

    @Test fun lettingGoOfEitherStickCentresItImmediately() {
        val root = rule.onNodeWithTag("controller")
        val r = bounds("controller")
        for (tag in listOf("stick_left", "stick_right")) {
            val s = bounds(tag)
            val c = Offset(s.center.x - r.left, s.center.y - r.top)
            root.performTouchInput {
                down(0, c)
                repeat(20) { advanceEventTime(8); moveBy(0, Offset(4f, -3f)) } // walk for a moment, thumb moving
            }
            rule.waitForIdle()
            val moving = HidReportManager.decode(hid.sent.last())
            assertTrue("$tag moving", if (tag == "stick_left") moving.x != 0 else moving.z != 0)
            val sentBeforeLift = hid.sent.size
            root.performTouchInput { up(0) }
            rule.waitForIdle()
            val last = HidReportManager.decode(hid.sent.last())
            assertEquals("$tag centred the moment the thumb lifted", listOf(0, 0, 0, 0), listOf(last.x, last.y, last.z, last.rz))
            assertEquals("$tag: exactly one report on lift (nothing queued behind it)", sentBeforeLift + 1, hid.sent.size)
        }
    }

    @Test fun buttonMashWhileWalkingNeverLeavesAButtonHeld() {
        val root = rule.onNodeWithTag("controller")
        val r = bounds("controller")
        val ls = bounds("stick_left")
        val cross = bounds("btn_CROSS")
        val square = bounds("btn_SQUARE")
        root.performTouchInput { down(0, Offset(ls.center.x - r.left, ls.center.y - r.top)); moveBy(0, Offset(30f, 0f)) }
        repeat(8) { i ->
            val b = if (i % 2 == 0) cross else square
            root.performTouchInput {
                down(1, Offset(b.center.x - r.left, b.center.y - r.top))
                advanceEventTime(40); moveBy(1, Offset(2f, 1f))
                advanceEventTime(30); up(1)
            }
            rule.waitForIdle()
            assertEquals("tap $i released", 0, HidReportManager.decode(hid.sent.last()).buttons)
        }
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        val last = HidReportManager.decode(hid.sent.last())
        assertEquals(listOf(0, 0, 0), listOf(last.buttons, last.x, last.y))
        assertTrue("every press reached the PC", hid.sent.count { HidReportManager.decode(it).buttons and ControllerButton.CROSS.mask != 0 } >= 4)
    }
}
