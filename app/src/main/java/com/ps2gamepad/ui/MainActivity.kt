package com.ps2gamepad.ui

import android.os.BatteryManager
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ps2gamepad.bluetooth.BluetoothManagerHelper
import com.ps2gamepad.bluetooth.HidDeviceManager
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.permissions.BluetoothPermissionHandler
import com.ps2gamepad.settings.DataStoreTextStorage
import com.ps2gamepad.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 3.4.0: the whole app is portrait; only the controller (playing and editing its layout) is landscape. */
fun orientationFor(screen: Screen): Int =
    if (screen == Screen.CONTROLLER) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels { factory() }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionsChanged(BluetoothPermissionHandler.hasAll(this))
        vm.autoStart()
    }
    /** Runs after the scan permission dialog, if granted. */
    private var afterScanPermission: (() -> Unit)? = null
    private val scanPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = BluetoothPermissionHandler.hasScan(this)
        if (ok) afterScanPermission?.invoke() else vm.log.add("Search needs the \"Nearby devices\" permission")
        afterScanPermission = null
    }
    private val enableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val discoverableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // resultCode = discoverable duration in seconds, or RESULT_CANCELED.
        vm.log.add(if (result.resultCode > 0) "Phone discoverable for ${result.resultCode} s - pair from Windows now" else "Discoverable request declined")
    }

    /**
     * Battery saver while playing: the screen is the biggest power user (and heat source).
     *  - 60 Hz instead of 90/120 Hz: about half the display and GPU work, nothing visible changes.
     *  - Brightness is NOT touched (2.1.0): the phone's own brightness control keeps working while
     *    playing. The override is cleared in case an older version left one set.
     */
    private fun applyPowerSaving(on: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        lp.preferredDisplayModeId = if (on) lowRefreshModeId() else 0
        window.attributes = lp
    }

    /**
     * 3.2.0: on the controller, touches are delivered the moment the screen reports them instead of
     * being collected and handed over once per frame (up to 16 ms at 60 Hz). Presses and releases
     * reach the PC sooner; the extra touch events only happen while fingers are down.
     */
    private fun applyLowLatencyTouch(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        window.decorView.requestUnbufferedDispatch(if (on) android.view.InputDevice.SOURCE_CLASS_POINTER else android.view.InputDevice.SOURCE_CLASS_NONE)
    }

    /** The display mode with the current resolution and the lowest refresh rate of at least 60 Hz (0 = no preference). */
    private fun lowRefreshModeId(): Int {
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay ?: return 0
        val cur = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && it.refreshRate >= 59f }
            .minByOrNull { it.refreshRate }?.modeId ?: 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersive()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.screen, vm.profiles) { s, store -> s to store.batterySaver }.collect { (s, saver) ->
                    val playing = s == Screen.CONTROLLER
                    if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    applyPowerSaving(playing && saver)
                    applyLowLatencyTouch(playing)
                    val wanted = orientationFor(s)
                    if (requestedOrientation != wanted) requestedOrientation = wanted
                }
            }
        }

        setContent {
            App(
                vm = vm,
                actions = AppActions(
                    requestPermissions = { permissionLauncher.launch(BluetoothPermissionHandler.requiredPermissions()) },
                    enableBluetooth = { safeLaunch { enableLauncher.launch(BluetoothManagerHelper.enableIntent()) } },
                    makeDiscoverable = { safeLaunch { discoverableLauncher.launch(BluetoothManagerHelper.discoverableIntent()) } },
                    exit = { finish() },
                    requestScanPermission = { onGranted ->
                        if (BluetoothPermissionHandler.hasScan(this)) onGranted()
                        else { afterScanPermission = onGranted; scanPermissionLauncher.launch(BluetoothPermissionHandler.scanPermissions()) }
                    },
                ),
            )
        }
    }

    private inline fun safeLaunch(block: () -> Unit) {
        try { block() } catch (e: Exception) { vm.log.add("Could not open system dialog: ${e.message}") }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        vm.onPermissionsChanged(BluetoothPermissionHandler.hasAll(this))
        vm.onForeground()
        vm.autoStart()
    }

    override fun onPause() {
        vm.onInterrupted("app paused")
        super.onPause()
    }

    override fun onStop() {
        vm.onBackground()
        vm.flushSettings()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { enterImmersive(); vm.onFocusGained() } else vm.onInterrupted("window lost focus")
    }

    private fun enterImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun factory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = applicationContext
            val log = DiagnosticLog()
            // Logcat only in debuggable builds: messages can contain Bluetooth device names.
            val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val logger: (String) -> Unit = { msg -> if (debuggable) Log.i(TAG, msg); log.add(msg) }
            // Process-wide scope so pending settings are still written after the ViewModel is cleared.
            val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val battery = app.getSystemService(BatteryManager::class.java)
            val bt = BluetoothManagerHelper(app).also { it.start() }
            val hid = HidDeviceManager(app, { bt.adapter }, logger)
            return MainViewModel(
                hid = hid,
                bluetoothState = bt.state,
                bondedHostsProvider = bt::bondedHosts,
                log = log,
                compatibility = bt.compatibilitySummary() +
                    (if (bt.state.value == BluetoothState.UNAVAILABLE) listOf("STOP: this phone has no usable Bluetooth adapter") else emptyList()),
                uptimeMs = SystemClock::uptimeMillis,
                cleanup = { bt.stop() },
                settingsRepository = SettingsRepository(DataStoreTextStorage(app), settingsScope),
                phoneNameControl = com.ps2gamepad.bluetooth.AdapterPhoneName(app) { bt.adapter },
                motionSource = com.ps2gamepad.motion.AndroidMotionSource(app),
                laptopScanner = com.ps2gamepad.bluetooth.AndroidLaptopScanner(app) { bt.adapter },
                playerPrefs = com.ps2gamepad.bluetooth.AndroidPlayerPrefs(app),
                batteryPercent = {
                    battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
                },
            ) as T
        }
    }

    companion object {
        const val TAG = "BtGamepad"
    }
}
