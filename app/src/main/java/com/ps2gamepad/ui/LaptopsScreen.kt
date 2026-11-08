package com.ps2gamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ps2gamepad.model.HostDevice

/**
 * "Choose a laptop" (3.5.0): every paired laptop with its own Connect button, a search for new
 * laptops near the phone (Pair & connect), and pairing from the laptop side. The phone never
 * reconnects to the old laptop while you pick or pair another one.
 */
@Composable
fun LaptopsScreen(vm: MainViewModel, actions: AppActions) {
    val hid by vm.hidState.collectAsStateWithLifecycle()
    val bonded by vm.bondedHosts.collectAsStateWithLifecycle()
    val found by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val connecting by vm.connectRequested.collectAsStateWithLifecycle()
    val laptops = bonded.filter { it.isComputer }
    val pairedAddresses = bonded.map { it.address }.toSet()
    val newOnes = found.filter { it.address !in pairedAddresses }
    val lastUsed = vm.knownPc?.address

    LaunchedEffect(Unit) { vm.refreshBondedHosts() }
    // A NEW connection made from this screen: back to the start screen ("Connected ✓", Open Controller).
    val connectedAtEntry = remember { hid.connectedHost?.address }
    LaunchedEffect(hid.connectedHost?.address) {
        val now = hid.connectedHost?.address
        if (now != null && now != connectedAtEntry) { vm.stopSearch(); vm.navigate(Screen.HOME) }
    }

    ScreenFrame("Choose a laptop", onBack = { vm.stopSearch(); vm.navigate(Screen.HOME) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                hid.connectedHost?.let { "Connected to ${it.displayName} ✓" }
                    ?: if (connecting) "Connecting…" else "Not connected",
                color = if (hid.isConnected) Palette.Good else Palette.TextDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("laptopsStatus"),
            )

            Section("Your laptops") {
                if (laptops.isEmpty()) {
                    Text("No laptop paired yet. Add one below.", color = Palette.TextDim, fontSize = 13.sp)
                }
                for (h in laptops) {
                    val isConnected = hid.connectedHost?.address == h.address
                    LaptopRow(
                        h, subtitle = when {
                            isConnected -> "Connected"
                            h.address == lastUsed -> "Used last"
                            else -> "Paired"
                        },
                        button = if (isConnected) "Connected ✓" else "Connect",
                        enabled = !isConnected,
                        tag = "laptop_${h.address}",
                    ) { vm.chooseLaptop(h) }
                }
            }

            Section("Add a new laptop") {
                Text(
                    "On the new laptop open Settings → Bluetooth & devices → Add device → Bluetooth, so it can be found. " +
                        "Then search here and tap it.",
                    color = Palette.TextDim, fontSize = 12.sp,
                )
                if (vm.canSearch) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(if (searching) "Stop searching" else "Search for laptops", primary = !searching, tag = "laptopSearch") {
                            if (searching) vm.stopSearch() else actions.requestScanPermission { vm.startSearch() }
                        }
                        if (searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    if (!searching && newOnes.isEmpty()) {
                        Text("Nothing found yet.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.testTag("laptopNoneFound"))
                    }
                    for (h in newOnes) {
                        LaptopRow(
                            h, subtitle = if (h.isComputer) "Laptop / PC nearby" else "Other device nearby",
                            button = if (pairing == h.address) "Pairing…" else "Pair & connect",
                            enabled = pairing == null,
                            tag = "found_${h.address}",
                        ) { vm.pairAndConnect(h) }
                    }
                }
                Text("Or pair from the laptop:", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap the button, then on the laptop: Add device → Bluetooth → choose \"${vm.phoneName.value ?: "PadLink"}\" and confirm the code.",
                    color = Palette.TextDim, fontSize = 12.sp,
                )
                PillButton("Make this phone visible", primary = false, tag = "laptopVisible") {
                    vm.prepareForNewLaptop(); actions.makeDiscoverable()
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun LaptopRow(h: HostDevice, subtitle: String, button: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(if (subtitle == "Connected") Palette.Good else Palette.Info, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(h.displayName, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = Palette.TextDim, fontSize = 11.sp)
        }
        PillButton(button, primary = enabled, enabled = enabled, tag = "${tag}_btn", onClick = onClick)
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(50))
            .then(if (primary) Modifier.background(Palette.Info) else Modifier.border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(50)))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (primary) Color.White else if (enabled) Palette.Info else Palette.TextDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
