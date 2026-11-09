package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.ps2gamepad.settings.DefaultProfiles
import com.ps2gamepad.settings.ProfileCodec
import com.ps2gamepad.settings.ProfileManager
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import com.ps2gamepad.ui.controller.FaceColors
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

/** 3.7.0 model: the Glossy style and Nintendo labels change only the look, never the signals. */
class NewStylesModelTest {
    @Test fun nintendoLabelsAreXOnTopAOnTheRight() {
        assertEquals(listOf("X", "A", "B", "Y"), (0..3).map { FaceColors.text(FaceLabels.NINTENDO, it) })
        assertEquals("X A B Y", FaceLabels.NINTENDO.label)
        // the other sets are unchanged
        assertEquals(listOf("Y", "B", "A", "X"), (0..3).map { FaceColors.text(FaceLabels.XBOX, it) })
        assertEquals(listOf("△", "○", "✕", "□"), (0..3).map { FaceColors.text(FaceLabels.PLAYSTATION, it) })
    }

    @Test fun glossyAndNintendoAreSavedAndLoaded() {
        val s = ProfileManager.updateActive(DefaultProfiles.store()) {
            it.copy(appearance = it.appearance.copy(buttonStyle = ButtonStyle.GLOSSY, faceLabels = FaceLabels.NINTENDO))
        }
        val back = ProfileCodec.decode(ProfileCodec.encode(s))
        assertEquals(ButtonStyle.GLOSSY, back.active.appearance.buttonStyle)
        assertEquals(FaceLabels.NINTENDO, back.active.appearance.faceLabels)
    }

    @Test fun olderSettingsStillLoadWithTheirStyle() {
        val old = """{"activeId":"standard","profiles":[{"id":"standard","name":"Standard PS2","builtIn":true,
            "appearance":{"buttonStyle":"OUTLINE","faceLabels":"PLAYSTATION"}}]}"""
        val s = ProfileCodec.decode(old)
        assertEquals(ButtonStyle.OUTLINE, s.active.appearance.buttonStyle)
        assertEquals(FaceLabels.PLAYSTATION, s.active.appearance.faceLabels)
    }
}

/** Real UI (landscape phone), MOCK Bluetooth: the one-button top bar, its menu and the style tiles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class ControllerMenuUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle {
            vm.onPermissionsChanged(true); vm.onForeground(); vm.register()
            hid.emit(HidEvent.AppStatusChanged(true)); hid.emit(HidEvent.ConnectionChanged(HostDevice("1", "RUPESH"), HidStateReducer.STATE_CONNECTED))
            vm.navigate(Screen.CONTROLLER)
        }
    }

    private fun openMenu() = rule.onNodeWithTag("menuButton").performClick()

    @Test fun whilePlayingOnlyOneButtonIsOnTop() {
        rule.onNodeWithTag("menuButton").assertIsDisplayed()
        rule.onNodeWithTag("playerBadge", useUnmergedTree = true).assertIsDisplayed() // P1 stays visible for two-player games
        for (gone in listOf("chip_Home", "chip_Live", "chip_Edit", "chip_Settings", "chip_gyro", "connectionChip")) {
            rule.onNodeWithTag(gone).assertDoesNotExist()
        }
    }

    @Test fun theMenuShowsConnectionPlayerAndEveryOption() {
        openMenu()
        rule.onNodeWithTag("quickMenu").assertIsDisplayed()
        rule.onNodeWithText("Connected: RUPESH").assertIsDisplayed()
        rule.onNodeWithText("Player 1").assertIsDisplayed()
        for (tag in listOf("chip_Home", "chip_Live", "chip_Edit", "chip_Settings")) rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun homeEditSettingsAndLiveDoWhatTheyDidBefore() {
        openMenu(); rule.onNodeWithTag("chip_Settings").performClick()
        rule.onNodeWithTag("controllerSettings").assertIsDisplayed()
        rule.onNodeWithTag("quickMenu").assertDoesNotExist()
        rule.onNodeWithTag("chip_Close").performClick()

        openMenu(); rule.onNodeWithTag("chip_Live").performClick()
        rule.onNodeWithText("pointers=", substring = true).assertIsDisplayed()
        openMenu(); rule.onNodeWithText("Live ON").assertIsDisplayed()
        rule.onNodeWithTag("chip_Live").performClick()
        rule.onNodeWithText("pointers=", substring = true).assertDoesNotExist()

        openMenu(); rule.onNodeWithTag("chip_Edit").performClick()
        rule.runOnIdle { assertTrue(vm.editMode.value); vm.setEditMode(false) }

        openMenu(); rule.onNodeWithTag("chip_Home").performClick()
        rule.runOnIdle { assertEquals(Screen.HOME, vm.screen.value) }
    }

    @Test fun tappingOutsideClosesTheMenuAndPressesNothing() {
        openMenu()
        hid.sent.clear()
        // tap on the scrim right where the face buttons are (bottom right)
        rule.onNodeWithTag("menuScrim").performTouchInput { down(Offset(width * 0.85f, height * 0.7f)); up() }
        rule.onNodeWithTag("quickMenu").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue("no button reached the PC", hid.sent.none { HidReportManager.decode(it).buttons != 0 })
        }
    }

    @Test fun openingTheMenuReleasesHeldButtons() {
        rule.runOnIdle { vm.input.setButton(ControllerButton.R1, true) }
        openMenu()
        rule.runOnIdle { assertEquals(0, vm.input.state.value.buttons) }
    }

    @Test fun glossyStyleAndNintendoLabelsApplyLiveFromTheTiles() {
        openMenu(); rule.onNodeWithTag("chip_Settings").performClick()
        rule.onNodeWithTag("style_GLOSSY").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(ButtonStyle.GLOSSY, vm.activeProfile.value.appearance.buttonStyle) }
        rule.onNodeWithTag("faces_NINTENDO").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(FaceLabels.NINTENDO, vm.activeProfile.value.appearance.faceLabels) }
        rule.onNodeWithText("Switch / 3DS").assertExists()
        rule.onNodeWithTag("chip_Close").performClick()

        // the top button now reads "X", but still sends the same signal as before (Triangle = button 4)
        rule.onNodeWithTag("btn_TRIANGLE").performTouchInput { down(center) }
        rule.runOnIdle { assertEquals(ControllerButton.TRIANGLE.mask, HidReportManager.decode(hid.sent.last()).buttons) }
        rule.onNodeWithTag("btn_TRIANGLE").performTouchInput { up() }
    }

    @Test fun everyStyleAndLabelSetDrawsWithoutCrashing() {
        for (st in ButtonStyle.entries) for (fl in FaceLabels.entries) {
            rule.runOnIdle { vm.updateAppearance { it.copy(buttonStyle = st, faceLabels = fl) } }
            rule.waitForIdle()
            rule.onNodeWithTag("btn_CROSS").assertIsDisplayed()
            rule.onNodeWithTag("stick_left").assertIsDisplayed()
        }
        assertFalse(ButtonStyle.entries.isEmpty())
    }
}

