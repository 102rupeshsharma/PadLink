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
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.ReportSink
import com.ps2gamepad.controller.StickVector
import com.ps2gamepad.model.Appearance
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.ExtraButton
import com.ps2gamepad.model.FaceLabels
import com.ps2gamepad.model.Hat
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.Stick
import com.ps2gamepad.model.StickDirection
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 3.8.0 model: stick-direction buttons, hidden controls, saving. */
class LayoutFeaturesModelTest {
    @Test fun everyDirectionPushesTheRightAxes() {
        // HID: y negative = up
        val expect = mapOf(
            Hat.UP to (0f to -1f), Hat.UP_RIGHT to (1f to -1f), Hat.RIGHT to (1f to 0f), Hat.DOWN_RIGHT to (1f to 1f),
            Hat.DOWN to (0f to 1f), Hat.DOWN_LEFT to (-1f to 1f), Hat.LEFT to (-1f to 0f), Hat.UP_LEFT to (-1f to -1f),
        )
        for ((h, xy) in expect) {
            val d = StickDirection(Stick.LEFT, h)
            assertEquals(h.name, xy.first, d.x)
            assertEquals(h.name, xy.second, d.y)
        }
        assertEquals("L↖", StickDirection(Stick.LEFT, Hat.UP_LEFT).label)
        assertEquals("R↘", StickDirection(Stick.RIGHT, Hat.DOWN_RIGHT).label)
        assertEquals("diagonals are offered first", listOf(Hat.UP_LEFT, Hat.UP_RIGHT, Hat.DOWN_LEFT, Hat.DOWN_RIGHT), StickDirection.ORDER.take(4))
        assertEquals(8, StickDirection.ORDER.toSet().size)
    }

    @Test fun aStickDirectionButtonIsNotACombo() {
        val e = ExtraButton("d1", ControllerButton.CROSS, stickDirection = StickDirection(Stick.RIGHT, Hat.UP))
        assertTrue(e.isStickDirection)
        assertFalse(e.isCombo)
        assertNull("a neutral direction is dropped", ExtraButton("d2", ControllerButton.CROSS, stickDirection = StickDirection(dir = Hat.NEUTRAL)).sanitized().stickDirection)
    }

    @Test fun hiddenControlsAndStickDirectionsAreSavedPerLayout() {
        var s = DefaultProfiles.store()
        s = ProfileManager.createFrom(s, "No triggers", s.activeId) { "a" }
        s = ProfileManager.updateActive(s) {
            it.copy(
                appearance = it.appearance.copy(hidden = setOf(ControlId.L2, ControlId.R2), faceLabels = FaceLabels.PLAYSTATION),
                extraButtons = listOf(ExtraButton("d1", ControllerButton.CROSS, 0.3f, 0.4f, stickDirection = StickDirection(Stick.LEFT, Hat.DOWN_LEFT))),
            )
        }
        val back = ProfileCodec.decode(ProfileCodec.encode(s))
        val mine = back.profiles.single { it.id == "a" }
        assertEquals(setOf(ControlId.L2, ControlId.R2), mine.appearance.hidden)
        assertEquals(FaceLabels.PLAYSTATION, mine.appearance.faceLabels)
        assertEquals(StickDirection(Stick.LEFT, Hat.DOWN_LEFT), mine.extraButtons.single().stickDirection)
        val standard = back.profiles.single { it.id == DefaultProfiles.STANDARD }
        assertTrue("the other layout keeps every button", standard.appearance.hidden.isEmpty())
    }

    @Test fun theLegacyFaceClusterCannotBeHidden() {
        assertEquals(setOf(ControlId.L2), Appearance(hidden = setOf(ControlId.L2, ControlId.FACE)).sanitized().hidden)
    }

    @Test fun settingsFromOlderVersionsStillLoad() {
        val old = """{"activeId":"standard","profiles":[{"id":"standard","name":"Standard PS2","builtIn":true,
            "appearance":{"faceLabels":"XBOX"},"extraButtons":[{"id":"x","button":"L1"}]}]}"""
        val s = ProfileCodec.decode(old)
        assertTrue(s.active.appearance.hidden.isEmpty())
        assertNull(s.active.extraButtons.single().stickDirection)
    }
}

/** The input manager: a held direction button wins over the thumb; releasing gives the thumb back. */
class StickDirectionInputTest {
    private val sent = mutableListOf<ByteArray>()
    private val m = ControllerInputManager(object : ReportSink {
        override fun send(report: ByteArray, eventUptimeMs: Long): Boolean { sent += report.copyOf(); return true }
    })
    private fun last() = HidReportManager.decode(sent.last())

    @Test fun diagonalPushesBothAxesFullyAndReleaseReturnsToCentre() {
        m.setStickPushFrom("d", Stick.LEFT, StickVector(-1f, -1f))
        assertEquals(-127, last().x); assertEquals(-127, last().y)
        m.setStickPushFrom("d", Stick.LEFT, null)
        assertEquals(0, last().x); assertEquals(0, last().y)
    }

    @Test fun releasingTheButtonGivesTheStickBackToTheThumb() {
        m.setStick(Stick.LEFT, StickVector(0.5f, 0f))
        val thumb = last().x
        m.setStickPushFrom("d", Stick.LEFT, StickVector(1f, 1f))
        assertEquals(127, last().x); assertEquals(127, last().y)
        m.setStickPushFrom("d", Stick.LEFT, null)
        assertEquals("back to where the thumb is", thumb, last().x)
        assertEquals(0, last().y)
    }

    @Test fun rightStickButtonWinsOverTheGyroAndTheLeftStickIsUntouched() {
        m.setGyroStick(StickVector(0.3f, 0.3f))
        m.setStickPushFrom("d", Stick.RIGHT, StickVector(1f, -1f))
        assertEquals(127, last().z); assertEquals(-127, last().rz)
        assertEquals(0, last().x); assertEquals(0, last().y)
        m.setStickPushFrom("d", Stick.RIGHT, null)
        assertTrue("gyro again", last().z in 1..60)
    }

    @Test fun twoButtonsTheLastPressedCountsAndLettingGoFallsBack() {
        m.setStickPushFrom("a", Stick.LEFT, StickVector(-1f, 0f))
        m.setStickPushFrom("b", Stick.LEFT, StickVector(0f, -1f))
        assertEquals(0, last().x); assertEquals(-127, last().y)
        m.setStickPushFrom("b", Stick.LEFT, null)
        assertEquals(-127, last().x); assertEquals(0, last().y)
    }

    @Test fun releaseAllClearsDirectionButtons() {
        m.setStickPushFrom("d", Stick.RIGHT, StickVector(1f, 1f))
        m.releaseAll()
        assertEquals(0, last().z); assertEquals(0, last().rz)
        m.setStick(Stick.RIGHT, StickVector.ZERO)
        assertEquals("nothing comes back after a reset", 0, last().z)
    }

    @Test fun buttonsAndTheDpadAreUnaffected() {
        m.setButton(ControllerButton.CROSS, true)
        m.setStickPushFrom("d", Stick.LEFT, StickVector(-1f, -1f))
        assertEquals(ControllerButton.CROSS.mask, last().buttons)
        assertEquals(Hat.NEUTRAL.value, last().hat)
    }
}

/** Real UI (landscape), MOCK Bluetooth: hiding buttons, stick-direction buttons, 10 layouts with their own settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class LayoutFeaturesUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private lateinit var vm: MainViewModel
    private var n = 0

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L }, newId = { "id${n++}" })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle {
            vm.onPermissionsChanged(true); vm.onForeground(); vm.register()
            hid.emit(HidEvent.AppStatusChanged(true)); hid.emit(HidEvent.ConnectionChanged(HostDevice("1", "PC"), HidStateReducer.STATE_CONNECTED))
            vm.navigate(Screen.CONTROLLER)
        }
    }

    private fun openSettings() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("chip_Settings").performClick()
    }

    private fun openEditor() {
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("chip_Edit").performClick()
    }

    @Test fun hideL2AndR2InSettingsThenShowThemAgain() {
        rule.onNodeWithTag("btn_L2").assertExists()
        openSettings()
        rule.onNodeWithTag("show_L2").performScrollTo().performClick()
        rule.onNodeWithTag("show_R2").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(setOf(ControlId.L2, ControlId.R2), vm.activeProfile.value.appearance.hidden) }
        rule.onNodeWithTag("btn_L2").assertDoesNotExist()
        rule.onNodeWithTag("btn_R2").assertDoesNotExist()
        rule.onNodeWithTag("btn_L1").assertExists() // everything else stays
        rule.onNodeWithTag("show_L2").performScrollTo().performClick()
        rule.onNodeWithTag("btn_L2").assertExists()
        rule.onNodeWithTag("btn_R2").assertDoesNotExist()
    }

    @Test fun whereAHiddenButtonWasNothingIsPressed() {
        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val l2 = rule.onNodeWithTag("btn_L2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { vm.setControlHidden(ControlId.L2, true) }
        rule.waitForIdle()
        hid.sent.clear()
        root.performTouchInput { down(0, Offset(l2.center.x - r.left, l2.center.y - r.top)) }
        rule.waitForIdle()
        assertTrue("no L2 sent", hid.sent.none { HidReportManager.decode(it).buttons and ControllerButton.L2.mask != 0 })
        root.performTouchInput { up(0) }
    }

    @Test fun hideTheSelectedControlInTheEditor() {
        openEditor()
        rule.onNodeWithTag("edit_R2").performClick()
        rule.onNodeWithTag("hide_control").performClick()
        rule.runOnIdle { assertTrue(vm.activeProfile.value.appearance.isHidden(ControlId.R2)) }
        rule.onNodeWithTag("edit_R2").assertDoesNotExist()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.onNodeWithTag("btn_R2").assertDoesNotExist()
    }

    @Test fun addARightStickDiagonalButtonAndPressIt() {
        openEditor()
        rule.onNodeWithTag("chip_+ Stick ↗").performClick()
        rule.onNodeWithTag("stickDirEditor").assertIsDisplayed()
        rule.onNodeWithTag("dir_stick_RIGHT").performClick()
        rule.onNodeWithTag("dir_DOWN_RIGHT").performClick()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.waitForIdle()
        val e = vm.activeProfile.value.extraButtons.single()
        assertEquals(StickDirection(Stick.RIGHT, Hat.DOWN_RIGHT), e.stickDirection)
        rule.onNodeWithText("R↘").assertIsDisplayed()

        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag("extra_${e.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(0, Offset(b.center.x - r.left, b.center.y - r.top)) }
        rule.waitForIdle()
        val held = HidReportManager.decode(hid.sent.last())
        assertEquals(127, held.z); assertEquals(127, held.rz)
        assertEquals("no button pressed", 0, held.buttons)
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        val after = HidReportManager.decode(hid.sent.last())
        assertEquals(0, after.z); assertEquals(0, after.rz)
    }

    @Test fun eachLayoutKeepsItsOwnLabelsAndHiddenButtons() {
        rule.runOnIdle {
            vm.createLoadout("Tekken"); vm.setEditMode(false)
            vm.updateAppearance { it.copy(faceLabels = FaceLabels.PLAYSTATION) }
            vm.setControlHidden(ControlId.L2, true)
        }
        val tekken = vm.profiles.value.activeId
        rule.runOnIdle {
            // a new layout starts as a copy of the one in use (existing behaviour): Mario shows L2 again
            vm.createLoadout("Mario"); vm.setEditMode(false)
            assertTrue("copied from Tekken", vm.activeProfile.value.appearance.isHidden(ControlId.L2))
            vm.updateAppearance { it.copy(faceLabels = FaceLabels.NINTENDO) }
            vm.setControlHidden(ControlId.L2, false)
        }
        rule.onNodeWithTag("btn_L2").assertExists()
        rule.onNodeWithText("X").assertExists() // Nintendo: X on top
        rule.runOnIdle { vm.playLoadout(tekken) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(FaceLabels.PLAYSTATION, vm.activeProfile.value.appearance.faceLabels) }
        rule.onNodeWithTag("btn_L2").assertDoesNotExist()
        openSettings()
        rule.onNodeWithText("Saved to this layout: Tekken").assertIsDisplayed()
    }

    @Test fun tenLayoutsCanBeMade() {
        rule.runOnIdle { repeat(10) { i -> assertTrue("layout ${i + 1}", vm.createLoadout("Game $i")); vm.setEditMode(false) } }
        rule.runOnIdle {
            assertFalse(vm.createLoadout("Game 11"))
            assertEquals(10, vm.loadouts.size)
        }
    }
}
