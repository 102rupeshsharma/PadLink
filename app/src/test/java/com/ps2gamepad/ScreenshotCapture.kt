package com.ps2gamepad

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ps2gamepad.bluetooth.HidEvent
import com.ps2gamepad.bluetooth.HidStateReducer
import com.ps2gamepad.model.BluetoothState
import com.ps2gamepad.model.ControllerBackground
import com.ps2gamepad.model.DiagnosticLog
import com.ps2gamepad.model.HostDevice
import com.ps2gamepad.model.Stick
import com.ps2gamepad.model.ButtonStyle
import com.ps2gamepad.model.FaceLabels
import com.ps2gamepad.model.ExtraButton
import com.ps2gamepad.model.ComboStep
import com.ps2gamepad.model.ControllerButton
import com.ps2gamepad.model.Hat
import com.ps2gamepad.ui.App
import com.ps2gamepad.ui.AppActions
import com.ps2gamepad.ui.MainViewModel
import com.ps2gamepad.ui.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real Compose screens with Robolectric's native graphics and writes PNGs to
 * docs/screenshots. Runs only with -Pscreenshots (skipped otherwise). The Bluetooth state shown
 * is MOCK state (see FakeHid); "Connected" here is a staged picture, not a hardware result.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xhdpi")
class ScreenshotCapture {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    private fun shoot(name: String) {
        rule.waitForIdle()
        val root = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        out.mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun captureScreens() {
        assumeTrue("run with -Pscreenshots", System.getProperty("screenshotDir") != null)
        val hid = FakeHid()
        val vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { emptyList() }, DiagnosticLog(), listOf("screenshot"), { 0L },
            batteryPercent = { 80 }, phoneNameControl = FakePhoneName(), motionSource = FakeMotionSource())
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        val host = HostDevice("00:00:00:00:00:01", "LAPTOP (mock)")
        rule.runOnIdle { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart(); hid.emit(HidEvent.AppStatusChanged(true)) }
        rule.runOnIdle {
            vm.onPermissionsChanged(true); vm.register()
            hid.emit(HidEvent.AppStatusChanged(true)); hid.emit(HidEvent.ConnectionChanged(host, HidStateReducer.STATE_CONNECTED))
        }
        rule.runOnIdle {
            vm.createLoadout("God of War"); vm.updateAppearance { it.copy(buttonScale = 1.2f) }; vm.setEditMode(false)
            vm.createLoadout("Tekken 5"); vm.updateAppearance { it.copy(buttonStyle = ButtonStyle.SIMPLE) }; vm.setEditMode(false)
            vm.openController()
        }
        shoot("07_loadouts")
        rule.runOnIdle { vm.playLoadout(com.ps2gamepad.settings.DefaultProfiles.STANDARD) }
        shoot("11_controller_real")
        rule.onNodeWithTag("menuButton").performClick()
        shoot("20_quick_menu")
        rule.onNodeWithTag("menuClose").performClick()
        rule.runOnIdle { vm.setGyroEnabled(true) }
        shoot("11e_controller_gyro_on")
        rule.runOnIdle { vm.setGyroEnabled(false) }
        rule.onNodeWithTag("menuButton").performClick() // 3.7.0: Settings is in the menu
        rule.onNodeWithTag("chip_Settings").performClick()
        shoot("08_controller_settings")
        rule.onNodeWithTag("chip_Close").performClick()
        rule.runOnIdle { vm.updateAppearance { it.copy(faceLabels = FaceLabels.PLAYSTATION) } }
        shoot("11b_controller_real_playstation")
        rule.runOnIdle { vm.updateAppearance { it.copy(buttonStyle = ButtonStyle.SIMPLE, faceLabels = FaceLabels.XBOX) } }
        shoot("11c_controller_simple")
        rule.runOnIdle { vm.updateAppearance { it.copy(buttonStyle = ButtonStyle.OUTLINE) } }
        shoot("11d_controller_outline")
        rule.runOnIdle { vm.updateAppearance { it.copy(buttonStyle = ButtonStyle.GLOSSY, faceLabels = FaceLabels.PLAYSTATION) } }
        shoot("11f_controller_glossy_playstation")
        rule.runOnIdle { vm.updateAppearance { it.copy(faceLabels = FaceLabels.NINTENDO) } }
        shoot("11g_controller_glossy_switch")
        rule.onNodeWithTag("menuButton").performClick()
        rule.onNodeWithTag("chip_Settings").performClick()
        shoot("08b_settings_style_tiles")
        rule.onNodeWithTag("chip_Close").performClick()
        rule.runOnIdle { vm.resetAppearance() }
        val combos = listOf(
            ExtraButton("c1", ControllerButton.CROSS, 0.5f, 0.2f, steps = listOf(ComboStep(setOf(ControllerButton.L1, ControllerButton.R1)))),
            ExtraButton("c2", ControllerButton.CROSS, 0.5f, 0.62f, steps = listOf(
                ComboStep(hat = Hat.DOWN), ComboStep(hat = Hat.DOWN_RIGHT), ComboStep(setOf(ControllerButton.SQUARE), Hat.RIGHT),
            )),
        )
        rule.runOnIdle { vm.saveLayout(emptyMap(), combos) }
        shoot("19_controller_combos")
        rule.runOnIdle { vm.setEditMode(true) }
        rule.onNodeWithTag("editx_c2").performClick()
        shoot("12_edit_combo")
        rule.runOnIdle { vm.setEditMode(false); vm.saveLayout(emptyMap(), emptyList()); vm.navigate(Screen.DASHBOARD) }
        shoot("10_dashboard")
        rule.runOnIdle { vm.navigate(Screen.CUSTOMIZE) }
        shoot("14_customize")
        rule.runOnIdle { vm.navigate(Screen.PROFILES) }
        shoot("15_profiles")
        rule.runOnIdle { vm.updateStick(Stick.LEFT) { it.copy(floating = false) }; vm.navigate(Screen.CALIBRATION) }
        shoot("16_calibration")
        rule.runOnIdle { vm.navigate(Screen.DIAGNOSTICS) }
        shoot("17_diagnostics")
        rule.runOnIdle { vm.navigate(Screen.CONNECTION) }
        shoot("18_connection")
    }
}

/** The start screen is portrait since 3.3.0: rendered at a portrait phone size. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class PortraitHomeScreenshots {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = java.io.File(System.getProperty("screenshotDir") ?: "build/screenshots")

    private fun shoot(name: String) {
        rule.waitForIdle()
        val root = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        out.mkdirs()
        java.io.File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun captureHome() {
        assumeTrue("run with -Pscreenshots", System.getProperty("screenshotDir") != null)
        val hid = FakeHid()
        val laptop = HostDevice("00:00:00:00:00:01", "LAPTOP (mock)")
        val vm = MainViewModel(hid, MutableStateFlow(BluetoothState.ENABLED), { listOf(laptop, HostDevice("00:00:00:00:00:02", "OFFICE-PC (mock)")) }, DiagnosticLog(), listOf("screenshot"), { 0L },
            batteryPercent = { 80 }, phoneNameControl = FakePhoneName(),
            laptopScanner = FakeLaptopScanner().also { it.found.value = listOf(HostDevice("00:00:00:00:00:03", "GAMING-PC (mock)")) })
        rule.setContent { App(vm, AppActions({}, {}, {}, {})) }
        rule.runOnIdle { vm.onPermissionsChanged(true); vm.onForeground(); vm.autoStart() }
        shoot("01_home_connect")
        rule.runOnIdle { vm.navigate(Screen.LAPTOPS) }
        shoot("03_choose_laptop")
        rule.runOnIdle { vm.navigate(Screen.HOME) }
        rule.runOnIdle { vm.connectNow(); hid.emit(HidEvent.AppStatusChanged(true)) }
        shoot("01b_home_connecting")
        rule.runOnIdle { hid.emit(HidEvent.ConnectionChanged(laptop, HidStateReducer.STATE_CONNECTED)) }
        shoot("02_home_connected")
        // 3.4.0: every screen except the controller is portrait
        rule.runOnIdle { vm.createLoadout("God of War"); vm.setEditMode(false); vm.openController() }
        shoot("07_loadouts")
        for ((screen, name) in listOf(
            Screen.DASHBOARD to "10_dashboard", Screen.CUSTOMIZE to "14_customize", Screen.PROFILES to "15_profiles",
            Screen.CALIBRATION to "16_calibration", Screen.DIAGNOSTICS to "17_diagnostics", Screen.CONNECTION to "18_connection",
        )) {
            rule.runOnIdle { vm.navigate(screen) }
            shoot(name)
        }
    }
}
