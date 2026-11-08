using System.Text;
using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>Checks that run on the real PC (no phone needed): the "Test" button and <c>--selftest</c>.</summary>
public static class SelfTest
{
    public readonly record struct Result(bool Ok, string Message);

    /// <summary>
    /// Plugs a temporary Xbox controller, presses A, and checks with XInput (the API games use) that a new
    /// controller appeared with A held. Then releases and unplugs it.
    /// </summary>
    public static Result VirtualControllerCheck()
    {
        ViGEmPad? pad = null;
        try
        {
            try { pad = new ViGEmPad(); }
            catch { return new Result(false, "The Xbox controller driver (ViGEmBus) is not installed. Install it in step 1."); }

            var before = XInput.Connected().Select(c => c.Slot).ToHashSet();
            pad.Connect();
            var pressA = XboxMapper.Map(new PadLinkReport(1 << (int)(PadLinkButton.Cross - 1), PadLinkReport.HatReleased, 0, 0, 0, 0));
            for (var i = 0; i < 40; i++) // up to 2 s for Windows to see the new controller
            {
                pad.Submit(pressA);
                Thread.Sleep(50);
                foreach (var (slot, state) in XInput.Connected())
                    if (!before.Contains(slot) && (state.Buttons & (ushort)XboxButtons.A) != 0)
                        return new Result(true, $"Xbox controller seen by Windows in slot {slot + 1} with A pressed.");
            }
            return new Result(false, "The controller was created but Windows (XInput) did not report it. Restart the PC once after installing the driver and try again.");
        }
        catch (Exception e) { return new Result(false, e.Message); }
        finally
        {
            try { pad?.Submit(XboxState.Neutral); } catch { }
            pad?.Dispose();
        }
    }

    /// <summary><c>PadLinkBridge.exe --selftest out.txt</c>: a report of everything that can be checked without the phone.</summary>
    public static int RunToFile(string path)
    {
        var sb = new StringBuilder();
        var failures = 0;
        void Line(bool ok, string what) { sb.AppendLine((ok ? "PASS " : "FAIL ") + what); if (!ok) failures++; }
        void Info(string what) => sb.AppendLine("INFO " + what);

        sb.AppendLine($"PadLink Bridge {typeof(SelfTest).Assembly.GetName().Version} self-test, {DateTime.Now:yyyy-MM-dd HH:mm:ss}");

        // 1. the bridge logic with a recording pad: a PadLink session end to end
        var rec = new RecordingPad();
        var engine = new BridgeEngine(rec);
        engine.OnPadLinkFound("PadLink");
        engine.OnReport(new PadLinkReport(1 << (int)(PadLinkButton.Cross - 1), 0, 127, -127, 0, 0).ToBytes());
        Line(rec.Connected && rec.Last.Has(XboxButtons.A) && rec.Last.Has(XboxButtons.DpadUp) && rec.Last.LeftX == 32767 && rec.Last.LeftY == 32767,
            "bridge: PadLink Cross + D-pad up + stick up-right -> Xbox A + D-pad up + stick up-right");
        engine.OnPadLinkLost();
        Line(!rec.Connected && rec.Last == XboxState.Neutral, "bridge: phone disconnect releases everything and unplugs the controller");

        // 2. HID enumeration on this PC (must never crash)
        try
        {
            var list = PadLinkWatcher.Candidates();
            Line(true, $"HID: listed {list.Count} devices");
            var pick = DeviceMatcher.Pick(list);
            Info(pick == null ? "HID: PadLink not connected right now" : $"HID: PadLink found: {pick.ProductName} ({pick.DevicePath})");
            foreach (var c in list.Where(c => c.IsGamepad)) Info($"HID gamepad: {c.ProductName} VID {c.VendorId:X4} PID {c.ProductId:X4} report {c.MaxInputReportLength}");
        }
        catch (Exception e) { Line(false, "HID enumeration crashed: " + e.Message); }

        // 3. drivers
        var vigem = Drivers.ViGEmInstalled();
        Info("ViGEmBus driver: " + (vigem ? "installed" : "NOT installed"));
        Info("HidHide: " + (Drivers.HidHideCli() ?? "NOT installed"));
        if (vigem)
        {
            var r = VirtualControllerCheck();
            Line(r.Ok, "virtual Xbox controller seen by XInput: " + r.Message);
        }
        else Info("virtual controller check skipped (needs ViGEmBus)");

        // 4. XInput itself is callable
        try { Info($"XInput controllers connected: {XInput.Connected().Count}"); Line(true, "XInput callable"); }
        catch (Exception e) { Line(false, "XInput: " + e.Message); }

        sb.AppendLine(failures == 0 ? "RESULT: ALL PASSED" : $"RESULT: {failures} FAILED");
        File.WriteAllText(path, sb.ToString());
        return failures == 0 ? 0 : 1;
    }

    private sealed class RecordingPad : IVirtualPad
    {
        public bool Connected;
        public XboxState Last = XboxState.Neutral;
        public void Connect() => Connected = true;
        public void Submit(XboxState s) => Last = s;
        public void Disconnect() => Connected = false;
    }
}
