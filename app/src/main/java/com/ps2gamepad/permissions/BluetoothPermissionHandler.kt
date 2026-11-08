package com.ps2gamepad.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime Bluetooth permissions by Android version:
 *  - API 31+ (Android 12+): BLUETOOTH_CONNECT (HID register/connect/send, device names) and
 *    BLUETOOTH_ADVERTISE (making the phone discoverable for Windows pairing).
 *  - API 28-30: the install-time BLUETOOTH permission, nothing to request at runtime.
 * Searching for laptops needs one more permission, asked only when the user taps Search
 * (see [scanPermissions]).
 */
object BluetoothPermissionHandler {

    @SuppressLint("InlinedApi") // guarded by the SDK check below
    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    /**
     * Searching for laptops (3.5.0, "Choose a laptop"): API 31+ BLUETOOTH_SCAN (declared
     * neverForLocation), API 28-30 the location permission Android requires for Bluetooth discovery.
     */
    @SuppressLint("InlinedApi")
    fun scanPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdkInt >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasScan(context: Context): Boolean = scanPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasAll(context: Context): Boolean = missingPermissions(context).isEmpty()
}
