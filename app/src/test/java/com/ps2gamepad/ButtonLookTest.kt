package com.ps2gamepad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.model.Appearance
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ButtonShape
import com.ps2gamepad.model.ButtonStyle
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.ExtraButton
import com.ps2gamepad.model.FaceLabels
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 3.11.0 model: own text and shape per button, saved per layout. */
class ButtonLookModelTest {
    @Test fun textIsTrimmedCutToSixAndBlankMeansNone() {
        assertEquals("JUMP", ButtonShape.cleanLabel("  JUMP  "))
        assertEquals("ABCDEF", ButtonShape.cleanLabel("ABCDEFGH"))
        assertEquals("A B", ButtonShape.cleanLabel("A\nB"))
        assertNull(ButtonShape.cleanLabel("   "))
        assertNull(ButtonShape.cleanLabel(""))
        assertNull(ButtonShape.cleanLabel(null))
        assertEquals("🔥", ButtonShape.cleanLabel("🔥"))
    }

    @Test fun onlyRealButtonsCanHaveTextOrShape() {
        val a = Appearance(
            labels = mapOf(ControlId.L1 to "JUMP", ControlId.DPAD to "X", ControlId.LEFT_STICK to "Y", ControlId.R1 to "WAYTOOLONG", ControlId.R2 to "  "),
            shapes = mapOf(ControlId.CROSS to ButtonShape.SQUARE, ControlId.RIGHT_STICK to ButtonShape.CIRCLE),
        ).sanitized()
        assertEquals(mapOf(ControlId.L1 to "JUMP", ControlId.R1 to "WAYTOO"), a.labels)
        assertEquals(mapOf(ControlId.CROSS to ButtonShape.SQUARE), a.shapes)
        assertTrue(ControlId.DPAD !in ControlId.customizable && ControlId.LEFT_STICK !in ControlId.customizable)
        assertEquals(12, ControlId.customizable.size)
    }

    @Test fun savedAndLoadedPerLayoutWithAddedButtons() {
        var s = DefaultProfiles.store()
        s = ProfileManager.createFrom(s, "Tekken", s.activeId) { "t" }
        s = ProfileManager.updateActive(s) {
            it.copy(
                appearance = it.appearance.copy(labels = mapOf(ControlId.L1 to "BLOCK"), shapes = mapOf(ControlId.L1 to ButtonShape.CIRCLE), hidden = setOf(ControlId.L2, ControlId.R2)),
                extraButtons = listOf(ExtraButton("x", ControllerButton.CROSS, label = "DASH", shape = ButtonShape.SQUARE)),
            )
        }
        val back = ProfileCodec.decode(ProfileCodec.encode(s))
        val t = back.profiles.single { it.id == "t" }
        assertEquals("BLOCK", t.appearance.labelOf(ControlId.L1))
        assertEquals(ButtonShape.CIRCLE, t.appearance.shapeOf(ControlId.L1))
        assertEquals("DASH", t.extraButtons.single().label)
        assertEquals(ButtonShape.SQUARE, t.extraButtons.single().shape)
        val std = back.profiles.single { it.id == DefaultProfiles.STANDARD }
        assertTrue("the other layout is untouched", std.appearance.labels.isEmpty() && std.appearance.shapes.isEmpty())
    }

    @Test fun addedButtonTextIsCleanedToo() {
        assertEquals("ABCDEF", ExtraButton("x", ControllerButton.L1, label = " ABCDEFGHI ").sanitized().label)
        assertNull(ExtraButton("x", ControllerButton.L1, label = "  ").sanitized().label)
    }

    @Test fun olderSettingsLoadWithNormalLabelsAndShapes() {
        val old = """{"activeId":"standard","profiles":[{"id":"standard","name":"Standard PS2","builtIn":true,
            "appearance":{"hidden":["L2"]},"extraButtons":[{"id":"x","button":"L1"}]}]}"""
        val s = ProfileCodec.decode(old)
        assertTrue(s.active.appearance.labels.isEmpty() && s.active.appearance.shapes.isEmpty())
        assertNull(s.active.extraButtons.single().label)
        assertNull(s.active.extraButtons.single().shape)
    }
}

/** Real UI (landscape), MOCK Bluetooth: rename, reshape, delete and bring back buttons in the editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class ButtonLookUiTest {
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

    private fun openEditor() { rule.onNodeWithTag("menuButton").performClick(); rule.onNodeWithTag("chip_Edit").performClick() }
    private fun save() = rule.onNodeWithTag("chip_Save").performClick()
    private val a get() = vm.activeProfile.value.appearance

    /** Presses the middle of [tag] on the live controller and returns the buttons the PC got. */
    private fun press(tag: String): Int {
        val root = rule.onNodeWithTag("controller")
        val r = root.fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(0, Offset(b.center.x - r.left, b.center.y - r.top)) }
        rule.waitForIdle()
        val buttons = HidReportManager.decode(hid.sent.last()).buttons
        root.performTouchInput { up(0) }
        rule.waitForIdle()
        return buttons
    }

    @Test fun renameL1AndItStillSendsL1() {
        openEditor()
        rule.onNodeWithTag("edit_L1").performClick()
        rule.onNodeWithTag("labelField").performTextInput("JUMP")
        rule.runOnIdle { assertEquals("JUMP", a.labelOf(ControlId.L1)) }
        save()
        rule.onNodeWithText("JUMP").assertIsDisplayed()
        assertEquals(ControllerButton.L1.mask, press("btn_L1"))
    }

    @Test fun textLongerThanSixIsCut() {
        openEditor()
        rule.onNodeWithTag("edit_START").performClick()
        rule.onNodeWithTag("labelField").performTextInput("PAUSEGAME")
        rule.runOnIdle { assertEquals("PAUSEG", a.labelOf(ControlId.START)) }
    }

    @Test fun resetGivesTheNormalLabelBack() {
        rule.runOnIdle { vm.setControlLabel(ControlId.R1, "FIRE") }
        openEditor()
        rule.onNodeWithTag("edit_R1").performClick()
        rule.onNodeWithTag("labelReset").performClick()
        rule.runOnIdle { assertNull(a.labelOf(ControlId.R1)) }
        save()
        rule.onNodeWithText("FIRE").assertDoesNotExist()
    }

    @Test fun ownTextReplacesThePlayStationSymbolAndStillSendsTheSameButton() {
        rule.runOnIdle { vm.updateAppearance { it.copy(faceLabels = FaceLabels.PLAYSTATION) }; vm.setControlLabel(ControlId.TRIANGLE, "KICK") }
        rule.onNodeWithText("KICK").assertIsDisplayed()
        assertEquals(ControllerButton.TRIANGLE.mask, press("btn_TRIANGLE"))
    }

    @Test fun shapesChangeOnlyTheLookTheTouchAreaAndSignalStay() {
        val before = rule.onNodeWithTag("btn_L1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        openEditor()
        rule.onNodeWithTag("edit_L1").performClick()
        rule.onNodeWithTag("shape_SQUARE").performClick()
        rule.runOnIdle { assertEquals(ButtonShape.SQUARE, a.shapeOf(ControlId.L1)) }
        rule.onNodeWithTag("shape_RECTANGLE").performClick() // L1's normal shape: back to default
        rule.runOnIdle { assertNull(a.shapeOf(ControlId.L1)) }
        rule.onNodeWithTag("shape_CIRCLE").performClick()
        save()
        rule.runOnIdle { assertEquals(ButtonShape.CIRCLE, a.shapeOf(ControlId.L1)) }
        assertEquals("same box", before, rule.onNodeWithTag("btn_L1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertEquals(ControllerButton.L1.mask, press("btn_L1"))
    }

    @Test fun everyShapeOnEveryButtonStillSendsItsButton() {
        val buttons = mapOf(
            ControlId.L1 to ControllerButton.L1, ControlId.L2 to ControllerButton.L2, ControlId.R1 to ControllerButton.R1, ControlId.R2 to ControllerButton.R2,
            ControlId.L3 to ControllerButton.L3, ControlId.R3 to ControllerButton.R3, ControlId.SELECT to ControllerButton.SELECT, ControlId.START to ControllerButton.START,
            ControlId.TRIANGLE to ControllerButton.TRIANGLE, ControlId.CIRCLE to ControllerButton.CIRCLE, ControlId.CROSS to ControllerButton.CROSS, ControlId.SQUARE to ControllerButton.SQUARE,
        )
        for (shape in ButtonShape.entries) {
            rule.runOnIdle { for (id in buttons.keys) vm.setControlShape(id, shape) }
            rule.waitForIdle()
            for ((id, b) in buttons) assertEquals("$shape $id", b.mask, press("btn_${b.name}"))
        }
    }

    @Test fun everyShapeDrawsInEveryStyle() {
        for (style in ButtonStyle.entries) for (shape in ButtonShape.entries) {
            rule.runOnIdle { vm.updateAppearance { it.copy(buttonStyle = style) }; vm.setControlShape(ControlId.CROSS, shape); vm.setControlLabel(ControlId.CROSS, "OK") }
            rule.waitForIdle()
            rule.onNodeWithTag("btn_CROSS").assertIsDisplayed()
        }
    }

    @Test fun deleteL1L2R1R2WithTheRedXAndBringThemBack() {
        openEditor()
        for (id in listOf("L1", "L2", "R1", "R2")) {
            rule.onNodeWithTag("edit_$id").performClick()
            rule.onNodeWithTag("controlDeleteX").performClick()
        }
        rule.runOnIdle { assertEquals(setOf(ControlId.L1, ControlId.L2, ControlId.R1, ControlId.R2), a.hidden) }
        rule.onNodeWithTag("deletedRow").assertIsDisplayed()
        rule.onNodeWithTag("restore_L2").performClick()
        rule.runOnIdle { assertEquals(setOf(ControlId.L1, ControlId.R1, ControlId.R2), a.hidden) }
        save()
        rule.onNodeWithTag("btn_L2").assertExists()
        for (gone in listOf("btn_L1", "btn_R1", "btn_R2")) rule.onNodeWithTag(gone).assertDoesNotExist()
        assertEquals("the rest still works", ControllerButton.CROSS.mask, press("btn_CROSS"))
    }

    @Test fun addAButtonWithItsOwnTextAndShape() {
        openEditor()
        rule.onNodeWithTag("chip_+ Button").performClick()
        rule.onNodeWithTag("map_R3").performClick()
        rule.onNodeWithTag("labelField").performTextInput("DASH")
        rule.onNodeWithTag("shape_CIRCLE").performClick()
        save()
        val e = vm.activeProfile.value.extraButtons.single()
        assertEquals("DASH", e.label)
        assertEquals(ButtonShape.CIRCLE, e.shape)
        rule.onNodeWithText("DASH").assertIsDisplayed()
        assertEquals(ControllerButton.R3.mask, press("extra_${e.id}"))
    }

    @Test fun cancelDiscardsAnAddedButtonsTextAndShape() {
        rule.runOnIdle { vm.saveLayout(emptyMap(), listOf(ExtraButton("x", ControllerButton.L1))) }
        openEditor()
        rule.onNodeWithTag("editx_x").performClick()
        rule.onNodeWithTag("labelField").performTextInput("NOPE")
        rule.onNodeWithTag("chip_Cancel").performClick()
        rule.runOnIdle {
            val e = vm.activeProfile.value.extraButtons.single()
            assertNull(e.label); assertNull(e.shape)
        }
    }

    @Test fun theDpadAndSticksHaveNoTextOrShapeButCanBeDeleted() {
        openEditor()
        rule.onNodeWithTag("edit_DPAD").performClick()
        rule.onNodeWithTag("lookEditor").assertDoesNotExist()
        rule.onNodeWithTag("controlDeleteX").performClick()
        rule.runOnIdle { assertTrue(a.isHidden(ControlId.DPAD)) }
    }

    @Test fun eachLayoutKeepsItsOwnTextAndShape() {
        rule.runOnIdle { vm.createLoadout("Tekken"); vm.setEditMode(false); vm.setControlLabel(ControlId.L1, "BLOCK"); vm.setControlShape(ControlId.L1, ButtonShape.CIRCLE) }
        val tekken = vm.profiles.value.activeId
        rule.runOnIdle { vm.playLoadout(DefaultProfiles.STANDARD) }
        rule.onNodeWithText("BLOCK").assertDoesNotExist()
        rule.runOnIdle { assertNull(a.labelOf(ControlId.L1)) }
        rule.runOnIdle { vm.playLoadout(tekken) }
        rule.onNodeWithText("BLOCK").assertIsDisplayed()
    }
}
