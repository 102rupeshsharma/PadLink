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

    @Test fun laptopPairedFromItsSideButNotConnectingIsConnectedFromThePhone() = runTest(dispatcher) {
        paired = listOf(laptopA)
        open()
        vm.connectNow(); runCurrent(); hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        connected(laptopA)
        vm.prepareForNewLaptop(); runCurrent()
        disconnected(laptopA)
        hid.connects.clear()
        // laptop B pairs from its Bluetooth settings: the PC says "Connected", but no gamepad connection arrives
        paired = listOf(laptopA, laptopB)
        scanner.pairedFromLaptop(laptopB.address)
        advanceTimeBy(MainViewModel.NEW_LAPTOP_WAIT_MS + 100); runCurrent()
        assertEquals("the phone connects to the new laptop itself", listOf(laptopB.address), hid.connects)
        connected(laptopB)
        assertEquals(laptopB, vm.knownPc)
    }

    @Test fun newLaptopIsOnlyConnectedOnceTheAppIsVisibleAgainAfterThePairingDialog() = runTest(dispatcher) {
        paired = listOf(laptopA)
        open()
        vm.prepareForNewLaptop(); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        val registersBefore = hid.registerCalls
        vm.onBackground() // the pairing dialog covers the app
        paired = listOf(laptopA, laptopB)
        scanner.pairedFromLaptop(laptopB.address)
        advanceTimeBy(20_000); runCurrent()
        assertTrue("nothing is started while the app is not visible: ${hid.connects}", hid.connects.isEmpty())
        assertEquals(registersBefore, hid.registerCalls)
        vm.onForeground()
        advanceTimeBy(MainViewModel.NEW_LAPTOP_WAIT_MS + 1_000); runCurrent()
        assertEquals(listOf(laptopB.address), hid.connects)
    }

    @Test fun laptopThatConnectsByItselfAfterPairingIsNotConnectedTwice() = runTest(dispatcher) {
        paired = listOf(laptopA)
        open()
        vm.prepareForNewLaptop(); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        paired = listOf(laptopA, laptopB)
        scanner.pairedFromLaptop(laptopB.address)
        advanceTimeBy(1_000); connected(laptopB) // Windows opens the gamepad connection itself
        advanceTimeBy(30_000); runCurrent()
        assertTrue("the phone must not connect on top of it: ${hid.connects}", hid.connects.isEmpty())
        assertEquals(0, hid.disconnects)
        assertEquals(laptopB, vm.knownPc)
    }

    @Test fun connectAfterMakingThePhoneVisibleStillTriesEveryPairedLaptop() = runTest(dispatcher) {
        paired = listOf(laptopA)
        open()
        vm.connectNow(); runCurrent(); hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        connected(laptopA)
        vm.prepareForNewLaptop(); runCurrent()
        disconnected(laptopA)
        paired = listOf(laptopA, laptopB)
        vm.refreshBondedHosts()
        hid.connects.clear()
        vm.connectNow(); runCurrent() // home screen's Connect: A (used last) is off, B must be tried next
        advanceTimeBy(MainViewModel.ATTEMPT_MS + 100); runCurrent()
        assertEquals(listOf(laptopA.address, laptopB.address), hid.connects)
    }

    @Test fun searchPairAndConnectFromThePhone() = runTest(dispatcher) {
        paired = listOf(laptopA)
        open()
        assertTrue(vm.startSearch())
        assertEquals(1, scanner.starts)
        scanner.found.value = listOf(laptopB, HostDevice("CC:CC", "Galaxy Buds", isComputer = false))
        vm.pairAndConnect(laptopB); runCurrent()
        // 3.6.2: the gamepad starts BEFORE pairing, so Windows pairs a game controller, not a plain phone
        assertEquals(1, hid.registerCalls)
        assertTrue("no pairing before the gamepad is registered", scanner.pairRequests.isEmpty())
        assertEquals(laptopB.address, vm.pairing.value)
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        assertEquals(listOf(laptopB.address), scanner.pairRequests)
        assertTrue("never auto-connects to the old laptop A meanwhile", hid.connects.isEmpty())
        paired = listOf(laptopA, laptopB)
        scanner.finishPairing(laptopB.address, ok = true); runCurrent()
        assertEquals(null, vm.pairing.value)
        assertEquals("connects to the newly paired laptop, not A", listOf(laptopB.address), hid.connects)
        connected(laptopB)
        assertEquals(laptopB, vm.knownPc)
    }

    @Test fun refusedPairingConnectsNothing() = runTest(dispatcher) {
        open()
        vm.pairAndConnect(laptopB); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        scanner.finishPairing(laptopB.address, ok = false); runCurrent()
        assertEquals(null, vm.pairing.value)
        assertTrue(hid.connects.isEmpty())
    }

    @Test fun gamepadThatCannotStartPairsNothing() = runTest(dispatcher) {
        open()
        vm.pairAndConnect(laptopB); runCurrent()
        hid.emit(HidEvent.RegisterRejected("registerApp() returned false")); runCurrent()
        assertTrue("pairing without the gamepad would make Windows see a plain phone", scanner.pairRequests.isEmpty())
        assertEquals(null, vm.pairing.value)
    }

    @Test fun gamepadThatNeverAnswersStopsWaiting() = runTest(dispatcher) {
        open()
        vm.pairAndConnect(laptopB); runCurrent()
        advanceTimeBy(MainViewModel.REGISTER_WAIT_MS + 1); runCurrent()
        assertTrue(scanner.pairRequests.isEmpty())
        assertEquals(null, vm.pairing.value)
    }

    @Test fun alreadyRunningGamepadPairsAtOnce() = runTest(dispatcher) {
        open()
        vm.prepareForNewLaptop(); runCurrent()
        hid.emit(HidEvent.AppStatusChanged(true)); runCurrent()
        vm.pairAndConnect(laptopB); runCurrent()
        assertEquals(listOf(laptopB.address), scanner.pairRequests)
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
        rule.runOnIdle { assertTrue("gamepad first, then pairing", scanner.pairRequests.isEmpty()) }
        rule.runOnIdle { hid.emit(HidEvent.AppStatusChanged(true)) }
        rule.runOnIdle { assertEquals(listOf("DD:DD"), scanner.pairRequests) }
    }

    @Test fun makeVisibleForPairingFromTheLaptop() {
        rule.onNodeWithTag("homeAction").performClick()
        rule.onNodeWithTag("laptopVisible").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, discoverable); assertEquals(listOf(false), hid.autoConnectFlags) }
    }
}

/**
 * A person adds a NEW laptop (reported: "the PC says connected, the app says connect again"). Real UI,
 * MOCK Bluetooth: laptop A was used before; laptop B is paired from its own Bluetooth settings and, like
 * Windows often does, never opens the gamepad connection by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
class NewLaptopJourneyUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private val scanner = FakeLaptopScanner()
    private val laptopA = HostDevice("AA:AA", "RUPESH-LAPTOP", isComputer = true)
    private val laptopB = HostDevice("BB:BB", "NEW-LAPTOP", isComputer = true)
    private var paired = listOf(laptopA)
    private lateinit var vm: MainViewModel
    private var discoverable = 0

    private fun passTime(ms: Long) = rule.runOnIdle { org.robolectric.shadows.ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { paired }, DiagnosticLog(), emptyList(), { 0L },
            phoneNameControl = FakePhoneName(), laptopScanner = scanner)
        rule.setContent { App(vm, AppActions({}, {}, { discoverable++ }, {}, { granted -> granted() })) }
        rule.runOnIdle { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart() }
        // the old laptop is in use
        rule.onNodeWithTag("homeAction").performClick()
        rule.runOnIdle { hid.emit(HidEvent.AppStatusChanged(true)) }
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptopA, HidStateReducer.STATE_CONNECTED)) }
        rule.onNodeWithText("Connected to RUPESH-LAPTOP ✓").assertIsDisplayed()
    }

    private fun makeVisibleAndPairFromTheNewLaptop() {
        rule.onNodeWithText("Choose another laptop / pair a new one").performScrollTo().performClick()
        rule.onNodeWithTag("laptopVisible").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, discoverable); assertEquals("the old laptop is let go", 1, hid.disconnects) }
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptopA, HidStateReducer.STATE_DISCONNECTED)) }
        rule.runOnIdle { hid.connects.clear(); paired = listOf(laptopA, laptopB); scanner.pairedFromLaptop(laptopB.address) }
    }

    @Test fun newLaptopPairedFromItsSideEndsConnectedInTheApp() {
        makeVisibleAndPairFromTheNewLaptop()
        passTime(MainViewModel.NEW_LAPTOP_WAIT_MS + 500)
        rule.runOnIdle { assertEquals("the phone connects to the new laptop by itself", listOf("BB:BB"), hid.connects) }
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptopB, HidStateReducer.STATE_CONNECTED)) }
        rule.runOnIdle { assertEquals(Screen.HOME, vm.screen.value) }
        rule.onNodeWithText("Connected to NEW-LAPTOP ✓").assertIsDisplayed()
        rule.onNodeWithText("Open Controller").assertIsDisplayed()
    }

    @Test fun ifTheNewLaptopNeverAnswersTheAppSaysSoAndOffersPairAgain() {
        makeVisibleAndPairFromTheNewLaptop()
        passTime(MainViewModel.NEW_LAPTOP_WAIT_MS + MainViewModel.ATTEMPT_MS + 1_000)
        rule.onNodeWithTag("noAnswerCard").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("NEW-LAPTOP did not answer").assertIsDisplayed()
        rule.onNodeWithTag("retryLaptop").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("BB:BB", "BB:BB"), hid.connects) }
    }

    @Test fun goingHomeAndTappingConnectAlsoReachesTheNewLaptop() {
        rule.onNodeWithText("Choose another laptop / pair a new one").performScrollTo().performClick()
        rule.onNodeWithTag("laptopVisible").performScrollTo().performClick()
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptopA, HidStateReducer.STATE_DISCONNECTED)) }
        // paired on the laptop, but this phone gave the app no "paired" event; the person goes back and taps Connect
        rule.runOnIdle { hid.connects.clear(); paired = listOf(laptopA, laptopB); vm.navigate(Screen.HOME); vm.onForeground() }
        passTime(MainViewModel.CONNECT_TIMEOUT_MS + 1_000) // "Connecting…" runs out
        rule.onNodeWithTag("homeAction").performClick()
        passTime(MainViewModel.ATTEMPT_MS + 500)
        rule.runOnIdle { assertEquals("old laptop first, then the new one", listOf("AA:AA", "BB:BB"), hid.connects) }
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptopB, HidStateReducer.STATE_CONNECTED)) }
        rule.onNodeWithText("Connected to NEW-LAPTOP ✓").assertIsDisplayed()
    }
}
