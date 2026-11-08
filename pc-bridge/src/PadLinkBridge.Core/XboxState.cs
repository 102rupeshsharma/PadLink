namespace PadLinkBridge.Core;

/// <summary>Xbox 360 controller buttons (same bit values as XInput's XINPUT_GAMEPAD_*).</summary>
[Flags]
public enum XboxButtons : ushort
{
    None = 0,
    DpadUp = 0x0001, DpadDown = 0x0002, DpadLeft = 0x0004, DpadRight = 0x0008,
    Start = 0x0010, Back = 0x0020, LeftThumb = 0x0040, RightThumb = 0x0080,
    LeftShoulder = 0x0100, RightShoulder = 0x0200,
    A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000,
}

/// <summary>A complete Xbox 360 controller state, as games see it through XInput.</summary>
public readonly record struct XboxState(
    XboxButtons Buttons, byte LeftTrigger, byte RightTrigger,
    short LeftX, short LeftY, short RightX, short RightY)
{
    public static readonly XboxState Neutral = new(XboxButtons.None, 0, 0, 0, 0, 0, 0);
    public bool Has(XboxButtons b) => (Buttons & b) == b;
}

/// <summary>
/// PadLink → Xbox 360 controller. Positions match the face buttons: Cross/A bottom, Circle/B right,
/// Square/X left, Triangle/Y top. L2/R2 are digital on PadLink, so they become fully pressed triggers.
/// Stick Y is flipped: PadLink (HID) uses + = down, XInput uses + = up.
/// </summary>
public static class XboxMapper
{
    private static readonly (PadLinkButton From, XboxButtons To)[] ButtonMap =
    {
        (PadLinkButton.Cross, XboxButtons.A), (PadLinkButton.Circle, XboxButtons.B),
        (PadLinkButton.Square, XboxButtons.X), (PadLinkButton.Triangle, XboxButtons.Y),
        (PadLinkButton.L1, XboxButtons.LeftShoulder), (PadLinkButton.R1, XboxButtons.RightShoulder),
        (PadLinkButton.Select, XboxButtons.Back), (PadLinkButton.Start, XboxButtons.Start),
        (PadLinkButton.L3, XboxButtons.LeftThumb), (PadLinkButton.R3, XboxButtons.RightThumb),
    };

    public static XboxState Map(PadLinkReport r)
    {
        var b = XboxButtons.None;
        foreach (var (from, to) in ButtonMap) if (r.IsPressed(from)) b |= to;
        b |= Hat(r.Hat);
        return new XboxState(
            b,
            LeftTrigger: r.IsPressed(PadLinkButton.L2) ? (byte)255 : (byte)0,
            RightTrigger: r.IsPressed(PadLinkButton.R2) ? (byte)255 : (byte)0,
            LeftX: Axis(r.LeftX), LeftY: Axis(-r.LeftY),
            RightX: Axis(r.RightX), RightY: Axis(-r.RightY));
    }

    /// <summary>Hat switch (0 = up, clockwise in 45° steps, 8 = released) → D-pad bits.</summary>
    public static XboxButtons Hat(int hat) => hat switch
    {
        0 => XboxButtons.DpadUp,
        1 => XboxButtons.DpadUp | XboxButtons.DpadRight,
        2 => XboxButtons.DpadRight,
        3 => XboxButtons.DpadDown | XboxButtons.DpadRight,
        4 => XboxButtons.DpadDown,
        5 => XboxButtons.DpadDown | XboxButtons.DpadLeft,
        6 => XboxButtons.DpadLeft,
        7 => XboxButtons.DpadUp | XboxButtons.DpadLeft,
        _ => XboxButtons.None,
    };

    /// <summary>-127..127 → -32767..32767 (full range, 0 stays exactly 0).</summary>
    public static short Axis(int v)
    {
        var c = Math.Clamp(v, -127, 127);
        return (short)Math.Clamp(c * 32767 / 127, -32767, 32767);
    }
}
