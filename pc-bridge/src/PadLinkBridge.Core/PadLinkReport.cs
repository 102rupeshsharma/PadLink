namespace PadLinkBridge.Core;

/// <summary>
/// One PadLink input report, exactly as the phone sends it (see the Android app's HidDescriptor.kt):
/// Report ID 1, then 6 bytes:
///   byte 0: buttons 1-8 (bit 0 = button 1)
///   byte 1: buttons 9-12 (low nibble), hat switch / D-pad (high nibble: 0 = up, clockwise, 8 = released)
///   bytes 2-5: X, Y (left stick), Z, Rz (right stick), signed -127..127, Y positive = down
/// Windows hands it to us with the report ID in front, so a read is 7 bytes.
/// </summary>
public readonly record struct PadLinkReport(int Buttons, int Hat, sbyte LeftX, sbyte LeftY, sbyte RightX, sbyte RightY)
{
    public const byte ReportId = 1;
    /// <summary>Report length with the leading report ID, as Windows delivers it.</summary>
    public const int LengthWithId = 7;
    public const int HatReleased = 8;

    public static readonly PadLinkReport Neutral = new(0, HatReleased, 0, 0, 0, 0);

    public bool IsPressed(PadLinkButton b) => (Buttons & (1 << ((int)b - 1))) != 0;

    /// <summary>
    /// Parses a report read from Windows (report ID first). Returns null for anything that is not a
    /// PadLink gamepad report (wrong ID or length), so a stray report can never press buttons.
    /// </summary>
    public static PadLinkReport? Parse(ReadOnlySpan<byte> data)
    {
        if (data.Length < LengthWithId || data[0] != ReportId) return null;
        int b0 = data[1], b1 = data[2];
        int hat = (b1 >> 4) & 0x0F;
        if (hat > 8) hat = HatReleased; // out of range = released (the descriptor declares a null state)
        return new PadLinkReport(
            Buttons: b0 | ((b1 & 0x0F) << 8),
            Hat: hat,
            LeftX: (sbyte)data[3], LeftY: (sbyte)data[4], RightX: (sbyte)data[5], RightY: (sbyte)data[6]);
    }

    /// <summary>Builds the bytes the phone would send (tests and the self-test).</summary>
    public byte[] ToBytes() => new byte[]
    {
        ReportId, (byte)(Buttons & 0xFF), (byte)(((Buttons >> 8) & 0x0F) | ((Hat & 0x0F) << 4)),
        (byte)LeftX, (byte)LeftY, (byte)RightX, (byte)RightY,
    };
}

/// <summary>PadLink's 12 buttons, by HID button number (same numbers as in the Android app).</summary>
public enum PadLinkButton
{
    Square = 1, Cross = 2, Circle = 3, Triangle = 4,
    L1 = 5, R1 = 6, L2 = 7, R2 = 8,
    Select = 9, Start = 10, L3 = 11, R3 = 12,
}
