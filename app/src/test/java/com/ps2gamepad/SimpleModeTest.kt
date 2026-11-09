package com.ps2gamepad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.bluetooth.PhoneNameControl
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ConnectionState
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.HidState
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.PermissionState
import com.ps2gamepad.model.RegistrationState
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import com.ps2gamepad.ui.SimpleAction
import com.ps2gamepad.ui.simpleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake phone-name control (no real adapter). */
class FakePhoneName(var name: String? = "POCO F6", var allowRename: Boolean = true) : PhoneNameControl {
    override var originalName: String? = null
    var renames = 0
    override fun current() = name
    override fun applyGamepadName(name: String): Boolean {
        if (this.name == name) return true
        if (!allowRename) return false
        if (originalName == null && !com.ps2gamepad.bluetooth.PlayerSlot.isGamepadName(this.name)) originalName = this.name
        this.name = name; renames++; return true
    }
    override fun restoreOriginal(): Boolean { val o = originalName ?: return false; name = o; originalName = null; return true }
}

/** Simple-mode status texts: pure function, every state. */
class SimpleStatusTest {
    private val pc = HostDevice("AA", "RUPESH")
    private val reg = HidState(true, RegistrationState.Registered, ConnectionState.WaitingForHost)
    private fun st(bt: BluetoothState = BluetoothState.ENABLED, perm: PermissionState = PermissionState.GRANTED, hid: HidState = reg, known: HostDevice? = null) =
        simpleStatus(bt, perm, hid, known, "PadLink")

    @Test fun permissionFirst() = assertEquals(SimpleAction.ALLOW_PERMISSION, st(bt = BluetoothState.DISABLED, perm = PermissionState.REQUIRED).action)
    @Test fun bluetoothOff() = assertEquals(SimpleAction.TURN_ON_BLUETOOTH, st(bt = BluetoothState.DISABLED).action)
    @Test fun registering() = assertEquals(SimpleAction.NONE_WAIT, st(hid = HidState(registration = RegistrationState.Registering)).action)
    @Test fun failedOffersRetry() {
        val s = st(hid = HidState(registration = RegistrationState.Failed("x")))
        assertEquals(SimpleAction.RETRY_SETUP, s.action); assertTrue(s.help.contains("x"))
    }
    @Test fun noPcMeansPairWithNameInstructions() {
        val s = st()
        assertEquals("one Connect button, also for pairing", SimpleAction.CONNECT, s.action)
        assertTrue(s.help.contains("\"PadLink\""))
    }
    @Test fun knownPcMeansConnect() {
        val s = st(known = pc, hid = reg.copy(connection = ConnectionState.Disconnected(pc)))
        assertEquals(SimpleAction.CONNECT, s.action); assertEquals("Connect", s.actionLabel)
    }
    @Test fun beforeTappingConnectNothingIsStartedAndConnectIsTheOnlyAction() {
        val idle = HidState()
        val s = simpleStatus(BluetoothState.ENABLED, PermissionState.GRANTED, idle, pc, "PadLink", started = false)
        assertEquals("Not connected", s.title)
        assertEquals(SimpleAction.CONNECT, s.action); assertEquals("Connect", s.actionLabel)
    }
    @Test fun afterTappingConnectItShowsConnecting() {
        val s = simpleStatus(BluetoothState.ENABLED, PermissionState.GRANTED, HidState(), pc, "PadLink", started = true, connecting = true)
        assertEquals("Connecting to RUPESH…", s.title); assertEquals(SimpleAction.NONE_WAIT, s.action)
    }
    @Test fun connectingWaits() = assertEquals(SimpleAction.NONE_WAIT, st(known = pc, hid = reg.copy(connection = ConnectionState.Connecting(pc))).action)
    @Test fun connectedOnlyFromRealConnection() {
        assertEquals(SimpleAction.OPEN_CONTROLLER, st(hid = reg.copy(connection = ConnectionState.Connected(pc))).action)
        assertFalse(st(known = pc).title.contains("Connected"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutoModeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val hid = FakeHid()
    private val bt = MutableStateFlow(BluetoothState.ENABLED)
    private val laptop = HostDevice("11:22", "RUPESH", isComputer = true)
    private val buds = HostDevice("33:44", "realme Buds T500 Pro", isComputer = false)
    private val names = FakePhoneName()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = MainViewModel(hid, bt, { listOf(buds, laptop) }, DiagnosticLog(), emptyList(), { 0L }, phoneNameControl = names)
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open() { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart(); runCurrent() }

    @Test fun earbudsAreNotOfferedAsPc() = runTest(dispatcher) {
        open()
        assertEquals(listOf(laptop), vm.computerHosts)
        assertEquals(laptop, vm.knownPc)
    }

    @Test fun openingTheAppRenamesThePhoneButDoesNotConnect() = runTest(dispatcher) {
        open()
        assertEquals(PhoneNameControl.GAMEPAD_NAME, names.name)
        assertEquals("POCO F6", names.originalName)
        assertEquals(PhoneNameControl.GAMEPAD_NAME, vm.phoneName.value)
        assertEquals("3.3.0: nothing starts before Connect", 0, hid.registerCalls)
        advanceUntilIdle()
        assertTrue(hid.connects.isEmpty())
        open() // again: no second rename
        assertEquals(1, names.renames)
        assertEquals(0, hid.registerCalls)
    }

    @Test fun connectStartsTheGamepadAndConnectsToTheKnownLaptop() = runTest(dispatcher) {
        open()
        assertFalse("laptop known: no pairing needed", vm.connectNow()); runCurrent()
        assertEquals(1, hid.registerCalls)
        assertTrue(vm.connectRequested.value)
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        advanceTimeBy(1_600); runCurrent()
        assertEquals(listOf(laptop.address), hid.connects)
        hid.emit(HidEvent.ConnectionChanged(laptop, HidStateReducer.STATE_CONNECTED)); runCurrent()
        assertFalse(vm.connectRequested.value)
        vm.connectNow(); runCurrent() // tapping again while registered: just connects, no re-registration
        assertEquals(1, hid.registerCalls)
    }

    @Test fun connectWithoutAKnownLaptopAsksForPairing() = runTest(dispatcher) {
        val vm2 = MainViewModel(hid, bt, { emptyList() }, DiagnosticLog(), emptyList(), { 0L }, phoneNameControl = names)
        vm2.onPermissionsChanged(true); vm2.onForeground(); vm2.autoStart(); runCurrent()
        assertTrue("no laptop known: the phone must be made visible", vm2.connectNow())
        assertEquals(1, hid.registerCalls)
        advanceTimeBy(MainViewModel.CONNECT_TIMEOUT_MS + 100); runCurrent()
        assertFalse("gives up showing Connecting after the timeout", vm2.connectRequested.value)
    }

    @Test fun nothingHappensWithoutPermissionOrBluetooth() = runTest(dispatcher) {
        vm.autoStart(); runCurrent()
        assertEquals(0, hid.registerCalls)
        vm.onPermissionsChanged(true); bt.value = BluetoothState.DISABLED; runCurrent()
        vm.autoStart(); runCurrent()
        assertEquals(0, hid.registerCalls)
    }

    @Test fun bluetoothTurnedOnDoesNotConnectByItselfButResumesAfterConnect() = runTest(dispatcher) {
        bt.value = BluetoothState.DISABLED; runCurrent()
        open()
        bt.value = BluetoothState.ENABLED; runCurrent()
        assertEquals("not started yet: stays off", 0, hid.registerCalls)
        vm.connectNow(); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        bt.value = BluetoothState.DISABLED; runCurrent()
        bt.value = BluetoothState.ENABLED; runCurrent()
        assertEquals("after Connect, Bluetooth coming back resumes as before", 2, hid.registerCalls)
    }

    @Test fun afterRegistrationItConnectsToTheKnownLaptop() = runTest(dispatcher) {
        open(); vm.connectNow(); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        advanceTimeBy(1_600); runCurrent()
        assertEquals(listOf(laptop.address), hid.connects)
    }

    @Test fun noAutoConnectIfTheStackAlreadyConnects() = runTest(dispatcher) {
        open(); vm.connectNow(); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        hid.emit(HidEvent.ConnectionChanged(laptop, HidStateReducer.STATE_CONNECTING)); runCurrent()
        advanceUntilIdle()
        assertTrue(hid.connects.isEmpty())
    }

    @Test fun userUnregisterStopsItUntilConnectIsTappedAgain() = runTest(dispatcher) {
        open(); vm.connectNow(); runCurrent()
        vm.unregister(); runCurrent()
        vm.autoStart(); vm.onForeground(); runCurrent()
        assertEquals(1, hid.registerCalls)
        vm.connectNow(); runCurrent()
        assertEquals(2, hid.registerCalls)
    }

    @Test fun renameRefusedIsReportedNotHidden() = runTest(dispatcher) {
        names.allowRename = false
        open()
        assertEquals("POCO F6", vm.phoneName.value)
        assertTrue(vm.log.lines.value.any { it.contains("Could not rename") })
    }

    @Test fun restorePhoneName() = runTest(dispatcher) {
        open()
        vm.restorePhoneName()
        assertEquals("POCO F6", names.name)
        assertEquals("POCO F6", vm.phoneName.value)
    }
}

/** The simple home screen on the real UI (Robolectric, MOCK Bluetooth). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class SimpleHomeUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private val laptop = HostDevice("11:22", "RUPESH")
    private val names = FakePhoneName()
    private lateinit var vm: MainViewModel
    private var discoverableRequests = 0

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), emptyList(), { 0L }, phoneNameControl = names)
        rule.setContent { App(vm, AppActions({}, {}, { discoverableRequests++ }, {})) }
    }

    @Test fun walksThroughTheStepsInPlainWords() {
        rule.onNodeWithText("Allow Bluetooth access").assertIsDisplayed()
        rule.runOnIdle { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart() }
        rule.onNodeWithText("Not connected").assertIsDisplayed()
        rule.runOnIdle { assertEquals("opening the app does not connect", 0, hid.registerCalls) }
        rule.onNodeWithText("Connect").assertIsDisplayed()
        rule.onNodeWithText("\"PadLink\"").assertIsDisplayed()
        rule.onNodeWithTag("homeAction").performClick()
        rule.runOnIdle { assertEquals("no laptop known yet: Connect opens the laptop list", Screen.LAPTOPS, vm.screen.value) }
        rule.onNodeWithTag("laptopVisible").performScrollTo().performClick() // first time: pair from the laptop
        rule.runOnIdle { assertEquals(1, hid.registerCalls); assertEquals(1, discoverableRequests) }
        rule.onNodeWithText("Connecting…").assertExists() // status line at the top (scrolled away after tapping the button)
        rule.runOnIdle { hid.emit(HidEvent.AppStatusChanged(true)) }
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptop, HidStateReducer.STATE_CONNECTED)) }
        rule.runOnIdle { assertEquals("connected: back to the start screen by itself", Screen.HOME, vm.screen.value) }
        rule.onNodeWithText("Connected to RUPESH ✓").assertIsDisplayed()
        rule.onNodeWithText("Open Controller").performClick()
        rule.runOnIdle { assertEquals("Open Controller asks for the loadout", Screen.LOADOUTS, vm.screen.value) }
        rule.onNodeWithTag("play_standard").performClick()
        rule.runOnIdle { assertEquals(Screen.CONTROLLER, vm.screen.value) }
    }

    @Test fun noTestingLogsButtonOnHome() {
        rule.onNodeWithTag("homeLogs").assertDoesNotExist()
        rule.onNodeWithText("Logs (testing)").assertDoesNotExist()
    }

    @Test fun noStylesTileAnyMore() {
        rule.onNodeWithTag("homeStyles").assertDoesNotExist()
        rule.onNodeWithTag("homePreview").performScrollTo().assertIsDisplayed() // below the Player row since 3.6.0
    }

    @Test fun moreOpensTheFullMenu() {
        rule.onNodeWithTag("homeMore").performScrollTo().performClick()
        rule.onNodeWithTag("dashboard").assertIsDisplayed()
    }
}
