using System.Text.RegularExpressions;

namespace PadLinkBridge.Core;

/// <summary>One connected phone as the window shows it.</summary>
public sealed record PlayerView(string Id, int Player, string Name, int? XboxSlot, XboxState Current);

/// <summary>
/// Two (up to four) phones on one PC: every connected PadLink gets its own Xbox controller.
/// The player number comes from the phone ("PadLink Gamepad P2" → player 2, plain "PadLink Gamepad" →
/// player 1); a phone without a free number takes the lowest free one. Each phone has its own
/// <see cref="BridgeEngine"/>, so player 1's buttons can never reach player 2's controller.
/// Thread-safe: every phone is read on its own thread.
/// </summary>
public sealed partial class PadHub(Func<IVirtualPad> createPad)
{
    public const int MaxPlayers = 4;

    private sealed record Entry(BridgeEngine Engine, IVirtualPad Pad, int Player, string Name);

    private readonly object _lock = new();
    private readonly Dictionary<string, Entry> _players = new();
    private bool _closed;

    /// <summary>A phone connected or disconnected, or a button/stick changed (any thread).</summary>
    public event Action? Changed;

    public int Count { get { lock (_lock) return _players.Count; } }

    /// <summary>Connected phones, by player number.</summary>
    public IReadOnlyList<PlayerView> Players
    {
        get
        {
            lock (_lock)
                return _players.OrderBy(p => p.Value.Player)
                    .Select(p => new PlayerView(p.Key, p.Value.Player, p.Value.Name, p.Value.Pad.XboxSlot, p.Value.Engine.Current))
                    .ToList();
        }
    }

    [GeneratedRegex(@"\bP([1-4])\s*$", RegexOptions.IgnoreCase)]
    private static partial Regex NumberedName();

    /// <summary>"PadLink Gamepad P2" → 2, "PadLink Gamepad" / "PS2 Gamepad" → 1, anything else → null.</summary>
    public static int? PlayerFromName(string? name)
    {
        if (name == null) return null;
        var m = NumberedName().Match(name.Trim());
        if (m.Success && DeviceMatcher.NameLooksLikePadLink(name)) return int.Parse(m.Groups[1].Value);
        return DeviceMatcher.NameLooksLikePadLink(name) ? 1 : null;
    }

    /// <summary>A phone appeared: plug in its own Xbox controller. False when full, already known or stopped.</summary>
    public bool OnDeviceFound(string id, string? name)
    {
        lock (_lock)
        {
            if (_closed || _players.ContainsKey(id) || _players.Count >= MaxPlayers) return false;
            var pad = createPad();
            var engine = new BridgeEngine(pad);
            engine.Changed += () => Changed?.Invoke();
            var taken = _players.Values.Select(p => p.Player).ToHashSet();
            var wanted = PlayerFromName(name);
            var player = wanted is int w && !taken.Contains(w) ? w : Enumerable.Range(1, MaxPlayers).First(n => !taken.Contains(n));
            _players[id] = new Entry(engine, pad, player, name ?? "PadLink");
            engine.OnPadLinkFound(name);
        }
        Changed?.Invoke();
        return true;
    }

    /// <summary>One report from phone [id]: goes only to that phone's Xbox controller.</summary>
    public void OnReport(string id, ReadOnlySpan<byte> data)
    {
        BridgeEngine? engine;
        lock (_lock) engine = _players.TryGetValue(id, out var e) ? e.Engine : null;
        engine?.OnReport(data); // a phone removed meanwhile has a closed engine: ignored there
    }

    /// <summary>Phone [id] disconnected: release its buttons, unplug its controller. The others keep playing.</summary>
    public void OnDeviceLost(string id)
    {
        Entry? gone;
        lock (_lock)
        {
            if (!_players.Remove(id, out gone)) return;
        }
        Release(gone);
        Changed?.Invoke();
    }

    /// <summary>Xbox mode off: every controller released and unplugged; nothing can be added afterwards.</summary>
    public void CloseAll()
    {
        List<Entry> all;
        lock (_lock)
        {
            _closed = true;
            all = _players.Values.ToList();
            _players.Clear();
        }
        foreach (var e in all) Release(e);
        if (all.Count > 0) Changed?.Invoke();
    }

    private static void Release(Entry e)
    {
        e.Engine.Close(); // neutral first, then unplug
        try { (e.Pad as IDisposable)?.Dispose(); } catch { }
    }
}
