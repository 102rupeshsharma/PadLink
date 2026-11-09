package com.ps2gamepad.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import com.ps2gamepad.model.HidState
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Counters measured on the phone: sendReport() calls, not delivery to Windows. */
data class SenderStats(
    val reportsSent: Long = 0,
    val sendFailures: Long = 0,
    val reportsPerSecond: Int = 0,
    /** Touch event timestamp -> sendReport() returned, last report (ms). In-app only, excludes the radio and Windows. */
    val lastLatencyMs: Long = -1,
    val avgLatencyMs: Double = -1.0,
    val lastReport: ByteArray? = null,
)

/** What the ViewModel needs from the HID layer; lets ViewModel tests use a fake. */
interface HidController {
    val state: StateFlow<HidState>
    val stats: StateFlow<SenderStats>
    /** [autoConnectPlugged]: reconnect to the stack's last host after registering (false after a user Disconnect). */
    fun register(autoConnectPlugged: Boolean = true)
    /** The controller name the PC shows ("PadLink Gamepad", "PadLink Gamepad P2"); used at the next register. */
    fun setControllerName(name: String) {}
    fun unregister()
    fun connect(address: String)
    fun disconnect()
    /** Queues a report for the connected host. Returns false (and drops it) when no host is connected. */
    fun sendReport(report: ByteArray, eventUptimeMs: Long = SystemClock.uptimeMillis()): Boolean
    fun onBluetoothOff()
    fun close()
    /**
     * Battery saver: send at most one report every 8 ms (125 Hz) instead of every 4 ms (250 Hz).
     * Stick moves are coalesced, so only the radio's duty cycle changes (worst case +4 ms).
     */
    fun setPowerSaving(on: Boolean) {}
}

/**
 * Owns the BluetoothHidDevice profile proxy, registration and reports.
 *
 * Threads:
 *  - "hid-callback": BluetoothHidDevice callbacks and timeouts.
 *  - "hid-sender": sends queued reports, at most one every 4 ms (8 ms in battery saver).
 *  - Callers (main thread) only take a short lock to queue a report.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT; SecurityException is caught as well.
class HidDeviceManager(
    private val context: Context,
    private val adapterProvider: () -> BluetoothAdapter?,
    private val log: (String) -> Unit,
) : HidController {

    private val _state = MutableStateFlow(HidState())
    override val state: StateFlow<HidState> = _state.asStateFlow()
    private val _stats = MutableStateFlow(SenderStats())
    override val stats: StateFlow<SenderStats> = _stats.asStateFlow()

    private val callbackExecutor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hid-callback") }

    /** Usable proxy (service connected). */
    @Volatile private var hidDevice: BluetoothHidDevice? = null
    /** Every proxy we were given, usable or not, so it can always be closed (audit H1). */
    @Volatile private var anyProxy: BluetoothProfile? = null
    @Volatile private var proxyRequested = false
    @Volatile private var autoConnectPlugged = true
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var pendingRegister = false
    @Volatile private var closed = false
    private var registerTimeout: ScheduledFuture<*>? = null
    private var proxyTimeout: ScheduledFuture<*>? = null

    // Sender state, guarded by lock
    private val lock = ReentrantLock()
    private val hasWork = lock.newCondition()
    private val queue = ReportQueue()
    /** Latest gamepad report (for GET_REPORT and retries). */
    @Volatile private var lastReport: ByteArray = HidReportManager.NEUTRAL_REPORT
    private val senderThread = Thread(::senderLoop, "hid-sender").apply { isDaemon = true; start() }

    private fun dispatch(e: HidEvent) = _state.update { HidStateReducer.reduce(it, e) }

    // ---------------------------------------------------------------- profile proxy

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            if (closed || callbackExecutor.isShutdown) { closeProxy(proxy); return }
            runOnCallbackThread {
                proxyTimeout?.cancel(false)
                proxyRequested = false
                if (closed) { closeProxy(proxy); return@runOnCallbackThread }
                // A second proxy (e.g. old instance rebinding after BT on) replaces and closes the first.
                anyProxy?.let { if (it !== proxy) closeProxy(it) }
                anyProxy = proxy
                hidDevice = proxy as BluetoothHidDevice
                log("HID Device profile proxy connected - the profile is available on this phone")
                dispatch(HidEvent.ProxyConnected)
                if (pendingRegister) { pendingRegister = false; doRegister() }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            runOnCallbackThread {
                log("HID Device profile proxy disconnected (Bluetooth stack restarted or turned off)")
                hidDevice = null
                clearConnection()
                dispatch(HidEvent.ProxyDisconnected)
            }
        }
    }

    /** Executes on the callback thread unless we are shutting down (avoids RejectedExecutionException). */
    private fun runOnCallbackThread(block: () -> Unit) {
        if (closed || callbackExecutor.isShutdown) return
        try { callbackExecutor.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun requestProxy(): Boolean {
        if (proxyRequested) { log("Proxy request already pending"); return true }
        val adapter = adapterProvider() ?: run {
            dispatch(HidEvent.RegisterRejected("no Bluetooth adapter"))
            return false
        }
        val ok = try {
            adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            log("getProfileProxy: permission denied: ${e.message}")
            false
        }
        if (!ok) {
            log("getProfileProxy(HID_DEVICE) returned false - this phone's Bluetooth stack does not expose the HID Device profile")
            dispatch(HidEvent.RegisterRejected("HID Device profile not supported by this phone"))
            return false
        }
        proxyRequested = true
        log("Requested HID Device profile proxy...")
        proxyTimeout = callbackExecutor.schedule({
            if (hidDevice == null && pendingRegister) {
                pendingRegister = false
                proxyRequested = false
                log("HID Device profile service did not connect within 5 s - the profile is probably disabled by the manufacturer")
                dispatch(HidEvent.RegisterRejected("HID Device profile service did not respond"))
            }
        }, 5, TimeUnit.SECONDS)
        return true
    }

    private fun closeProxy(proxy: BluetoothProfile) {
        runCatching { adapterProvider()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
    }

    // ---------------------------------------------------------------- registration

    @Volatile private var controllerName: String = HidDescriptor.SDP_NAME
    override fun setControllerName(name: String) { controllerName = name }

    override fun register(autoConnectPlugged: Boolean) {
        this.autoConnectPlugged = autoConnectPlugged
        runOnCallbackThread {
            val reg = _state.value.registration
            if (reg == RegistrationState.Registering || reg == RegistrationState.Registered) {
                log("register(): already $reg")
                return@runOnCallbackThread
            }
            dispatch(HidEvent.RegisterRequested)
            if (hidDevice == null) {
                pendingRegister = true
                if (!requestProxy()) pendingRegister = false
            } else {
                doRegister()
            }
        }
    }

    private fun doRegister() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            controllerName,
            HidDescriptor.SDP_DESCRIPTION,
            HidDescriptor.SDP_PROVIDER,
            (BluetoothHidDevice.SUBCLASS1_NONE.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()).toByte(),
            HidDescriptor.REPORT_DESCRIPTOR,
        )
        log("Registering with gamepad descriptor as \"$controllerName\"")
        dispatch(HidEvent.RegisterRequested)
        val ok = try {
            hid.registerApp(sdp, null, null, callbackExecutor, HidDeviceCallback(listener))
        } catch (e: SecurityException) {
            log("registerApp: permission denied: ${e.message}")
            dispatch(HidEvent.RegisterRejected("permission denied"))
            return
        }
        if (!ok) {
            log("registerApp() returned false. Another app may hold the HID registration, or the stack refused it.")
            dispatch(HidEvent.RegisterRejected("registerApp() returned false"))
            return
        }
        log("registerApp() accepted, waiting for onAppStatusChanged...")
        registerTimeout?.cancel(false)
        registerTimeout = callbackExecutor.schedule({
            if (_state.value.registration == RegistrationState.Registering) {
                log("No registration callback within 10 s")
                dispatch(HidEvent.RegisterTimeout)
            }
        }, 10, TimeUnit.SECONDS)
    }

    override fun unregister() {
        runOnCallbackThread {
            pendingRegister = false
            registerTimeout?.cancel(false)
            val hid = hidDevice
            if (hid != null) {
                try {
                    connectedDevice?.let { hid.disconnect(it) }
                    hid.unregisterApp()
                    log("unregisterApp() called")
                } catch (e: SecurityException) {
                    log("unregister: permission denied: ${e.message}")
                }
            }
            clearConnection()
            dispatch(HidEvent.Unregistered)
        }
    }

    // ---------------------------------------------------------------- connection

    override fun connect(address: String) {
        runOnCallbackThread {
            val hid = hidDevice
            if (hid == null || _state.value.registration != RegistrationState.Registered) {
                dispatch(HidEvent.ConnectFailed("register the HID device first"))
                return@runOnCallbackThread
            }
            try {
                val device = adapterProvider()?.getRemoteDevice(address) ?: return@runOnCallbackThread
                log("connect(${device.name ?: address})")
                if (!hid.connect(device)) dispatch(HidEvent.ConnectFailed("connect() returned false"))
            } catch (e: Exception) {
                log("connect failed: ${e.message}")
                dispatch(HidEvent.ConnectFailed(e.message ?: "connect failed"))
            }
        }
    }

    override fun disconnect() {
        runOnCallbackThread {
            val hid = hidDevice ?: return@runOnCallbackThread
            val dev = connectedDevice ?: return@runOnCallbackThread
            try {
                log("disconnect() requested")
                if (!hid.disconnect(dev)) log("disconnect() returned false")
            } catch (e: SecurityException) {
                log("disconnect: permission denied")
            }
        }
    }

    override fun onBluetoothOff() {
        runOnCallbackThread {
            anyProxy?.let { closeProxy(it) }
            anyProxy = null
            proxyRequested = false
            hidDevice = null
            pendingRegister = false
            registerTimeout?.cancel(false)
            clearConnection()
            dispatch(HidEvent.BluetoothOff)
        }
    }

    private fun clearConnection() {
        lock.withLock {
            connectedDevice = null
            queue.clear()
        }
    }

    private fun host(device: BluetoothDevice): HostDevice =
        HostDevice(device.address, try { device.name } catch (e: SecurityException) { null })

    private val listener = object : HidCallbackListener {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            registerTimeout?.cancel(false)
            log("onAppStatusChanged(registered=$registered, plugged=${pluggedDevice?.let { host(it).displayName }})")
            dispatch(HidEvent.AppStatusChanged(registered))
            if (!registered) { clearConnection(); return }
            // The stack reports the last host as "plugged"; reconnect to it like a real controller would.
            if (pluggedDevice != null && connectedDevice == null && autoConnectPlugged) {
                try {
                    hidDevice?.connect(pluggedDevice)
                    log("Reconnecting to previously paired host ${host(pluggedDevice).displayName}")
                } catch (e: SecurityException) { log("auto-reconnect: permission denied") }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val h = host(device)
            log("onConnectionStateChanged(${h.displayName}, ${stateName(state)})")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    // Start the host from a known neutral baseline.
                    lock.withLock { queue.clear(); queue.offer(PendingReport(HidReportManager.NEUTRAL_REPORT, SystemClock.uptimeMillis())); hasWork.signal() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (connectedDevice == null || connectedDevice?.address == device.address) clearConnection()
            }
            dispatch(HidEvent.ConnectionChanged(h, state))
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val hid = hidDevice ?: return
            try {
                if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == HidDescriptor.REPORT_ID) {
                    hid.replyReport(device, type, id, lastReport)
                } else if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                } else {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
                }
            } catch (e: SecurityException) { log("onGetReport: permission denied") }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            log("Host SET_REPORT(type=$type id=$id) - not supported (no output reports in this descriptor)")
            try { hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) } catch (_: SecurityException) {}
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            log("Host SET_PROTOCOL(${if (protocol == BluetoothHidDevice.PROTOCOL_REPORT_MODE) "report" else "boot"})")
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray?) {
            log("Host interrupt data id=$reportId len=${data?.size ?: 0} (ignored)")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            log("Host sent virtual cable unplug (${host(device).displayName})")
            clearConnection()
            dispatch(HidEvent.ConnectionChanged(host(device), BluetoothProfile.STATE_DISCONNECTED))
        }
    }

    // ---------------------------------------------------------------- reports

    override fun sendReport(report: ByteArray, eventUptimeMs: Long): Boolean {
        require(report.size == HidDescriptor.REPORT_LENGTH) { "report must be ${HidDescriptor.REPORT_LENGTH} bytes" }
        lock.withLock {
            lastReport = report
            if (connectedDevice == null || hidDevice == null) return false
            queue.offer(PendingReport(report, eventUptimeMs))
            hasWork.signal()
        }
        return true
    }

    /** Pacing, priority and confirmation of reports (see [SendScheduler]). Used by the sender thread only. */
    private val scheduler = SendScheduler(axisMs = { if (powerSaving) SendScheduler.AXIS_MS_SAVER else SendScheduler.AXIS_MS })

    private fun senderLoop() {
        var windowStart = SystemClock.uptimeMillis()
        var windowCount = 0
        var latencySum = 0L
        var latencyN = 0L
        var sent = 0L
        var failures = 0L
        var lastLatency = -1L
        var lastPublish = 0L
        var rate = 0
        var retries = 0
        while (!closed) {
            // Ask the scheduler; sleep until something is due (a newly queued report wakes us early).
            val step: SendStep.Send = try {
                lock.withLock {
                    var s: SendStep.Send? = null
                    while (s == null && !closed) {
                        when (val n = scheduler.next(queue, SystemClock.uptimeMillis())) {
                            SendStep.Idle -> hasWork.await()
                            is SendStep.Wait -> hasWork.await(n.ms, TimeUnit.MILLISECONDS)
                            is SendStep.Send -> s = n
                        }
                    }
                    s
                }
            } catch (e: InterruptedException) {
                break
            } ?: break
            val item = step.report
            val hid = hidDevice
            val dev = connectedDevice
            if (hid == null || dev == null) { scheduler.reset(); continue }

            val ok = try {
                hid.sendReport(dev, item.reportId, item.bytes)
            } catch (e: Exception) {
                false
            }
            val now = SystemClock.uptimeMillis()
            scheduler.onSent(step, ok, now)
            if (ok) {
                retries = 0
                sent++
                if (!step.confirm) { lastLatency = now - item.eventUptimeMs; latencySum += lastLatency; latencyN++ }
            } else {
                failures++
                if (failures % 50 == 1L) log("sendReport() failed (total $failures)")
                // A lost report (e.g. a button release) would leave the host in a stale state:
                // resend the CURRENT state if nothing newer is queued (audit H2). Bounded retries.
                if (retries < MAX_RETRIES) {
                    retries++
                    lock.withLock {
                        if (queue.isEmpty() && connectedDevice != null) {
                            queue.offer(PendingReport(lastReport, item.eventUptimeMs)); hasWork.signal()
                        }
                    }
                    try { Thread.sleep(RETRY_DELAY_MS) } catch (e: InterruptedException) { break }
                }
            }
            windowCount++
            if (now - windowStart >= 1000) {
                rate = (windowCount * 1000 / (now - windowStart)).toInt()
                windowStart = now; windowCount = 0
            }
            // Publish at most 4x per second: the diagnostics UI must not recompose per report (audit M4).
            if (now - lastPublish >= 250 || !ok) {
                lastPublish = now
                val avg = if (latencyN > 0) latencySum.toDouble() / latencyN else -1.0
                _stats.value = SenderStats(sent, failures, rate, lastLatency, avg, scheduler.lastSent)
            }
        }
    }

    /** Releases everything. Safe to call from any thread, and more than once (audit M1). */
    @Volatile private var powerSaving = false
    override fun setPowerSaving(on: Boolean) { powerSaving = on }

    override fun close() {
        if (closed) return
        closed = true // first: every callback from now on closes what it gets instead of storing it
        lock.withLock { queue.clear(); hasWork.signalAll() }
        val cleanup = Runnable {
            try {
                hidDevice?.let { hid ->
                    connectedDevice?.let { runCatching { hid.disconnect(it) } }
                    runCatching { hid.unregisterApp() }
                }
            } catch (_: SecurityException) {}
            anyProxy?.let { closeProxy(it) }
            hidDevice?.let { closeProxy(it) }
            anyProxy = null
            hidDevice = null
            connectedDevice = null
        }
        try { callbackExecutor.execute(cleanup) } catch (_: java.util.concurrent.RejectedExecutionException) { cleanup.run() }
        callbackExecutor.shutdown() // lets the cleanup task finish
        senderThread.interrupt()
    }

    companion object {
        const val MAX_RETRIES = 5
        const val RETRY_DELAY_MS = 15L

        fun stateName(state: Int) = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "UNKNOWN($state)"
        }
    }
}
