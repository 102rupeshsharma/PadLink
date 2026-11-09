package com.ps2gamepad.ui.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ps2gamepad.model.ButtonStyle
import com.ps2gamepad.model.FaceLabels

/*
 * 3.7.0: the controller's top bar is ONE button. Everything that used to sit along the top (Home, connection,
 * player, Live, Edit, Settings, Gyro) lives in the quick menu it opens, so the screen stays clean while playing.
 */

private val Good = Color(0xFF34C759)
private val Bad = Color(0xFFFF453A)
private val Accent = Color(0xFF4C6FFF)
private val CardTop = Color(0xFF1C1F26)
private val CardBottom = Color(0xFF0D0F13)

/** The only button on top while playing: connection dot, player number and the menu icon. */
@Composable
internal fun MenuButton(player: Int, connected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.verticalGradient(listOf(Color(0xFF2C3038), Color(0xFF121418))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF))), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
            .testTag("menuButton"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (connected) Good else Bad, CircleShape).testTag("menuDot"))
        Text(
            "P$player", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.background(if (player == 1) Accent else Color(0xFFE0475B), RoundedCornerShape(50))
                .padding(horizontal = 7.dp, vertical = 1.dp).testTag("playerBadge"),
        )
        Canvas(Modifier.size(width = 16.dp, height = 12.dp)) { menuIcon(Color.White) }
    }
}

/** One tile of the quick menu (icon + label). */
@Composable
private fun MenuTile(label: String, tag: String, on: Boolean = false, onClick: () -> Unit, icon: DrawScope.(Color) -> Unit) {
    val tint = if (on) Color.White else Color(0xFFDDE2EC)
    Column(
        Modifier.width(78.dp).clip(RoundedCornerShape(16.dp))
            .background(
                if (on) Brush.verticalGradient(listOf(Color(0xFF5B7BFF), Color(0xFF3A55D9)))
                else Brush.verticalGradient(listOf(Color(0xFF2A2E37), Color(0xFF181B21)))
            )
            .border(1.dp, if (on) Color(0x80FFFFFF) else Color(0x22FFFFFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(tag), // on the tile: a tappable tile merges its label into itself
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(22.dp)) { icon(tint) }
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            maxLines = 1)
    }
}

/**
 * The quick menu: connection + player on top, then Home, Live, Edit, Settings and Gyro. Tapping outside
 * closes it. While it is open, nothing behind it can be pressed.
 */
@Composable
internal fun BoxScope.QuickMenu(
    statusText: String,
    connected: Boolean,
    player: Int,
    liveOn: Boolean,
    gyroAvailable: Boolean,
    gyroOn: Boolean,
    onHome: () -> Unit,
    onLive: () -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    onGyro: () -> Unit,
    onClose: () -> Unit,
) {
    // scrim: dims the controller, swallows touches, tap closes
    Box(
        Modifier.fillMaxSize().background(Color(0x8C000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .testTag("menuScrim")
    )
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp).width(470.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x0FFFFFFF))), RoundedCornerShape(24.dp))
            // a touch target that never consumes (real fingers wobble; consuming would cancel the tiles' taps)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .padding(16.dp)
            .testTag("quickMenu"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(if (connected) Good else Bad, CircleShape))
            Text(statusText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.weight(1f).testTag("connectionChip"))
            Text(
                "Player $player", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(if (player == 1) Accent else Color(0xFFE0475B), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp).testTag("menuPlayer"),
            )
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable(onClick = onClose).testTag("menuClose"),
                contentAlignment = Alignment.Center,
            ) { Canvas(Modifier.size(12.dp)) { closeIcon(Color.White) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            MenuTile("Home", "chip_Home", onClick = onHome) { homeIcon(it) }
            MenuTile(if (liveOn) "Live ON" else "Live", "chip_Live", on = liveOn, onClick = onLive) { liveIcon(it) }
            MenuTile("Edit", "chip_Edit", onClick = onEdit) { editIcon(it) }
            MenuTile("Settings", "chip_Settings", onClick = onSettings) { gearIcon(it) }
            if (gyroAvailable) MenuTile(if (gyroOn) "Gyro ON" else "Gyro OFF", "chip_gyro", on = gyroOn, onClick = onGyro) { gyroIcon(it) }
        }
    }
}

// ------------------------------------------------------------------ a button style, drawn as a preview tile

/**
 * Preview tile for the settings panel: draws a real face button in [style] with [labels], so the user sees
 * exactly what they pick. [cluster] draws all four face buttons (for the label choice).
 */
@Composable
internal fun StylePreviewTile(
    title: String, subtitle: String, style: ButtonStyle, labels: FaceLabels, selected: Boolean, tag: String,
    cluster: Boolean = false, width: Dp = 84.dp, onClick: () -> Unit,
) {
    val neu = LocalNeu.current
    Column(
        Modifier.width(width).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x334C6FFF) else Color(0xFF16181D))
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else Color(0x26FFFFFF), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 4.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Canvas(Modifier.size(40.dp)) {
            val face = { i: Int, c: Offset, r: Float ->
                val col = FaceColors.color(labels, i)
                val ring = col.takeIf { style != ButtonStyle.REAL }
                val d = (r * 0.12f).coerceIn(2f, 6f)
                neuPath(circlePath(c, r), neu, pressed = false, depth = d, accent = ring, style = style)
                if (labels == FaceLabels.PLAYSTATION) drawFaceMark(i, c, r, col)
                else drawLetter(FaceColors.text(labels, i), c, r, col)
            }
            if (cluster) {
                val o = size.minDimension * 0.31f
                val r = size.minDimension * 0.17f
                listOf(Offset(0f, -o), Offset(o, 0f), Offset(0f, o), Offset(-o, 0f)).forEachIndexed { i, off -> face(i, center + off, r) }
            } else face(0, center, size.minDimension * 0.42f)
        }
        Text(if (subtitle.isEmpty()) title else "$title · $subtitle", color = Color.White, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A letter (Y, B, X…) drawn on the canvas, centred on [c]. */
private fun DrawScope.drawLetter(text: String, c: Offset, r: Float, color: Color) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        textSize = r * 1.05f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val fm = paint.fontMetrics
    drawContext.canvas.nativeCanvas.drawText(text, c.x, c.y - (fm.ascent + fm.descent) / 2f, paint)
}

// ------------------------------------------------------------------ icons (drawn, no icon library)

private fun DrawScope.line(color: Color) = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.menuIcon(color: Color) {
    val w = size.width; val h = size.height; val s = Stroke(width = h * 0.16f, cap = StrokeCap.Round)
    for (y in listOf(h * 0.12f, h * 0.5f, h * 0.88f)) drawLine(color, Offset(0f, y), Offset(w, y), s.width, StrokeCap.Round)
}

private fun DrawScope.closeIcon(color: Color) {
    val w = size.width; val sw = w * 0.16f
    drawLine(color, Offset(0f, 0f), Offset(w, w), sw, StrokeCap.Round)
    drawLine(color, Offset(w, 0f), Offset(0f, w), sw, StrokeCap.Round)
}

private fun DrawScope.homeIcon(color: Color) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.12f, w * 0.48f); lineTo(w * 0.5f, w * 0.14f); lineTo(w * 0.88f, w * 0.48f)
        moveTo(w * 0.22f, w * 0.4f); lineTo(w * 0.22f, w * 0.86f); lineTo(w * 0.78f, w * 0.86f); lineTo(w * 0.78f, w * 0.4f)
    }
    drawPath(p, color, style = line(color))
}

private fun DrawScope.liveIcon(color: Color) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.04f, w * 0.55f); lineTo(w * 0.28f, w * 0.55f); lineTo(w * 0.4f, w * 0.22f)
        lineTo(w * 0.58f, w * 0.82f); lineTo(w * 0.7f, w * 0.55f); lineTo(w * 0.96f, w * 0.55f)
    }
    drawPath(p, color, style = line(color))
}

private fun DrawScope.editIcon(color: Color) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.2f, w * 0.8f); lineTo(w * 0.24f, w * 0.6f); lineTo(w * 0.66f, w * 0.18f); lineTo(w * 0.82f, w * 0.34f)
        lineTo(w * 0.4f, w * 0.76f); close()
    }
    drawPath(p, color, style = line(color))
}

private fun DrawScope.gearIcon(color: Color) {
    val c = center; val r = size.minDimension * 0.27f
    drawCircle(color, radius = r, center = c, style = line(color))
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val from = Offset(c.x + (r * 1.25f) * kotlin.math.cos(a).toFloat(), c.y + (r * 1.25f) * kotlin.math.sin(a).toFloat())
        val to = Offset(c.x + (r * 1.75f) * kotlin.math.cos(a).toFloat(), c.y + (r * 1.75f) * kotlin.math.sin(a).toFloat())
        drawLine(color, from, to, size.minDimension * 0.12f, StrokeCap.Round)
    }
}

private fun DrawScope.gyroIcon(color: Color) {
    val c = center; val r = size.minDimension * 0.42f
    drawCircle(color, radius = r, center = c, style = line(color))
    drawCircle(color, radius = r * 0.32f, center = c)
    drawLine(color, Offset(c.x, c.y - r * 1.15f), Offset(c.x, c.y - r * 0.62f), size.minDimension * 0.1f, StrokeCap.Round)
    drawLine(color, Offset(c.x, c.y + r * 0.62f), Offset(c.x, c.y + r * 1.15f), size.minDimension * 0.1f, StrokeCap.Round)
}
