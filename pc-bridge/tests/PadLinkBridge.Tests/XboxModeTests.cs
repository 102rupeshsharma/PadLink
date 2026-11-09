using PadLinkBridge.Core;
using Xunit;

namespace PadLinkBridge.Tests;

/// <summary>The virtual Xbox controller, as games would see it.</summary>
internal sealed class FakeXboxPad : IVirtualPad, IDisposable
{
    public bool PluggedIn, Disposed;
    public XboxState Seen = XboxState.Neutral;
    public int Plugs;
    public void Connect() { if (Disposed) throw new ObjectDisposedException("pad"); PluggedIn = true; Plugs++; }
    public void Submit(XboxState s) { if (Disposed) throw new ObjectDisposedException("pad"); Seen = s; }
    public void Disconnect() => PluggedIn = false;
    public void Dispose() => Disposed = true;
}

/// <summary>One phone, as the HID watcher would deliver it (device path + PC name).</summary>
internal sealed class FakePhone(string id, string name)
{
    public PadHub? Hub;
    public string Id => id;
    public void Connect() => Hub?.OnDeviceFound(id, name);
    public void Send(PadLinkReport r) => Hub?.OnReport(id, r.ToBytes());
    public void Press(PadLinkButton b, sbyte lx = 0, sbyte ly = 0) =>
        Send(new PadLinkReport(1 << ((int)b - 1), PadLinkReport.HatReleased, lx, ly, 0, 0));
    public void Release() => Send(PadLinkReport.Neutral);
    public void Disconnect() => Hub?.OnDeviceLost(id);
}

/// <summary>
/// PadLink Bridge as an on/off app: open it → Xbox mode, close it → normal controller again.
/// A fake driver (records what games would see) and a fake phone (sends PadLink reports).
/// </summary>
public class XboxModeTests
{
    private readonly List<FakeXboxPad> _pads = new();
    private readonly FakePhone _phone = new(@"\\?\hid#phone1", "PadLink Gamepad");
    private bool _driverInstalled = true;
    private bool _reading;

    private XboxMode NewMode() => new(
        createPad: () => { var p = new FakeXboxPad(); _pads.Add(p); return p; },
        startReading: hub => { _phone.Hub = hub; _reading = true; return new Stopper(() => _reading = false); },
        driverInstalled: () => _driverInstalled);

    private sealed class Stopper(Action a) : IDisposable { public void Dispose() => a(); }

    [Fact]
    public void BeforeOpeningTheAppNothingIsXbox()
    {
        var mode = NewMode();
        Assert.Equal(XboxModeState.Off, mode.State);
        Assert.Empty(_pads); // no Xbox controller exists: PadLink is the normal controller
    }

    [Fact]
    public void OpenPlayClose_TheWholeEvening()
    {
        var mode = NewMode();
        Assert.True(mode.Start());
        Assert.Equal(XboxModeState.WaitingForPadLink, mode.State);
        Assert.Empty(_pads); // no Xbox controller until a phone is there

        _phone.Connect();
        Assert.Equal(XboxModeState.On, mode.State);
        Assert.True(_pads[0].PluggedIn);

        _phone.Press(PadLinkButton.Square, lx: 0, ly: -127); // run forward while attacking
        Assert.True(_pads[0].Seen.Has(XboxButtons.X));
        Assert.Equal(32767, _pads[0].Seen.LeftY);

        mode.Stop(); // close the app in the middle of a press
        Assert.Equal(XboxModeState.Off, mode.State);
        Assert.Equal(XboxState.Neutral, _pads[0].Seen); // released before unplugging
        Assert.False(_pads[0].PluggedIn);
        Assert.True(_pads[0].Disposed);
        Assert.False(_reading); // PadLink is a normal controller again
    }

    [Fact]
    public void AfterStopTheXboxControllerCanNeverComeBackByItself()
    {
        var mode = NewMode();
        mode.Start();
        var hub = mode.Hub!;
        _phone.Connect();
        mode.Stop();

        // a late device scan or a report still in flight after Stop
        Assert.False(hub.OnDeviceFound(@"\\?\hid#late", "PadLink Gamepad"));
        hub.OnReport(_phone.Id, new PadLinkReport(1, PadLinkReport.HatReleased, 0, 0, 0, 0).ToBytes());

        Assert.Single(_pads);
        Assert.False(_pads[0].PluggedIn);
        Assert.Equal(XboxModeState.Off, mode.State);
    }

    [Fact]
    public void StopStartStopAgain_EachTimeAFreshControllerAndCleanEnd()
    {
        var mode = NewMode();
        for (var round = 0; round < 3; round++)
        {
            mode.Start();
            _phone.Connect();
            _phone.Press(PadLinkButton.Cross);
            Assert.True(_pads[round].Seen.Has(XboxButtons.A));
            mode.Stop();
            Assert.False(_pads[round].PluggedIn);
            Assert.Equal(XboxState.Neutral, _pads[round].Seen);
        }
        Assert.Equal(3, _pads.Count);
        Assert.All(_pads, p => Assert.True(p.Disposed));
    }

    [Fact]
    public void PhoneDisconnectsWhileOn_WaitsAndComesBack()
    {
        var mode = NewMode();
        mode.Start();
        _phone.Connect();
        _phone.Press(PadLinkButton.R1);
        _phone.Disconnect(); // phone walked away / Bluetooth dropped
        Assert.Equal(XboxModeState.WaitingForPadLink, mode.State);
        Assert.Equal(XboxState.Neutral, _pads[0].Seen);
        Assert.False(_pads[0].PluggedIn);

        _phone.Connect();
        Assert.Equal(XboxModeState.On, mode.State);
        Assert.True(_pads[1].PluggedIn);
    }

    [Fact]
    public void ClosingTwiceOrStoppingWhenOffIsHarmless()
    {
        var mode = NewMode();
        mode.Stop();
        mode.Start();
        mode.Stop();
        mode.Stop();
        Assert.Equal(XboxModeState.Off, mode.State);
    }

    [Fact]
    public void StartingTwiceKeepsOneHub()
    {
        var mode = NewMode();
        mode.Start();
        var hub = mode.Hub;
        mode.Start();
        Assert.Same(hub, mode.Hub);
    }

    [Fact]
    public void WithoutTheDriver_SaysDriverNeeded_ThenWorksAfterInstalling()
    {
        _driverInstalled = false;
        var mode = NewMode();
        Assert.False(mode.Start());
        Assert.Equal(XboxModeState.DriverMissing, mode.State);
        Assert.False(_reading);

        _driverInstalled = true; // user clicked Install driver
        Assert.True(mode.Start());
        Assert.Equal(XboxModeState.WaitingForPadLink, mode.State);
    }

    [Fact]
    public void WithoutADriverCheckThePadFactoryDecides()
    {
        var mode = new XboxMode(() => throw new InvalidOperationException("ViGEmBus not found"), _ => new Stopper(() => { }));
        Assert.False(mode.Start());
        Assert.Equal(XboxModeState.DriverMissing, mode.State);
    }

    [Fact]
    public void ChangedIsRaisedForTheWindow()
    {
        var mode = NewMode();
        var n = 0;
        mode.Changed += () => n++;
        mode.Start();
        _phone.Connect();
        mode.Stop();
        Assert.True(n >= 3);
    }

    [Fact]
    public async Task StopWhilePhoneIsMashingButtonsNeverLeavesAButtonHeld()
    {
        for (var trial = 0; trial < 20; trial++)
        {
            _pads.Clear();
            var mode = NewMode();
            mode.Start();
            _phone.Connect();
            var hub = mode.Hub!;
            using var cts = new CancellationTokenSource();
            var masher = Task.Run(() =>
            {
                var i = 0;
                while (!cts.IsCancellationRequested)
                    hub.OnReport(_phone.Id, new PadLinkReport(1 << (i++ % 12), PadLinkReport.HatReleased, 127, 127, -127, -127).ToBytes());
            });
            await Task.Delay(5);
            mode.Stop();
            await Task.Delay(5);
            cts.Cancel();
            await masher;
            Assert.Equal(XboxState.Neutral, _pads[0].Seen);
            Assert.False(_pads[0].PluggedIn);
        }
    }
}
