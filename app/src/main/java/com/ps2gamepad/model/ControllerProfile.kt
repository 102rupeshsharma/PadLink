package com.ps2gamepad.model

import kotlinx.serialization.Serializable

/**
 * Everything the user can customise. Profiles only change layout, look and analog behaviour.
 * They never change the HID descriptor or which HID button a control sends.
 */

/** Movable controls in the layout editor. Each maps to fixed HID usages; that mapping is not editable. */
@Serializable
enum class ControlId(val label: String) {
    L2("L2"), L1("L1"), R2("R2"), R1("R1"),
    DPAD("D-pad"),
    /** Legacy (before 1.9.0): the four face buttons as one cluster. Kept so old layouts still load;
     *  its position/size is now only the starting point of the four separate buttons below. */
    FACE("Face buttons"),
    LEFT_STICK("Left stick"), RIGHT_STICK("Right stick"),
    L3("L3"), R3("R3"), SELECT("Select"), START("Start"),
    TRIANGLE("Triangle"), CIRCLE("Circle"), CROSS("Cross"), SQUARE("Square");

    companion object {
        /** Face buttons in cluster order: top, right, bottom, left. */
        val FACE_BUTTONS = listOf(TRIANGLE, CIRCLE, CROSS, SQUARE)
        /** Controls that are drawn and can be moved (everything except the legacy cluster). */
        val editable: List<ControlId> = entries.filter { it != FACE }
    }
}

@Serializable
enum class ControllerBackground(val label: String) { DS2_BODY("Controller body"), DARK("Dark"), BLACK("Black (OLED)"), BLUE("Midnight blue") }

@Serializable
data class StickSettings(
    /** Radial dead zone, fraction of travel. Output is exactly 0 inside it. */
    val deadZone: Float = 0.10f,
    /** Multiplier after dead zone / curve. */
    val sensitivity: Float = 1.0f,
    /** Travel fraction at which output reaches 100 % (outer dead zone). 1 = full travel. */
    val maxRange: Float = 1.0f,
    /** Response curve exponent: 1 = linear, > 1 finer control near centre, < 1 quicker start. */
    val curve: Float = 1.0f,
    val invertY: Boolean = false,
    /** Floating: centre where the thumb lands. Fixed: centre of the stick (+ calibration). */
    val floating: Boolean = true,
    /** Fixed-mode centre offset, fraction of travel. Set by the calibration screen. */
    val centerOffsetX: Float = 0f,
    val centerOffsetY: Float = 0f,
) {
    /** Clamps every field into its safe range (protects against corrupt or old saved data). */
    fun sanitized(): StickSettings {
        val dz = deadZone.finiteOr(0.10f).coerceIn(0f, 0.5f)
        return StickSettings(
        deadZone = dz,
        sensitivity = sensitivity.finiteOr(1f).coerceIn(0.25f, 3f),
        // max range must stay clearly above the dead zone so the response never divides by ~0
        maxRange = maxRange.finiteOr(1f).coerceIn(maxOf(0.5f, dz + 0.1f), 1f),
        curve = curve.finiteOr(1f).coerceIn(MIN_CURVE, 3f),
        invertY = invertY,
        floating = floating,
        centerOffsetX = centerOffsetX.finiteOr(0f).coerceIn(-MAX_CENTER_OFFSET, MAX_CENTER_OFFSET),
        centerOffsetY = centerOffsetY.finiteOr(0f).coerceIn(-MAX_CENTER_OFFSET, MAX_CENTER_OFFSET),
        )
    }

    companion object {
        const val MAX_CENTER_OFFSET = 0.5f
        /** Below ~0.6 the curve is so steep that 1 % past the dead zone gives > 10 % output (a jump). */
        const val MIN_CURVE = 0.6f
    }
}

@Serializable
data class Appearance(
    /** Size multiplier for digital buttons (D-pad, face cluster, shoulders, Start/Select, L3/R3). */
    val buttonScale: Float = 1.0f,
    val stickScale: Float = 1.0f,
    /** Opacity of the controls. */
    val buttonOpacity: Float = 1.0f,
    /** Opacity of the controller body / background art. */
    val controllerOpacity: Float = 1.0f,
    /** Spacing between the four face buttons. */
    val buttonSpacing: Float = 1.0f,
    val background: ControllerBackground = ControllerBackground.DS2_BODY,
    val showLabels: Boolean = true,
    val haptics: Boolean = true,
    /** Extra opacity per control (1 = fully visible), applied on top of [buttonOpacity]. */
    val controlOpacity: Map<ControlId, Float> = emptyMap(),
    /** How the buttons are drawn (the background is always black). */
    val buttonStyle: ButtonStyle = ButtonStyle.REAL,
    /** What the four face buttons show. They always send the same HID buttons. */
    val faceLabels: FaceLabels = FaceLabels.XBOX,
) {
    fun opacityOf(id: ControlId): Float =
        controlOpacity[id] ?: (if (id in ControlId.FACE_BUTTONS) controlOpacity[ControlId.FACE] else null) ?: 1f

    fun sanitized() = copy(
        buttonScale = buttonScale.finiteOr(1f).coerceIn(0.6f, 1.6f),
        stickScale = stickScale.finiteOr(1f).coerceIn(0.6f, 1.6f),
        buttonOpacity = buttonOpacity.finiteOr(1f).coerceIn(0.2f, 1f),
        controllerOpacity = controllerOpacity.finiteOr(1f).coerceIn(0f, 1f),
        buttonSpacing = buttonSpacing.finiteOr(1f).coerceIn(0.8f, 1.3f),
        controlOpacity = controlOpacity.mapValues { it.value.finiteOr(1f).coerceIn(MIN_CONTROL_OPACITY, 1f) }.filterValues { it < 1f },
    )

    companion object {
        /** Never fully invisible: a control you cannot see is a control you hit by accident. */
        const val MIN_CONTROL_OPACITY = 0.1f
    }
}

/** Button look. All styles send exactly the same buttons; only the drawing differs. */
@Serializable
enum class ButtonStyle(val label: String, val hint: String) {
    REAL("Real", "3D buttons like a physical controller"),
    SIMPLE("Simple", "Flat dark buttons with a thin ring"),
    OUTLINE("Outline", "Rings only: the most battery-friendly"),
    GLOSSY("Glossy", "Dark glossy buttons with a soft rim, like a modern console pad"),
}

/**
 * Face button labels (top, right, bottom, left): PlayStation symbols, Xbox letters or Nintendo letters
 * (X on top, A right, B bottom, Y left, in white). Only the labels change: each position always sends
 * the same HID button, so emulator bindings keep working.
 */
@Serializable
enum class FaceLabels(val label: String) { PLAYSTATION("\u25B3 \u25CB \u2715 \u25A1"), XBOX("Y B A X"), NINTENDO("X A B Y") }

/**
 * A control moved/resized by the user. Centre is a fraction of the screen (0..1) so layouts
 * survive different screen sizes; [scale] multiplies the control's default size.
 */
@Serializable
data class Placement(val cx: Float, val cy: Float, val scale: Float = 1f) {
    fun sanitized() = Placement(cx.finiteOr(0.5f).coerceIn(0f, 1f), cy.finiteOr(0.5f).coerceIn(0f, 1f), scale.finiteOr(1f).coerceIn(0.5f, 2f))
}

/** One step of a combo: buttons held together, optionally with a D-pad direction. */
@Serializable
data class ComboStep(val buttons: Set<ControllerButton> = emptySet(), val hat: Hat = Hat.NEUTRAL) {
    val isEmpty: Boolean get() = buttons.isEmpty() && hat == Hat.NEUTRAL
}

/**
 * A button the user added in the layout editor. Centre is a screen fraction (0..1); [scale]
 * multiplies the default size. It only ever presses the existing 12 buttons / D-pad, so the HID
 * report never changes.
 *  - No [steps]: a second place to press [button] (e.g. a second Cross).
 *  - One step: a COMBO pressed together while held (e.g. L1+R1).
 *  - Several steps: a COMBO played in order on each tap (e.g. down, down-right, right + Square),
 *    each step held [stepMs].
 */
@Serializable
data class ExtraButton(
    val id: String,
    val button: ControllerButton,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val scale: Float = 1f,
    val steps: List<ComboStep> = emptyList(),
    val stepMs: Int = DEFAULT_STEP_MS,
) {
    val isCombo: Boolean get() = steps.isNotEmpty()
    /** A combo with several steps plays as a sequence; one step is held together. */
    val isSequence: Boolean get() = steps.size > 1

    fun sanitized() = copy(
        cx = cx.finiteOr(0.5f).coerceIn(0f, 1f),
        cy = cy.finiteOr(0.5f).coerceIn(0f, 1f),
        scale = scale.finiteOr(1f).coerceIn(0.5f, 2f),
        steps = steps.filterNot { it.isEmpty }.take(MAX_STEPS),
        stepMs = stepMs.coerceIn(MIN_STEP_MS, MAX_STEP_MS),
    )

    companion object {
        const val MAX_STEPS = 8
        const val DEFAULT_STEP_MS = 80
        const val MIN_STEP_MS = 30
        const val MAX_STEP_MS = 300
    }
}

/**
 * Gyro aiming for the right stick (3.1.0): the phone's rotation SPEED drives the right stick, like
 * gyro aiming on Steam / Switch. Off by default; switched on the controller's top bar.
 */
@Serializable
data class GyroAim(
    val enabled: Boolean = false,
    /** Higher = less phone rotation for the same camera turn. */
    val sensitivity: Float = 1f,
    /**
     * Game dead-zone fix: most games ignore stick values below ~15-25 %. Any phone movement starts
     * at this value, so slow, small aiming still moves the camera. 0 = off.
     */
    val gameDeadZone: Float = 0.2f,
    /** Up/down speed relative to left/right. */
    val verticalScale: Float = 1f,
    /** 0 = raw, 1 = smoothest (50 ms). */
    val smoothing: Float = 0.3f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
) {
    fun sanitized() = copy(
        sensitivity = sensitivity.finiteOr(1f).coerceIn(0.25f, 4f),
        gameDeadZone = gameDeadZone.finiteOr(0.2f).coerceIn(0f, 0.4f),
        verticalScale = verticalScale.finiteOr(1f).coerceIn(0.5f, 1.5f),
        smoothing = smoothing.finiteOr(0.3f).coerceIn(0f, 1f),
    )

    companion object {
        /** Phone rotation speed (°/s) that gives full stick at sensitivity 1. */
        const val FULL_SPEED_DPS = 120f
    }
}

@Serializable
data class ControllerProfile(
    val id: String,
    val name: String,
    /** Built-in profiles can be restored to defaults and cannot be deleted. */
    val builtIn: Boolean = false,
    val appearance: Appearance = Appearance(),
    val left: StickSettings = StickSettings(),
    val right: StickSettings = StickSettings(),
    /** Only controls the user moved; everything else uses the default layout. */
    val placements: Map<ControlId, Placement> = emptyMap(),
    /** Buttons and combo buttons added by the user. */
    val extraButtons: List<ExtraButton> = emptyList(),
    /** Gyro aiming (right stick). New field name: the old tilt-based "gyro" settings are ignored. */
    val gyroAim: GyroAim = GyroAim(),
) {
    fun sanitized() = copy(
        name = name.trim().take(MAX_NAME).ifEmpty { "Profile" },
        appearance = appearance.sanitized(),
        left = left.sanitized(),
        right = right.sanitized(),
        placements = placements.mapValues { it.value.sanitized() },
        gyroAim = gyroAim.sanitized(),
        extraButtons = extraButtons.distinctBy { it.id }.filter { it.id.isNotBlank() }.take(MAX_EXTRA_BUTTONS).map { it.sanitized() },
    )

    fun stick(s: Stick) = if (s == Stick.LEFT) left else right

    companion object {
        const val MAX_NAME = 32
        const val MAX_EXTRA_BUTTONS = 12
    }
}

@Serializable
data class ProfileStore(
    val activeId: String,
    val profiles: List<ControllerProfile>,
    /** Last host that reached STATE_CONNECTED (shown on the dashboard). */
    val lastConnection: LastConnection? = null,
    /**
     * Battery saver while a controller is shown (default ON): 60 Hz screen instead of 90/120 Hz,
     * and 125 Hz Bluetooth reports. Brightness is left to the phone (2.1.0).
     */
    val batterySaver: Boolean = true,
) {
    val active: ControllerProfile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
}

@Serializable
data class LastConnection(val hostName: String, val epochMillis: Long, val address: String? = null)

private fun Float.finiteOr(d: Float) = if (isFinite()) this else d
