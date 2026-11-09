using PadLinkBridge.Core;
using Xunit;

namespace PadLinkBridge.Tests;

/// <summary>
/// Two phones on one PC, Xbox mode: a Mortal Kombat match. Each phone must drive only its own Xbox
/// controller, at the same time, and one phone dropping out must not touch the other player.
/// </summary>
public class TwoPlayerTests
{
    private readonly Dictionary<int, FakeXboxPad> _byOrder = new();
    private int _created;
    private readonly PadHub _hub;
    private readonly FakePhone _p1 = new(@"\\?\hid#poco", "PadLink Gamepad");
    private readonly FakePhone _p2 = new(@"\\?\hid#galaxy", "PadLink Gamepad P2");

    public TwoPlayerTests()
    {
        _hub = new PadHub(() => { var p = new FakeXboxPad(); _byOrder[_created++] = p; return p; });
        _p1.Hub = _hub;
        _p2.Hub = _hub;
    }

    private FakeXboxPad? _p1Pad, _p2Pad;

    private void ConnectBoth()
    {
        _p1.Connect(); _p1Pad = _byOrder[_created - 1];
        _p2.Connect(); _p2Pad = _byOrder[_created - 1];
    }

    [Theory]
    [InlineData("PadLink Gamepad", 1)]
    [InlineData("PadLink Gamepad P2", 2)]
    [InlineData("PadLink Gamepad P4", 4)]
    [InlineData("PadLink Gamepad p3 ", 3)]
    [InlineData("PS2 Gamepad", 1)]
    [InlineData("Wireless Controller", null)]
    [InlineData("Some P2 Thing", null)]
    [InlineData(null, null)]
    public void PlayerNumberComesFromThePhonesName(string? name, int? player) =>
        Assert.Equal(player, PadHub.PlayerFromName(name));

    [Fact]
    public void EachPhoneGetsItsOwnXboxController()
    {
        ConnectBoth();
        Assert.Equal(2, _hub.Count);
        Assert.Equal([1, 2], _hub.Players.Select(p => p.Player));
        Assert.True(_p1Pad!.PluggedIn);
        Assert.True(_p2Pad!.PluggedIn);
        Assert.NotSame(_p1Pad, _p2Pad);
    }

    [Fact]
    public void MortalKombatRound_BothPlayersAtTheSameMoment()
    {
        ConnectBoth();
        // P1 walks forward (right) and punches (□ → X); P2 blocks (R1 → RB) and crouches (down)
        _p1.Send(new PadLinkReport(1 << ((int)PadLinkButton.Square - 1), PadLinkReport.HatReleased, 127, 0, 0, 0));
        _p2.Send(new PadLinkReport(1 << ((int)PadLinkButton.R1 - 1), 4 /* hat down */, 0, 0, 0, 0));

        Assert.True(_p1Pad!.Seen.Has(XboxButtons.X));
        Assert.Equal(32767, _p1Pad.Seen.LeftX);
        Assert.False(_p1Pad.Seen.Has(XboxButtons.RightShoulder)); // P2's block never reaches P1
        Assert.False(_p1Pad.Seen.Has(XboxButtons.DpadDown));

        Assert.True(_p2Pad!.Seen.Has(XboxButtons.RightShoulder));
        Assert.True(_p2Pad.Seen.Has(XboxButtons.DpadDown));
        Assert.False(_p2Pad.Seen.Has(XboxButtons.X));             // P1's punch never reaches P2
        Assert.Equal(0, _p2Pad.Seen.LeftX);

        // P1 lets go, P2 still holds block
        _p1.Release();
        Assert.Equal(XboxState.Neutral, _p1Pad.Seen);
        Assert.True(_p2Pad.Seen.Has(XboxButtons.RightShoulder));
    }

    [Fact]
    public async Task BothPhonesMashingOnTheirOwnThreadsNeverMixUp()
    {
        ConnectBoth();
        var p1Buttons = new[] { PadLinkButton.Square, PadLinkButton.Cross };      // X, A
        var p2Buttons = new[] { PadLinkButton.Triangle, PadLinkButton.Circle };   // Y, B
        var mixups = 0;
        var t1 = Task.Run(() =>
        {
            for (var i = 0; i < 5000; i++)
            {
                _p1.Press(p1Buttons[i % 2]);
                var s = _p1Pad!.Seen;
                if (s.Has(XboxButtons.Y) || s.Has(XboxButtons.B)) Interlocked.Increment(ref mixups);
            }
        });
        var t2 = Task.Run(() =>
        {
            for (var i = 0; i < 5000; i++)
            {
                _p2.Press(p2Buttons[i % 2]);
                var s = _p2Pad!.Seen;
                if (s.Has(XboxButtons.X) || s.Has(XboxButtons.A)) Interlocked.Increment(ref mixups);
            }
        });
        await Task.WhenAll(t1, t2);
        Assert.Equal(0, mixups);
        Assert.Equal(2, _hub.Count); // both still connected
    }

    [Fact]
    public void Player2DropsOut_Player1KeepsPlaying()
    {
        ConnectBoth();
        _p1.Press(PadLinkButton.L1);
        _p2.Press(PadLinkButton.Cross);
        _p2.Disconnect(); // P2's phone battery died

        Assert.Equal(XboxState.Neutral, _p2Pad!.Seen); // released...
        Assert.False(_p2Pad.PluggedIn);                // ...and unplugged
        Assert.True(_p1Pad!.PluggedIn);                // P1 untouched
        Assert.True(_p1Pad.Seen.Has(XboxButtons.LeftShoulder));
        Assert.Equal([1], _hub.Players.Select(p => p.Player));

        _p2.Connect(); // P2 back
        Assert.Equal([1, 2], _hub.Players.Select(p => p.Player));
    }

    [Fact]
    public void Player2ConnectingFirstIsStillPlayer2()
    {
        _p2.Connect();
        _p1.Connect();
        Assert.Equal(2, _hub.Players.Single(p => p.Id == _p2.Id).Player);
        Assert.Equal(1, _hub.Players.Single(p => p.Id == _p1.Id).Player);
    }

    [Fact]
    public void TwoPhonesBothLeftOnP1_TheSecondGetsP2()
    {
        var other = new FakePhone(@"\\?\hid#friend", "PadLink Gamepad") { Hub = _hub };
        _p1.Connect();
        other.Connect();
        Assert.Equal([1, 2], _hub.Players.Select(p => p.Player));
    }

    [Fact]
    public void AtMostFourPlayers()
    {
        for (var i = 0; i < 6; i++) _hub.OnDeviceFound($"phone{i}", "PadLink Gamepad");
        Assert.Equal(4, _hub.Count);
        Assert.Equal([1, 2, 3, 4], _hub.Players.Select(p => p.Player));
    }

    [Fact]
    public void SamePhoneFoundTwiceIsOneController()
    {
        _p1.Connect();
        _p1.Connect();
        Assert.Equal(1, _hub.Count);
        Assert.Equal(1, _created);
    }

    [Fact]
    public void CloseAllReleasesAndUnplugsEveryPlayer()
    {
        ConnectBoth();
        _p1.Press(PadLinkButton.R2);
        _p2.Press(PadLinkButton.L2);
        _hub.CloseAll();
        foreach (var pad in new[] { _p1Pad!, _p2Pad! })
        {
            Assert.Equal(XboxState.Neutral, pad.Seen);
            Assert.False(pad.PluggedIn);
            Assert.True(pad.Disposed);
        }
        Assert.Equal(0, _hub.Count);
        Assert.False(_hub.OnDeviceFound("late", "PadLink Gamepad P2"));
    }

    [Fact]
    public void BothPhonesAreFoundOnThePc_InPlayerOrder()
    {
        const string guid = "#{4d1e55b2-f16f-11cf-88cb-001111000030}";
        var list = new List<HidCandidate>
        {
            new(@"\\?\hid#galaxy" + guid, "PadLink Gamepad P2", 0x00E0, 0, true, 7),
            new(@"\\?\hid#mouse" + guid, "USB Mouse", 0x046D, 0xC077, false, 8),
            new(@"\\?\hid#xbox" + guid, "Controller (XBOX 360 For Windows)", 0x045E, 0x028E, true, 15),
            new(@"\\?\hid#poco" + guid, "PadLink Gamepad", 0x00E0, 0, true, 7),
        };
        var picked = DeviceMatcher.PickAll(list);
        Assert.Equal(["PadLink Gamepad", "PadLink Gamepad P2"], picked.Select(p => p.ProductName));
    }

    [Fact]
    public void OnePhoneStillFoundTheOldWay()
    {
        var list = new List<HidCandidate> { new(@"\\?\hid#renamed", "Galaxy A54", 0x00E0, 0, true, 7) };
        Assert.Single(DeviceMatcher.PickAll(list)); // shape match fallback, as before
        Assert.Empty(DeviceMatcher.PickAll([new(@"\\?\hid#kb", "Keyboard", 1, 2, false, 9)]));
    }
}
