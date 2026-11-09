package com.ps2gamepad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.bluetooth.InMemoryPlayerPrefs
import com.ps2gamepad.bluetooth.PlayerSlot
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** Names per player: player 1 must stay exactly what every earlier version used (no re-pairing). */
class PlayerSlotTest {
    @Test fun player1KeepsTheOldNames() {
        assertEquals("PadLink", PlayerSlot.phoneName(1))
        assertEquals("PadLink Gamepad", PlayerSlot.controllerName(1))
    }

    @Test fun otherPlayersGetTheirNumber() {
        assertEquals("PadLink P2", PlayerSlot.phoneName(2))
        assertEquals("PadLink Gamepad P2", PlayerSlot.controllerName(2))
        assertEquals("PadLink Gamepad P4", PlayerSlot.controllerName(4))
        assertEquals("out of range is clamped", "PadLink Gamepad P4", PlayerSlot.controllerName(9))
        assertEquals("PadLink", PlayerSlot.phoneName(0))
    }

    @Test fun ownNamesAreNeverMistakenForThePhonesRealName() {
        for (n in listOf("PadLink", "PadLink P2", "PadLink P4", "PS2 Gamepad")) assertTrue(n, PlayerSlot.isGamepadName(n))
        for (n in listOf("POCO F6", "Galaxy A54", "PadLink fan's phone", null)) assertFalse("$n", PlayerSlot.isGamepadName(n))
    }
}

/**
 * Two phones, one PC (MOCK Bluetooth: each phone has its own fake stack). Player 1 and player 2
 * must register under different names and both reach the PC at the same time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoPlayersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pc = HostDevice("AA:AA", "RUPESH-LAPTOP", isComputer = true)

    private class Phone(val vm: MainViewModel, val hid: FakeHid, val names: FakePhoneName, val prefs: InMemoryPlayerPrefs)

    private fun phone(realName: String, savedPlayer: Int = 1): Phone {
        val hid = FakeHid()
        val names = FakePhoneName(realName)
        val prefs = InMemoryPlayerPrefs(savedPlayer)
        val vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { listOf(pc) }, DiagnosticLog(), emptyList(), { 0L },
            phoneNameControl = names, playerPrefs = prefs)
        return Phone(vm, hid, names, prefs)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open(p: Phone) { p.vm.onPermissionsChanged(true); p.vm.onForeground(); p.vm.autoStart(); runCurrent() }
    private fun TestScope.connect(p: Phone) {
        p.vm.connectNow(); runCurrent()
        p.hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        p.hid.emit(HidEvent.ConnectionChanged(pc, HidStateReducer.STATE_CONNECTED)); runCurrent()
    }

    @Test fun aPhoneStartsAsPlayer1WithTheOldNames() = runTest(dispatcher) {
        val p = phone("POCO F6")
        open(p)
        assertEquals(1, p.vm.player.value)
        assertEquals("PadLink", p.names.name)
        assertEquals("PadLink Gamepad", p.hid.controllerName)
    }

    @Test fun makingTheSecondPhonePlayer2RenamesIt() = runTest(dispatcher) {
        val p = phone("Galaxy A54")
        open(p)
        assertTrue(p.vm.setPlayer(2)); runCurrent()
        assertEquals(2, p.vm.player.value)
        assertEquals("PadLink P2", p.names.name)
        assertEquals("PadLink P2", p.vm.phoneName.value)
        assertEquals("PadLink Gamepad P2", p.hid.controllerName)
        assertEquals("remembered", 2, p.prefs.load())
        assertEquals("the phone's real name is kept for later", "Galaxy A54", p.names.originalName)
    }

    @Test fun thePlayerIsRememberedWhenTheAppOpensAgain() = runTest(dispatcher) {
        val p = phone("Galaxy A54", savedPlayer = 2)
        assertEquals(2, p.vm.player.value)
        assertEquals("set before anything registers", "PadLink Gamepad P2", p.hid.controllerName)
        open(p)
        connect(p)
        assertEquals(listOf("PadLink Gamepad P2"), p.hid.registeredNames)
    }

    @Test fun twoPhonesOnOnePcPlayAtTheSameTime() = runTest(dispatcher) {
        val p1 = phone("POCO F6")
        val p2 = phone("Galaxy A54")
        open(p1); open(p2)
        p2.vm.setPlayer(2); runCurrent()
        connect(p1); connect(p2)

        assertTrue(p1.vm.hidState.value.isConnected)
        assertTrue(p2.vm.hidState.value.isConnected)
        assertEquals("PadLink Gamepad", p1.hid.registeredNames.last())
        assertEquals("PadLink Gamepad P2", p2.hid.registeredNames.last())
        assertNotEquals(p1.names.name, p2.names.name)

        // fight: player 1 punches (Square), player 2 blocks (L1) at the same moment
        p1.vm.input.setButton(ControllerButton.SQUARE, true)
        p2.vm.input.setButton(ControllerButton.L1, true)
        runCurrent()
        assertEquals("P1's phone sends only P1's Square", ControllerButton.SQUARE.mask, com.ps2gamepad.bluetooth.HidReportManager.decode(p1.hid.sent.last()).buttons)
        assertEquals("P2's phone sends only P2's L1", ControllerButton.L1.mask, com.ps2gamepad.bluetooth.HidReportManager.decode(p2.hid.sent.last()).buttons)
    }

    @Test fun cannotChangeThePlayerWhileConnected() = runTest(dispatcher) {
        val p = phone("POCO F6")
        open(p); connect(p)
        assertFalse(p.vm.setPlayer(2))
        assertEquals(1, p.vm.player.value)
        assertEquals("PadLink", p.names.name)
        assertTrue("still connected", p.vm.hidState.value.isConnected)
    }

    @Test fun changingAfterConnectWasTappedRegistersAgainWithTheNewName() = runTest(dispatcher) {
        val p = phone("Galaxy A54")
        open(p)
        p.vm.connectNow(); runCurrent()
        p.hid.emit(HidEvent.AppStatusChanged(true)); runCurrent() // registered as player 1, PC not connected yet
        assertTrue(p.vm.setPlayer(2)); runCurrent()
        assertEquals("old registration removed", 1, p.hid.unregisterCalls)
        assertFalse("waits for Connect again", p.vm.connectRequested.value)
        connect(p)
        assertEquals("PadLink Gamepad P2", p.hid.registeredNames.last())
    }

    @Test fun backToPlayer1AndTheRealNameStillRestores() = runTest(dispatcher) {
        val p = phone("Galaxy A54")
        open(p)
        p.vm.setPlayer(2); runCurrent()
        p.vm.setPlayer(1); runCurrent()
        assertEquals("PadLink", p.names.name)
        assertEquals("PadLink Gamepad", p.hid.controllerName)
        p.vm.restorePhoneName()
        assertEquals("Galaxy A54", p.names.name)
    }
}

/** Real UI on a portrait phone, MOCK Bluetooth: picking the player and seeing it while playing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
class TwoPlayersUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private val names = FakePhoneName("Galaxy A54")
    private val pc = HostDevice("AA:AA", "RUPESH-LAPTOP", isComputer = true)
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { listOf(pc) }, DiagnosticLog(), emptyList(), { 0L },
            phoneNameControl = names, playerPrefs = InMemoryPlayerPrefs())
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart() }
    }

    @Test fun tapP2OnTheHomeScreen() {
        rule.onNodeWithTag("player_2").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(2, vm.player.value) }
        rule.onNodeWithText("\"PadLink P2\"").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals("PadLink Gamepad P2", hid.controllerName) }
    }

    @Test fun whileConnectedThePlayerIsLocked() {
        rule.runOnIdle {
            vm.connectNow(); hid.emit(HidEvent.AppStatusChanged(true))
            hid.emit(HidEvent.ConnectionChanged(pc, HidStateReducer.STATE_CONNECTED))
        }
        rule.onNodeWithTag("player_2").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, vm.player.value) }
        rule.onNodeWithText("Disconnect to change the player.").performScrollTo().assertIsDisplayed()
    }

    @Test fun theControllerShowsWhichPlayerThisPhoneIs() {
        rule.onNodeWithTag("player_2").performScrollTo().performClick()
        rule.runOnIdle { vm.navigate(Screen.CONTROLLER) }
        rule.onNodeWithTag("playerBadge", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("P2").assertIsDisplayed()
    }
}
