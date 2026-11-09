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
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.ControllerProfile
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.ExtraButton
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ExtraButtonModelTest {
    @Test fun buttonHeldByTwoControlsStaysDownUntilBothRelease() {
        val sent = mutableListOf<ByteArray>()
        val input = ControllerInputManager({ r, _ -> sent += r; true })
        input.setButton(ControllerButton.CROSS, true)
        input.setButtonFrom("extra_a", ControllerButton.CROSS, true)
        input.setButtonFrom("extra_a", ControllerButton.CROSS, false)
        assertEquals("still held by the face buttons", ControllerButton.CROSS.mask, input.state.value.buttons)
        input.setButton(ControllerButton.CROSS, false)
        assertEquals(0, input.state.value.buttons)
        // releaseAll forgets every holder
        input.setButtonFrom("extra_a", ControllerButton.R3, true)
        input.releaseAll()
        input.setButton(ControllerButton.R3, true); input.setButton(ControllerButton.R3, false)
        assertEquals(0, input.state.value.buttons)
    }

    @Test fun extrasAreSanitizedLimitedAndStored() {
        val many = (0 until 12).map { ExtraButton("e$it", ControllerButton.L3, cx = 2f, cy = Float.NaN, scale = 9f) } + ExtraButton("e0", ControllerButton.R3)
        val s = ProfileManager.updateActive(DefaultProfiles.store()) { it.copy(extraButtons = many) }
        val back = ProfileCodec.decode(ProfileCodec.encode(s)).active.extraButtons
        assertEquals(ControllerProfile.MAX_EXTRA_BUTTONS, back.size)
        assertEquals(listOf(1f, 0.5f, 2f), back[0].let { listOf(it.cx, it.cy, it.scale) })
        assertEquals(ControllerButton.L3, back[0].button)
    }

    @Test fun oldSettingsWithoutExtrasStillLoad() {
        val old = """{"activeId":"standard","profiles":[{"id":"standard","name":"Standard PS2","builtIn":true}]}"""
        assertTrue(ProfileCodec.decode(old).active.extraButtons.isEmpty())
    }
}

/** Real UI, MOCK Bluetooth: adding a button in the layout editor, mapping it and playing with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class ExtraButtonUiTest {
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

    @Test fun addMapSaveAndPressAnExtraButton() {
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Edit is in the menu
        rule.onNodeWithTag("chip_Edit").performClick()
        rule.onNodeWithTag("chip_+ Button").performClick()
        rule.onNodeWithTag("extraEditor").assertIsDisplayed()
        rule.onNodeWithTag("map_R3").performClick()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        val extra = vm.activeProfile.value.extraButtons.single()
        assertEquals(ControllerButton.R3, extra.button)

        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag("extra_${extra.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(0, Offset(b.center.x - r.left, b.center.y - r.top)) }
        rule.waitForIdle()
        assertEquals(ControllerButton.R3.mask, HidReportManager.decode(hid.sent.last()).buttons)
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(0, HidReportManager.decode(hid.sent.last()).buttons)
    }

    @Test fun deletedExtraButtonIsGoneAfterSave() {
        rule.runOnIdle { vm.saveLayout(emptyMap(), listOf(ExtraButton("x1", ControllerButton.L1))) }
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Edit is in the menu
        rule.onNodeWithTag("chip_Edit").performClick()
        rule.onNodeWithTag("editx_x1").performClick()
        rule.onNodeWithTag("extra_delete").performClick()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        assertTrue(vm.activeProfile.value.extraButtons.isEmpty())
    }
}
