package com.ps2gamepad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.Placement
import com.ps2gamepad.settings.DefaultProfiles
import com.ps2gamepad.settings.InMemoryTextStorage
import com.ps2gamepad.settings.ProfileManager
import com.ps2gamepad.settings.SettingsRepository
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LoadoutLimitTest {
    private var n = 0
    private val ids = { "l${n++}" }

    @Test fun builtInsDoNotCountAndTheLimitIsTen() {
        assertEquals("3.8.0: up to 10 loadouts", 10, ProfileManager.MAX_LOADOUTS)
        var s = DefaultProfiles.store()
        assertEquals(0, ProfileManager.loadouts(s).size)
        repeat(10) { i -> s = ProfileManager.createFrom(s, "Game $i", s.activeId, ids) }
        assertEquals(10, ProfileManager.loadouts(s).size)
        assertFalse(ProfileManager.canCreateLoadout(s))
        // every way of adding one more is refused
        assertEquals(s, ProfileManager.createFrom(s, "Sixth", s.activeId, ids))
        assertEquals(s, ProfileManager.create(s, "Sixth", ids))
        assertEquals(s, ProfileManager.duplicate(s, s.activeId, ids))
        // deleting one frees a slot
        val freed = ProfileManager.delete(s, ProfileManager.loadouts(s).first().id)
        assertTrue(ProfileManager.canCreateLoadout(freed))
    }

    @Test fun loadoutMayShareANameWithABuiltInPresetButNotWithAnotherLoadout() {
        var s = ProfileManager.createFrom(DefaultProfiles.store(), "God of War", DefaultProfiles.STANDARD, ids)
        assertEquals("God of War", s.active.name)
        s = ProfileManager.createFrom(s, "god of war", DefaultProfiles.STANDARD, ids)
        assertEquals("god of war 2", s.active.name)
    }

    @Test fun newLoadoutCopiesTheTemplateSetup() {
        var s = ProfileManager.updateActive(DefaultProfiles.store()) {
            it.copy(appearance = it.appearance.copy(showLabels = false, buttonScale = 1.2f),
                placements = mapOf(ControlId.L1 to Placement(0.3f, 0.3f)),
                extraButtons = listOf(com.ps2gamepad.model.ExtraButton("c1", ControllerButton.CROSS, steps = listOf(com.ps2gamepad.model.ComboStep(setOf(ControllerButton.L1, ControllerButton.R1))))))
        }
        s = ProfileManager.createFrom(s, "GTA", s.activeId, ids)
        val p = s.active
        assertEquals("GTA", p.name)
        assertFalse(p.builtIn)
        assertFalse(p.appearance.showLabels)
        assertEquals(1.2f, p.appearance.buttonScale)
        assertEquals(Placement(0.3f, 0.3f), p.placements[ControlId.L1])
        assertTrue("combo buttons are copied too", p.extraButtons.single().isCombo)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoadoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val disk = InMemoryTextStorage()
    private var n = 0
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.newVm(): MainViewModel {
        val repo = SettingsRepository(disk, this, debounceMs = 10)
        return MainViewModel(FakeHid(), MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L },
            settingsRepository = repo, newId = { "id${n++}" })
    }

    @Test fun openControllerAsksForALoadout() = runTest(dispatcher) {
        val vm = newVm(); runCurrent()
        vm.openController()
        assertEquals("Open Controller asks for the loadout", Screen.LOADOUTS, vm.screen.value)
        vm.playLoadout(DefaultProfiles.STANDARD)
        assertEquals(Screen.CONTROLLER, vm.screen.value)
        assertEquals(DefaultProfiles.STANDARD, vm.profiles.value.activeId)
    }

    @Test fun createOpensEditModeOnTheNewLoadout() = runTest(dispatcher) {
        val vm = newVm(); runCurrent()
        assertTrue(vm.createLoadout("God of War"))
        assertEquals("God of War", vm.profiles.value.active.name)
        assertEquals(Screen.CONTROLLER, vm.screen.value)
        assertTrue(vm.editMode.value)
    }

    @Test fun eachLoadoutKeepsItsOwnLayoutAndSettingsAcrossRestart() = runTest(dispatcher) {
        val vm = newVm(); runCurrent()
        vm.createLoadout("GTA"); val gta = vm.profiles.value.activeId
        vm.savePlacements(mapOf(ControlId.FACE to Placement(0.6f, 0.4f, 1.3f)))
        vm.updateAppearance { it.copy(buttonOpacity = 0.5f, buttonStyle = com.ps2gamepad.model.ButtonStyle.OUTLINE) }

        vm.createLoadout("NFS"); val nfs = vm.profiles.value.activeId
        vm.resetLayout()
        vm.updateAppearance { it.copy(stickScale = 1.3f, controlOpacity = mapOf(ControlId.DPAD to 0.4f)) }
        assertNotEquals(gta, nfs)

        vm.playLoadout(gta)
        val g = vm.profiles.value.active
        assertEquals(Placement(0.6f, 0.4f, 1.3f), g.placements[ControlId.FACE])
        assertEquals(0.5f, g.appearance.buttonOpacity)
        assertEquals(com.ps2gamepad.model.ButtonStyle.OUTLINE, g.appearance.buttonStyle)

        vm.flushSettings(); advanceUntilIdle()
        // "restart": a brand-new ViewModel reading the same storage
        val vm2 = newVm(); advanceUntilIdle()
        assertEquals(2, vm2.loadouts.size)
        vm2.playLoadout(nfs)
        val nf = vm2.profiles.value.active
        assertEquals("NFS", nf.name)
        assertEquals(1.3f, nf.appearance.stickScale)
        assertEquals("GTA keeps its own stick size", 1f, g.appearance.stickScale)
        assertTrue(nf.placements.isEmpty())
        assertEquals(0.4f, nf.appearance.opacityOf(ControlId.DPAD))
    }

    @Test fun eleventhLoadoutIsRefusedWithAMessage() = runTest(dispatcher) {
        val vm = newVm(); runCurrent()
        repeat(10) { assertTrue(vm.createLoadout("G$it")) }
        assertFalse(vm.canCreateLoadout)
        val screenBefore = vm.screen.value
        assertFalse(vm.createLoadout("G11"))
        assertEquals(10, vm.loadouts.size)
        assertEquals(screenBefore, vm.screen.value)
        assertTrue(vm.log.lines.value.last().contains("10 loadouts"))
        vm.deleteLoadout(vm.loadouts.first().id)
        assertTrue(vm.canCreateLoadout)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class LoadoutUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var vm: MainViewModel
    private var n = 0

    @Before fun setUp() {
        vm = MainViewModel(FakeHid(), MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L }, newId = { "u${n++}" })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle { vm.openController() }
    }

    @Test fun pickerShowsDefaultAndNewCard() {
        rule.onNodeWithText("Choose a loadout").assertIsDisplayed()
        rule.onNodeWithText("0 / 10 loadouts").assertIsDisplayed()
        rule.onNodeWithTag("loadout_standard").assertIsDisplayed()
        rule.onNodeWithTag("newLoadoutCard").assertIsDisplayed()
    }

    @Test fun createFromThePickerThenPlayFromThePicker() {
        rule.onNodeWithTag("newLoadoutName").performTextInput("God of War")
        rule.onNodeWithText("Create & arrange buttons").performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(Screen.CONTROLLER, vm.screen.value)
            assertTrue(vm.editMode.value)
            assertEquals("God of War", vm.profiles.value.active.name)
        }
        rule.onNodeWithText("EDIT LAYOUT").assertIsDisplayed()
        rule.onNodeWithTag("chip_Save").performClick()
        rule.runOnIdle { vm.openController() }
        rule.onNodeWithText("1 / 10 loadouts").assertIsDisplayed()
        rule.onNodeWithText("LAST USED").assertIsDisplayed()
        rule.onNodeWithTag("play_standard").performClick()
        rule.runOnIdle {
            assertEquals(DefaultProfiles.STANDARD, vm.profiles.value.activeId)
            assertEquals(Screen.CONTROLLER, vm.screen.value)
        }
    }

    @Test fun deleteNeedsConfirmation() {
        rule.runOnIdle { vm.createLoadout("Temp"); vm.openController() }
        val id = vm.loadouts.first().id
        rule.onNodeWithTag("delete_$id").performClick()
        rule.onNodeWithText("Delete loadout \"Temp\"?").assertIsDisplayed()
        rule.onNodeWithTag("confirmDeleteLoadout").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(vm.loadouts.isEmpty()) }
        rule.onNodeWithTag("loadout_$id").assertDoesNotExist()
    }

    @Test fun newCardDisappearsAtTheLimit() {
        rule.runOnIdle { repeat(10) { vm.createLoadout("Game $it") }; vm.openController() }
        rule.onNodeWithText("10 / 10 loadouts").assertIsDisplayed()
        rule.onNodeWithTag("newLoadoutCard").assertDoesNotExist()
    }

    @Suppress("unused") private fun scroll(tag: String) = rule.onNodeWithTag(tag).performScrollTo()
}
