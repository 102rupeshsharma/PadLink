package com.ps2gamepad.ui.controller

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.controller.ControlRect
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.LayoutEngine
import com.ps2gamepad.model.ControlId
import com.ps2gamepad.model.ControllerBackground
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.ControllerProfile
import com.ps2gamepad.model.ExtraButton
import com.ps2gamepad.model.ButtonStyle
import com.ps2gamepad.model.ComboStep
import com.ps2gamepad.model.FaceLabels
import com.ps2gamepad.model.Hat
import com.ps2gamepad.model.Stick
import com.ps2gamepad.ui.ConnectionChip
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Palette
import com.ps2gamepad.ui.Screen
import com.ps2gamepad.ui.overallStatus
import kotlinx.coroutines.delay

/** Places a child by its centre and size (dp values). */
private fun Modifier.place(r: ControlRect) = this.offset(r.left.dp, r.top.dp).size(r.w.dp, r.h.dp)

/** Size/position of a user-added button on a [w] x [h] dp screen (same size rules for play and edit). */
internal fun extraRect(e: ExtraButton, w: Float, h: Float, a: com.ps2gamepad.model.Appearance): ControlRect {
    val d = LayoutEngine.unit(w, h) * 0.2f * e.scale * a.buttonScale
    return LayoutEngine.clampInside(ControlRect(e.cx * w, e.cy * h, d * 1.3f, d), w, h)
}

/** Index 0..3 (top, right, bottom, left) of a face button, else -1. */
internal fun faceIndexOf(b: ControllerButton) = when (b) {
    ControllerButton.TRIANGLE -> 0; ControllerButton.CIRCLE -> 1; ControllerButton.CROSS -> 2; ControllerButton.SQUARE -> 3; else -> -1
}

/** Name of a button as shown on this controller (face buttons follow the chosen labels). */
internal fun buttonName(b: ControllerButton, labels: FaceLabels): String {
    val i = faceIndexOf(b)
    return if (i >= 0) FaceColors.text(labels, i) else extraLabel(b)
}

/** Arrow for a D-pad direction. */
internal fun hatArrow(h: Hat) = when (h) {
    Hat.UP -> "\u2191"; Hat.UP_RIGHT -> "\u2197"; Hat.RIGHT -> "\u2192"; Hat.DOWN_RIGHT -> "\u2198"
    Hat.DOWN -> "\u2193"; Hat.DOWN_LEFT -> "\u2199"; Hat.LEFT -> "\u2190"; Hat.UP_LEFT -> "\u2196"; Hat.NEUTRAL -> ""
}

/** Combo label, e.g. "L1+R1" (held together) or "\u2193 \u2198 \u2192+X" (sequence). */
internal fun comboLabel(e: ExtraButton, labels: FaceLabels): String =
    e.steps.joinToString(" ") { st ->
        (listOf(hatArrow(st.hat)).filter { it.isNotEmpty() } + ControllerButton.entries.filter { it in st.buttons }.map { buttonName(it, labels) })
            .joinToString("+")
    }.ifEmpty { "Combo" }

/** Short label for a user-added button. */
internal fun extraLabel(b: ControllerButton) = when (b) {
    ControllerButton.TRIANGLE -> "Y"; ControllerButton.CIRCLE -> "B"
    ControllerButton.CROSS -> "A"; ControllerButton.SQUARE -> "X"
    ControllerButton.SELECT -> "SEL"; ControllerButton.START -> "START"
    else -> b.label
}

/** Full-screen landscape DualShock 2 style controller, plus the layout editor. */
@Composable
fun ControllerScreen(vm: MainViewModel) {
    val epoch by vm.input.resetEpoch.collectAsStateWithLifecycle()
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    val editing by vm.editMode.collectAsStateWithLifecycle()
    val bt by vm.bluetoothState.collectAsStateWithLifecycle()
    val perm by vm.permission.collectAsStateWithLifecycle()
    val hid by vm.hidState.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val status = overallStatus(bt, perm, hid)
    val store by vm.profiles.collectAsStateWithLifecycle()
    val neu = NeuThemes.BLACK
    var showOverlay by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val mapping by vm.mappingMode.collectAsStateWithLifecycle()
    var mappingNeedsSetting by remember { mutableStateOf(false) }
    LaunchedEffect(mappingNeedsSetting) { if (mappingNeedsSetting) { delay(6000); mappingNeedsSetting = false } }

    // Back: press twice within 2 s to leave, so a stray edge swipe cannot end a game session.
    var lastBack by remember { mutableLongStateOf(0L) }
    var backHint by remember { mutableStateOf(false) }
    BackHandler {
        // 3.7.1: Back closes what is open first (the ☰ menu, then Settings), like every Android screen
        if (showMenu) { showMenu = false; return@BackHandler }
        if (showSettings) { showSettings = false; return@BackHandler }
        if (editing) { vm.setEditMode(false); return@BackHandler }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) vm.navigate(Screen.HOME) else { lastBack = now; backHint = true; vm.input.releaseAll() }
    }
    LaunchedEffect(backHint) { if (backHint) { delay(2000); backHint = false } }

    CompositionLocalProvider(LocalNeu provides neu) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(neu.bg).windowInsetsPadding(WindowInsets.displayCutout).testTag("controller")
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        if (editing) {
            LayoutEditor(vm, profile, w, h)
            return@BoxWithConstraints
        }
        val rects = remember(w, h, profile.appearance, profile.placements) { LayoutEngine.resolve(w, h, profile.appearance, profile.placements) }
        ControllerLayout(vm.input, epoch, profile, rects, interactive = true)

        // 3.7.0: one button on top; Home, connection, player, Live, Edit, Settings and Gyro are in its menu
        MenuButton(player, hid.isConnected, Modifier.align(Alignment.TopCenter).padding(top = 6.dp)) {
            vm.input.releaseAll(); showMenu = true
        }
        if (!hid.isConnected) {
            Text(
                "NOT CONNECTED - inputs are not reaching the PC",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 42.dp).testTag("notConnectedBanner")
                    .background(Palette.Bad.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // 3.10.0 Mapping mode: always visible while on, so nobody plays with 8-way sticks by accident
        if (mapping) {
            Text(
                "MAPPING MODE: each stick direction sends its own code (↖ ↗ ↙ ↘ = Button 12-15 left, 16-19 right). ☰ → Mapping to play normally.",
                color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).testTag("mappingBanner")
                    .background(Palette.Warn, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (mappingNeedsSetting) {
            Text(
                "Mapping mode needs ☰ → Settings → Diagonal buttons for emulators ON (then pair again once).",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).testTag("mappingUnavailable")
                    .background(Color(0xDD000000), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
        if (backHint) {
            Text(
                "Press back again to leave the controller", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).background(Color(0xDD000000), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
        if (showOverlay) LiveOverlay(vm, Modifier.align(Alignment.Center).width(260.dp))
        if (showSettings) ControllerSettingsPanel(vm, profile, store.batterySaver, Modifier.align(Alignment.Center)) { showSettings = false }
        if (showMenu) QuickMenu(
            statusText = hid.connectedHost?.let { "Connected: ${it.displayName}" } ?: status.text,
            connected = hid.isConnected,
            player = player,
            liveOn = showOverlay,
            gyroAvailable = vm.gyroAvailable,
            gyroOn = profile.gyroAim.enabled,
            mappingOn = mapping,
            onMapping = {
                showMenu = false
                if (!vm.setMappingMode(!mapping)) mappingNeedsSetting = true
            },
            onHome = { showMenu = false; vm.navigate(Screen.HOME) },
            onLive = { showMenu = false; showOverlay = !showOverlay },
            onEdit = { showMenu = false; vm.setEditMode(true) },
            onSettings = { showMenu = false; vm.input.releaseAll(); showSettings = true },
            onGyro = { vm.setGyroEnabled(!profile.gyroAim.enabled) },
            onClose = { showMenu = false },
        )
    }
    }
}

/**
 * Draws the background and every control at [rects]. Used by the live controller (interactive),
 * the layout editor and the customisation preview (not interactive).
 */
@Composable
fun ControllerLayout(
    input: ControllerInputManager,
    epoch: Int,
    profile: ControllerProfile,
    rects: Map<ControlId, ControlRect>,
    interactive: Boolean,
    /** User-added buttons (the layout editor passes its unsaved draft). */
    extras: List<ExtraButton> = profile.extraButtons,
) {
    val a = profile.appearance
    val neu = LocalNeu.current
    val skin = Skins.of(neu)
    // pure black background: nothing lit but the buttons
    NeuScreenBackground(neu, Modifier.fillMaxSize())
    val style = ControlStyle(
        showLabels = a.showLabels, haptics = a.haptics && interactive, faceSpacing = a.buttonSpacing, interactive = interactive,
        buttonStyle = a.buttonStyle, faceLabels = a.faceLabels,
    )
    CompositionLocalProvider(LocalControlStyle provides style, LocalSkin provides skin) {
        Box(Modifier.fillMaxSize().alpha(a.buttonOpacity)) {
            // place + per-control opacity
            fun Modifier.at(id: ControlId) = this.place(rects.getValue(id)).alpha(a.opacityOf(id))
            fun r(id: ControlId) = rects.getValue(id)
            fun fs(id: ControlId, k: Float) = (r(id).h * k).sp
            // test tags keep the PS2 names; the visible label comes from the style
            for ((id, b, tag) in listOf(
                Triple(ControlId.L2, ControllerButton.L2, "L2"), Triple(ControlId.L1, ControllerButton.L1, "L1"),
                Triple(ControlId.R2, ControllerButton.R2, "R2"), Triple(ControlId.R1, ControllerButton.R1, "R1"),
            )) {
                if (a.isHidden(id)) continue // 3.8.0: hidden in this layout
                val label = when (id) { ControlId.L2 -> skin.labelL2; ControlId.L1 -> skin.labelL1; ControlId.R2 -> skin.labelR2; else -> skin.labelR1 }
                DigitalButton(b, input, epoch, label, a.shapeOf(id).keyShapeOr(KeyShape.ROUNDED), fs(id, 0.36f), Modifier.at(id).testTag("btn_$tag"),
                    customLabel = a.labelOf(id))
            }

            if (!a.isHidden(ControlId.DPAD)) DpadControl(input, epoch, Modifier.at(ControlId.DPAD).testTag("dpad"))
            // 1.9.0: four independent buttons (move, resize and overlap each one on its own)
            ControlId.FACE_BUTTONS.forEachIndexed { i, id ->
                if (a.isHidden(id)) return@forEachIndexed
                val b = when (id) { ControlId.TRIANGLE -> ControllerButton.TRIANGLE; ControlId.CIRCLE -> ControllerButton.CIRCLE; ControlId.CROSS -> ControllerButton.CROSS; else -> ControllerButton.SQUARE }
                DigitalButton(b, input, epoch, b.label, a.shapeOf(id).keyShapeOr(KeyShape.CIRCLE), fs(id, 0.42f), Modifier.at(id).testTag("btn_${b.name}"),
                    faceIndex = i, customLabel = a.labelOf(id))
            }
            if (!a.isHidden(ControlId.LEFT_STICK)) AnalogStick(Stick.LEFT, input, epoch, profile.left, Modifier.at(ControlId.LEFT_STICK).testTag("stick_left"))
            if (!a.isHidden(ControlId.RIGHT_STICK)) AnalogStick(Stick.RIGHT, input, epoch, profile.right, Modifier.at(ControlId.RIGHT_STICK).testTag("stick_right"))
            for ((id, b, def) in listOf(
                Triple(ControlId.L3, ControllerButton.L3, KeyShape.CIRCLE), Triple(ControlId.R3, ControllerButton.R3, KeyShape.CIRCLE),
                Triple(ControlId.SELECT, ControllerButton.SELECT, KeyShape.PILL), Triple(ControlId.START, ControllerButton.START, KeyShape.PILL),
            )) {
                if (a.isHidden(id)) continue
                val label = when (id) { ControlId.L3 -> skin.labelL3; ControlId.R3 -> skin.labelR3; ControlId.SELECT -> skin.labelSelect; else -> skin.labelStart }
                val k = if (def == KeyShape.CIRCLE) 0.3f else 0.34f
                DigitalButton(b, input, epoch, label, a.shapeOf(id).keyShapeOr(def), fs(id, k), Modifier.at(id).testTag("btn_${b.name}"),
                    customLabel = a.labelOf(id))
            }
            if (extras.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxSize()) {
                val cw = maxWidth.value
                val ch = maxHeight.value
                for (e in extras) {
                    val r = extraRect(e, cw, ch, a)
                    if (e.stickDirection != null) {
                        StickDirectionButton(e.id, e.stickDirection, input, epoch, (r.h * 0.34f).sp, Modifier.place(r).testTag("extra_${e.id}"),
                            customLabel = e.label, shape = e.shape.keyShapeOr(KeyShape.CIRCLE))
                    } else if (e.isCombo) {
                        val label = e.label ?: comboLabel(e, a.faceLabels)
                        ComboButton(e, input, epoch, label, (r.h * if (label.length > 6) 0.24f else 0.32f).sp, Modifier.place(r).testTag("extra_${e.id}"),
                            shape = e.shape.keyShapeOr(KeyShape.ROUNDED))
                    } else {
                        DigitalButton(
                            e.button, input, epoch, extraLabel(e.button), e.shape.keyShapeOr(KeyShape.ROUNDED), (r.h * 0.34f).sp,
                            Modifier.place(r).testTag("extra_${e.id}"), source = "extra_${e.id}", faceIndex = faceIndexOf(e.button),
                            customLabel = e.label,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Layout editor. Controls are drawn non-interactive (no HID input is possible). Tap selects,
 * drag moves, the slider resizes the selected control. A move or resize that would overlap
 * another control is refused (the control snaps back). Nothing is saved until "Save".
 */
@Composable
private fun LayoutEditor(vm: MainViewModel, profile: ControllerProfile, w: Float, h: Float) {
    val store by vm.profiles.collectAsStateWithLifecycle()
    val defaults = remember(w, h, profile.appearance) { LayoutEngine.defaults(w, h, profile.appearance) }
    // Read through the State object in gesture callbacks: they can run several times between
    // recompositions, so a captured/updated-on-recompose copy would be stale (drops were lost).
    val draftState = remember(w, h) { mutableStateOf(LayoutEngine.resolve(w, h, profile.appearance, profile.placements)) }
    var draft by draftState
    val extrasState = remember(w, h) { mutableStateOf(profile.extraButtons) }
    var extras by extrasState
    var selected by remember { mutableStateOf<ControlId?>(null) }
    var selectedExtra by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>("Drag a control to move it (overlap is fine). Tap it, then drag the blue corner to resize.") }
    var comboStep by remember { mutableStateOf(0) }
    val density = LocalDensity.current.density

    ControllerLayout(vm.input, 0, profile, draft, interactive = false, extras = extras)

    // Invisible touch handles on top of each control: the control itself moves under the finger.
    // Only the selected control gets a thin outline in its own shape (no boxes around everything).
    for (id in ControlId.editable) {
        if (profile.appearance.isHidden(id)) continue // hidden in this layout: nothing to grab
        val rect = draft.getValue(id)
        Box(
            Modifier.place(rect)
                .testTag("edit_${id.name}")
                .pointerInput(id) { detectTapGestures { selected = id; selectedExtra = null } }
                .pointerInput(id, draftState) {
                    var start: ControlRect? = null
                    detectDragGestures(
                        onDragStart = { selected = id; selectedExtra = null; start = draftState.value.getValue(id) },
                        onDrag = { change, amount ->
                            change.consume()
                            val r = draftState.value.getValue(id)
                            draft = draftState.value + (id to r.copy(cx = r.cx + amount.x / density, cy = r.cy + amount.y / density))
                        },
                        onDragEnd = {
                            // overlapping is allowed: only keep the control on screen
                            draft = draftState.value + (id to LayoutEngine.clampInside(draftState.value.getValue(id), w, h))
                            message = "${id.label} moved"
                        },
                        onDragCancel = { start?.let { draft = draftState.value + (id to it) } },
                    )
                }
        )
    }

    // user-added buttons: tap selects, drag moves (they may sit anywhere, even over other controls)
    for (e in extras) {
        val r = extraRect(e, w, h, profile.appearance)
        Box(
            Modifier.place(r)
                .testTag("editx_${e.id}")
                .pointerInput(e.id) { detectTapGestures { selectedExtra = e.id; selected = null; comboStep = 0 } }
                .pointerInput(e.id, extrasState) {
                    detectDragGestures(
                        onDragStart = { selectedExtra = e.id; selected = null; comboStep = 0 },
                        onDrag = { change, amount ->
                            change.consume()
                            extras = extrasState.value.map {
                                if (it.id != e.id) it
                                else it.copy(cx = (it.cx + amount.x / density / w).coerceIn(0f, 1f), cy = (it.cy + amount.y / density / h).coerceIn(0f, 1f))
                            }
                        },
                    )
                }
        )
    }

    // Selected control: dashed frame, blue corner handle (drag = resize), red x (delete an added button).
    val selRect: ControlRect? = selected?.let { draft.getValue(it) }
        ?: extras.firstOrNull { it.id == selectedExtra }?.let { extraRect(it, w, h, profile.appearance) }
    if (selRect != null) {
        val pad = 5f
        val frame = ControlRect(selRect.cx, selRect.cy, selRect.w + 2 * pad, selRect.h + 2 * pad)
        Box(
            Modifier.place(frame).testTag("editFrame").drawBehind {
                drawRoundRect(
                    Palette.Info, cornerRadius = CornerRadius(8.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            }
        )
        val handle = 24f
        Box(
            Modifier.offset((frame.right - handle / 2).dp, (frame.bottom - handle / 2).dp).size(handle.dp)
                .background(Palette.Info, CircleShape).border(2.dp, Color.White, CircleShape)
                .testTag("resizeHandle")
                .pointerInput(selected, selectedExtra) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        val grow = (amount.x + amount.y) / 2f / density
                        val sel = selected
                        val sx = selectedExtra
                        if (sel != null) {
                            val d = defaults.getValue(sel)
                            val cur = draftState.value.getValue(sel)
                            val sc = ((cur.w + grow * 2f) / d.w).coerceIn(0.5f, 2f)
                            draft = draftState.value + (sel to LayoutEngine.clampInside(cur.copy(w = d.w * sc, h = d.h * sc), w, h))
                        } else if (sx != null) {
                            extras = extrasState.value.map {
                                if (it.id != sx) it else {
                                    val cur = extraRect(it, w, h, profile.appearance)
                                    it.copy(scale = (it.scale * (cur.w + grow * 2f) / cur.w).coerceIn(0.5f, 2f))
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("↘", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        if (selectedExtra != null) {
            Box(
                Modifier.offset((frame.left - handle / 2).dp, (frame.top - handle / 2).dp).size(handle.dp)
                    .background(Palette.Bad, CircleShape).border(2.dp, Color.White, CircleShape)
                    .clickable { extras = extras.filter { it.id != selectedExtra }; selectedExtra = null; message = "Button removed (press Save to keep)" }
                    .testTag("extraDeleteX"),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        val selBuiltIn = selected
        if (selBuiltIn != null) {
            // 3.11.0: delete a built-in button from this layout (it can be brought back with "+ L2" etc.)
            Box(
                Modifier.offset((frame.left - handle / 2).dp, (frame.top - handle / 2).dp).size(handle.dp)
                    .background(Palette.Bad, CircleShape).border(2.dp, Color.White, CircleShape)
                    .clickable {
                        vm.setControlHidden(selBuiltIn, true); selected = null
                        message = "${selBuiltIn.label} deleted from this layout. Bring it back with + ${selBuiltIn.label} below"
                    }
                    .testTag("controlDeleteX"),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.background(Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("EDIT LAYOUT", color = Palette.Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            SmallChipButton("Save") {
                // the legacy face cluster is never saved: the four buttons carry their own placements
                val placements = draft.filter { (id, r) -> id != ControlId.FACE && r != defaults.getValue(id) }
                    .mapValues { (id, r) -> LayoutEngine.toPlacement(r, defaults.getValue(id), w, h) }
                vm.saveLayout(placements, extras)
                vm.log.add("Layout saved (${placements.size} control(s) moved/resized, ${extras.size} added button(s))")
                vm.setEditMode(false)
            }
            SmallChipButton("Cancel") { vm.setEditMode(false) }
            SmallChipButton("Defaults") { draft = defaults; message = "Default positions (press Save to keep)" }
            if (extras.size < ControllerProfile.MAX_EXTRA_BUTTONS) SmallChipButton("+ Button") {
                val e = ExtraButton(java.util.UUID.randomUUID().toString().take(6), ControllerButton.CROSS, 0.5f, 0.55f)
                extras = extras + e
                selectedExtra = e.id; selected = null
                message = "Button added: drag it into place, then choose what it presses"
            }
            if (extras.size < ControllerProfile.MAX_EXTRA_BUTTONS) SmallChipButton("+ Stick ↗") {
                val e = ExtraButton(
                    java.util.UUID.randomUUID().toString().take(6), ControllerButton.CROSS, 0.5f, 0.45f,
                    stickDirection = com.ps2gamepad.model.StickDirection(Stick.LEFT, com.ps2gamepad.model.Hat.UP_LEFT),
                )
                extras = extras + e
                selectedExtra = e.id; selected = null
                message = "Stick direction added: pick the stick and the direction (↖ ↗ ↙ ↘ for emulators that ask for diagonals)"
            }
            if (extras.size < ControllerProfile.MAX_EXTRA_BUTTONS) SmallChipButton("+ Combo") {
                val e = ExtraButton(
                    java.util.UUID.randomUUID().toString().take(6), ControllerButton.CROSS, 0.5f, 0.3f,
                    steps = listOf(ComboStep(setOf(ControllerButton.L1, ControllerButton.R1))),
                )
                extras = extras + e
                selectedExtra = e.id; selected = null; comboStep = 0
                message = "Combo added: pick buttons. One step = pressed together, more steps = played in order"
            }
        }
        val selX = extras.firstOrNull { it.id == selectedExtra }
        val selDir = selX?.stickDirection
        if (selX != null && selDir != null) {
            Column(
                Modifier.padding(top = 4.dp).background(Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("stickDirEditor"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                fun change(d: com.ps2gamepad.model.StickDirection) { extras = extras.map { if (it.id == selX.id) it.copy(stickDirection = d) else it } }
                val stickName = if (selDir.stick == Stick.LEFT) "left" else "right"
                Text(
                    if (selDir.ownButton && store.diagonalButtons && selDir.diagonalIndex != null)
                        "While held, sends ${selDir.emulatorButtonName} (${stickName} stick ${selDir.dir.label.lowercase()}, ${selDir.label})"
                    else "While held, pushes the $stickName stick ${selDir.dir.label.lowercase()} (${selDir.label})",
                    color = Color.White, fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    MapChip("Left stick", selDir.stick == Stick.LEFT, "dir_stick_LEFT") { change(selDir.copy(stick = Stick.LEFT)) }
                    MapChip("Right stick", selDir.stick == Stick.RIGHT, "dir_stick_RIGHT") { change(selDir.copy(stick = Stick.RIGHT)) }
                }
                if (selDir.diagonalIndex != null) {
                    val on = store.diagonalButtons
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        MapChip("Moves the stick", !selDir.ownButton, "dir_mode_stick") { change(selDir.copy(ownButton = false)) }
                        MapChip("Own button (${selDir.emulatorButtonName})", selDir.ownButton, "dir_mode_button") { change(selDir.copy(ownButton = true)) }
                    }
                    Text(
                        when {
                            !selDir.ownButton -> "Works in every game. For an emulator's Up-Left/Up-Right/Down-Left/Down-Right slots, choose Own button."
                            on -> "Sends ${selDir.emulatorButtonName} only: bind it in the emulator's ${selDir.dir.label} slot."
                            else -> "Turn on ☰ → Settings → Diagonal buttons for emulators first (until then it moves the stick)."
                        },
                        color = if (selDir.ownButton && !on) Palette.Warn else Color(0xFFB7BCCB), fontSize = 10.sp, modifier = Modifier.testTag("dir_mode_hint"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    for (h in com.ps2gamepad.model.StickDirection.ORDER) {
                        MapChip(com.ps2gamepad.model.StickDirection.ARROWS.getValue(h), selDir.dir == h, "dir_${h.name}", fontSize = 14.sp) { change(selDir.copy(dir = h)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size ${(selX.scale * 100).toInt()} %", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(90.dp))
                    Slider(value = selX.scale, valueRange = 0.5f..2f, modifier = Modifier.width(200.dp).testTag("extraSize"),
                        onValueChange = { v -> extras = extras.map { if (it.id == selX.id) it.copy(scale = v) else it } })
                    MapChip("Delete", false, "extra_delete") { extras = extras.filter { it.id != selX.id }; selectedExtra = null; message = "Button removed (press Save to keep)" }
                }
            }
        } else if (selX != null && selX.isCombo) {
            ComboEditor(selX, profile.appearance.faceLabels, comboStep, onStep = { comboStep = it }) { f -> extras = extras.map { if (it.id == selX.id) f(it) else it } }
        } else if (selX != null) {
            Column(
                Modifier.padding(top = 4.dp).background(Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("extraEditor"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                fun change(f: (ExtraButton) -> ExtraButton) { extras = extras.map { if (it.id == selX.id) f(it) else it } }
                Text("Added button presses: ${selX.button.label}", color = Color.White, fontSize = 11.sp)
                for (row in ControllerButton.entries.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                        for (b in row) MapChip(b.label, b == selX.button, "map_${b.name}") { change { it.copy(button = b) } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size ${(selX.scale * 100).toInt()} %", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(90.dp))
                    Slider(value = selX.scale, valueRange = 0.5f..2f, modifier = Modifier.width(200.dp).testTag("extraSize"), onValueChange = { v -> change { it.copy(scale = v) } })
                    MapChip("Delete", false, "extra_delete") { extras = extras.filter { it.id != selX.id }; selectedExtra = null; message = "Button removed (press Save to keep)" }
                }
            }
        }
        if (selX != null) {
            val default = when {
                selX.stickDirection != null -> selX.stickDirection.label
                selX.isCombo -> comboLabel(selX.copy(label = null), profile.appearance.faceLabels)
                else -> extraLabel(selX.button)
            }
            val defShape = when {
                selX.stickDirection != null -> com.ps2gamepad.model.ButtonShape.CIRCLE
                else -> com.ps2gamepad.model.ButtonShape.RECTANGLE
            }
            ButtonLookEditor(
                text = selX.label ?: "", placeholder = default, shape = selX.shape, defaultShape = defShape,
                onText = { t -> extras = extras.map { if (it.id == selX.id) it.copy(label = com.ps2gamepad.model.ButtonShape.cleanLabel(t)) else it } },
                onShape = { sh -> extras = extras.map { if (it.id == selX.id) it.copy(shape = sh) else it } },
            )
        }
        val sel = selected
        if (sel != null && sel in ControlId.customizable) {
            val a = profile.appearance
            val defShape = when (sel) {
                ControlId.L3, ControlId.R3, ControlId.TRIANGLE, ControlId.CIRCLE, ControlId.CROSS, ControlId.SQUARE -> com.ps2gamepad.model.ButtonShape.CIRCLE
                else -> com.ps2gamepad.model.ButtonShape.RECTANGLE
            }
            ButtonLookEditor(
                text = a.labelOf(sel) ?: "", placeholder = defaultLabelOf(sel, a.faceLabels), shape = a.shapeOf(sel), defaultShape = defShape,
                onText = { vm.setControlLabel(sel, it) }, onShape = { vm.setControlShape(sel, it) },
            )
        }
        if (sel != null) {
            val scale = draft.getValue(sel).w / defaults.getValue(sel).w
            Row(
                Modifier.padding(top = 4.dp).background(Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapChip("Hide", false, "hide_control") {
                    vm.setControlHidden(sel, true); selected = null
                    message = "${sel.label} hidden in this layout. Show it again: ☰ → Settings → Buttons on screen"
                }
                Text("${sel.label} size ${(scale * 100).toInt()} %", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(140.dp).padding(start = 6.dp))
                Slider(
                    value = scale, valueRange = 0.5f..2f, modifier = Modifier.width(200.dp).testTag("sizeSlider"),
                    onValueChange = { sc ->
                        val d = defaults.getValue(sel)
                        val cur = draft.getValue(sel)
                        draft = draft + (sel to LayoutEngine.clampInside(cur.copy(w = d.w * sc, h = d.h * sc), w, h))
                    },
                )
                Text("Opacity ${(profile.appearance.opacityOf(sel) * 100).toInt()} %", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(90.dp))
                Slider(
                    value = profile.appearance.opacityOf(sel), valueRange = com.ps2gamepad.model.Appearance.MIN_CONTROL_OPACITY..1f,
                    modifier = Modifier.width(160.dp).testTag("opacitySlider"),
                    onValueChange = { vm.setControlOpacity(sel, it) },
                )
            }
        }
        val deleted = ControlId.editable.filter { profile.appearance.isHidden(it) }
        if (deleted.isNotEmpty()) {
            Row(
                Modifier.padding(top = 4.dp).background(Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("deletedRow"),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Deleted:", color = Color(0xFFB7BCCB), fontSize = 11.sp)
                for (id in deleted) MapChip("+ ${defaultLabelOf(id, profile.appearance.faceLabels)}", false, "restore_${id.name}") {
                    vm.setControlHidden(id, false); message = "${id.label} is back"
                }
            }
        }
        message?.let {
            Text(it, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

/** The name a control shows when it has no own text (face buttons follow the chosen labels). */
private fun defaultLabelOf(id: ControlId, labels: FaceLabels): String {
    val i = ControlId.FACE_BUTTONS.indexOf(id)
    return if (i >= 0) FaceColors.text(labels, i) else id.label
}

/**
 * 3.11.0: own text (up to 6 characters, empty = the normal label) and shape (Circle / Square / Rectangle, or the
 * normal one) for the selected button. Only the look changes; the button sends the same signal.
 */
@Composable
private fun ButtonLookEditor(
    text: String, placeholder: String, shape: com.ps2gamepad.model.ButtonShape?, defaultShape: com.ps2gamepad.model.ButtonShape,
    onText: (String) -> Unit, onShape: (com.ps2gamepad.model.ButtonShape?) -> Unit,
) {
    Row(
        Modifier.padding(top = 4.dp).background(Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("lookEditor"),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Text", color = Color.White, fontSize = 11.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { onText(it.take(com.ps2gamepad.model.ButtonShape.MAX_LABEL)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Info),
            modifier = Modifier.width(90.dp).background(Color(0xFF2A2F3A), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp).testTag("labelField"),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(placeholder, color = Color(0xFF7D8494), fontSize = 13.sp, maxLines = 1)
                    inner()
                }
            },
        )
        if (text.isNotEmpty()) MapChip("Reset", false, "labelReset") { onText("") }
        Text("Shape", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        for (s in com.ps2gamepad.model.ButtonShape.entries) {
            val chosen = (shape ?: defaultShape) == s
            MapChip("${s.icon} ${s.label}", chosen, "shape_${s.name}", fontSize = 11.sp) { onShape(if (s == defaultShape) null else s) }
        }
    }
}

@Composable
private fun MapChip(text: String, on: Boolean, tag: String, fontSize: androidx.compose.ui.unit.TextUnit = 11.sp, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = fontSize, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.background(if (on) Palette.Info else Color(0xFF2A2F3A), RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp).testTag(tag),
    )
}

/** Which pads each face-label set looks like (shown under its preview tile). */
private fun faceLabelsHint(fl: FaceLabels) = when (fl) {
    FaceLabels.PLAYSTATION -> "PS4 / PS5"
    FaceLabels.XBOX -> "Xbox"
    FaceLabels.NINTENDO -> "Switch / 3DS"
}

@Composable
private fun SmallChipButton(text: String, onClick: () -> Unit) {
    val neu = LocalNeu.current
    Text(
        text, color = neu.text, fontSize = 11.sp,
        modifier = Modifier.background(neu.chip, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp).testTag("chip_$text"),
    )
}

/** Compact live readout, so inputs can be checked while using the real controller. */
@Composable
private fun LiveOverlay(vm: MainViewModel, modifier: Modifier) {
    val pad by vm.gamepad.collectAsStateWithLifecycle()
    val pointers by vm.input.activePointers.collectAsStateWithLifecycle()
    val last by vm.input.lastReport.collectAsStateWithLifecycle()
    Column(modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(6.dp)) {
        val t = 9.sp
        Text(HidReportManager.describe(pad), color = Color(0xFFB8F5C8), fontSize = t, fontFamily = FontFamily.Monospace)
        Text("pointers=$pointers", color = Color(0xFFB8F5C8), fontSize = t, fontFamily = FontFamily.Monospace)
        Text("last: " + (last?.let { "01 " + HidReportManager.toHex(it) } ?: "-"), color = Color(0xFFB8F5C8), fontSize = t, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Quick settings inside the controller: button style, face labels, battery saver, opacity and size.
 * Changes apply live and are saved to the loadout in use. The panel takes all touches, so nothing
 * behind it is pressed.
 */
@Composable
private fun ControllerSettingsPanel(vm: MainViewModel, profile: ControllerProfile, saver: Boolean, modifier: Modifier, onClose: () -> Unit) {
    val store by vm.profiles.collectAsStateWithLifecycle()
    val a = profile.appearance
    val neu = LocalNeu.current
    Column(
        modifier.width(400.dp).padding(vertical = 40.dp)
            .background(neu.panel, RoundedCornerShape(18.dp))
            .border(1.dp, neu.labelDim.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            // Being a pointer-input node makes the whole panel a touch target, so touches on it never
            // reach the controls behind it. It must NOT consume: a real finger always moves a little,
            // and consumed moves made every chip, switch, slider and the scroll cancel (3.1.1 fix).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .verticalScroll(androidx.compose.foundation.rememberScrollState()) // short screens: nothing is cut off
            .padding(16.dp)
            .testTag("controllerSettings"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Controller settings", color = neu.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Saved to this layout: ${profile.name}", color = neu.textDim, fontSize = 11.sp, modifier = Modifier.testTag("settingsLayoutName"))
            }
            SmallChipButton("Close") { onClose() }
        }
        // 3.7.0: every choice is a tile that draws the real button, and the controller behind updates live
        Text("Button style", color = neu.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (st in ButtonStyle.entries) StylePreviewTile(
                st.label, "", st, a.faceLabels, a.buttonStyle == st, "style_${st.name}",
            ) { vm.updateAppearance { it.copy(buttonStyle = st) } }
        }
        Text(a.buttonStyle.hint, color = neu.textDim, fontSize = 11.sp)
        Text("Face buttons", color = neu.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (fl in FaceLabels.entries) StylePreviewTile(
                faceLabelsHint(fl), "", a.buttonStyle, fl, a.faceLabels == fl, "faces_${fl.name}", cluster = true, width = 112.dp,
            ) { vm.updateAppearance { it.copy(faceLabels = fl) } }
        }
        Text("Only the labels change: the buttons send the same signals, so emulator bindings stay.", color = neu.textDim, fontSize = 11.sp)
        // 3.8.0: show / hide controls in this layout (e.g. no L2 / R2)
        Text("Buttons on screen", color = neu.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val faceNames = (0..3).associate { ControlId.FACE_BUTTONS[it] to FaceColors.text(a.faceLabels, it) }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (id in ControlId.editable) {
                val shown = !a.isHidden(id)
                MapChip((if (shown) "✓ " else "") + (faceNames[id] ?: id.label), shown, "show_${id.name}") { vm.setControlHidden(id, shown) }
            }
        }
        Text("Tap to hide or show. Hidden controls are not drawn and cannot be pressed. Each layout keeps its own choice.", color = neu.textDim, fontSize = 11.sp)
        // 3.9.0: diagonal buttons (all layouts, opt-in)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagonal buttons for emulators", color = neu.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            MapChip(if (store.diagonalButtons) "ON" else "OFF", store.diagonalButtons, "set_diagonals") { vm.setDiagonalButtons(!store.diagonalButtons) }
        }
        Text(
            "Gives each stick diagonal its own button for emulators with Up-Left / Up-Right / Down-Left / Down-Right slots " +
                "(e.g. 3DS): left ↖ ↗ ↙ ↘ = Button 12-15, right ↖ ↗ ↙ ↘ = Button 16-19. Your other mappings stay. " +
                "All layouts. After switching: on the PC remove PadLink and pair again once, then Connect. " +
                "Then in Edit set a stick direction button to Own button.",
            color = neu.textDim, fontSize = 11.sp, modifier = Modifier.testTag("diagonalsHint"),
        )
        val g = profile.gyroAim
        Text("Gyro aim" + if (g.enabled) " (ON)" else " (OFF: turn on with Gyro in the ☰ menu)", color = neu.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Turn the phone to aim: how fast you turn = how fast the camera turns. Stop and it stops.", color = neu.textDim, fontSize = 11.sp)
        SettingSlider("Gyro sensitivity", g.sensitivity, 0.25f..4f, "set_gyroSens") { v -> vm.updateGyroAim { it.copy(sensitivity = v) } }
        SettingSlider("Game dead-zone fix", g.gameDeadZone, 0f..0.4f, "set_gyroDz") { v -> vm.updateGyroAim { it.copy(gameDeadZone = v) } }
        Text("Small, slow aim does nothing? Raise this. Camera creeps when you barely move? Lower it.", color = neu.textDim, fontSize = 11.sp)
        SettingSlider("Up/down speed", g.verticalScale, 0.5f..1.5f, "set_gyroVert") { v -> vm.updateGyroAim { it.copy(verticalScale = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MapChip("Invert up/down", g.invertY, "set_gyroInvY") { vm.updateGyroAim { it.copy(invertY = !it.invertY) } }
            MapChip("Invert left/right", g.invertX, "set_gyroInvX") { vm.updateGyroAim { it.copy(invertX = !it.invertX) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Battery saver", color = neu.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("ON: 60 Hz screen and fewer stick updates (buttons stay instant). Brightness stays yours.", color = neu.textDim, fontSize = 11.sp)
            }
            androidx.compose.material3.Switch(checked = saver, onCheckedChange = { vm.setBatterySaver(it) }, modifier = Modifier.testTag("set_saver"))
        }
        SettingSlider("Opacity (all controls)", a.buttonOpacity, 0.2f..1f, "set_opacity") { v -> vm.updateAppearance { it.copy(buttonOpacity = v) } }
        SettingSlider("Button size (all buttons)", a.buttonScale, 0.6f..1.6f, "set_buttonSize") { v -> vm.updateAppearance { it.copy(buttonScale = v) } }
        SettingSlider("Joystick size", a.stickScale, 0.6f..1.6f, "set_stickSize") { v -> vm.updateAppearance { it.copy(stickScale = v) } }
        SmallChipButton("Reset size & opacity") {
            vm.updateAppearance { it.copy(buttonOpacity = 1f, buttonScale = 1f, stickScale = 1f) }
        }
    }
}

/**
 * Combo editor (layout editor, combo selected). Each step = buttons pressed together plus an
 * optional D-pad direction. One step: held together. Several: played in order, [ExtraButton.stepMs] each.
 */
@Composable
private fun ComboEditor(
    combo: ExtraButton, labels: FaceLabels, stepIndex: Int, onStep: (Int) -> Unit, change: ((ExtraButton) -> ExtraButton) -> Unit,
) {
    val i = stepIndex.coerceIn(0, (combo.steps.size - 1).coerceAtLeast(0))
    val step = combo.steps.getOrNull(i) ?: ComboStep()
    fun setStep(f: (ComboStep) -> ComboStep) = change { c -> c.copy(steps = c.steps.mapIndexed { k, st -> if (k == i) f(st) else st }) }
    Column(
        Modifier.padding(top = 4.dp).background(Color(0xEE000000), RoundedCornerShape(10.dp)).border(1.dp, COMBO_COLOR.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp).testTag("comboEditor"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Combo: ${comboLabel(combo, labels)}  \u00B7  " + if (combo.isSequence) "plays ${combo.steps.size} steps in order" else "pressed together",
            color = COMBO_COLOR, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
            combo.steps.indices.forEach { k -> MapChip("Step ${k + 1}", k == i, "combo_step_$k") { onStep(k) } }
            if (combo.steps.size < ExtraButton.MAX_STEPS) MapChip("+ Step", false, "combo_addStep") {
                change { it.copy(steps = it.steps + ComboStep()) }; onStep(combo.steps.size)
            }
            if (combo.steps.size > 1) MapChip("Remove step", false, "combo_removeStep") {
                change { c -> c.copy(steps = c.steps.filterIndexed { k, _ -> k != i }) }; onStep((i - 1).coerceAtLeast(0))
            }
        }
        for (row in ControllerButton.entries.chunked(6)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                for (b in row) MapChip(buttonName(b, labels), b in step.buttons, "combo_btn_${b.name}") {
                    setStep { st -> st.copy(buttons = if (b in st.buttons) st.buttons - b else st.buttons + b) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
            MapChip("No dir", step.hat == Hat.NEUTRAL, "combo_hat_NEUTRAL") { setStep { it.copy(hat = Hat.NEUTRAL) } }
            for (h in Hat.entries.filter { it != Hat.NEUTRAL }) MapChip(hatArrow(h), step.hat == h, "combo_hat_${h.name}", fontSize = 17.sp) { setStep { it.copy(hat = h) } }
        }
        if (combo.isSequence) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Step time ${combo.stepMs} ms", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(110.dp))
            Slider(
                value = combo.stepMs.toFloat(), valueRange = ExtraButton.MIN_STEP_MS.toFloat()..ExtraButton.MAX_STEP_MS.toFloat(),
                modifier = Modifier.width(220.dp).testTag("combo_speed"), onValueChange = { v -> change { it.copy(stepMs = v.toInt()) } },
            )
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, onChange: (Float) -> Unit) {
    Text("$label: ${(value * 100).toInt()} %", color = LocalNeu.current.text, fontSize = 13.sp)
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.testTag(tag))
}
