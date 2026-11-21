# PCSX2 configuration

Status: **not yet tested with PCSX2.** The steps below follow how PCSX2 2.x handles generic HID
gamepads. Please report what you see (the table in TEST_REPORT.md §5 has room for it).

## 1. What Windows receives

The phone is a standard HID **Game Pad**. Its buttons and axes never change, whatever profile or
layout you use in the app.

| Phone control | HID usage (joy.cpl) | SDL index (PCSX2 shows) |
|---|---|---|
| □ Square | Button 1 | Button 0 |
| ✕ Cross | Button 2 | Button 1 |
| ○ Circle | Button 3 | Button 2 |
| △ Triangle | Button 4 | Button 3 |
| L1 / R1 | Button 5 / 6 | Button 4 / 5 |
| L2 / R2 | Button 7 / 8 (digital) | Button 6 / 7 |
| Select / Start | Button 9 / 10 | Button 8 / 9 |
| L3 / R3 | Button 11 / 12 | Button 10 / 11 |
| D-pad | POV hat (8 directions) | Hat 0 |
| Left stick X / Y | X axis / Y axis (up = negative) | Axis 0 / Axis 1 |
| Right stick X / Y | Z axis / Z rotation (up = negative) | Axis 2 / Axis 3 |

## 2. Check Windows first

1. Phone status shows **Connected**.
2. `Win + R` → `joy.cpl` → select the device → **Properties**.
3. Press each control on the phone and check the table above. Leave the sticks: the cross-hair
   must sit exactly in the centre.

If joy.cpl does not work, PCSX2 will not either. See BLUETOOTH_SETUP.md, troubleshooting.

## 3. PCSX2 (2.x) setup

1. Connect the phone **before** starting PCSX2 (PCSX2 also detects devices that connect later).
2. **Settings → Controllers**:
   - Enable the **SDL** input source. Leave "SDL raw input" off at first.
   - If the device is not listed under SDL, also enable **DInput**.
3. **Controller Port 1 → Controller type: DualShock 2.**
4. The pad is generic and not in SDL's controller database, so "Automatic Mapping" says
   *"No generic bindings were generated for device 'SDL-0'"* and no button works until it is bound.

   **Make Automatic Mapping work (once per PC):** close PCSX2, copy
   `C:\Program Files\PCSX2\resources\game_controller_db.txt` to `Documents\PCSX2\game_controller_db.txt`
   and add this line at the end of the copy (one line, checked with PCSX2 2.x / SDL3 on Windows 11):

   ```
   05000000e00000000000000000000000,PadLink Gamepad,a:b1,b:b2,x:b0,y:b3,leftshoulder:b4,rightshoulder:b5,lefttrigger:b6,righttrigger:b7,back:b8,start:b9,leftstick:b10,rightstick:b11,dpup:h0.1,dpright:h0.2,dpdown:h0.4,dpleft:h0.8,leftx:a0,lefty:a1,rightx:a2,righty:a3,platform:Windows,
   ```

   Start PCSX2 again: Automatic Mapping → "SDL-0: PadLink Gamepad" now fills every binding.

   **Or bind manually:** click each binding and press the matching phone control.

   | PCSX2 binding | Press on the phone |
   |---|---|
   | Up / Down / Left / Right | D-pad |
   | Cross, Circle, Square, Triangle | the face buttons |
   | L1, L2, R1, R2 | the shoulder buttons |
   | Select, Start | SELECT, START |
   | L3, R3 | L3, R3 buttons |
   | Left Stick Up/Down/Left/Right | push the left stick that way |
   | Right Stick Up/Down/Left/Right | push the right stick that way |
   | Analog (mode toggle) | optional: e.g. a keyboard key |

5. Deadzones: the app already applies its own dead zone. Start with PCSX2's analog deadzone at
   **0 %** and sensitivity at **100 %**, and change only one side if needed.
6. Save the bindings as an input profile, e.g. "Phone".

## 4. Per-game notes (expected, NOT verified)

| Game | Phone profile to start with | Things to check |
|---|---|---|
| God of War / God of War II | God of War / God of War II | Analog mode on; right-stick evade rolls; □ △ combos (fast tapping); L1 + face button magic/specials |
| GTA San Andreas | GTA San Andreas | Right stick camera/aiming; L1/R1 + face buttons; driving with ✕ / □ |
| Need for Speed Most Wanted | Need for Speed Most Wanted | Left-stick steering precision; check which buttons the game uses for gas/brake (digital L2/R2 here) |
| Shadow of the Colossus | Standard PS2 | Holding R1 (grip) for a long time while moving the left stick and camera |

If a game behaves wrongly: first check joy.cpl (is the phone sending the right thing?), then PCSX2's
binding, then the app's analog settings (Customize → Analog, Calibration screen).

## 5. Known constraints

- L2/R2 are on/off. Games that use pressure (some racing games) see full pressure.
- No rumble: the HID descriptor has no output report.
- If the connection drops during a game, the phone releases every input and PCSX2 sees the pad
  disconnect. After reconnecting, check that PCSX2 picked the pad up again (re-open the
  controller settings if not).
