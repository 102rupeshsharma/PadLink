package com.ps2gamepad.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ps2gamepad.bluetooth.HidController
import com.ps2gamepad.bluetooth.SenderStats
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.LayoutEngine
import com.ps2gamepad.model.Appearance
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerProfile
import com.ps2gamepad.model.LastConnection
import com.ps2gamepad.model.Placement
import com.ps2gamepad.model.ProfileStore
import com.ps2gamepad.model.Stick
import com.ps2gamepad.model.StickSettings
import com.ps2gamepad.settings.InMemoryTextStorage
import com.ps2gamepad.settings.ProfileManager
import com.ps2gamepad.settings.SettingsRepository
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ConnectionState
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.GamepadState
import com.ps2gamepad.model.HidState
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.PermissionState
import com.ps2gamepad.model.RegistrationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen { HOME, LAPTOPS, LOADOUTS, DASHBOARD, CONNECTION, CONTROLLER, DIAGNOSTICS, CUSTOMIZE, PROFILES, CALIBRATION }

/** A controller session, for measured (not estimated) battery and duration figures. */
data class Session(val startUptimeMs: Long, val startBattery: Int?)

/**
 * Glue between the Bluetooth layer, the input manager and the UI. Holds no Android framework
 * objects, so it can be unit tested with a fake [HidController].
 */
class MainViewModel(
    private val hid: HidController,
    val bluetoothState: StateFlow<BluetoothState>,
    private val bondedHostsProvider: () -> List<HostDevice>,
    val log: DiagnosticLog,
    val compatibility: List<String>,
    uptimeMs: () -> Long,
    private val reconnectDelaysMs: List<Long> = listOf(2_000, 5_000, 10_000),
    private val cleanup: () -> Unit = {},
    settingsRepository: SettingsRepository? = null,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    val batteryPercent: () -> Int? = { null },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString().take(8) },
    private val phoneNameControl: com.ps2gamepad.bluetooth.PhoneNameControl? = null,
    private val autoConnectDelayMs: Long = 1_500,
    /** Gyroscope + accelerometer (null in tests that do not need them). */
    private val motionSource: com.ps2gamepad.motion.MotionSource? = null,
    /** Searches for and pairs laptops (null in tests that do not need it). */
    private val laptopScanner: com.ps2gamepad.bluetooth.LaptopScanner? = null,
    /** Which player this phone is (two or more phones on one PC). */
    private val playerPrefs: com.ps2gamepad.bluetooth.PlayerPrefs = com.ps2gamepad.bluetooth.InMemoryPlayerPrefs(),
) : ViewModel() {

    private val uptime = uptimeMs
    val settings: SettingsRepository = settingsRepository ?: SettingsRepository(InMemoryTextStorage(), viewModelScope)
    val profiles: StateFlow<ProfileStore> = settings.store
    val activeProfile: StateFlow<ControllerProfile> =
        settings.store.map { it.active }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.store.value.active)

    val hidState: StateFlow<HidState> = hid.state
    val senderStats: StateFlow<SenderStats> = hid.stats

    val input = ControllerInputManager(sink = { report, t -> hid.sendReport(report, t) }, clock = uptimeMs)
    val gamepad: StateFlow<GamepadState> = input.state

    private val _permission = MutableStateFlow(PermissionState.REQUIRED)
    val permission: StateFlow<PermissionState> = _permission.asStateFlow()

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _bondedHosts = MutableStateFlow<List<HostDevice>>(emptyList())
    val bondedHosts: StateFlow<List<HostDevice>> = _bondedHosts.asStateFlow()

    private val _editMode = MutableStateFlow(false)
    /** Layout editor active: controls are not interactive and send nothing. */
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    /** Uptime (ms) when the current connection was confirmed by the stack; null when not connected. */
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun uptimeNow(): Long = uptime()

    // ------------------------------------------------------------------ gyro aiming (right stick)

    /** True when this phone has a gyroscope and an accelerometer. */
    val gyroAvailable: Boolean get() = motionSource?.status?.usable == true
    private val _gyroRunning = MutableStateFlow(false)
    /** The sensors are running and drive the right stick. */
    val gyroRunning: StateFlow<Boolean> = _gyroRunning.asStateFlow()
    @Volatile private var gyroAim: com.ps2gamepad.motion.GyroAimEngine? = null
    private var windowFocused = true

    /** Gyro on/off for the loadout in use (top bar of the controller). */
    fun setGyroEnabled(on: Boolean) = updateGyroAim { it.copy(enabled = on) }

    fun updateGyroAim(f: (com.ps2gamepad.model.GyroAim) -> com.ps2gamepad.model.GyroAim) {
        settings.update { s -> ProfileManager.updateActive(s) { it.copy(gyroAim = f(it.gyroAim)) } }
        log.add("Gyro aim: " + if (settings.store.value.active.gyroAim.enabled) "on" else "off")
        refreshMotion()
    }

    /**
     * Sensors run only while they are used: controller shown (not editing), app visible and focused,
     * gyro on for the loadout in use. Stopping always centres the gyro part of the right stick.
     */
    fun refreshMotion() {
        val source = motionSource ?: return
        val aim = settings.store.value.active.gyroAim
        val want = aim.enabled && source.status.usable && inForeground && windowFocused &&
            _screen.value == Screen.CONTROLLER && !_editMode.value
        val engine = gyroAim
        if (want && engine == null) {
            val e = com.ps2gamepad.motion.GyroAimEngine().also { it.settings = aim }
            gyroAim = e
            if (source.start { sample -> onMotionSample(e, sample) }) {
                _gyroRunning.value = true
                log.add("Gyro aim started")
            } else {
                gyroAim = null
                log.add("Gyro aim: sensors not available")
            }
        } else if (!want && engine != null) {
            source.stop()
            gyroAim = null
            _gyroRunning.value = false
            input.setGyroStick(com.ps2gamepad.controller.StickVector.ZERO)
        }
        gyroAim?.settings = aim
    }

    /** Sensor thread. */
    private fun onMotionSample(e: com.ps2gamepad.motion.GyroAimEngine, sample: com.ps2gamepad.motion.MotionSample) {
        if (e !== gyroAim) return // stopped meanwhile
        val v = e.onSample(sample) ?: return
        input.setGyroStick(v)
    }

    /** Window focus is back (after a dialog, the notification shade...). */
    fun onFocusGained() { windowFocused = true; refreshMotion() }

    // ------------------------------------------------------------------ simple mode (auto start / connect / name)

    private val _phoneName = MutableStateFlow<String?>(null)
    /** The phone's Bluetooth name as the PC sees it. */
    val phoneName: StateFlow<String?> = _phoneName.asStateFlow()
    /** True after the user explicitly tapped Unregister: then nothing starts automatically. */
    private var userUnregistered = false
    private var autoConnectJob: Job? = null

    /** Laptops/PCs paired with the phone (earbuds and other devices are hidden). */
    val computerHosts: List<HostDevice> get() = _bondedHosts.value.filter { it.isComputer }

    /** The PC to connect to: the last one used, else the only paired computer. */
    val knownPc: HostDevice?
        get() = lastHost
            ?: profiles.value.lastConnection?.let { lc -> lc.address?.let { HostDevice(it, lc.hostName) } }
            ?: computerHosts.singleOrNull()

    /**
     * Called whenever the app comes to the foreground (and after permissions are granted). 3.3.0:
     * it only names the phone "PadLink". Nothing connects until the user taps Connect
     * ([connectNow]). After that, the existing behaviour stays: the gamepad is re-registered when
     * the app comes back, and a lost connection is retried.
     */
    fun autoStart() {
        if (permission.value != PermissionState.GRANTED || bluetoothState.value != BluetoothState.ENABLED) return
        applyPhoneName()
    }

    private val _connectRequested = MutableStateFlow(false)
    /** The user tapped Connect and the connection is not established yet (shown as "Connecting…"). */
    val connectRequested: StateFlow<Boolean> = _connectRequested.asStateFlow()
    private var connectTimeoutJob: Job? = null

    /** True once the user started the gamepad with Connect in this app session. */
    val started: Boolean get() = wantRegistered

    /**
     * The home screen's Connect button: starts the gamepad and connects to the laptop used last.
     * Returns true when no laptop is known yet, so the caller makes the phone visible for pairing.
     */
    fun connectNow(): Boolean {
        if (permission.value != PermissionState.GRANTED || bluetoothState.value != BluetoothState.ENABLED) return false
        applyPhoneName()
        userUnregistered = false
        userDisconnected = false
        wantRegistered = true
        autoRegisterAttempts = 0
        _connectRequested.value = true
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!hidState.value.isConnected) { _connectRequested.value = false; log.add("Connect: no answer from the laptop") }
        }
        val pc = knownPc
        when (hidState.value.registration) {
            RegistrationState.Registered -> if (pc != null) connect(pc)
            RegistrationState.Registering -> Unit // connects to the known laptop once registered
            else -> { log.add("Connect: starting the gamepad"); registerInternal() }
        }
        return pc == null
    }

    // ------------------------------------------------------------------ choosing a laptop (3.5.0)

    /**
     * The laptop the user picked in "Choose a laptop". While it is set, connections to any OTHER
     * laptop (e.g. Android's automatic reconnect to the last one) are dropped and the chosen one is
     * connected instead. Cleared once connected to it.
     */
    @Volatile private var chosenHost: HostDevice? = null
    /** "Make this phone visible": wait for a (new) laptop to connect; never auto-connect to the old one. */
    @Volatile private var waitingForNewLaptop = false

    private val _pairing = MutableStateFlow<String?>(null)
    /** Address being paired from the phone ("Pair & connect"), null when none. */
    val pairing: StateFlow<String?> = _pairing.asStateFlow()

    val searchResults: StateFlow<List<HostDevice>> = laptopScanner?.found ?: MutableStateFlow(emptyList())
    val searching: StateFlow<Boolean> = laptopScanner?.scanning ?: MutableStateFlow(false)
    val canSearch: Boolean get() = laptopScanner != null

    /** Start the gamepad (if needed) without reconnecting to the old laptop by itself. */
    private fun startGamepadForChoice() {
        applyPhoneName()
        userUnregistered = false
        userDisconnected = false
        wantRegistered = true
        autoRegisterAttempts = 0
        _connectRequested.value = true
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!hidState.value.isConnected) { _connectRequested.value = false; log.add("Connect: no answer from the laptop") }
        }
        if (hidState.value.registration == RegistrationState.NotRegistered || hidState.value.registration is RegistrationState.Failed) {
            log.add("Starting the gamepad")
            registerInternal()
        }
    }

    /** "Connect" on one laptop of the list: switches to it (disconnecting another laptop first). */
    fun chooseLaptop(host: HostDevice) {
        if (permission.value != PermissionState.GRANTED || bluetoothState.value != BluetoothState.ENABLED) return
        waitingForNewLaptop = false
        chosenHost = host
        lastHost = host
        reconnectJob?.cancel()
        startGamepadForChoice()
        val st = hidState.value
        when {
            st.connectedHost?.address == host.address -> { chosenHost = null; _connectRequested.value = false }
            st.isConnected -> { log.add("Switching laptop: ${st.connectedHost?.displayName} -> ${host.displayName}"); hid.disconnect() }
            st.registration == RegistrationState.Registered -> { log.add("Connecting to ${host.displayName}"); hid.connect(host.address) }
            else -> Unit // connects in onHidState once registered
        }
    }

    /** "Search for laptops" (scan permission must already be granted). */
    fun startSearch(): Boolean {
        val ok = laptopScanner?.start() ?: false
        log.add(if (ok) "Searching for laptops..." else "Search could not start (Bluetooth off or permission missing)")
        return ok
    }

    fun stopSearch() = laptopScanner?.stop()

    /** "Pair & connect" on a laptop found by the search: pair from the phone, then connect to it. */
    fun pairAndConnect(host: HostDevice) {
        val s = laptopScanner ?: return
        _pairing.value = host.address
        log.add("Pairing with ${host.displayName}...")
        val started = s.pair(host.address) { ok ->
            _pairing.value = null
            if (ok) {
                log.add("Paired with ${host.displayName}")
                refreshBondedHosts()
                chooseLaptop(host)
            } else {
                log.add("Pairing with ${host.displayName} failed or was cancelled")
            }
        }
        if (!started) { _pairing.value = null; log.add("Could not start pairing with ${host.displayName}") }
    }

    /**
     * "Make this phone visible" (pair from the laptop): start the gamepad but do NOT reconnect to the
     * old laptop, so the new laptop can pair and connect. Whoever connects first is then used.
     */
    fun prepareForNewLaptop() {
        if (permission.value != PermissionState.GRANTED || bluetoothState.value != BluetoothState.ENABLED) return
        chosenHost = null
        waitingForNewLaptop = true
        autoConnectJob?.cancel()
        reconnectJob?.cancel()
        startGamepadForChoice()
        if (hidState.value.isConnected) { log.add("Disconnecting so a new laptop can connect"); hid.disconnect() }
    }

    fun applyPhoneName() {
        val c = phoneNameControl ?: return
        val before = c.current()
        val target = com.ps2gamepad.bluetooth.PlayerSlot.phoneName(_player.value)
        if (before != target) {
            val ok = c.applyGamepadName(target)
            log.add(if (ok) "Phone Bluetooth name changed from \"$before\" to \"$target\""
                    else "Could not rename the phone (still \"$before\")")
        }
        _phoneName.value = c.current() ?: before
    }

    fun restorePhoneName() {
        val c = phoneNameControl ?: return
        val original = c.originalName
        log.add(if (c.restoreOriginal()) "Phone name restored to \"$original\"" else "Could not restore the phone name")
        _phoneName.value = c.current()
    }

    fun connectKnownPc() {
        val pc = knownPc ?: run { log.add("No paired laptop known: pair from the laptop first"); return }
        connect(pc)
    }

    /** After registering, connect to the known PC automatically unless the user disconnected. */
    private fun scheduleAutoConnect() {
        autoConnectJob?.cancel()
        if (userDisconnected || waitingForNewLaptop) return
        chosenHost?.let { h -> log.add("Connecting to ${h.displayName}"); hid.connect(h.address); return }
        autoConnectJob = viewModelScope.launch {
            delay(autoConnectDelayMs) // give the stack's own reconnect a chance first
            val st = hidState.value
            if (st.isConnected || st.connection is ConnectionState.Connecting || userDisconnected || waitingForNewLaptop) return@launch
            if (st.registration != RegistrationState.Registered) return@launch
            val pc = knownPc ?: return@launch
            log.add("Auto connect to ${pc.displayName}")
            hid.connect(pc.address)
        }
    }

    /** True while the user wants the HID app registered (survives stack-initiated unregistration). */
    private var wantRegistered = false
    private var userDisconnected = false
    private var lastHost: HostDevice? = null
    private var reconnectJob: Job? = null
    private var inForeground = false
    /** Automatic re-registrations since the last user action or successful connection (loop guard). */
    private var autoRegisterAttempts = 0
    private var previous: HidState = hid.state.value

    private val _player = MutableStateFlow(com.ps2gamepad.bluetooth.PlayerSlot.clamp(playerPrefs.load()))
    /** This phone's player number, 1-4 (player 1 uses the same names as before). */
    val player: StateFlow<Int> = _player.asStateFlow()

    /**
     * Makes this phone player [n]: the PC then shows it as "PadLink P2" / "PadLink Gamepad P2".
     * Only while not connected (the PC reads the names when it connects). A started gamepad is stopped,
     * so the next Connect registers with the new name. Returns false when it was not changed.
     */
    fun setPlayer(n: Int): Boolean {
        val p = com.ps2gamepad.bluetooth.PlayerSlot.clamp(n)
        if (p == _player.value) return true
        if (hidState.value.isConnected) { log.add("Disconnect first to change the player"); return false }
        _player.value = p
        playerPrefs.save(p)
        hid.setControllerName(com.ps2gamepad.bluetooth.PlayerSlot.controllerName(p))
        log.add("This phone is now player $p (\"${com.ps2gamepad.bluetooth.PlayerSlot.controllerName(p)}\")")
        if (wantRegistered || hidState.value.registration != RegistrationState.NotRegistered) {
            unregister() // registered under the old name: the next Connect registers again with the new one
            _connectRequested.value = false
        }
        if (permission.value == PermissionState.GRANTED && bluetoothState.value == BluetoothState.ENABLED) applyPhoneName()
        return true
    }

    init {
        hid.setControllerName(com.ps2gamepad.bluetooth.PlayerSlot.controllerName(_player.value))
        compatibility.forEach { log.add(it) }
        viewModelScope.launch { hid.state.collect(::onHidState) }
        viewModelScope.launch { bluetoothState.collect(::onBluetoothState) }
        viewModelScope.launch {
            settings.loaded.collect { loaded ->
                if (loaded && registerWhenLoaded) { registerWhenLoaded = false; if (wantRegistered) registerInternal() }
            }
        }
        // battery saver also covers the saved setting once it is loaded from disk
        viewModelScope.launch { settings.store.map { it.batterySaver }.distinctUntilChanged().collect { hid.setPowerSaving(it) } }
    }

    // ------------------------------------------------------------------ inputs from the Activity

    fun onPermissionsChanged(granted: Boolean) {
        val newState = if (granted) PermissionState.GRANTED else PermissionState.REQUIRED
        if (newState != _permission.value) log.add("Bluetooth permissions: ${if (granted) "granted" else "required"}")
        _permission.value = newState
        if (granted) refreshBondedHosts()
    }

    /** Activity resumed. The stack unregisters HID apps that leave the foreground, so restore it. */
    fun onForeground() {
        inForeground = true
        refreshMotion()
        refreshBondedHosts()
        if (wantRegistered && hidState.value.registration == RegistrationState.NotRegistered) {
            log.add("Back in foreground: HID registration was dropped by the system, registering again")
            autoRegister()
        }
    }

    /** Activity stopped (not visible). Android drops HID registrations of background apps. */
    fun onBackground() {
        inForeground = false
        refreshMotion()
        input.releaseAll()
    }

    /** Activity paused / lost window focus: never leave buttons held on the host. */
    fun onInterrupted(reason: String) {
        windowFocused = false
        refreshMotion()
        if (input.state.value != GamepadState.NEUTRAL || input.activePointers.value > 0) {
            log.add("Input interrupted ($reason): all inputs released")
        }
        input.releaseAll()
    }

    // ------------------------------------------------------------------ user actions

    fun register() {
        userUnregistered = false
        wantRegistered = true
        autoRegisterAttempts = 0
        registerInternal()
    }

    private fun autoRegister() {
        if (autoRegisterAttempts >= MAX_AUTO_REGISTER) {
            log.add("Not re-registering automatically: dropped $autoRegisterAttempts times in a row. Tap Register HID Device.")
            wantRegistered = false
            return
        }
        autoRegisterAttempts++
        registerInternal()
    }

    /** Set when a registration was requested before the saved settings were loaded (registered once they are). */
    private var registerWhenLoaded = false

    private fun registerInternal() {
        when {
            bluetoothState.value != BluetoothState.ENABLED -> log.add("Cannot register: ${bluetoothState.value.label}")
            permission.value != PermissionState.GRANTED -> log.add("Cannot register: Bluetooth permission required")
            !settings.loaded.value -> { registerWhenLoaded = true; log.add("Waiting for saved settings before registering...") }
            else -> { log.add("Registering HID gamepad..."); hid.register(autoConnectPlugged = !userDisconnected && chosenHost == null && !waitingForNewLaptop) }
        }
    }

    fun unregister() {
        userUnregistered = true
        autoConnectJob?.cancel()
        wantRegistered = false
        userDisconnected = true
        reconnectJob?.cancel()
        input.releaseAll()
        log.add("Unregistering HID gamepad")
        hid.unregister()
    }

    fun connect(host: HostDevice) {
        userDisconnected = false
        lastHost = host
        log.add("Connecting to ${host.displayName}")
        hid.connect(host.address)
    }

    fun disconnect() {
        userDisconnected = true
        autoConnectJob?.cancel()
        reconnectJob?.cancel()
        input.releaseAll()
        hid.disconnect()
    }

    fun refreshBondedHosts() {
        if (permission.value == PermissionState.GRANTED) _bondedHosts.value = bondedHostsProvider()
    }

    /** Phase 1 diagnostic buttons: toggle HID buttons 1-3. */
    fun toggleTestButton(index: Int) {
        val button = when (index) { 1 -> ControllerButton.SQUARE; 2 -> ControllerButton.CROSS; 3 -> ControllerButton.CIRCLE; else -> return }
        val pressed = !input.state.value.isPressed(button)
        input.setButton(button, pressed)
        log.add("Test Button $index (HID button ${button.hidButton}) ${if (pressed) "pressed" else "released"}" + sentSuffix())
    }

    fun releaseAllInputs() {
        input.releaseAll()
        log.add("Release All: neutral report" + sentSuffix())
    }

    private fun sentSuffix() = if (hidState.value.isConnected) "" else " (not sent: no host connected)"

    // ------------------------------------------------------------------ customisation & profiles

    fun updateAppearance(f: (Appearance) -> Appearance) =
        settings.update { s -> ProfileManager.updateActive(s) { it.copy(appearance = f(it.appearance)) } }

    fun updateStick(stick: Stick, f: (StickSettings) -> StickSettings) = settings.update { s ->
        ProfileManager.updateActive(s) { if (stick == Stick.LEFT) it.copy(left = f(it.left)) else it.copy(right = f(it.right)) }
    }

    /** Opacity of one control (1 = fully visible); stored per profile. */
    fun setControlOpacity(id: ControlId, value: Float) = updateAppearance {
        it.copy(controlOpacity = if (value >= 0.995f) it.controlOpacity - id else it.controlOpacity + (id to value))
    }

    fun savePlacements(placements: Map<ControlId, Placement>) =
        settings.update { s -> ProfileManager.updateActive(s) { it.copy(placements = placements) } }

    /** Battery saver (60 Hz screen and 125 Hz reports while playing). */
    fun setBatterySaver(on: Boolean) {
        settings.update { it.copy(batterySaver = on) }
        hid.setPowerSaving(on)
    }

    /** Layout editor "Save": positions/sizes of the built-in controls plus the user's extra buttons. */
    fun saveLayout(placements: Map<ControlId, Placement>, extras: List<com.ps2gamepad.model.ExtraButton>) =
        settings.update { s -> ProfileManager.updateActive(s) { it.copy(placements = placements, extraButtons = extras) } }

    fun resetLayout() {
        settings.update { s -> ProfileManager.updateActive(s) { it.copy(placements = emptyMap()) } }
        log.add("Layout reset to default positions")
    }

    fun resetAppearance() = settings.update { s -> ProfileManager.updateActive(s) { it.copy(appearance = Appearance()) } }

    fun switchProfile(id: String) {
        input.releaseAll()
        settings.update { ProfileManager.switchTo(it, id) }
        refreshMotion()
    }
    fun createProfile(name: String) {
        if (!ProfileManager.canCreateLoadout(profiles.value)) { log.add(LOADOUT_LIMIT_MESSAGE); return }
        settings.update { ProfileManager.create(it, name, newId) }
    }
    fun duplicateProfile(id: String) {
        if (!ProfileManager.canCreateLoadout(profiles.value)) { log.add(LOADOUT_LIMIT_MESSAGE); return }
        settings.update { ProfileManager.duplicate(it, id, newId) }
    }

    // ------------------------------------------------------------------ loadouts (per-game setups)

    /** The user's own loadouts (max [ProfileManager.MAX_LOADOUTS]). */
    val loadouts: List<ControllerProfile> get() = ProfileManager.loadouts(profiles.value)
    val canCreateLoadout: Boolean get() = ProfileManager.canCreateLoadout(profiles.value)

    /** "Open Controller" goes through the loadout picker. */
    fun openController() = navigate(Screen.LOADOUTS)

    /** Play with [id]: make it active and open the controller. */
    fun playLoadout(id: String) {
        switchProfile(id)
        navigate(Screen.CONTROLLER)
    }

    /**
     * Creates a loadout named [name] from the current setup, makes it active and opens the
     * controller in Edit mode so the buttons can be arranged right away. Returns false at the limit.
     */
    fun createLoadout(name: String): Boolean {
        val before = profiles.value
        if (!ProfileManager.canCreateLoadout(before)) { log.add(LOADOUT_LIMIT_MESSAGE); return false }
        settings.update { ProfileManager.createFrom(it, name, it.activeId, newId) }
        log.add("Loadout \"${profiles.value.active.name}\" created")
        navigate(Screen.CONTROLLER)
        setEditMode(true)
        return true
    }

    fun deleteLoadout(id: String) {
        val name = profiles.value.profiles.firstOrNull { it.id == id }?.name
        settings.update { ProfileManager.delete(it, id) }
        log.add("Loadout \"$name\" deleted")
    }
    fun renameProfile(id: String, name: String) = settings.update { ProfileManager.rename(it, id, name) }
    fun deleteProfile(id: String) = settings.update { ProfileManager.delete(it, id) }
    fun restoreProfileDefaults(id: String) = settings.update { ProfileManager.restoreDefaults(it, id) }

    /** Stores the thumb's natural resting point (fraction of travel) as the fixed-mode centre. */
    fun calibrateCenter(stick: Stick, offsetX: Float, offsetY: Float) {
        updateStick(stick) { it.copy(centerOffsetX = offsetX, centerOffsetY = offsetY) }
        log.add("Calibrated $stick stick centre: (%.2f, %.2f)".format(offsetX, offsetY))
    }

    fun setEditMode(on: Boolean) {
        input.releaseAll() // nothing may stay held while controls move
        _editMode.value = on
        refreshMotion()
    }

    /** Overlapping controls in the active layout for a screen of [w] x [h] dp. */
    fun layoutOverlaps(w: Float, h: Float) = activeProfile.value.let {
        LayoutEngine.overlaps(LayoutEngine.resolve(w, h, it.appearance, it.placements))
    }

    fun reconnect() {
        val host = lastHost ?: profiles.value.lastConnection?.let { lc -> lc.address?.let { HostDevice(it, lc.hostName) } }
        if (host == null) { log.add("Reconnect: no previous host known"); return }
        connect(host)
    }

    val lastKnownHostName: String? get() = lastHost?.displayName ?: profiles.value.lastConnection?.hostName

    fun navigate(to: Screen) {
        if (to != _screen.value) input.releaseAll()
        if (to != Screen.CONTROLLER) _editMode.value = false
        if (to == Screen.CONTROLLER && _session.value == null) _session.value = Session(uptime(), batteryPercent())
        _screen.value = to
        refreshMotion()
    }

    fun resetSession() { _session.value = Session(uptime(), batteryPercent()) }

    /** Persist pending settings now (app going to the background). */
    fun flushSettings() = settings.flush()

    // ------------------------------------------------------------------ state reactions

    private fun onBluetoothState(s: BluetoothState) {
        log.add("Adapter: ${s.label}")
        when (s) {
            BluetoothState.TURNING_OFF, BluetoothState.DISABLED -> {
                reconnectJob?.cancel()
                input.releaseAll()
                hid.onBluetoothOff()
            }
            BluetoothState.ENABLED -> {
                refreshBondedHosts()
                if (inForeground) autoStart()
                if (wantRegistered && hidState.value.registration == RegistrationState.NotRegistered) {
                    log.add("Bluetooth back on: registering again")
                    autoRegister()
                }
            }
            else -> Unit
        }
    }

    private fun onHidState(s: HidState) {
        val prev = previous
        previous = s
        if (prev.registration != s.registration) log.add("Registration: ${s.registration.label}")
        if (prev.connection != s.connection) log.add("Connection: ${s.connection.label}")

        if (s.registration is RegistrationState.Failed) { wantRegistered = false; _connectRequested.value = false }
        if (prev.registration != RegistrationState.Registered && s.registration == RegistrationState.Registered && !s.isConnected) {
            scheduleAutoConnect()
        }
        // Registration dropped by the system while we are visible (e.g. a dialog briefly took the
        // foreground). In the background we wait for onForeground() instead.
        if (prev.registration == RegistrationState.Registered && s.registration == RegistrationState.NotRegistered &&
            wantRegistered && inForeground && bluetoothState.value == BluetoothState.ENABLED
        ) {
            log.add("HID registration dropped by the system, registering again")
            autoRegister()
        }

        val wasConnected = prev.connection is ConnectionState.Connected
        val chosen = chosenHost
        if (s.isConnected && chosen != null && s.connectedHost?.address != chosen.address) {
            // connected to a different laptop (e.g. Android reconnected the last one): switch to the chosen one
            log.add("Connected to ${s.connectedHost?.displayName}, but ${chosen.displayName} was chosen: switching")
            hid.disconnect()
            previous = s
            return
        }
        if (!s.isConnected && wasConnectedTo(prev) && chosen != null && s.registration == RegistrationState.Registered &&
            s.connection !is ConnectionState.Connecting) {
            log.add("Connecting to ${chosen.displayName}")
            hid.connect(chosen.address)
        }
        if (s.isConnected && !wasConnected) {
            chosenHost = null
            waitingForNewLaptop = false
            _connectRequested.value = false
            connectTimeoutJob?.cancel()
            autoConnectJob?.cancel() // connected: a pending auto-connect must not fire later
            _connectedSince.value = uptime()
            s.connectedHost?.let { h ->
                settings.update { ProfileManager.recordConnection(it, LastConnection(h.displayName, wallClockMs(), h.address)) }
            }
            reconnectJob?.cancel()
            userDisconnected = false
            autoRegisterAttempts = 0
            lastHost = s.connectedHost
            // Fresh baseline for the host (HidDeviceManager also queues one).
            input.releaseAll()
        } else if (wasConnected && !s.isConnected) {
            _connectedSince.value = null
            // Stop, clear touches, reset state. The sink already drops reports while disconnected.
            input.releaseAll()
            input.onHostLost()
            log.add("WARNING: host connection lost - inputs reset")
            if (chosenHost == null && !waitingForNewLaptop) scheduleReconnect()
        }
    }

    private fun wasConnectedTo(prev: HidState) = prev.connection is ConnectionState.Connected

    private fun scheduleReconnect() {
        val host = lastHost ?: return
        if (userDisconnected || !wantRegistered) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            for ((i, d) in reconnectDelaysMs.withIndex()) {
                delay(d)
                val st = hidState.value
                if (st.isConnected || !wantRegistered || userDisconnected) return@launch
                if (st.registration != RegistrationState.Registered || bluetoothState.value != BluetoothState.ENABLED) continue
                log.add("Auto-reconnect attempt ${i + 1}/${reconnectDelaysMs.size} to ${host.displayName}")
                hid.connect(host.address)
            }
        }
    }

    companion object {
        const val LOADOUT_LIMIT_MESSAGE = "You already have 5 loadouts. Delete one to create a new one."
        const val MAX_AUTO_REGISTER = 3
        /** How long "Connecting…" is shown after tapping Connect without a connection. */
        const val CONNECT_TIMEOUT_MS = 20_000L
    }

    override fun onCleared() {
        motionSource?.stop()
        gyroAim = null
        reconnectJob?.cancel()
        input.releaseAll()
        cleanup()
        hid.close()
    }
}
