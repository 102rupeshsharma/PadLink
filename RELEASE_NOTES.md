# Release notes

## 3.5.0 (versionCode 21), 2026-11-08: choose between laptops, search and pair new ones

- **Fix: the phone always connected to the laptop it used last** (reported with two laptops). Both the
  app and Android's Bluetooth stack reconnected the last laptop by themselves, also while the user
  was pairing a second one, and the phone had no list to pick from.
- **New "Choose a laptop" screen** (start screen → "Choose another laptop / pair a new one", or
  Connect when no laptop is known or several are paired):
  - every paired laptop with its own **Connect** button ("Connected" / "Used last" shown);
  - **Search for laptops**: finds laptops near the phone (classic Bluetooth discovery), tap
    **Pair & connect** to pair from the phone and connect to it;
  - **Make this phone visible**: pair from the laptop's Bluetooth settings instead.
- While a laptop is being chosen or a new one is being paired, the app never reconnects the old one:
  Android's own "reconnect the last host" is not requested, a connection to a different laptop is
  dropped and the chosen one connected, and automatic reconnects wait. A successful connection from
  this screen returns to the start screen (Connected ✓, Open Controller). The laptop chosen is
  remembered for the next Connect.
- Searching asks for the **Nearby devices** permission (scan, marked "never for location") only when
  you tap Search; Android 9-11 need location permission for Bluetooth search instead.
- Tests: ChooseLaptopTest (6 view-model scenarios with two laptops, including Android reconnecting
  the old one, pairing from the phone and from the laptop; 4 UI tests on a portrait phone).
  297 automated tests (294 passed, 3 skipped). Pairing from the phone and Windows accepting the connection are not yet checked on real
  laptops.

## 3.4.0 (versionCode 20), 2026-11-08: portrait app, landscape controller; payment section removed

- **The whole app is portrait now; only the controller is landscape** (playing and editing its layout).
  The screen turns by itself when the controller opens and closes. The app starts in portrait.
- Every menu screen was reworked for a portrait phone (they were built for landscape and broke:
  one letter per line, buttons pushed off screen): "More", Connection, Customize, Profiles,
  Diagnostics, Calibration and the loadout picker now stack their two halves in one scrolling column,
  button rows wrap, loadout cards sit two per row and the calibration sticks stack. On a wide screen
  (tablet) the two halves stay side by side.
- **README:** the payment / UPI section and its QR image are removed.
- Tests: PortraitAppTest (orientation rule, app starts in portrait, README has no payment section,
  every menu screen opened from "More" on a portrait phone with its main buttons reached by
  scrolling and pressed). 287 automated tests (284 passed, 3 skipped). The "New profile" name dialog cannot be driven by the test
  framework (text field inside a dialog, same as before); it is on the manual checklist.
  Not yet checked on the phone.

## 3.3.0 (versionCode 19), 2026-11-08: portrait start screen, one Connect button, no automatic connecting

- **Start screen in portrait** (the controller and every other screen stay landscape). Same content as
  before, in one column: status, one big button, "Shown on your PC as", controller preview, tiles.
- **One Connect button, nothing automatic before it.** Opening the app only names the phone
  "PadLink"; the gamepad is not started and nothing connects until you tap **Connect**. Connect then:
  - starts the gamepad and connects to the laptop used last, or
  - if no laptop is known yet, makes the phone visible so the laptop can pair.
  The status shows "Connecting…" (up to 20 s), then **Open Controller** once connected. The other
  "Connect to <laptop>" and "Make phone visible to PC" buttons became this one Connect button;
  "Pair a laptop again / a new laptop" stays as a small link under it.
- **Unchanged after Connect:** the gamepad comes back when you return to the app, and a lost
  connection is retried, exactly as before.
- Tests: 278 automated tests (275 passed, 3 skipped). Not yet checked on the phone.

## 3.2.0 (versionCode 18), 2026-11-08: Settings fix, sticks stop at once, faster buttons, user guide

- **Fix: Settings panel did nothing while playing** (style, face labels, switches, sliders, scrolling).
  The panel grabbed every finger movement to protect the controls behind it, and a real finger always
  moves a few pixels during a tap or swipe, so every tap and scroll was cancelled. Older tests used a
  perfectly still synthetic finger and passed. Now the panel only blocks touches; it no longer grabs
  movements. New tests tap and swipe like a real thumb (lands, wobbles, lifts), and they fail on the
  old code exactly as reported.
- **Fix: character kept moving 1-2 s after letting go of a stick.** The app handed stick movement to
  Bluetooth faster than the link to the PC could carry it (250 Hz, or 125 Hz in battery saver). The
  extra reports queued up in Android's Bluetooth stack, and "stick released" waited behind them. New
  report scheduler:
  - button / D-pad changes and a stick returning to centre are sent at once and never wait behind
    movement;
  - plain movement is limited to about 80 per second (about 60 in battery saver); games read the pad
    once per frame (60 Hz), and movement superseded by a newer report is skipped instead of sent late;
  - every button change and stop is confirmed twice (about 30 ms and 100 ms later), so a report the
    Bluetooth stack drops cannot leave a button held or a character walking.
  In a simulated Bluetooth link that carries about 89 reports/s, the old sender kept the character
  walking 1218 ms after the thumb lifted; the new one stops it within 10 ms. This is a simulation of
  the most likely cause; it is not yet confirmed on the phone.
- **Faster buttons:** on the controller screen, touches are delivered the moment the screen reports
  them, not collected and handed over once per frame (up to 16 ms at 60 Hz). Android 11+.
- **Tested like a player:** HumanPlayTest (real-thumb taps and swipes on Settings while walking,
  letting go of each stick, mashing buttons while walking), SendSchedulerTest (simulated Bluetooth
  link), and the gyro play test now with a real hand's shake (crosshair wandered at most 0.08° while
  holding aim; slow tracking still works).
- **README:** 30-second summary, "what you need", playing tips, a table of every setting, updating,
  and new troubleshooting rows.
- Tests: 273 automated tests (271 passed, 2 skipped). Not yet checked on the phone.

## 3.1.0 (versionCode 17), 2026-11-07: gyro aiming for shooters

- **Gyro aiming is back, rebuilt for aiming.** The old gyro (1.3 to 2.x) mapped the phone's tilt
  ANGLE to a stick POSITION with a few-degree dead zone. Together with the game's own stick dead
  zone, small aiming corrections did nothing until the phone was tilted a lot (the reason it was
  removed). The new gyro maps the phone's ROTATION SPEED to the stick, like gyro aiming on Steam /
  Switch:
  - turn slowly = small camera turn, stop = camera stops at once, no neutral angle to hold;
  - **game dead-zone fix** (default 20 %): any real movement starts just past the game's inner
    dead zone, so the game's response is linear from the first degree per second;
  - **tiered smoothing**: only tiny jitter is smoothed, real turns pass straight through (no lag,
    no drift after stopping);
  - automatic gyroscope drift learning while the phone is held still; 200 Hz gyroscope;
  - works however the phone is held (gravity-relative axes), up/down follows the top edge.
- **Gyro ON/OFF on the controller's top bar** (saved per loadout). Settings: sensitivity, game
  dead-zone fix, up/down speed, invert up/down, invert left/right. The right touch stick still works
  and wins while touched.
- Sensors run only while the controller is open with gyro ON (stopped in Edit, on other screens and
  in the background), and the gyro part of the right stick is centred whenever they stop.
- **Simulated play test** (GyroAimTest: a simulated hand turning the phone, a simulated shooter with a
  20 % dead zone and 180°/s max turn): 2° tiny correction -> camera 3.2° (old tilt method: 0°);
  3°/s slow tracking -> 4.6°; a 10° turn -> 15° whether slow or fast; camera moves at most 0.07°
  after the phone stops; no drift at rest after the bias is learned. This found and fixed two
  feel problems before release: lag after stopping (smoothing on big turns) and slow tracking
  being mistaken for sensor drift.
- Tests: 257 automated tests (255 passed, 2 skipped). Not yet checked on the phone.

## 3.0.0 (versionCode 16), 2026-11-07: simple Bluetooth controller, button styles, combo buttons

- **Simplified:** gyro aiming and PC Gaming Mode (Bluetooth keyboard + mouse, gyro mouse, PC
  profiles, mode selection) are removed from the app, the tests and the README. The phone registers
  only the gamepad descriptor again. Open Controller goes straight to the loadout picker.
  If the PC was paired while "PC input" was on, remove the phone in Windows once and pair again.
- **Button styles** (controller → Settings): **Real** (3D keys lit from above, domed sticks),
  **Simple** (flat dark keys with a thin ring) and **Outline** (rings only, the fewest lit pixels).
  **Face labels:** Y B A X or △ ○ ✕ □. Only the look changes; the HID buttons are the same, so
  emulator bindings stay. Saved per loadout.
- **Pure black background** on the controller and on every app screen (AMOLED pixels off).
- **Combo buttons** (Edit → + Combo): each step holds any of the 12 buttons plus one D-pad direction.
  One step = held together while pressed (sent in one report); several steps = played in order on
  each tap, 30–300 ms per step, with a short release before a repeated button. Combos never cut a
  button that is also held on the real control, and they release everything on screen change or
  reset. Up to 12 added buttons/combos per loadout, 8 steps per combo.
- Saved settings from older versions still load (gyro/PC fields are ignored).
- Tests: 244 automated tests (242 passed, 2 skipped). Not yet checked on the phone.

## 2.1.0 (versionCode 15), 2026-11-07: PC input fix for new phones/laptops, black glass controllers

- **Fix: "Online" but PC controls / gyro mouse did nothing** (reported on a Samsung phone + another
  laptop). The app could register with Bluetooth before its saved settings had loaded, i.e. with
  the default PC-input value instead of the saved one; it then registered as a plain gamepad and
  every keyboard/mouse/gyro-mouse report was refused while the status still said Online.
  Registration now waits for the saved settings (regression test added).
- **PC input is ON by default for new installs**, so the first pairing of a new phone + laptop
  already includes keyboard and mouse. Existing installs keep their saved choice.
- **"?" help on the PC controller:** explains the one fix the app cannot do itself: if Windows
  paired with the phone before PC input was on, remove the phone in Windows and pair once more.
- **New controller look (both controllers):** pure black background (AMOLED pixels off), dark
  "glass" buttons with a thin ring, round D-pad with four arrow buttons, sticks with a ringed knob.
  Face buttons are **Y (yellow, top) / B (red, right) / A (green, bottom) / X (blue, left)**; they
  still send the same HID buttons (Triangle/Circle/Cross/Square), so PCSX2 bindings are unchanged.
  PC buttons labelled A/B/X/Y use the same colours. The light/black theme switch is removed
  (there is one black look).
- **Brightness is yours again:** battery saver no longer forces the screen to 20 % while a controller
  is open, so the phone's own brightness control works while playing. Battery saver keeps the 60 Hz
  screen and 125 Hz Bluetooth.
- **Edit mode like other controller apps:** the selected control gets a dashed frame, a blue corner
  handle (drag to resize) and, for added buttons, a red x to delete.
- Tests: 314 (312 passed, 2 skipped). The Samsung fix is not yet confirmed on that phone.

## 2.0.0 (versionCode 14), 2026-11-07: PadLink, battery saver, UPI support

- **New name: PadLink** (it is more than a PS2 pad: emulators, PC games with keyboard + mouse,
  gyro). App name, home screen, the phone's Bluetooth name shown in Windows, the HID service
  name and the README/docs. A phone still named "PS2 Gamepad" by an older version is renamed to
  "PadLink" and that old name is never stored as the phone's own name. A PC paired before keeps
  working; Windows may show the old name until it is paired again. The package id is unchanged, so
  2.0.0 installs over older versions and keeps all settings.
- **Battery saver (ON by default)**, in the controller Settings and PC profiles & settings. While a
  controller is open: screen limited to 60 Hz (instead of 90/120 Hz), screen dimmed to 20 %,
  Bluetooth reports at 125 Hz instead of 250 Hz (at most +4 ms). Leaving the controller restores
  brightness and refresh rate.
- **No idle wake-ups:** the Bluetooth sender thread now sleeps until a report is queued (it used to
  wake 4 times a second).
- README: UPI QR code and UPI IDs in "Support this project"; APK file renamed to `download/PadLink.apk`.
- Tests: 311 (309 passed, 2 skipped). Battery and heat savings are not measured on the phone yet.

## 1.9.0 (versionCode 13), 2026-11-07: separate face buttons, free layout editing, theme fix

- **△ ○ ✕ □ are four separate buttons:** each one can be moved, resized and faded on its own.
  Layouts saved by older versions keep the buttons where the cluster was (and its opacity).
- **Edit layout without boxes:** no more rectangles around every control. Drag a control itself
  to move it; only the selected control shows a thin outline in its own shape.
- **Controls may overlap:** moves and resizes are never refused or snapped back any more. Where
  two controls overlap, the one drawn on top gets the touch.
- **Light / black theme fix:** the soft shadows are now drawn with plain translucent strokes
  instead of a blur filter (which is not drawn the same by every phone's GPU and could look like
  hard boxes). There is a one-tap **☾ Dark / ☀ Light** button on both controllers' top bars, next to
  the Settings switch. A rendering test checks that the screen really changes both ways.
- README: the download link is only at the bottom now.
- Tests: 309 (307 passed, 2 skipped). Not yet checked on the phone.

## 1.8.0 (versionCode 12), 2026-11-06: soft-UI controllers, light and black theme

- **Soft UI ("neumorphic") buttons** on both controllers (Emulator and PC): every button has the
  background colour and is shaped by soft light/dark shadows; a pressed button looks pushed in.
  Sticks are a pushed-in well with a raised knob.
- **Background art removed:** the controller-body drawing is gone; the screen is one plain soft
  colour. The Customize screen no longer shows the background and controller-opacity options.
- **Light / Black theme:** the "Black theme" switch is in the controller's Settings (and in PC
  profiles & settings). Light is the default; the choice is shared by both controllers and saved.
- No change to input, HID reports, gyro or layouts. Tests: 304 (302 passed, 2 skipped).
  Not yet checked on the phone.

## 1.7.0 (versionCode 11), 2026-11-06: gyro up/down fixed, sky-blue buttons, add your own buttons

- **Gyro up/down fixed** (reported as reversed): tilting the phone up (top edge towards you) now
  looks up, like aiming a camera. This applies to both the Emulator gyro right stick and the PC gyro
  mouse. Left/right is unchanged. The "Invert Y" switches still flip it if a game wants the other
  direction.
- **Plain sky-blue buttons** on both controllers (Emulator and PC): solid fill, no outlines or
  glow, dark labels; a pressed button turns dark blue.
- **Add buttons to the Emulator controller:** Edit → "+ Button" (up to 8 per loadout). Drag the
  new button anywhere, choose which gamepad button it presses (any of the 12), resize it or delete
  it, then Save. It is a second place to press an existing button, so the HID report and PCSX2
  bindings do not change. Holding the same button in two places keeps it pressed until both are
  released. The PC controller already had "+ Button" with key mapping.
- Tests: 303 (301 passed, 2 skipped). Not yet checked on the phone.

## 1.6.0 (versionCode 10), 2026-11-06: one simple controller + in-controller settings

- **Single controller look:** the 5 styles (PS2 Classic, PS5, Xbox, Retro, Neon) and the Styles screen
  are removed. The controller always uses the simple PS2 button look (△ ○ ✕ □, L1/L2/R1/R2,
  START/SELECT). Saved settings from older versions still load (the old style value is ignored).
- **Settings button inside the controller:**
  - Gyro aiming on/off: ON turns the right joystick into the gyro automatically, with Recenter.
  - Opacity of all controls, size of all buttons, joystick size, and Reset.
  - Changes apply live and are saved to the loadout in use.
  - The panel blocks touches, so nothing behind it is pressed. It scrolls on short screens.
- Everything else is unchanged: loadouts, layout editor, per-control opacity in Customize, PC Mode,
  Bluetooth.
- Tests: 298 (296 passed, 2 skipped).

## 1.5.0 (versionCode 9), 2026-11-06: PC Gaming Mode

- **Mode selection** on Open Controller: Emulator Gaming (unchanged) or PC Gaming. The choice is remembered.
- **PC Gaming controller:** W/A/S/D stick, right-stick mouse, A/B/X/Y, LB/RB/LT/RT, Start/Select,
  Sprint/Crouch, left/right mouse, wheel ▲/▼, gyro pause, recenter. Defaults follow the spec table;
  everything is editable.
- **Keyboard and mouse over Bluetooth HID** (composite descriptor: gamepad unchanged + keyboard ID 2 +
  mouse ID 3). **Opt-in**, needs a one-time re-pair; emulator-only users keep the original descriptor.
- **Key handling:** transitions only (no repeats), reference-counted shared keys, Ctrl/Shift/Alt
  combinations (modifier down first, key up first), 6-key rollover. Everything is released on
  disconnect, mode switch, focus loss, background or edit mode.
- **Gyro mouse:** relative, from angular velocity, with orientation correction, bias calibration,
  dead zone, smoothing, X/Y sensitivity, inversion, rate limit, pause and recenter.
- **Custom buttons** (max 12 per profile) and **edit mode:** move, resize, opacity, rename, remap, delete,
  restore defaults (with confirmation).
- **PC profiles** (max 10): Generic PC Game, GTA V example, plus your own. Create, rename, duplicate,
  delete, restore, persist. Separate from emulator loadouts.
- **Fixed during development:** the mouse ticker woke the CPU every 8 ms even when idle (it now
  sleeps until the stick moves); a default PC layout overlap (Wheel ▲ / Start); the top bar covering
  LT/RT; the store-repair step could drop new fields (it now copies the stored data).
- Tests: 299 (297 passed, 2 skipped). **Not yet tested on Windows.**

## 1.4.0 (versionCode 8), 2026-11-05: loadouts

- **Loadouts: one saved setup per game, up to 5** (plus the built-in Default). Each loadout keeps
  its own button positions and sizes, opacity (all and per control), style, stick settings and gyro.
- **"Choose a loadout" every time you open the controller:** cards with a live preview, "LAST USED"
  badge, Play, and Delete (with confirmation).
- **Create:** type a name → *Create & arrange buttons*. It starts as a copy of your current setup and
  opens the controller in Edit mode; Save stores the layout in that loadout. Changes made later in
  Customize, Styles or Gyro are saved to the loadout in use.
- At 5 loadouts the "New loadout" card is hidden, and the Profiles screen's New/Duplicate are disabled
  ("Limit: 5 loadouts"). Deleting one frees a slot.
- **Fixed (found by tests):** a loadout named like a built-in preset ("God of War") became "God of War 2".
  Names now only need to be unique among your loadouts.
- Built on the existing profile system: same storage, same HID report, no re-pairing.
- Tests: 256 (254 passed, 2 skipped).

## 1.3.0 (versionCode 7), 2026-11-05: gyro right stick

- **360° gyroscope right stick.** Phone tilt drives the RIGHT analog stick continuously in every direction.
  It is position-based like a real stick: a held tilt keeps the stick displaced (the camera keeps
  turning), and returning to neutral centres it. It is not angular-velocity / mouse-like.
  - Sensor fusion: gyroscope (fast) + accelerometer (anchors "down"), so the neutral cannot drift.
    Accelerometer samples taken while shaking get less weight.
  - Tilt is measured relative to the calibrated hold. Any holding angle works (flat, 45°, upright).
  - Mapping reuses the stick maths: radial dead zone without a jump, sensitivity, max tilt angle,
    circular clamp (|v| ≤ 1, direction kept), smoothing, invert X/Y.
  - Recenter (instant) and Calibrate (1.5 s average). The neutral is saved and shared by all profiles.
  - Sensors run only on the controller (gyro on) or the gyro test screen, with the app visible.
    Otherwise they are off, and a centred right stick is sent.
  - Jitter filter: changes under 2 HID counts are not sent; centre and full deflection are always sent.
- **New Gyro screen:** live circle (the value in the HID report), X/Y, HID values, magnitude, direction,
  tilt angles, sensor status and rate, and all settings.
- **Controller:** with the gyro on, the touch right stick is replaced by a GYRO indicator (tap = recenter),
  plus a Recenter chip. The HID descriptor and report format are unchanged, so no re-pairing is needed.
- **Fixed (found by a screenshot):** a held tilt was not re-sent after an input reset (screen change, reconnect).
- Tests: 245 (243 passed, 2 skipped). Gyro maths, fusion, engine and ViewModel integration are tested
  with simulated sensors; nothing is verified on the phone yet.

## 1.2.0 (versionCode 6), 2026-11-05: premium styles

- **5 controller styles:** PS2 Classic, PS5 Style (two-tone white, CREATE/OPTIONS), Xbox Style
  (Y B A X, LB/LT/RB/RT, VIEW/MENU, LS/RS), Retro (coloured X A B Y), Neon (glowing outlines).
  New **Styles** screen with live previews. Styles only change the look: each position always
  sends the same HID button (the bottom face button is HID button 2 whether it shows ✕ or A).
- **Opacity per control** (D-pad, face buttons, every shoulder button, sticks, L3/R3, Start/Select),
  on top of the opacity for all controls. Set it in Customize, or for the selected control in Edit layout.
  A control can't go below 10 % (an invisible control gets hit by accident).
- **Premium home screen:** brand header, status card with the one action needed, live preview of your
  controller style, tiles for Styles / Customize / More.
- **Removed:** the testing Logs button and screen. "Restore my phone's original Bluetooth name" moved to More.
- Tests: 211 (209 passed, 2 skipped), including "every style sends the same HID buttons".

## 1.1.0 (versionCode 5), 2026-11-05: simple mode

After the first real test on the POCO F6 (HID registration works on Android 16):
- **New simple home screen:** one status in plain words and the one button needed now.
  Advanced screens are under "More".
- **Automatic setup:** opening the app (or turning Bluetooth on while it is open) registers the gamepad
  automatically, and connects to the known laptop 1.5 s later if the stack didn't already.
- **The PC sees "PS2 Gamepad":** the app renames the phone's Bluetooth name (the name Windows shows)
  and remembers the original. "Restore phone name" is on the Logs screen.
- **Logs button (testing):** full log with Copy / Clear. It is in its own file so it is easy to remove later.
- **Fixed:** earbuds were offered as a "host" to connect to; only computers are listed now.
- **Fixed (found by tests):** a pending auto-connect could fire again after a later disconnect.
- Tests: 201 (199 passed, 2 skipped).

## 1.0.0 (versionCode 4), 2026-11-05: release candidate

> **Release candidate, not a verified release.** All automated tests pass and the release build
> runs on the Android emulator, but nothing has been tested on the POCO F6, Windows 11 or PCSX2 yet.
> Run TEST_REPORT.md §4 before relying on it.

APKs (`release/`):
- `PS2BluetoothGamepad-1.0.0-release.apk`: signed, R8-shrunk, 2.5 MB
- `PS2BluetoothGamepad-1.0.0-debug.apk`: debug key, logcat output, 27 MB

### What's in it
- Bluetooth HID gamepad (Android 9+): 12 buttons, 8-way D-pad, two analog sticks; descriptor
  unchanged since Phase 1, so no re-pairing is needed.
- DualShock 2-style full-screen landscape controller with full multi-touch.
- Customization, layout editor, 6 built-in profiles + your own, per-stick analog settings,
  centre calibration with a live visualizer.
- Dashboard with connection timer, last connection, battery and active profile.
- Diagnostics with measured report generation time, send rate, touch→send time, CPU, heap and battery.

### Changes since 0.3.0 (Phase 4)
- **Fixed (critical):** a failed "release" report could leave a button held on the PC; it is now retried with the current state.
- **Fixed (critical):** Bluetooth profile proxies leaked on every Bluetooth off/on, and a late callback could crash the app after exit.
- **Fixed (high):** the stack reconnected to the PC after the user had tapped Disconnect.
- **Fixed (high):** a setting changed right after launch was lost if the app closed quickly.
- Fixed: the floating stick jumped when a second finger took over; layout-editor drafts could be reset.
- Security/privacy: no cloud backup or device transfer of settings (they contain the PC's Bluetooth address);
  release builds write nothing to logcat.
- Release build: signing config (key outside the repository), R8 shrinking, explicit keep rules for saved settings.
- App renamed "PS2 Bluetooth Gamepad". The Bluetooth service name Windows sees is unchanged.
- Tests: 181 automated (unit, Robolectric UI, lifecycle integration, stress, benchmark).
  Full list in BUG_REPORT.md.

### Known limitations
- The app must stay in the foreground (Android unregisters background HID apps); it re-registers on return.
- L2/R2 digital; 8-bit sticks with circular range; no vibration.
- Only one HID app per phone at a time.
- Auto-reconnect is attempted but unverified.
- Game profiles are unverified starting points.
- Debug and release use different signing keys: uninstall one before installing the other.

### Upgrading
- From 0.2.0/0.3.0 debug builds to the release build: **uninstall first** (signature change).
  Windows pairing stays valid.

## 0.3.0: Phase 3
Customization, layout editor, profiles, DataStore persistence, advanced analog, calibration, dashboard, measurements.

## 0.2.0: Phase 2
Full touch controller, multi-touch, report pacing, interruption safety, diagnostics.

## 0.1: Phase 1 (included in 0.2.0)
HID registration, pairing, connection lifecycle, test buttons.
