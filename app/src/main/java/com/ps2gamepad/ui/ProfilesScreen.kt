package com.ps2gamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ps2gamepad.model.ControllerProfile

private sealed interface ProfileDialog {
    data object Create : ProfileDialog
    data class Rename(val p: ControllerProfile) : ProfileDialog
    data class Delete(val p: ControllerProfile) : ProfileDialog
}

/** Profile manager: switch, create, rename, duplicate, delete, restore defaults. Saved automatically. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(vm: MainViewModel) {
    val store by vm.profiles.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

    ScreenFrame("Profiles", onBack = { vm.navigate(Screen.HOME) }, actions = {
        val canCreate = com.ps2gamepad.settings.ProfileManager.canCreateLoadout(store)
        ActionButton(if (canCreate) "New profile" else "Limit: ${com.ps2gamepad.settings.ProfileManager.MAX_LOADOUTS} loadouts", enabled = canCreate) { dialog = ProfileDialog.Create }
    }) {
        Text(
            "Profiles change layout, look and analog response only. Every profile sends the same standard HID buttons, " +
                "so the PCSX2 binding stays valid. Game profiles are starting points, not tested presets.",
            color = Palette.TextDim, fontSize = 11.sp,
        )
        // Keep the active profile visible (new and duplicated profiles are added at the end).
        val listState = rememberLazyListState()
        LaunchedEffect(store.activeId) {
            val i = store.profiles.indexOfFirst { it.id == store.activeId }
            if (i >= 0) listState.animateScrollToItem(i)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(store.profiles, key = { it.id }) { p ->
                val active = p.id == store.activeId
                Column(
                    Modifier.fillMaxWidth().background(if (active) Palette.SurfaceHigh else Palette.Surface, RoundedCornerShape(8.dp))
                        .padding(8.dp).testTag("profile_${p.id}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column {
                        Text(p.name + if (active) "  (active)" else "", color = Palette.Text, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            (if (p.builtIn) "Built-in" else "Custom") + " · L sens ${mult(p.left.sensitivity)} · R sens ${mult(p.right.sensitivity)}" +
                                " · ${p.placements.size} moved control(s)",
                            color = Palette.TextDim, fontSize = 10.sp,
                        )
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!active) Box(Modifier.testTag("use_${p.id}")) { ActionButton("Use") { vm.switchProfile(p.id) } }
                    ActionButton("Rename", outlined = true) { dialog = ProfileDialog.Rename(p) }
                    ActionButton("Duplicate", enabled = com.ps2gamepad.settings.ProfileManager.canCreateLoadout(store), outlined = true) { vm.duplicateProfile(p.id) }
                    if (p.builtIn) ActionButton("Restore", outlined = true) { vm.restoreProfileDefaults(p.id) }
                    else ActionButton("Delete", outlined = true) { dialog = ProfileDialog.Delete(p) }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        ProfileDialog.Create -> NameDialog("New profile", "", { dialog = null }) { vm.createProfile(it); dialog = null }
        is ProfileDialog.Rename -> NameDialog("Rename profile", d.p.name, { dialog = null }) { vm.renameProfile(d.p.id, it); dialog = null }
        is ProfileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete \"${d.p.name}\"?") },
            text = { Text("Its layout and analog settings will be lost.") },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(d.p.id); dialog = null }, modifier = Modifier.testTag("confirmDelete")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(ControllerProfile.MAX_NAME) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.testTag("nameField"))
                Text("Names must be unique.", fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("confirmName")) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
