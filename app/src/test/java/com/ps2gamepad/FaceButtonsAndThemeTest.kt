package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.controller.LayoutEngine
import com.ps2gamepad.model.Appearance
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.Placement
import com.ps2gamepad.settings.ProfileCodec
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import com.ps2gamepad.ui.controller.NeuThemes
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

class FaceButtonLayoutTest {
    @Test fun faceButtonsSitInTheClassicDiamondByDefault() {
        val l = LayoutEngine.defaults(986f, 443f)
        val t = l.getValue(ControlId.TRIANGLE); val c = l.getValue(ControlId.CIRCLE)
        val x = l.getValue(ControlId.CROSS); val s = l.getValue(ControlId.SQUARE)
        assertTrue(t.cy < c.cy && x.cy > c.cy && s.cx < t.cx && c.cx > t.cx)
        assertEquals(t.cx, x.cx, 0.01f); assertEquals(s.cy, c.cy, 0.01f)
    }

    @Test fun oneFaceButtonMovesAloneAndOverlapIsAllowed() {
        val base = LayoutEngine.resolve(986f, 443f, Appearance(), emptyMap())
        val moved = LayoutEngine.resolve(986f, 443f, Appearance(), mapOf(ControlId.CROSS to Placement(0.5f, 0.5f)))
        assertEquals(493f, moved.getValue(ControlId.CROSS).cx, 0.5f)
        for (id in listOf(ControlId.TRIANGLE, ControlId.CIRCLE, ControlId.SQUARE)) assertEquals(base.getValue(id), moved.getValue(id))
        // placing Cross right on top of Circle is kept (no refusal)
        val c = base.getValue(ControlId.CIRCLE)
        val onTop = LayoutEngine.resolve(986f, 443f, Appearance(), mapOf(ControlId.CROSS to Placement(c.cx / 986f, c.cy / 443f)))
        assertEquals(c.cx, onTop.getValue(ControlId.CROSS).cx, 0.5f)
    }

    @Test fun oldLayoutWithAMovedFaceClusterKeepsTheButtonsWhereTheClusterWas() {
        // saved by 1.8 and older: one placement for the whole cluster
        val old = """{"activeId":"standard","profiles":[{"id":"standard","name":"Standard PS2","builtIn":true,
            "placements":{"FACE":{"cx":0.6,"cy":0.4,"scale":1.0}},"appearance":{"controlOpacity":{"FACE":0.5}}}]}"""
        val p = ProfileCodec.decode(old).active
        val l = LayoutEngine.resolve(986f, 443f, p.appearance, p.placements)
        val centre = Offset((l.getValue(ControlId.CIRCLE).cx + l.getValue(ControlId.SQUARE).cx) / 2, (l.getValue(ControlId.TRIANGLE).cy + l.getValue(ControlId.CROSS).cy) / 2)
        assertEquals(0.6f * 986f, centre.x, 1f); assertEquals(0.4f * 443f, centre.y, 1f)
        assertEquals("cluster opacity applies to each button", 0.5f, p.appearance.opacityOf(ControlId.TRIANGLE))
    }
}

/** Real UI, MOCK Bluetooth: separate face buttons in the editor + the one-tap theme button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class FaceButtonsAndThemeUiTest {
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

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun cornerHandleResizesTheSelectedControl() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Edit is in the menu
        rule.onNodeWithTag("chip_Edit").performClick()
        rule.onNodeWithTag("edit_L1").performClick()
        val before = bounds("edit_L1").width
        rule.onNodeWithTag("resizeHandle").performTouchInput { down(center); moveBy(Offset(30f, 30f)); moveBy(Offset(30f, 30f)); up() }
        rule.waitForIdle()
        assertTrue("bigger after dragging the corner", bounds("edit_L1").width > before + 10f)
    }

    @Test fun addedButtonCanBeDeletedWithTheRedX() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Edit is in the menu
        rule.onNodeWithTag("chip_Edit").performClick()
        rule.onNodeWithTag("chip_+ Button").performClick()
        rule.onNodeWithTag("extraDeleteX").performClick()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        assertTrue(vm.activeProfile.value.extraButtons.isEmpty())
    }

    @Test fun dragTriangleAloneThenItStillPressesTriangle() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Edit is in the menu
        rule.onNodeWithTag("chip_Edit").performClick()
        val t0 = bounds("edit_TRIANGLE"); val c0 = bounds("edit_CIRCLE")
        rule.onNodeWithTag("edit_TRIANGLE").performTouchInput { down(center); moveBy(Offset(-25f, 0f)); moveBy(Offset(-25f, 0f)); up() }
        rule.waitForIdle()
        assertTrue(bounds("edit_TRIANGLE").center.x < t0.center.x - 20f)
        assertEquals("circle did not move", c0.center, bounds("edit_CIRCLE").center)
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        val pl = vm.activeProfile.value.placements
        assertTrue(ControlId.TRIANGLE in pl); assertFalse(ControlId.CIRCLE in pl); assertFalse(ControlId.FACE in pl)

        val root = rule.onNodeWithTag("controller"); val r = bounds("controller"); val b = bounds("btn_TRIANGLE")
        root.performTouchInput { down(0, Offset(b.center.x - r.left, b.center.y - r.top)) }
        rule.waitForIdle()
        assertEquals(ControllerButton.TRIANGLE.mask, HidReportManager.decode(hid.sent.last()).buttons)
        root.performTouchInput { up(0) }
    }
}

/** Real UI with native graphics: the one-tap theme button really repaints the controller. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class ThemeSwitchUiTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        vm = MainViewModel(FakeHid(), MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle { vm.navigate(Screen.CONTROLLER) }
    }

    /** Colour of a pixel near the left edge, half way down (plain background there). */
    private fun bg(): androidx.compose.ui.graphics.Color {
        rule.waitForIdle()
        val root = rule.activity.window.decorView.rootView
        val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        return androidx.compose.ui.graphics.Color(bmp.getPixel(root.width / 2, root.height - 4))
    }

    @Test fun backgroundIsPureBlackInEveryButtonStyle() {
        // black pixels are off on AMOLED: the lowest-power background
        for (st in com.ps2gamepad.model.ButtonStyle.entries) {
            rule.runOnIdle { vm.updateAppearance { it.copy(buttonStyle = st) } }
            assertEquals("$st", androidx.compose.ui.graphics.Color.Black, bg())
        }
    }
}

/** 2.0.0: PadLink name + battery saver plumbing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenameAndBatteryTest {
    @Test fun oldGamepadNameIsNeverSavedAsThePhonesOwnName() {
        assertEquals("PadLink", com.ps2gamepad.bluetooth.PhoneNameControl.GAMEPAD_NAME)
        assertTrue("PS2 Gamepad" in com.ps2gamepad.bluetooth.PhoneNameControl.LEGACY_NAMES)
    }

    @Test fun batterySaverIsOnByDefaultStoredAndSentToTheHidSender() {
        val old = """{"activeId":"standard","profiles":[{"id":"standard","name":"Standard PS2","builtIn":true}]}"""
        assertTrue("default ON, also for settings saved by older versions", ProfileCodec.decode(old).batterySaver)
        val hid = FakeHid()
        val vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        vm.setBatterySaver(false)
        assertFalse(vm.profiles.value.batterySaver); assertEquals(false, hid.powerSaving)
        assertFalse(ProfileCodec.decode(ProfileCodec.encode(vm.profiles.value)).batterySaver)
        vm.setBatterySaver(true)
        assertEquals(true, hid.powerSaving)
    }
}
