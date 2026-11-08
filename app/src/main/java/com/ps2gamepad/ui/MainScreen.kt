package com.ps2gamepad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ConnectionState
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.PermissionState
import com.ps2gamepad.model.RegistrationState
import com.ps2gamepad.ui.controller.ControllerScreen

class AppActions(
    val requestPermissions: () -> Unit,
    val enableBluetooth: () -> Unit,
    val makeDiscoverable: () -> Unit,
    val exit: () -> Unit,
    /** Asks for the "scan for nearby devices" permission, then runs the callback if granted. */
    val requestScanPermission: (onGranted: () -> Unit) -> Unit = { it() },
)

@Composable
fun App(vm: MainViewModel, actions: AppActions) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Info, onPrimary = Color.White, background = Palette.Background, surface = Palette.Surface)) {
        val screen by vm.screen.collectAsStateWithLifecycle()
        when (screen) {
            Screen.HOME -> SimpleHomeScreen(vm, actions)
            Screen.LAPTOPS -> LaptopsScreen(vm, actions)
            Screen.LOADOUTS -> LoadoutPickerScreen(vm)
            Screen.DASHBOARD -> DashboardScreen(vm, actions)
            Screen.CONNECTION -> MainScreen(vm, actions)
            Screen.CONTROLLER -> ControllerScreen(vm)
            Screen.DIAGNOSTICS -> DiagnosticScreen(vm)
            Screen.CUSTOMIZE -> CustomizeScreen(vm)
            Screen.PROFILES -> ProfilesScreen(vm)
            Screen.CALIBRATION -> CalibrationScreen(vm)
        }
    }
}

/** Phase 1 screen: Bluetooth status, HID registration, pairing and basic report diagnostics. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: MainViewModel, actions: AppActions) {
    val bt by vm.bluetoothState.collectAsStateWithLifecycle()
    val perm by vm.permission.collectAsStateWithLifecycle()
    val hid by vm.hidState.collectAsStateWithLifecycle()
    val hosts by vm.bondedHosts.collectAsStateWithLifecycle()
    val pad by vm.gamepad.collectAsStateWithLifecycle()
    val lastReport by vm.input.lastReport.collectAsStateWithLifecycle()
    val logLines by vm.log.lines.collectAsStateWithLifecycle()
    val since by vm.connectedSince.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(vm.uptimeNow()) }
    LaunchedEffect(since) { while (since != null) { now = vm.uptimeNow(); kotlinx.coroutines.delay(1000) } }

    BackHandler { vm.navigate(Screen.HOME) }

    TwoPane(
        Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
        // ---------------- status and lifecycle actions
        first = {
            Text("Connection", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("Open Controller", onClick = { vm.openController() })
                ActionButton("Home", outlined = true, onClick = { vm.navigate(Screen.HOME) })
            }
            Spacer(Modifier.height(6.dp))
            Card {
                StatusRow("Bluetooth adapter", if (bt == BluetoothState.UNAVAILABLE) "Unavailable" else "Available",
                    if (bt == BluetoothState.UNAVAILABLE) Severity.BAD else Severity.GOOD)
                StatusRow("Bluetooth", bt.label, if (bt == BluetoothState.ENABLED) Severity.GOOD else Severity.BAD)
                StatusRow("Permissions", if (perm == PermissionState.GRANTED) "Granted" else "Permission required",
                    if (perm == PermissionState.GRANTED) Severity.GOOD else Severity.WARN)
                StatusRow("HID registration", hid.registration.label, registrationSeverity(hid.registration))
                StatusRow("Connection", hid.connection.label, connectionSeverity(hid.connection))
                StatusRow("Connected laptop", hid.connectedHost?.displayName ?: "-", if (hid.isConnected) Severity.GOOD else Severity.IDLE)
                StatusRow("Connected for", since?.let { formatDuration(now - it) } ?: "-", Severity.IDLE)
                val disc = hid.connection as? ConnectionState.Disconnected
                StatusRow("Disconnect status", disc?.label ?: if (hid.isConnected) "Active link" else "-", if (disc != null) Severity.WARN else Severity.IDLE)
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (perm != PermissionState.GRANTED) ActionButton("Grant permission", onClick = actions.requestPermissions)
                if (bt == BluetoothState.DISABLED) ActionButton("Enable Bluetooth", onClick = actions.enableBluetooth)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val canRegister = bt == BluetoothState.ENABLED && perm == PermissionState.GRANTED &&
                    hid.registration != RegistrationState.Registered && hid.registration != RegistrationState.Registering
                ActionButton("Register HID Device", enabled = canRegister, onClick = vm::register)
                ActionButton("Unregister HID", enabled = hid.registration == RegistrationState.Registered || hid.registration == RegistrationState.Registering,
                    outlined = true, onClick = vm::unregister)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("Make discoverable", enabled = hid.registration == RegistrationState.Registered && perm == PermissionState.GRANTED,
                    outlined = true, onClick = actions.makeDiscoverable)
                ActionButton("Disconnect", enabled = hid.isConnected, outlined = true, onClick = vm::disconnect)
                ActionButton(
                    "Reconnect" + (vm.lastKnownHostName?.let { " $it" } ?: ""),
                    enabled = !hid.isConnected && hid.registration == RegistrationState.Registered && vm.lastKnownHostName != null,
                    outlined = true, onClick = vm::reconnect,
                )
            }
            if (hid.registration == RegistrationState.Registered && !hid.isConnected && hosts.any { it.isComputer }) {
                Spacer(Modifier.height(6.dp))
                Text("Paired hosts (connect from phone):", color = Palette.TextDim, fontSize = 12.sp)
                hosts.filter { it.isComputer }.forEach { h ->
                    ActionButton("Connect ${h.displayName}", outlined = true, onClick = { vm.connect(h) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Card {
                Text("Compatibility", color = Palette.TextDim, fontSize = 12.sp)
                vm.compatibility.forEach { Text(it, color = Palette.Text, fontSize = 12.sp) }
            }
        },
        // ---------------- Phase 1 HID input diagnostic + log
        second = {
            Card {
                Text("HID input diagnostic", color = Palette.Text, fontWeight = FontWeight.SemiBold)
                Text("Tap to toggle a button; Windows should show it held until tapped again.", color = Palette.TextDim, fontSize = 11.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ControllerButton.SQUARE, ControllerButton.CROSS, ControllerButton.CIRCLE).forEachIndexed { i, b ->
                        val on = pad.isPressed(b)
                        Button(
                            onClick = { vm.toggleTestButton(i + 1) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (on) Palette.Good else Palette.SurfaceHigh,
                                contentColor = if (on) Palette.Background else Palette.Text,
                            ),
                        ) { Text("Test Button ${i + 1}${if (on) " ON" else ""}", fontSize = 12.sp) }
                    }
                }
                ActionButton("Release All Buttons", outlined = true, onClick = vm::releaseAllInputs)
                Text("Last report sent (ID 1 + 6 bytes):", color = Palette.TextDim, fontSize = 11.sp)
                Text(lastReport?.let { "01 " + HidReportManager.toHex(it) } ?: "none yet", color = Palette.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(HidReportManager.describe(pad), color = Palette.TextDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Diagnostic log", color = Palette.TextDim, fontSize = 12.sp)
            val listState = rememberLazyListState()
            LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) listState.scrollToItem(logLines.lastIndex) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(320.dp).background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp),
            ) {
                items(logLines) { Text(it, color = Color(0xFFB8F5C8), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            }
        },
    )
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        content()
    }
}

@Composable
fun ActionButton(text: String, enabled: Boolean = true, outlined: Boolean = false, onClick: () -> Unit) {
    if (outlined) OutlinedButton(onClick = onClick, enabled = enabled) { Text(text, fontSize = 12.sp) }
    else Button(onClick = onClick, enabled = enabled) { Text(text, fontSize = 12.sp) }
}
