package com.ps2gamepad.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.ps2gamepad.model.HostDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds laptops near the phone and pairs with one (3.5.0, "Choose a laptop").
 *
 * Before, the app only knew the laptop it was connected to last; the second laptop could only pair
 * from its own Bluetooth settings, and meanwhile the phone reconnected to the first one. Now the
 * phone can search (classic Bluetooth discovery) and pair a laptop the user picks from a list.
 */
interface LaptopScanner {
    /** Devices found by the current / last search (computers first), excluding nothing: the UI filters. */
    val found: StateFlow<List<HostDevice>>
    val scanning: StateFlow<Boolean>
    /** Starts a ~12 s search. False when Bluetooth or the scan permission is missing. */
    fun start(): Boolean
    fun stop()
    /** Pairs with [address] (already paired = immediately true). [onResult] runs on the main thread. */
    fun pair(address: String, onResult: (Boolean) -> Unit): Boolean
}

@SuppressLint("MissingPermission") // every call is guarded by try/catch SecurityException
class AndroidLaptopScanner(
    private val context: Context,
    private val adapter: () -> BluetoothAdapter?,
) : LaptopScanner {
    private val _found = MutableStateFlow<List<HostDevice>>(emptyList())
    override val found: StateFlow<List<HostDevice>> = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val pending = HashMap<String, (Boolean) -> Unit>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = device(i) ?: return
                    if (d.type == BluetoothDevice.DEVICE_TYPE_LE) return // HID device profile is classic Bluetooth
                    val name = try { d.name } catch (_: SecurityException) { null } ?: return // unnamed = not a laptop the user can recognise
                    val major = d.bluetoothClass?.majorDeviceClass
                    val host = HostDevice(d.address, name, isComputer = major == null || major == BluetoothClass.Device.Major.COMPUTER)
                    _found.value = (_found.value.filter { it.address != host.address } + host)
                        .sortedWith(compareBy({ !it.isComputer }, { it.displayName.lowercase() }))
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _scanning.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = device(i) ?: return
                    val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    when {
                        state == BluetoothDevice.BOND_BONDED -> pending.remove(d.address)?.invoke(true)
                        state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING -> pending.remove(d.address)?.invoke(false)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun ensureRegistered() {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    override fun start(): Boolean = try {
        val a = adapter() ?: return false
        ensureRegistered()
        _found.value = emptyList()
        if (a.isDiscovering) a.cancelDiscovery()
        a.startDiscovery().also { _scanning.value = it }
    } catch (e: SecurityException) {
        false
    }

    override fun stop() {
        try { adapter()?.cancelDiscovery() } catch (_: SecurityException) {}
        _scanning.value = false
    }

    override fun pair(address: String, onResult: (Boolean) -> Unit): Boolean = try {
        val a = adapter() ?: return false
        ensureRegistered()
        a.cancelDiscovery() // discovery slows down pairing and connecting
        val d = a.getRemoteDevice(address)
        if (d.bondState == BluetoothDevice.BOND_BONDED) {
            onResult(true); true
        } else {
            pending[address] = onResult
            d.createBond().also { if (!it) pending.remove(address) }
        }
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}
