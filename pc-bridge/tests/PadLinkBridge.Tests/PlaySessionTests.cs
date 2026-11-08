using PadLinkBridge.Core;
using Xunit;

namespace PadLinkBridge.Tests;

/// <summary>Stands in for the ViGEmBus virtual Xbox controller and records what a game would see.</summary>
public sealed class RecordingPad : IVirtualPad
{
    public bool Plugged { get; private set; }
    public int Connects, Disconnects;
    public readonly List<XboxState> Seen = new();
    public XboxState Last => Seen.Count == 0 ? XboxState.Neutral : Seen[^1];
    public void Connect() { Plugged = true; Connects++; }
    public void Submit(XboxState s) { Assert.True(Plugged, "submitted while unplugged"); Seen.Add(s); }
    public void Disconnect() { Plugged = false; Disconnects++; }
}

/// <summary>
/// A whole play session through the real engine, report by report, the way the phone sends them:
/// connect, walk, jump, attack while walking, let go, Bluetooth drops, reconnect.
/// </summary>
public class PlaySessionTests
{
    private static byte[] R(int buttons = 0, int hat = 8, int lx = 0, int ly = 0, int rx = 0, int ry = 0) =>
        new PadLinkReport(buttons, hat, (sbyte)lx, (sbyte)ly, (sbyte)rx, (sbyte)ry).ToBytes();
    private static int B(PadLinkButton b) => 1 << ((int)b - 1);

    [Fact]
    public void GodOfWarSession()
    {
        var pad = new RecordingPad();
        var e = new BridgeEngine(pad);
        Assert.Equal(BridgeStatus.WaitingForPadLink, e.Status);

        e.OnReport(R(B(PadLinkButton.Cross)));
        Assert.Empty(pad.Seen); // nothing before PadLink is found

        e.OnPadLinkFound("PadLink");
        Assert.True(pad.Plugged);
        Assert.Equal(XboxState.Neutral, pad.Last); // plugged in centred
        Assert.Equal(BridgeStatus.Active, e.Status);

        // walk forward (left stick up), camera right
        e.OnReport(R(ly: -127, rx: 90));
        Assert.Equal(32767, pad.Last.LeftY);
        Assert.True(pad.Last.RightX > 23000);
        // jump (Cross) while walking → A plus the stick in the same state
        e.OnReport(R(B(PadLinkButton.Cross), ly: -127, rx: 90));
        Assert.True(pad.Last.Has(XboxButtons.A));
        Assert.Equal(32767, pad.Last.LeftY);
        // light attack (Square) + block (L1) together
        e.OnReport(R(B(PadLinkButton.Square) | B(PadLinkButton.L1), ly: -127));
        Assert.True(pad.Last.Has(XboxButtons.X | XboxButtons.LeftShoulder));
        Assert.False(pad.Last.Has(XboxButtons.A));
        // magic (R2 + Circle), D-pad down-left
        e.OnReport(R(B(PadLinkButton.R2) | B(PadLinkButton.Circle), hat: 5));
        Assert.Equal(255, pad.Last.RightTrigger);
        Assert.True(pad.Last.Has(XboxButtons.B | XboxButtons.DpadDown | XboxButtons.DpadLeft));
        // let go of everything → the game sees a centred, released controller at once
        e.OnReport(R());
        Assert.Equal(XboxState.Neutral, pad.Last);

        // hold attack, then Bluetooth drops: everything is released BEFORE unplugging
        e.OnReport(R(B(PadLinkButton.Triangle), lx: 127));
        e.OnPadLinkLost();
        Assert.Equal(XboxState.Neutral, pad.Last);
        Assert.False(pad.Plugged);
        Assert.Equal(BridgeStatus.WaitingForPadLink, e.Status);
        var seenBefore = pad.Seen.Count;
        e.OnReport(R(B(PadLinkButton.Cross)));
        Assert.Equal(seenBefore, pad.Seen.Count); // ignored while disconnected

        // phone reconnects: a fresh, centred controller
        e.OnPadLinkFound("PadLink");
        Assert.True(pad.Plugged);
        Assert.Equal(2, pad.Connects);
        Assert.Equal(XboxState.Neutral, pad.Last);
        e.OnReport(R(B(PadLinkButton.Start)));
        Assert.True(pad.Last.Has(XboxButtons.Start));
    }

    [Fact]
    public void EveryReportIsForwardedImmediatelyInOrder()
    {
        var pad = new RecordingPad();
        var e = new BridgeEngine(pad);
        e.OnPadLinkFound("PadLink");
        // fast button mashing: press/release 50 times
        for (var i = 0; i < 50; i++) { e.OnReport(R(B(PadLinkButton.Square))); e.OnReport(R()); }
        var presses = pad.Seen.Skip(1).Select(s => s.Has(XboxButtons.X)).ToList();
        Assert.Equal(100, presses.Count);
        for (var i = 0; i < presses.Count; i++) Assert.Equal(i % 2 == 0, presses[i]); // no press lost or merged
        Assert.Equal(100, e.ReportsForwarded);
    }

    [Fact]
    public void FoundTwiceDoesNotPlugASecondController()
    {
        var pad = new RecordingPad();
        var e = new BridgeEngine(pad);
        e.OnPadLinkFound("PadLink");
        e.OnPadLinkFound("PadLink");
        Assert.Equal(1, pad.Connects);
        e.OnPadLinkLost(); e.OnPadLinkLost();
        Assert.Equal(1, pad.Disconnects);
    }

    [Fact]
    public void ReportsFromManyThreadsNeverCorruptTheState()
    {
        var pad = new RecordingPad();
        var e = new BridgeEngine(pad);
        e.OnPadLinkFound("PadLink");
        Parallel.For(0, 2000, i => e.OnReport(R(i % 2 == 0 ? B(PadLinkButton.Cross) : 0, lx: i % 127)));
        e.OnReport(R());
        Assert.Equal(XboxState.Neutral, pad.Last);
        Assert.Equal(2001, e.ReportsForwarded);
    }

    [Fact]
    public void JunkReportsNeverPressAnything()
    {
        var pad = new RecordingPad();
        var e = new BridgeEngine(pad);
        e.OnPadLinkFound("PadLink");
        e.OnReport(new byte[] { 2, 0xFF, 0xFF, 1, 2, 3, 4 });
        e.OnReport(new byte[] { 1, 0xFF });
        Assert.Single(pad.Seen); // only the centred state from plugging in
    }
}
