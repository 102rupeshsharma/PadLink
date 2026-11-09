namespace PadLinkBridge.Core;

/// <summary>What the bridge knows about one HID device found on the PC.</summary>
public sealed record HidCandidate(
    string DevicePath,
    string? ProductName,
    int VendorId,
    int ProductId,
    /// <summary>True when the device's top-level collection is Generic Desktop / Game Pad.</summary>
    bool IsGamepad,
    /// <summary>Longest input report including the report ID (PadLink: 7).</summary>
    int MaxInputReportLength);

/// <summary>
/// Picks PadLink among all HID devices. Windows names a Bluetooth gamepad after the phone's
/// Bluetooth name ("PadLink", or "PS2 Gamepad" from older app versions), but the phone's vendor/product
/// ID is the phone maker's, so the name is the strong signal. A gamepad with PadLink's exact report
/// shape (Report ID 1 + 6 bytes) is accepted as a fallback, e.g. when the phone was renamed.
/// Xbox / Microsoft devices are never picked (that would include our own virtual controller).
/// </summary>
public static class DeviceMatcher
{
    public const int MicrosoftVendorId = 0x045E;
    private static readonly string[] Names = { "padlink", "ps2 gamepad" };

    public static bool NameLooksLikePadLink(string? name) =>
        name != null && Names.Any(n => name.Contains(n, StringComparison.OrdinalIgnoreCase));

    /// <summary>0 = not PadLink; higher = more certain.</summary>
    public static int Score(HidCandidate c)
    {
        if (c.VendorId == MicrosoftVendorId) return 0;
        if (!c.IsGamepad) return 0;
        var shape = c.MaxInputReportLength == PadLinkReport.LengthWithId;
        var name = NameLooksLikePadLink(c.ProductName);
        return (shape ? 1 : 0) + (name ? 2 : 0) + (shape && name ? 1 : 0);
    }

    /// <summary>The most likely PadLink, or null. Shape alone (score 1) is enough when nothing better exists.</summary>
    public static HidCandidate? Pick(IEnumerable<HidCandidate> all) =>
        all.Select(c => (c, s: Score(c))).Where(x => x.s > 0)
            .OrderByDescending(x => x.s).ThenBy(x => x.c.DevicePath, StringComparer.Ordinal)
            .Select(x => x.c).FirstOrDefault();

    /// <summary>
    /// Every PadLink (two or more phones): all devices named like PadLink, by player number.
    /// When none is named like PadLink, the single best shape match (as <see cref="Pick"/>).
    /// </summary>
    public static List<HidCandidate> PickAll(IEnumerable<HidCandidate> all)
    {
        var list = all.ToList();
        var named = list.Where(c => Score(c) >= 2)
            .OrderBy(c => PadHub.PlayerFromName(c.ProductName) ?? 9).ThenBy(c => c.DevicePath, StringComparer.Ordinal)
            .ToList();
        if (named.Count > 0) return named;
        var best = Pick(list);
        return best == null ? [] : [best];
    }

    /// <summary>
    /// Windows device interface path → device instance ID (what HidHide needs to hide a device), e.g.
    /// \\?\hid#{0000...}_vid&amp;0002...#9&amp;2bbf...&amp;0&amp;0000#{4d1e55b2-...} → HID\{0000...}_VID&amp;0002...\9&amp;2BBF...&amp;0&amp;0000
    /// </summary>
    public static string? InstanceIdFromPath(string path)
    {
        var p = path.StartsWith(@"\\?\", StringComparison.Ordinal) ? path[4..] : path;
        var lastGuid = p.LastIndexOf("#{", StringComparison.Ordinal);
        if (lastGuid <= 0) return null;
        var parts = p[..lastGuid].Split('#');
        if (parts.Length < 3 || parts.Any(string.IsNullOrEmpty)) return null;
        return string.Join('\\', parts).ToUpperInvariant();
    }
}
