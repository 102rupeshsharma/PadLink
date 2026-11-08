# PadLink Bridge (Windows)

Windows tray app that turns the PadLink phone controller (Bluetooth HID) into a virtual Xbox 360
controller via ViGEmBus, so XInput-only PC games work. User guide: see the main [README](../README.md#-pc-games-and-ports-padlink-bridge).

- `src/PadLinkBridge.Core`: report parsing, PadLink → Xbox mapping, device picking, bridge engine (no Windows APIs, fully tested)
- `src/PadLinkBridge`: WinForms app: HID reading (HidSharp), ViGEm client, XInput check, HidHide, tray, setup window
- `tests/PadLinkBridge.Tests`: xUnit tests

```
dotnet test PadLinkBridge.slnx
dotnet publish src/PadLinkBridge/PadLinkBridge.csproj -c Release -o out/publish   # single-file exe
out/publish/PadLinkBridge.exe --selftest report.txt                              # checks on a real PC
```
