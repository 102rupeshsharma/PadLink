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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 3.6.1, reported: "I connected laptop A, then laptop B. Now I am at laptop A again: the app still shows
 * B and I cannot connect, and pairing A again says cannot connect to RUPESH." MOCK Bluetooth: the fake
 * stack plays laptops that are switched off (never answer) and a laptop whose pairing is broken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectAnyLaptopViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val hid = FakeHid()
    private val scanner = FakeLaptopScanner()
    private val rupesh = HostDevice("AA:AA", "RUPESH", isComputer = true)
    private val office = HostDevice("BB:BB", "OFFICE-LAPTOP", isComputer = true)
    private var paired = listOf(rupesh, office)
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { paired }, DiagnosticLog(), emptyList(), { 0L },
            phoneNameControl = FakePhoneName(), laptopScanner = scanner)
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open() { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart(); runCurrent() }
    private fun TestScope.registered() { hid.emit(HidEvent.AppStatusChanged(true)); runCurrent() }
    private fun TestScope.answers(h: HostDevice) { hid.emit(HidEvent.ConnectionChanged(h, HidStateReducer.STATE_CONNECTED)); runCurrent() }
    private fun TestScope.leaves(h: HostDevice) { hid.emit(HidEvent.ConnectionChanged(h, HidStateReducer.STATE_DISCONNECTED)); runCurrent() }

    /** Used OFFICE-LAPTOP last. Now only RUPESH is on. */
    private fun TestScope.usedOfficeLast() {
        open()
        vm.chooseLaptop(office); runCurrent(); registered()
        answers(office)
        vm.disconnect(); leaves(office)
        hid.connects.clear()
        assertEquals(office, vm.knownPc)
    }

    @Test fun connectTriesTheLastLaptopThenTheOther() = runTest(dispatcher) {
        usedOfficeLast()
        vm.connectNow(); runCurrent()
        assertEquals("the laptop used last first", listOf(office.address), hid.connects)
        assertEquals(office, vm.connectingTo.value)

        advanceTimeBy(MainViewModel.ATTEMPT_MS + 1); runCurrent() // OFFICE-LAPTOP is switched off: no answer
        assertEquals("then the other paired laptop", listOf(office.address, rupesh.address), hid.connects)
        assertEquals(rupesh, vm.connectingTo.value)

        answers(rupesh)
        assertTrue(vm.hidState.value.isConnected)
        assertEquals("remembered for next time", rupesh, vm.knownPc)
        assertNull(vm.connectingTo.value)
        assertNull("no problem shown: it worked", vm.connectProblem.value)

        advanceTimeBy(60_000); runCurrent()
        assertEquals("nothing else is tried after connecting", 2, hid.connects.size)
    }

    @Test fun nextTimeTheLaptopThatWorkedIsTriedFirst() = runTest(dispatcher) {
        usedOfficeLast()
        vm.connectNow(); runCurrent()
        advanceTimeBy(MainViewModel.ATTEMPT_MS + 1); runCurrent()
        answers(rupesh)
        vm.disconnect(); leaves(rupesh)
        hid.connects.clear()

        vm.connectNow(); runCurrent()
        assertEquals(listOf(rupesh.address), hid.connects)
    }

    @Test fun noLaptopAnswers_ConnectStopsAndCanBeTappedAgain() = runTest(dispatcher) {
        usedOfficeLast()
        vm.connectNow(); runCurrent()
        advanceTimeBy(2 * MainViewModel.ATTEMPT_MS + 2); runCurrent()
        assertEquals(listOf(office.address, rupesh.address), hid.connects)
        assertEquals("Connecting… ends", false, vm.connectRequested.value)
        assertNull(vm.connectingTo.value)
        assertNull("not a broken pairing: the user did not pick one laptop", vm.connectProblem.value)

        hid.connects.clear()
        vm.connectNow(); runCurrent()
        assertEquals(listOf(office.address), hid.connects)
    }

    @Test fun aChosenLaptopThatNeverAnswersOffersPairAgain() = runTest(dispatcher) {
        usedOfficeLast()
        vm.chooseLaptop(rupesh); runCurrent() // RUPESH removed PadLink: it never accepts the phone
        assertEquals(listOf(rupesh.address), hid.connects)
        advanceTimeBy(MainViewModel.ATTEMPT_MS + 1); runCurrent()
        assertEquals("only the chosen laptop is tried", listOf(rupesh.address), hid.connects)
        assertEquals(rupesh, vm.connectProblem.value)
        assertEquals(false, vm.connectRequested.value)
    }

    @Test fun pairAgainForgetsTheBrokenPairingThenPairsAndConnects() = runTest(dispatcher) {
        usedOfficeLast()
        vm.chooseLaptop(rupesh); runCurrent()
        advanceTimeBy(MainViewModel.ATTEMPT_MS + 1); runCurrent()

        vm.pairAgain(rupesh); runCurrent()
        assertEquals("old pairing removed first", listOf(rupesh.address), scanner.forgetRequests)
        assertEquals("then paired fresh", listOf(rupesh.address), scanner.pairRequests)
        assertNull(vm.connectProblem.value)
        assertEquals(rupesh.address, vm.pairing.value)

        scanner.finishPairing(rupesh.address, ok = true); runCurrent() // code confirmed on both screens
        assertEquals(rupesh.address, hid.connects.last())
        answers(rupesh)
        assertTrue(vm.hidState.value.isConnected)
        assertEquals(rupesh, vm.knownPc)
    }

    @Test fun phoneThatBlocksForgettingSendsTheUserToBluetoothSettings() = runTest(dispatcher) {
        scanner.canForget = false
        usedOfficeLast()
        vm.chooseLaptop(rupesh); runCurrent()
        advanceTimeBy(MainViewModel.ATTEMPT_MS + 1); runCurrent()
        vm.pairAgain(rupesh); runCurrent()
        assertEquals(rupesh, vm.forgetInSettings.value)
        assertTrue("never pairs on top of the broken pairing", scanner.pairRequests.isEmpty())
        assertNull(vm.pairing.value)
    }

    @Test fun theLaptopConnectingByItselfIsAcceptedAndStopsTheSearch() = runTest(dispatcher) {
        usedOfficeLast()
        vm.connectNow(); runCurrent()
        answers(rupesh) // user clicked Connect on RUPESH in Windows while the phone was trying OFFICE-LAPTOP
        assertTrue(vm.hidState.value.isConnected)
        assertEquals(rupesh, vm.knownPc)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("nothing else tried", listOf(office.address), hid.connects)
    }

    @Test fun disconnectWhileTryingStopsTrying() = runTest(dispatcher) {
        usedOfficeLast()
        vm.connectNow(); runCurrent()
        vm.disconnect()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(office.address), hid.connects)
        assertNull(vm.connectingTo.value)
    }

    @Test fun oneLaptopOnlyIsTriedOnce() = runTest(dispatcher) {
        paired = listOf(rupesh)
        open()
        vm.connectNow(); runCurrent(); registered()
        advanceTimeBy(1_600); runCurrent() // auto connect after registering
        assertEquals(listOf(rupesh.address), hid.connects)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(rupesh.address), hid.connects)
    }
}

/** Real UI on a portrait phone, MOCK Bluetooth: the "did not answer" card and Pair again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
class ConnectAnyLaptopUiTest {
    @get:Rule val rule = createComposeRule()
    private val hid = FakeHid()
    private val scanner = FakeLaptopScanner()
    private val rupesh = HostDevice("AA:AA", "RUPESH", isComputer = true)
    private val office = HostDevice("BB:BB", "OFFICE-LAPTOP", isComputer = true)
    private lateinit var vm: MainViewModel
    private var settingsOpened = 0

    @Before fun setUp() {
        vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { listOf(rupesh, office) }, DiagnosticLog(), emptyList(), { 0L },
            phoneNameControl = FakePhoneName(), laptopScanner = scanner)
        rule.setContent { App(vm, AppActions({}, {}, {}, {}, { it() }, openBluetoothSettings = { settingsOpened++ })) }
        rule.runOnIdle { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart(); vm.navigate(Screen.LAPTOPS) }
    }

    private fun pickRupeshWhichNeverAnswers() {
        rule.onNodeWithTag("laptop_AA:AA_btn").performScrollTo().performClick()
        rule.runOnIdle { hid.emit(HidEvent.AppStatusChanged(true)) }
        // the wait runs on the main looper (viewModelScope), not on Compose's clock
        rule.runOnIdle { org.robolectric.shadows.ShadowLooper.idleMainLooper(MainViewModel.ATTEMPT_MS + 500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        rule.waitForIdle()
    }

    @Test fun noAnswerShowsTheFixAndPairAgainWorks() {
        pickRupeshWhichNeverAnswers()
        rule.onNodeWithTag("noAnswerCard").assertIsDisplayed()
        rule.onNodeWithText("RUPESH did not answer").assertIsDisplayed()
        rule.onNodeWithTag("pairAgain").performClick()
        rule.runOnIdle {
            assertEquals(listOf("AA:AA"), scanner.forgetRequests)
            assertEquals(listOf("AA:AA"), scanner.pairRequests)
        }
        rule.runOnIdle { scanner.finishPairing("AA:AA", true) }
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(rupesh, HidStateReducer.STATE_CONNECTED)) }
        rule.runOnIdle { assertEquals("connected: back to the start screen", Screen.HOME, vm.screen.value) }
        rule.onNodeWithText("Connected to RUPESH ✓").assertIsDisplayed()
    }

    @Test fun blockedPhoneShowsBluetoothSettingsSteps() {
        scanner.canForget = false
        pickRupeshWhichNeverAnswers()
        rule.onNodeWithTag("pairAgain").performClick()
        rule.onNodeWithTag("forgetCard").assertIsDisplayed()
        rule.onNodeWithTag("openBtSettings").performClick()
        rule.runOnIdle { assertEquals(1, settingsOpened) }
    }
}
