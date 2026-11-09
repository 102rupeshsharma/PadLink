package com.ps2gamepad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ps2gamepad.bluetooth.PhoneNameControl
import com.ps2gamepad.ui.controller.ControllerPreview

private val HomeTop = Color.Black // pure black: AMOLED pixels off
private val HomeBottom = Color.Black
private val CardTop = Color(0xFF1F2438)
private val CardBottom = Color(0xFF161A28)
private val CardLine = Color(0x33FFFFFF)

/**
 * Start screen, PORTRAIT (3.3.0): one status in plain words with ONE button (Connect, or Open
 * Controller once connected), a live preview of the controller, and quick tiles. Nothing connects
 * until Connect is tapped. Advanced screens are under "More".
 */
@Composable
fun SimpleHomeScreen(vm: MainViewModel, actions: AppActions) {
    val bt by vm.bluetoothState.collectAsStateWithLifecycle()
    val perm by vm.permission.collectAsStateWithLifecycle()
    val hid by vm.hidState.collectAsStateWithLifecycle()
    val name by vm.phoneName.collectAsStateWithLifecycle()
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    vm.bondedHosts.collectAsStateWithLifecycle().value // recompose when the paired list changes
    val store by vm.profiles.collectAsStateWithLifecycle() // ...or the last connection / theme changes

    val connecting by vm.connectRequested.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val phoneName = name ?: PhoneNameControl.GAMEPAD_NAME
    val trying by vm.connectingTo.collectAsStateWithLifecycle()
    val st = simpleStatus(bt, perm, hid, vm.knownPc, phoneName, started = vm.started, connecting = connecting,
        trying = trying, otherLaptops = (vm.connectCandidates().size - 1).coerceAtLeast(0))
    BackHandler { actions.exit() }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(HomeTop, HomeBottom))).testTag("home"),
    ) {
        // soft glow in the status colour behind the status card
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(st.severity.color.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.3f), radius = size.height * 0.75f),
                radius = size.height * 0.75f, center = Offset(size.width * 0.5f, size.height * 0.3f),
            )
        }
        // one portrait column: brand, status + the one button, name, controller preview, tiles
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 20.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF4C6FFF), Color(0xFF8E5CFF)))),
                        contentAlignment = Alignment.Center,
                    ) { Text("PL", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp) }
                    Column {
                        Text("PadLink", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Phone controller for emulators & PC games", color = Palette.TextDim, fontSize = 12.sp)
                    }
                }

                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
                        .border(1.dp, Brush.linearGradient(listOf(st.severity.color.copy(alpha = 0.7f), CardLine)), RoundedCornerShape(24.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(18.dp).background(Brush.radialGradient(listOf(st.severity.color, st.severity.color.copy(alpha = 0.35f))), CircleShape)
                                .border(3.dp, st.severity.color.copy(alpha = 0.25f), CircleShape),
                        )
                        Text(st.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("homeTitle"))
                    }
                    Text(st.help, color = Color(0xFFB7BCCB), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.testTag("homeHelp"))
                    if (st.action != SimpleAction.NONE_WAIT) {
                        Spacer(Modifier.height(4.dp))
                        GradientButton(
                            st.actionLabel,
                            if (st.action == SimpleAction.OPEN_CONTROLLER) listOf(Color(0xFF22C55E), Color(0xFF16A34A))
                            else listOf(Color(0xFF4C6FFF), Color(0xFF8E5CFF)),
                            Modifier.fillMaxWidth().testTag("homeAction"),
                        ) {
                            when (st.action) {
                                SimpleAction.ALLOW_PERMISSION -> actions.requestPermissions()
                                SimpleAction.TURN_ON_BLUETOOTH -> actions.enableBluetooth()
                                SimpleAction.RETRY_SETUP -> vm.register()
                                SimpleAction.MAKE_VISIBLE -> { vm.applyPhoneName(); actions.makeDiscoverable() }
                                SimpleAction.CONNECT_PC -> vm.connectKnownPc()
                                // no laptop known (none yet, or several paired): let the user pick one
                                SimpleAction.CONNECT -> if (vm.knownPc == null) vm.navigate(Screen.LAPTOPS) else vm.connectNow()
                                SimpleAction.OPEN_CONTROLLER -> vm.openController()
                                SimpleAction.NONE_WAIT -> Unit
                            }
                        }
                    }
                    if (st.action != SimpleAction.ALLOW_PERMISSION && st.action != SimpleAction.TURN_ON_BLUETOOTH && st.action != SimpleAction.NONE_WAIT || hid.isConnected) {
                        Text(
                            "Choose another laptop / pair a new one",
                            color = Color(0xFF9DB0FF), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.navigate(Screen.LAPTOPS) }
                                .padding(vertical = 4.dp, horizontal = 2.dp).testTag("homePairNew"),
                        )
                    }
                }

                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(horizontal = 14.dp, vertical = 7.dp).testTag("homeName"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).background(Color(0xFF4C6FFF), CircleShape))
                    Text("Shown on your PC as", color = Palette.TextDim, fontSize = 12.sp)
                    Text("\"$phoneName\"", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                PlayerPicker(player, enabled = !hid.isConnected) { vm.setPlayer(it) }
            }

            // ------------------------------------------------ controller preview + tiles
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
                        .border(1.dp, CardLine, RoundedCornerShape(24.dp))
                        .clickable { vm.navigate(Screen.CUSTOMIZE) }
                        .padding(14.dp)
                        .testTag("homePreview"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your controller", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Box(Modifier.background(Color(0xFF4C6FFF), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                            Text(profile.appearance.buttonStyle.label + " buttons", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    ControllerPreview(vm.input, profile, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)))
                    Text("Profile: ${profile.name}", color = Palette.TextDim, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeTile("Customize", "Size · opacity", Color(0xFF22C55E), Modifier.weight(1f).testTag("homeCustomize")) { vm.navigate(Screen.CUSTOMIZE) }
                    HomeTile("More", "Profiles", Color(0xFFF59E0B), Modifier.weight(1f).testTag("homeMore")) { vm.navigate(Screen.DASHBOARD) }
                }
            }
        }
    }
}

/** Two phones on one PC: each phone picks its player (P1 keeps the old names, P2 is "PadLink P2"). */
@Composable
private fun PlayerPicker(player: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("playerPicker")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Player", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(end = 2.dp))
            for (n in 1..com.ps2gamepad.bluetooth.PlayerSlot.MAX) {
                val on = n == player
                Text(
                    "P$n", color = if (on) Color.White else Color(0xFFB7BCCB).copy(alpha = if (enabled) 1f else 0.5f),
                    fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Color(0xFF4C6FFF) else Color(0x14FFFFFF))
                        .clickable(enabled = enabled) { onPick(n) }
                        .padding(horizontal = 14.dp, vertical = 7.dp).testTag("player_$n"),
                )
            }
        }
        Text(
            if (enabled) "Two phones on one PC? Make the second phone P2." else "Disconnect to change the player.",
            color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.testTag("playerHint"),
        )
    }
}

@Composable
private fun GradientButton(text: String, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(56.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(colors)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HomeTile(title: String, subtitle: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
            .border(1.dp, CardLine, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(26.dp).height(4.dp).background(accent, RoundedCornerShape(50)))
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Palette.TextDim, fontSize = 10.sp, maxLines = 1)
    }
}
