using PadLinkBridge.Core;
using Xunit;

namespace PadLinkBridge.Tests;

/// <summary>PadLink's reports, decoded exactly like the phone encodes them (HidReportManager.kt).</summary>
public class ReportTests
{
    [Fact]
    public void ParsesEveryButtonHatAndAxis()
    {
        // Cross (2) + Start (10), hat 2 (right), sticks
        var bytes = new byte[] { 1, 0b0000_0010, (byte)(0b0010 | (2 << 4)), 127, unchecked((byte)-127), 10, unchecked((byte)-20) };
        var r = PadLinkReport.Parse(bytes)!.Value;
        Assert.True(r.IsPressed(PadLinkButton.Cross));
        Assert.True(r.IsPressed(PadLinkButton.Start));
        Assert.False(r.IsPressed(PadLinkButton.Square));
        Assert.Equal(2, r.Hat);
        Assert.Equal((sbyte)127, r.LeftX); Assert.Equal((sbyte)-127, r.LeftY);
        Assert.Equal((sbyte)10, r.RightX); Assert.Equal((sbyte)-20, r.RightY);
    }

    [Fact]
    public void RoundTripsAllTwelveButtons()
    {
        foreach (var b in Enum.GetValues<PadLinkButton>())
        {
            var r = new PadLinkReport(1 << ((int)b - 1), PadLinkReport.HatReleased, 0, 0, 0, 0);
            var back = PadLinkReport.Parse(r.ToBytes())!.Value;
            Assert.True(back.IsPressed(b), b.ToString());
            Assert.Equal(r, back);
        }
    }

    [Theory]
    [InlineData(new byte[] { })]
    [InlineData(new byte[] { 1, 0, 0x80, 0, 0, 0 })]           // too short
    [InlineData(new byte[] { 2, 0xFF, 0x80, 0, 0, 0, 0 })]     // another report ID
    public void RejectsAnythingThatIsNotAPadLinkReport(byte[] data) => Assert.Null(PadLinkReport.Parse(data));

    [Fact]
    public void NeutralReportIsNeutral()
    {
        var r = PadLinkReport.Parse(new byte[] { 1, 0, 0x80, 0, 0, 0, 0 })!.Value;
        Assert.Equal(PadLinkReport.Neutral, r);
    }
}

public class MapperTests
{
    private static XboxState Press(PadLinkButton b) => XboxMapper.Map(new PadLinkReport(1 << ((int)b - 1), 8, 0, 0, 0, 0));

    [Theory]
    [InlineData(PadLinkButton.Cross, XboxButtons.A)]
    [InlineData(PadLinkButton.Circle, XboxButtons.B)]
    [InlineData(PadLinkButton.Square, XboxButtons.X)]
    [InlineData(PadLinkButton.Triangle, XboxButtons.Y)]
    [InlineData(PadLinkButton.L1, XboxButtons.LeftShoulder)]
    [InlineData(PadLinkButton.R1, XboxButtons.RightShoulder)]
    [InlineData(PadLinkButton.Select, XboxButtons.Back)]
    [InlineData(PadLinkButton.Start, XboxButtons.Start)]
    [InlineData(PadLinkButton.L3, XboxButtons.LeftThumb)]
    [InlineData(PadLinkButton.R3, XboxButtons.RightThumb)]
    public void EachButtonPressesExactlyItsXboxButton(PadLinkButton from, XboxButtons to)
    {
        var s = Press(from);
        Assert.Equal(to, s.Buttons);
        Assert.Equal(0, s.LeftTrigger); Assert.Equal(0, s.RightTrigger);
    }

    [Fact]
    public void L2AndR2AreFullTriggers()
    {
        Assert.Equal(new XboxState(XboxButtons.None, 255, 0, 0, 0, 0, 0), Press(PadLinkButton.L2));
        Assert.Equal(new XboxState(XboxButtons.None, 0, 255, 0, 0, 0, 0), Press(PadLinkButton.R2));
    }

    [Fact]
    public void AllEightDpadDirectionsAndReleased()
    {
        var expected = new[]
        {
            XboxButtons.DpadUp, XboxButtons.DpadUp | XboxButtons.DpadRight, XboxButtons.DpadRight,
            XboxButtons.DpadDown | XboxButtons.DpadRight, XboxButtons.DpadDown, XboxButtons.DpadDown | XboxButtons.DpadLeft,
            XboxButtons.DpadLeft, XboxButtons.DpadUp | XboxButtons.DpadLeft, XboxButtons.None,
        };
        for (var hat = 0; hat <= 8; hat++)
            Assert.Equal(expected[hat], XboxMapper.Map(new PadLinkReport(0, hat, 0, 0, 0, 0)).Buttons);
    }

    [Fact]
    public void SticksUseTheFullXboxRangeAndUpIsUp()
    {
        // PadLink: left stick fully up-right (y negative = up), right stick fully down-left
        var s = XboxMapper.Map(new PadLinkReport(0, 8, 127, -127, -127, 127));
        Assert.Equal(32767, s.LeftX); Assert.Equal(32767, s.LeftY);    // XInput: + = up
        Assert.Equal(-32767, s.RightX); Assert.Equal(-32767, s.RightY);
        var c = XboxMapper.Map(PadLinkReport.Neutral);
        Assert.Equal(XboxState.Neutral, c);
    }

    [Fact]
    public void AxisIsMonotonicAndSymmetric()
    {
        short prev = short.MinValue;
        for (var v = -127; v <= 127; v++)
        {
            var a = XboxMapper.Axis(v);
            Assert.True(a > prev, $"{v}"); prev = a;
            Assert.Equal(-XboxMapper.Axis(-v), a);
        }
        Assert.Equal(0, XboxMapper.Axis(0));
        Assert.Equal(32767, XboxMapper.Axis(-128 * -1)); // out of range clamps
    }

    [Fact]
    public void EverythingAtOnce()
    {
        var all = (1 << 12) - 1;
        var s = XboxMapper.Map(new PadLinkReport(all, 0, 0, 0, 0, 0));
        var expected = XboxButtons.A | XboxButtons.B | XboxButtons.X | XboxButtons.Y | XboxButtons.LeftShoulder |
            XboxButtons.RightShoulder | XboxButtons.Back | XboxButtons.Start | XboxButtons.LeftThumb | XboxButtons.RightThumb | XboxButtons.DpadUp;
        Assert.Equal(expected, s.Buttons);
        Assert.Equal(255, s.LeftTrigger); Assert.Equal(255, s.RightTrigger);
    }
}

public class DeviceMatcherTests
{
    private static HidCandidate C(string name, bool gamepad = true, int len = 7, int vid = 0x0002, string path = "p") =>
        new(path, name, vid, 0x1234, gamepad, len);

    [Fact]
    public void FindsPadLinkByNameAndShape()
    {
        var picked = DeviceMatcher.Pick(new[]
        {
            C("Logitech USB Receiver", gamepad: false, len: 9, path: "a"),
            C("Controller (XBOX 360 For Windows)", vid: DeviceMatcher.MicrosoftVendorId, len: 16, path: "b"),
            C("PadLink", path: "c"),
        });
        Assert.Equal("c", picked?.DevicePath);
    }

    [Fact]
    public void OlderAppNameStillWorks() => Assert.True(DeviceMatcher.Score(C("PS2 Gamepad")) > 0);

    [Fact]
    public void RenamedPhoneIsFoundByReportShape()
    {
        Assert.Equal("x", DeviceMatcher.Pick(new[] { C("Rahul's POCO F6", path: "x") })?.DevicePath);
    }

    [Fact]
    public void NameBeatsShapeOnly()
    {
        var picked = DeviceMatcher.Pick(new[] { C("Some 7-byte pad", path: "a"), C("PadLink", path: "z") });
        Assert.Equal("z", picked?.DevicePath);
    }

    [Fact]
    public void NeverPicksOurOwnVirtualXboxControllerOrNonGamepads()
    {
        Assert.Equal(0, DeviceMatcher.Score(C("PadLink", vid: DeviceMatcher.MicrosoftVendorId)));
        Assert.Equal(0, DeviceMatcher.Score(C("PadLink keyboard", gamepad: false)));
        Assert.Null(DeviceMatcher.Pick(new[] { C("DualSense", len: 64) }));
    }

    [Fact]
    public void InstanceIdForHidHide()
    {
        var path = @"\\?\hid#{00001124-0000-1000-8000-00805f9b34fb}_vid&0002000a_pid&0001#9&2bbf3ea4&0&0000#{4d1e55b2-f16f-11cf-88cb-001111000030}";
        Assert.Equal(@"HID\{00001124-0000-1000-8000-00805F9B34FB}_VID&0002000A_PID&0001\9&2BBF3EA4&0&0000", DeviceMatcher.InstanceIdFromPath(path));
        Assert.Null(DeviceMatcher.InstanceIdFromPath("garbage"));
    }

    /// <summary>The real PadLink as Windows 11 listed it on the developer laptop (self-test output).</summary>
    [Fact]
    public void RealPadLinkFromThisLaptopIsPickedAndHidHideIdIsRight()
    {
        const string path = @"\\?\hid#{00001124-0000-1000-8000-00805f9b34fb}_vid&000100e0_pid&0000&col01#8&29c5e746&3&0000#{4d1e55b2-f16f-11cf-88cb-001111000030}";
        var list = new List<HidCandidate>
        {
            new(@"\\?\hid#vid_046d&pid_c52b&mi_01&col01#7&1&0000#{4d1e55b2-f16f-11cf-88cb-001111000030}", "USB Receiver", 0x046D, 0xC52B, false, 8),
            new(@"\\?\hid#vid_045e&pid_028e#1&2&0000#{4d1e55b2-f16f-11cf-88cb-001111000030}", "Controller (XBOX 360 For Windows)", 0x045E, 0x028E, true, 15),
            new(path, "PadLink Gamepad", 0x00E0, 0x0000, true, 7),
        };
        Assert.Equal(path, DeviceMatcher.Pick(list)!.DevicePath);
        Assert.Equal(@"HID\{00001124-0000-1000-8000-00805F9B34FB}_VID&000100E0_PID&0000&COL01\8&29C5E746&3&0000", DeviceMatcher.InstanceIdFromPath(path));
    }
}
