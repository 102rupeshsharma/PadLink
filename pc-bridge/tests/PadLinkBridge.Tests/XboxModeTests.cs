using PadLinkBridge.Core;
using Xunit;

namespace PadLinkBridge.Tests;

/// <summary>
/// PadLink Bridge as an on/off app: open it → Xbox mode, close it → normal controller again.
/// A fake driver (records what games would see) and a fake phone (sends PadLink reports).
/// </summary>
public class XboxModeTests
{
    /// <summary>The virtual Xbox controller, as games would see it.</summary>
    private sealed class FakeXboxPad : IVirtualPad, IDisposable
    {
        public bool PluggedIn, Disposed;
        public XboxState Seen = XboxState.Neutral;
        public int Plugs;
        public void Connect() { if (Disposed) throw new ObjectDisposedException("pad"); PluggedIn = true; Plugs++; }
        public void Submit(XboxState s) { if (Disposed) throw new ObjectDisposedException("pad"); Seen = s; }
        public void Disconnect() => PluggedIn = false;
        public void Dispose() => Disposed = true;
    }

    /// <summary>The PadLink reader (the HID watcher in the app), driven by the test like a phone.</summary>
    private sealed class FakePhone : IDisposable
    {
        public BridgeEngine? Engine;
        public bool Reading;
        public void Connect() => Engine?.OnPadLinkFound("PadLink Gamepad");
        public void Press(PadLinkButton b, sbyte lx = 0, sbyte ly = 0) =>
            Engine?.OnReport(new PadLinkReport(1 << ((int)b - 1), PadLinkReport.HatReleased, lx, ly, 0, 0).ToBytes());
        public void Disconnect() => Engine?.OnPadLinkLost();
        public void Dispose() => Reading = false;
    }

    private readonly List<FakeXboxPad> _pads = new();
    private readonly FakePhone _phone = new();
    private bool _driverInstalled = true;

    private XboxMode NewMode() => new(
        createPad: () =>
        {
            if (!_driverInstalled) throw new InvalidOperationException("ViGEmBus not found");
            var p = new FakeXboxPad(); _pads.Add(p); return p;
        },
        startReading: engine => { _phone.Engine = engine; _phone.Reading = true; return _phone; });

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

        // open PadLink Bridge
        Assert.True(mode.Start());
        Assert.Equal(XboxModeState.WaitingForPadLink, mode.State);
        Assert.False(_pads[0].PluggedIn); // no Xbox controller until the phone is there

        // phone: Connect → Open Controller
        _phone.Connect();
        Assert.Equal(XboxModeState.On, mode.State);
        Assert.True(_pads[0].PluggedIn);

        // play: run forward while attacking
        _phone.Press(PadLinkButton.Square, lx: 0, ly: -127);
        Assert.True(_pads[0].Seen.Has(XboxButtons.X));
        Assert.Equal(32767, _pads[0].Seen.LeftY);

        // done playing: close the app in the middle of a press
        mode.Stop();
        Assert.Equal(XboxModeState.Off, mode.State);
        Assert.Equal(XboxState.Neutral, _pads[0].Seen); // released before unplugging: nothing stays held
        Assert.False(_pads[0].PluggedIn);
        Assert.True(_pads[0].Disposed); // driver connection closed
        Assert.False(_phone.Reading);   // the app stopped reading PadLink: it is a normal controller again
    }

    [Fact]
    public void AfterStopTheXboxControllerCanNeverComeBackByItself()
    {
        var mode = NewMode();
        mode.Start();
        var engine = mode.Engine!;
        _phone.Connect();
        mode.Stop();

        // a late device scan or a report still in flight after Stop
        engine.OnPadLinkFound("PadLink Gamepad");
        engine.OnReport(new PadLinkReport(1, PadLinkReport.HatReleased, 0, 0, 0, 0).ToBytes());

        Assert.Equal(1, _pads[0].Plugs);
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
        Assert.True(_pads[0].PluggedIn);
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
    public void StartingTwiceKeepsOneController()
    {
        var mode = NewMode();
        mode.Start();
        mode.Start();
        Assert.Single(_pads);
    }

    [Fact]
    public void WithoutTheDriver_SaysDriverNeeded_ThenWorksAfterInstalling()
    {
        _driverInstalled = false;
        var mode = NewMode();
        Assert.False(mode.Start());
        Assert.Equal(XboxModeState.DriverMissing, mode.State);
        Assert.False(_phone.Reading);

        _driverInstalled = true; // user clicked Install driver
        Assert.True(mode.Start());
        Assert.Equal(XboxModeState.WaitingForPadLink, mode.State);
    }

    [Fact]
    public void ChangedIsRaisedForTheWindow()
    {
        var mode = NewMode();
        var n = 0;
        mode.Changed += () => n++;
        mode.Start();          // on
        _phone.Connect();      // phone connected
        mode.Stop();           // off (+ released)
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
            var engine = mode.Engine!;
            using var cts = new CancellationTokenSource();
            var masher = Task.Run(() =>
            {
                var i = 0;
                while (!cts.IsCancellationRequested)
                    engine.OnReport(new PadLinkReport(1 << (i++ % 12), PadLinkReport.HatReleased, 127, 127, -127, -127).ToBytes());
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
