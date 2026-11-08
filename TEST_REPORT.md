# Test report: PS2 Bluetooth Gamepad 1.0.0

Date: 2026-11-05 · Build: 1.0.0 (versionCode 4)
Release APK SHA-256 `d5cc736080bc12632ffff45fad04a61ac65064398cb2e0a85ee49c06f4dfef91`

## 0. Summary

| Kind of test | Executed | Result |
|---|---|---|
| Automated JVM tests (unit, integration, UI, stress) | Yes | **181 total: 179 passed, 0 failed, 2 skipped** |
| Android emulator (Android 16, virtual Bluetooth) | Yes, partly | Install, launch, HID registration, settings persistence (release build) ✅ |
| **POCO F6 + Windows 11 hardware** | **No** | **0 of the hardware tests done.** Procedure in §4 |
| **PCSX2 / games** | **No** | **No game is verified compatible** |
| End-to-end latency | No | Method in §3.3 |

Legend: ✅ passed (actually executed) · ⏳ not done yet · ❌ failed.
A test with a **mock** Bluetooth layer proves app logic only, never Bluetooth or Windows behaviour.

## 1. Automated tests: what is real and what is mocked

| Layer | Real | Mocked / simulated |
|---|---|---|
| Unit tests | Production code (encoder, descriptor parser, stick maths, input manager, reducer, profiles, layout) | — |
| ViewModel / stress tests | ViewModel, input manager, reducer, settings repository | `FakeHid` (mock HID layer driven by the real state reducer), in-memory storage |
| UI tests (Robolectric + Compose) | Real Compose screens, real touch injection, real multi-touch dispatch | `FakeHid`, simulated Android framework |
| Lifecycle integration | Real `MainActivity`, ViewModel factory, **DataStore**, `BluetoothManagerHelper`, `HidDeviceManager` | Robolectric **shadow** Bluetooth adapter (no radio) |

Command: `.\gradlew.bat :app:testDebugUnitTest` (clean build, 2026-11-05).

| Suite | Tests | Passed | Skipped | Covers |
|---|---|---|---|---|
| HidDescriptorTest | 7 | 7 | 0 | Descriptor parsed item by item: Game Pad, Report ID 1, 12 buttons, hat with null state, X/Y/Z/Rz −127..127, 48 input bits = 6 bytes |
| HidReportEncodingTest | 13 | 13 | 0 | Neutral `00 80 00 00 00 00`, every button bit, all 9 hat values, axis order/sign/clamp, 2000 random round-trips |
| StickMathTest | 17 | 17 | 0 | 8 directions + centre, dead zone with no jump, clamping, symmetric rounding, D-pad sectors |
| AnalogSettingsTest | 9 | 9 | 0 | Max range, response curves, invert Y, neutral under every setting, 20 000-case fuzz never leaves the HID range, face spacing geometry |
| ControllerInputManagerTest | 21 | 21 | 0 | Press/release of all inputs, 5 s hold, rapid tapping, the 7 spec multi-touch combinations, disconnect behaviour |
| ReportQueueTest | 5 | 5 | 0 | Axis coalescing; press+release never merged |
| HidStateReducerTest | 15 | 15 | 0 | Registration/connection state machine; "Connected" only from a real callback |
| MainViewModelTest | 30 | 30 | 0 | Permission/BT gating, reconnect logic, re-registration, user-disconnect rules, profiles, edit mode, connection timer, sessions |
| ProfileManagerTest | 13 | 13 | 0 | Built-ins, create/rename/duplicate/delete/switch/restore, JSON round-trip, corrupt data repair, **no button-mapping field** |
| LayoutEngineTest | 9 | 9 | 0 | No overlaps on 5 screen sizes, on-screen, scaling, relative placements, collisions |
| SettingsRepositoryTest | 6 | 6 | 0 | Debounced save, persistence across restart, edits before load, flush during load |
| ControllerUiTest | 17 | 16 | 1 | Every button, D-pad incl. diagonal and sliding, both sticks, 5-finger multi-touch, interruption, connection loss, edit mode, customization, profiles, calibration, diagnostics, error states |
| LifecycleIntegrationTest | 6 | 6 | 0 | Real Activity: launch, pause/stop/focus-loss release, DataStore persistence across recreation, adapter broadcasts, HID manager close safety |
| StressTest | 8 | 8 | 0 | 10 000 taps, long holds under load, 100 000 analog updates, 1 000 profile switches, 500 connection drops, 200 background cycles, BT toggle storm, 3 concurrent threads |
| PerformanceBenchmarkTest | 1 | 1 | 0 | Pipeline micro-benchmark (desktop JVM) |
| MiscTest | 3 | 3 | 0 | Permissions per API level, log buffer, status texts |
| ScreenshotCapture | 1 | 0 | 1 | Renders docs/screenshots; runs only with `-Pscreenshots` (skipped otherwise, by design) |
| **Total** | **181** | **179** | **2** | |

Skipped tests:
1. `ControllerUiTest.newProfileDialogOpensAndCancels`: Robolectric never becomes idle with a text field
   inside a dialog. The profile logic is unit-tested; the dialog is in manual test P3.5.
2. `ScreenshotCapture.captureScreens`: a tool, not a test; run with `-Pscreenshots`.

Lint (debug and release): 0 errors, 0 code warnings (only "newer version available" notices).

## 2. Android emulator checks (Pixel 9a image, Android 16, virtual Bluetooth: NOT the POCO F6)

| Check | Build | Result |
|---|---|---|
| Install, launch, no crash | debug 0.2.0, release 1.0.0 | ✅ |
| HID profile proxy + `registerApp()` + `registered=true` callback | debug 0.2.0, **release 1.0.0 (R8)** | ✅ |
| App reports "Bluetooth disabled" and refuses to register when the adapter is off | release 1.0.0 | ✅ |
| Setting changed (background), app force-stopped, relaunched: setting kept | **release 1.0.0 (R8)** | ✅ |
| Controller layout renders without overlap | debug | ✅ (`docs/screenshots/02_controller.png`) |
| Pairing with Windows, joy.cpl, real touch on the emulator | — | Not possible / emulator System UI kept hanging |

Robolectric-rendered screenshots of every screen: `docs/screenshots/10_dashboard.png` …
`18_connection.png` (the "Connected" state shown there is mock state).

## 3. Performance

### 3.1 Measured so far (development PC, NOT the phone)
JVM micro-benchmark (`app/build/perf/benchmark.txt`, 2 runs): stick update → processed → HID report → send queue
**0.7–1.2 µs**, button 0.6–0.7 µs, encode 0.12–0.27 µs. This is software cost only; it says
nothing about the phone, Bluetooth or Windows.

### 3.2 Measured on the phone by the app (Diagnostics screen), still to be recorded ⏳
| Metric | What exactly it measures | Value |
|---|---|---|
| Report generation avg/max | Input change → HID report encoded and queued (`System.nanoTime`) | ⏳ |
| Touch → sendReport avg/last | Android touch event timestamp → `BluetoothHidDevice.sendReport()` returned. Includes the 4 ms pacing. **Excludes** the radio and Windows | ⏳ |
| Report rate | `sendReport()` calls per second while moving a stick | ⏳ |
| Send failures | `sendReport()` returned false | ⏳ |
| App CPU % | Process CPU time / wall time (2 s windows) | ⏳ |
| Heap | Java heap in use | ⏳ |
| Battery | % at session start → now | ⏳ |

### 3.3 Not measurable by the app: end-to-end latency
Bluetooth transmission time and Windows/PCSX2 processing cannot be seen from the phone.
Repeatable method: film the phone screen and the PC monitor (joy.cpl Test tab, or a game) together
with a phone camera in **240 fps slow motion**. Tap a button 20 times; for each tap count the frames
between the finger touching (button highlights on the phone) and the joy.cpl indicator lighting
up (1 frame = 4.2 ms; add the monitor's own lag). Report the median and the worst of the 20.

## 4. Hardware test procedure: POCO F6 + Windows 11 ⏳

Fill in Result (✅/❌ + notes). Use the release APK unless you need logcat (debug APK).

### 4.1 Connection
| # | Step | Expected | Result |
|---|---|---|---|
| C1 | Install the release APK | Installs | ⏳ |
| C2 | Phone Bluetooth on, laptop Bluetooth on | Dashboard: Bluetooth enabled | ⏳ |
| C3 | **Connection → Register HID Device** (critical) | Log: proxy connected, `registered=true`; "HID registration successful" | ✅ 2026-11-05, POCO F6 (24069PC21I), Android 16 / API 36, build BP3A.250905.014 (user screenshot) |
| C4 | Make discoverable; pair from Windows (remove old pairings first) | Paired | ⏳ |
| C5 | Gamepad recognised | App "Connected to <laptop>"; joy.cpl lists the device | ✅ app shows Connected to RUPESH after a clean re-pair (user, 2026-11-05, v1.1.0); joy.cpl ✅ device listed and reacts to phone input (user, 2026-11-05). First attempt failed because the old pairing pre-dated HID registration |
| C6 | Disconnect, then Reconnect | Disconnected, then Connected; no re-pairing | ⏳ |
| C7 | Restart the phone, open the app, register, Reconnect | Connected | ⏳ |
| C8 | Restart the laptop, then register/reconnect from the phone | Connected | ⏳ |
| C9 | Turn laptop Bluetooth off for 30 s, then on | App: Disconnected + banner; then reconnects (automatically or with the button). Note which | ⏳ |
| C10 | Lock the phone 10 s, unlock | Re-registers; reconnects; no input held | ⏳ |

### 4.2 Windows (joy.cpl → Properties → Test)
| # | Check | Expected | Result |
|---|---|---|---|
| W1 | Each of the 12 buttons | The right button number lights only while held (PCSX2_CONFIGURATION.md §1) | ⏳ |
| W2 | D-pad 8 directions | POV shows the matching direction, centre when released | ⏳ |
| W3 | Left stick, right stick (all directions) | Cross-hair / Z bars move the right way; up = up | ⏳ |
| W4 | Simultaneous: stick + ✕ + L1 + R1 + other stick | All shown together | ⏳ |
| W5 | Neutral at rest | Cross-hair centred, no buttons, POV centred | ⏳ |
| H-STUCK | Hold ✕ and switch the laptop's Bluetooth off; also hold ✕ and pull down the notification shade | Nothing stays held after reconnect / immediately | ⏳ |
| C-HANDOFF | Floating stick: finger A pushes right, finger B touches the stick, A lifts | No jump of the stick value | ⏳ |

### 4.3 Earbuds
| # | Step | Expected | Result |
|---|---|---|---|
| E1 | Earbuds connected to the phone, music playing (started in another app, then back to this one) | Music plays; HID stays Connected | ⏳ |
| E2 | Play 10 min with music | No HID drop, no audio drop; note any input lag | ⏳ |
| E3 | Restart the app and repeat E1 | Same | ⏳ |

### 4.4 Customization / profiles (Phase 3)
| # | Test | Expected | Result |
|---|---|---|---|
| P3.1 | Every Customize slider, background, labels, haptics | Preview + controller update immediately | ⏳ |
| P3.2 | Edit layout: move/resize; try dropping onto another control | Moves; overlap refused; joy.cpl idle while editing | ⏳ |
| P3.3 | Force-stop and reopen; reboot the phone | Layout, profiles, active profile kept | ⏳ |
| P3.5 | Profiles: create and rename through the **dialog**, duplicate, delete, switch, restore | Works and persists | ⏳ |
| P3.6 | Per-stick sensitivity / dead zone / max range / curve / invert Y | Visible in joy.cpl; no drift at rest | ⏳ |
| P3.7 | Fixed mode + Calibrate centre | Resting thumb reads 0,0 | ⏳ |

### 4.5 PCSX2 and games
| Game | Movement | Camera | Combat / actions | Shoulders | Menus | Simultaneous | Extended play | Issues |
|---|---|---|---|---|---|---|---|---|
| God of War | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | |
| God of War II | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | |
| GTA San Andreas | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | |
| Need for Speed Most Wanted | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | |
| Shadow of the Colossus | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | |

Also check: PCSX2 detects the device (SDL) ⏳ · all bindings ⏳ · reconnect during emulation ⏳.

### 4.6 Long-duration
| Session | Actual duration | Disconnects | Stuck inputs | Missed inputs | Crashes | Battery start→end | CPU % / heap (Diagnostics) | Phone temperature | Result |
|---|---|---|---|---|---|---|---|---|---|
| 30 min | | | | | | | | | ⏳ |
| 60 min | | | | | | | | | ⏳ |
| 2 h (optional) | | | | | | | | | ⏳ |

## 4.7 Gyro right stick (1.3.0): removed in 3.0.0

The gyro right stick was removed in 3.0.0 (the app is now a simple Bluetooth controller), so these
tests no longer apply.

## 4.8 Loadouts (1.4.0) ⏳

| # | Test | Expected | Result |
|---|---|---|---|
| L1 | Open Controller | "Choose a loadout" appears every time | ⏳ |
| L2 | New loadout "GTA" → arrange buttons → Save | Controller opens in Edit; layout saved in "GTA" | ⏳ |
| L3 | Create "God of War" with a different layout, button style and combos | Each loadout shows its own preview | ⏳ |
| L4 | Switch between them via the picker | Layout, button style, opacity and combos change accordingly | ⏳ |
| L5 | Force-stop the app / reboot the phone | All loadouts and "LAST USED" kept | ⏳ |
| L6 | Create 5, try a 6th | New card hidden; delete one → can create again | ⏳ |
| L7 | PCSX2 with each loadout | Same bindings work (loadouts never change buttons) | ⏳ |

Automated: LoadoutLimitTest (4), LoadoutViewModelTest (4, including per-loadout settings after a
simulated restart), LoadoutUiTest (4): all passed.

## 4.9 PC Gaming Mode (1.5.0): removed in 3.0.0

PC Gaming Mode (Bluetooth keyboard + mouse, gyro mouse) was removed in 3.0.0. The phone now
registers only the gamepad descriptor, so these tests no longer apply.

## 4.10 Button styles and combo buttons (3.0.0) ⏳

| # | Test | Expected | Result |
|---|---|---|---|
| S1 | Settings → Button style: Real, Simple, Outline | Look changes at once; background stays black | ⏳ |
| S2 | Settings → Face buttons: Y B A X / △ ○ ✕ □ | Labels change; joy.cpl shows the same button numbers | ⏳ |
| S3 | PCSX2 after switching styles/labels | Same bindings still work | ⏳ |
| C1 | Edit → + Combo → L1+R1 → Save, hold it | joy.cpl shows buttons 5 and 6 together; released on lift | ⏳ |
| C2 | Combo ↓ ↘ → + X (3 steps), tap it in a fighting game | The move comes out; nothing stays held | ⏳ |
| C3 | Combo that repeats a button (X, X) | The game sees two separate presses | ⏳ |
| C4 | Hold the real L1 and tap a combo with L1 | L1 stays held after the combo ends | ⏳ |

Automated: ComboModelTest (4), ComboButtonUiTest (3), ControllerSettingsUiTest (style/label
choices, every style still sends the same buttons), black-background pixel test in every style:
all passed.

## 4.11 Gyro aiming (3.1.0) ⏳

Setup: controller → **Gyro OFF** chip → ON. A shooter in PCSX2 (right stick = camera), or `joy.cpl`
Properties (Z / Z-rotation = right stick).

| # | Test | Expected | Result |
|---|---|---|---|
| A1 | Hold the phone still for 2 s | Right stick stays centred; camera does not drift | ⏳ |
| A2 | Small slow correction (a few degrees) | Camera moves a little at once, no "nothing then jump" | ⏳ |
| A3 | Turn 10° slowly, then 10° fast | Camera turns about the same amount both times | ⏳ |
| A4 | Stop turning | Camera stops at once, no glide | ⏳ |
| A5 | Tilt the top edge towards you | Look up (Invert up/down flips it) | ⏳ |
| A6 | Touch the right stick while gyro is on | Touch wins; gyro resumes after lifting | ⏳ |
| A7 | Game dead-zone fix 0 % vs 20 % vs 30 % | Find the value where tiny aim just moves the camera | ⏳ |
| A8 | Gyro OFF / Edit / Home / app in background | Right stick centred; sensors stopped | ⏳ |

Automated (simulated sensors, MOCK Bluetooth): GyroAimTest (11, including the simulated play test),
GyroChipUiTest (2): all passed.

## 4.12 Settings while playing, stopping, button latency (3.2.0) ⏳

| # | Test | Expected | Result |
|---|---|---|---|
| P1 | While walking in a game, open Settings and tap Outline, △ ○ ✕ □, Battery saver | Each change applies at once | ⏳ |
| P2 | Scroll the Settings panel with a finger | It scrolls; switches at the bottom work | ⏳ |
| P3 | Walk for 5-10 s with the left stick, let go | Character stops at once (no 1-2 s walk-on) | ⏳ |
| P4 | Same with the right stick (camera) | Camera stops at once | ⏳ |
| P5 | Mash Cross/Square while walking | Every press counts; nothing stays pressed | ⏳ |
| P6 | Repeat P3 with Battery saver ON and OFF | Stops at once in both | ⏳ |

Automated (MOCK Bluetooth, simulated link): HumanPlayTest (5), SendSchedulerTest (4), gyro shaky-hand
tests (2): all passed. HumanPlayTest fails on the 3.1 Settings code, as reported.

## 4.13 Portrait app, landscape controller (3.4.0) ⏳

| # | Test | Expected | Result |
|---|---|---|---|
| O1 | Open the app holding the phone sideways | Start screen shows in portrait | ⏳ |
| O2 | Open Controller → pick a loadout | Screen turns to landscape | ⏳ |
| O3 | Leave the controller (Home / Back twice) | Screen turns back to portrait | ⏳ |
| O4 | Open each menu screen (More, Connection, Customize, Profiles, Diagnostics, Calibration) | Readable, nothing cut off, everything reachable by scrolling | ⏳ |
| O5 | Profiles → New profile → type a name → OK | Profile created (not automatable, see above) | ⏳ |

Automated (MOCK Bluetooth): PortraitAppTest (OrientationRuleTest 3, PortraitScreensUiTest 6): all passed.

## 4.14 Two laptops (3.5.0) ⏳

| # | Test | Expected | Result |
|---|---|---|---|
| T1 | Laptop A paired and used; open "Choose a laptop" | A listed as Connected / Used last | ⏳ |
| T2 | Laptop B already paired: tap Connect next to B | A disconnects, B connects, start screen shows Connected to B | ⏳ |
| T3 | Laptop B never paired: B in Add device; phone Search → Pair & connect | Code shown on both, B connects | ⏳ |
| T4 | Same with "Make this phone visible", pair from B | B connects; the phone does not reconnect A meanwhile | ⏳ |
| T5 | Next day: Connect | Connects to the laptop used last | ⏳ |

Automated (MOCK Bluetooth): ChooseLaptopViewModelTest (6), ChooseLaptopUiTest (4): all passed.

## 4.15 PadLink Bridge 1.0.0 (Windows PC app) ⏳

Automated: `dotnet test pc-bridge/PadLinkBridge.slnx`: **33 passed, 0 failed** (no mocks of the mapping;
the virtual controller is a recording fake, so these prove the bridge logic, not the driver).

| Suite | Tests | Covers |
|---|---|---|
| ReportTests | 6 | PadLink report parsing: ID, length, every button bit, hat, axes, junk rejected |
| MapperTests | 15 | Every button → Xbox, all 9 hat values → D-pad, sticks full range, Y inverted (up = up) |
| DeviceMatcherTests | 7 | Picks PadLink, never a real Xbox/Microsoft pad or a keyboard; HidHide instance ID; the real device list from the developer laptop |
| PlaySessionTests | 5 | God of War session, 100 mashed reports in order with none lost, plug once, 4 threads, junk never presses anything |

Real laptop (Windows 11, no ViGEmBus installed), `PadLinkBridge.exe --selftest`:
**ALL PASSED**. Found 12 HID devices, picked **PadLink Gamepad** (report length 7), XInput callable.
Window opened (screenshot checked at the laptop's DPI), second copy refused, tray app closes.

| # | Test on a real PC | Expected | Result |
|---|---|---|---|
| B1 | Run exe, click Install driver | ViGEmBus installs, step 1 green | ⏳ |
| B2 | Click Test | "Test passed ✓" | ⏳ |
| B3 | Phone: Connect + Open Controller | Step 2 green, tray icon green, live line moves | ⏳ |
| B4 | `joy.cpl` | "Controller (XBOX 360 For Windows)" listed, all buttons/sticks react | ⏳ |
| B5 | God of War 3 PC port (or any XInput game) | Moves, attacks, menus | ⏳ |
| B6 | HidHide: Install, Turn on | Game sees only the Xbox controller; Bridge still works | ⏳ |
| B7 | Phone disconnects mid-game | Character stops, nothing stays pressed | ⏳ |
| B8 | Restart the PC | Bridge starts in the tray, works on the next Connect | ⏳ |

## 5. Final acceptance checklist

| # | Criterion | Status |
|---|---|---|
| 1 | Application installs | ✅ emulator · ⏳ POCO F6 |
| 2 | POCO F6 registers as Bluetooth HID gamepad | ✅ (user test 2026-11-05) |
| 3 | Windows recognises the device | ✅ joy.cpl (user test 2026-11-05) |
| 4 | All PS2 buttons work | ✅ app logic (UI tests, mocked BT) · ⏳ hardware |
| 5 | Both analog sticks work | ✅ app logic · ⏳ hardware |
| 6 | Multi-touch works | ✅ app logic (5 fingers, Robolectric) · ⏳ real touchscreen |
| 7 | Simultaneous inputs work | ✅ app logic · ⏳ hardware |
| 8 | Button releases are reliable | ✅ app logic + stress · ⏳ hardware |
| 9 | Bluetooth reconnect works | ⏳ |
| 10 | Earbud simultaneous connectivity verified | ⏳ |
| 11 | PCSX2 recognises the controller | ✅ PCSX2 sees it as SDL-0; manual binding works (user, 2026-11-05). Automatic Mapping is not available for this generic pad (expected) |
| 12 | Actual gameplay manually tested | ⏳ |
| 13 | No known reproducible critical bugs | ✅ none known (two critical audit bugs fixed; hardware unknown, see BUG_REPORT.md O1) |
| 14 | Automated regression tests pass | ✅ 179/179 executed, 2 skipped |
| 15 | Release APK builds | ✅ signed, R8, 2.5 MB |
| 16 | Documentation complete | ✅ |

**The project is not release-ready** until items 2, 3, 9, 10, 11 and 12 pass on the real hardware.
