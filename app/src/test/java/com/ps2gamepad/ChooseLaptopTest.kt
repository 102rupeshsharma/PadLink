package com.ps2gamepad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidStateReducer
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

/**
 * 3.5.0, two laptops (reported: "it always connects to the laptop it used before, I can't pick the
 * other one"). MOCK Bluetooth: the fake plays the Android stack, including its habit of reconnecting
 * the last laptop by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChooseLaptopViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val hid = FakeHid()
    private val scanner = FakeLaptopScanner()
    private val laptopA = HostDevice("AA:AA", "RUPESH-LAPTOP", isComputer = true)
    private val laptopB = HostDevice("BB:BB", "OFFICE-LAPTOP", isComputer = true)
    private var paired = listOf(laptopA, laptopB)
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { paired }, DiagnosticLog(), emptyList(), { 0L },
            phoneNameControl = FakePhoneName(), laptopScanner = scanner)
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open() { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart(); runCurrent() }
    private fun TestScope.connected(h: HostDevice) { hid.emit(HidEvent.ConnectionChanged(h, HidStateReducer.STATE_CONNECTED)); runCurrent() }
    private fun TestScope.disconnected(h: HostDevice) { hid.emit(HidEvent.ConnectionChanged(h, HidStateReducer.STATE_DISCONNECTED)); runCurrent() }

    @Test fun withTwoPairedLaptopsAndNoneUsedYetTheAppDoesNotPickOneByItself() = runTest(dispatcher) {
        open()
        assertEquals("two laptops: Connect must ask which one", null, vm.knownPc)
    }

    @Test fun switchingFromLaptopAToLaptopB() = runTest(dispatcher) {
        open()
        vm.chooseLaptop(laptopA); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        assertEquals(listOf(laptopA.address), hid.connects)
        connected(laptopA)
        assertEquals(laptopA, vm.knownPc)

        // now pick laptop B from the list
        vm.chooseLaptop(laptopB); runCurrent()
        assertEquals("A is disconnected first", 1, hid.disconnects)
        disconnected(laptopA)
        assertEquals("then B is connected", laptopB.address, hid.connects.last())
        advanceUntilIdle()
        assertFalse("never reconnects A on its own meanwhile", hid.connects.drop(1).contains(laptopA.address))
        connected(laptopB)
        assertEquals(laptopB, vm.knownPc)
        assertEquals("remembered for next time", laptopB.address, vm.profiles.value.lastConnection?.address)
    }

    @Test fun androidReconnectingTheOldLaptopIsOverruledByTheChoice() = runTest(dispatcher) {
        open()
        vm.chooseLaptop(laptopB); runCurrent()
        assertEquals("the stack's own reconnect-to-last is not requested", listOf(false), hid.autoConnectFlags)
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        // the stack (or Windows) still brings laptop A back
        connected(laptopA)
        assertEquals("A is dropped", 1, hid.disconnects)
        disconnected(laptopA)
        assertEquals(laptopB.address, hid.connects.last())
        connected(laptopB)
        assertEquals(laptopB, vm.knownPc)
    }

    @Test fun pairingANewLaptopFromTheLaptopDoesNotReconnectTheOldOne() = runTest(dispatcher) {
        paired = listOf(laptopA)
        open()
        vm.connectNow(); runCurrent(); hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        connected(laptopA)
        hid.connects.clear()

        vm.prepareForNewLaptop(); runCurrent() // "Make this phone visible"
        assertEquals("disconnects A so the new laptop can connect", 1, hid.disconnects)
        disconnected(laptopA)
        advanceTimeBy(30_000); runCurrent()
        assertTrue("no reconnect to A while waiting for the new laptop: ${hid.connects}", hid.connects.isEmpty())
        // laptop B pairs and connects from its Bluetooth settings
        paired = listOf(laptopA, laptopB)
        connected(laptopB)
        assertEquals(laptopB, vm.knownPc)
    }

    @Test fun searchPairAndConnectFromThePhone() = runTest(dispatcher) {
        paired = listOf(laptopA)
        open()
        assertTrue(vm.startSearch())
        assertEquals(1, scanner.starts)
        scanner.found.value = listOf(laptopB, HostDevice("CC:CC", "Galaxy Buds", isComputer = false))
        vm.pairAndConnect(laptopB); runCurrent()
        assertEquals(listOf(laptopB.address), scanner.pairRequests)
        assertEquals(laptopB.address, vm.pairing.value)
        paired = listOf(laptopA, laptopB)
        scanner.finishPairing(laptopB.address, ok = true); runCurrent()
        assertEquals(null, vm.pairing.value)
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        assertEquals("connects to the newly paired laptop, not A", listOf(laptopB.address), hid.connects)
        connected(laptopB)
        assertEquals(laptopB, vm.knownPc)
    }

    @Test fun refusedPairingConnectsNothing() = runTest(dispatcher) {
        open()
        vm.pairAndConnect(laptopB); runCurrent()
        scanner.finishPairing(laptopB.address, ok = false); runCurrent()
        assertEquals(null, vm.pairing.value)
        assertEquals(0, hid.registerCalls)
        assertTrue(hid.connects.isEmpty())
    }
}

/** Real UI on a portrait phone, MOCK Bluetooth: the "Choose a laptop" screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
class ChooseLaptopUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private val scanner = FakeLaptopScanner()
    private val laptopA = HostDevice("AA:AA", "RUPESH-LAPTOP", isComputer = true)
    private val laptopB = HostDevice("BB:BB", "OFFICE-LAPTOP", isComputer = true)
    private lateinit var vm: MainViewModel
    private var discoverable = 0
    private var scanAsked = 0

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { listOf(laptopA, laptopB) }, DiagnosticLog(), emptyList(), { 0L },
            phoneNameControl = FakePhoneName(), laptopScanner = scanner)
        rule.setContent { App(vm, AppActions({}, {}, { discoverable++ }, {}, { granted -> scanAsked++; granted() })) }
        rule.runOnIdle { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart() }
    }

    @Test fun connectWithTwoLaptopsOpensTheListAndBothAreThere() {
        rule.onNodeWithTag("homeAction").performClick()
        rule.runOnIdle { assertEquals(Screen.LAPTOPS, vm.screen.value) }
        rule.onNodeWithText("RUPESH-LAPTOP").assertIsDisplayed()
        rule.onNodeWithText("OFFICE-LAPTOP").assertIsDisplayed()
        rule.onNodeWithTag("laptop_BB:BB_btn").performClick()
        rule.runOnIdle { hid.emit(HidEvent.AppStatusChanged(true)) }
        rule.runOnIdle { assertEquals(listOf("BB:BB"), hid.connects) }
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptopB, HidStateReducer.STATE_CONNECTED)) }
        rule.runOnIdle { assertEquals("connected: back to the start screen", Screen.HOME, vm.screen.value) }
        rule.onNodeWithText("Connected to OFFICE-LAPTOP ✓").assertIsDisplayed()
        rule.onNodeWithText("Open Controller").assertIsDisplayed()
    }

    @Test fun homeAlwaysOffersAnotherLaptopAfterOneWasUsed() {
        rule.runOnIdle {
            vm.chooseLaptop(laptopA); hid.emit(HidEvent.AppStatusChanged(true))
            hid.emit(HidEvent.ConnectionChanged(laptopA, HidStateReducer.STATE_CONNECTED))
            vm.navigate(Screen.HOME)
        }
        rule.onNodeWithText("Choose another laptop / pair a new one").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(Screen.LAPTOPS, vm.screen.value) }
        rule.onNodeWithTag("laptop_AA:AA_btn").assertIsDisplayed() // shows "Connected ✓"
        rule.onNodeWithText("Connected ✓").assertIsDisplayed()
    }

    @Test fun searchShowsNewLaptopsAndPairsTheChosenOne() {
        rule.onNodeWithTag("homeAction").performClick()
        rule.onNodeWithTag("laptopSearch").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("asks for the scan permission first", 1, scanAsked); assertEquals(1, scanner.starts) }
        val newLaptop = HostDevice("DD:DD", "GAMING-PC", isComputer = true)
        rule.runOnIdle { scanner.found.value = listOf(laptopA, newLaptop); scanner.scanning.value = false }
        rule.onNodeWithText("GAMING-PC").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("found_DD:DD_btn").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("DD:DD"), scanner.pairRequests) }
    }

    @Test fun makeVisibleForPairingFromTheLaptop() {
        rule.onNodeWithTag("homeAction").performClick()
        rule.onNodeWithTag("laptopVisible").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, discoverable); assertEquals(listOf(false), hid.autoConnectFlags) }
    }
}
