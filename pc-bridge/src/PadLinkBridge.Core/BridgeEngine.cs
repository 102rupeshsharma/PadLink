namespace PadLinkBridge.Core;

/// <summary>The virtual Xbox 360 controller games see (ViGEmBus in the app, a recorder in tests).</summary>
public interface IVirtualPad
{
    void Connect();
    void Submit(XboxState state);
    void Disconnect();
}

/// <summary>Bridge status, shown in the tray and the setup window.</summary>
public enum BridgeStatus { WaitingForPadLink, Active }

/// <summary>
/// The bridge itself: PadLink reports in, Xbox controller states out.
///  - PadLink found → the virtual Xbox controller is plugged in (centred).
///  - Every PadLink report → the matching Xbox state, at once (no queue, no timer).
///  - PadLink lost (phone disconnected, Bluetooth off, app closed) → everything is released first,
///    then the virtual controller is unplugged, so nothing can stay pressed in the game.
/// Thread-safe: reports come from the HID read thread, device changes from the scan thread.
/// </summary>
public sealed class BridgeEngine
{
    private readonly IVirtualPad _pad;
    private readonly object _lock = new();
    private XboxState _last = XboxState.Neutral;

    public BridgeEngine(IVirtualPad pad) => _pad = pad;

    public BridgeStatus Status { get; private set; } = BridgeStatus.WaitingForPadLink;
    /// <summary>Name of the connected PadLink (for the window), null when waiting.</summary>
    public string? DeviceName { get; private set; }
    public XboxState Current { get { lock (_lock) return _last; } }
    public long ReportsForwarded { get; private set; }
    public event Action? Changed;

    private bool _closed;

    /// <summary>Xbox mode switched off: release and unplug, and never plug in again (late scans are ignored).</summary>
    public void Close()
    {
        lock (_lock) _closed = true;
        OnPadLinkLost();
    }

    public void OnPadLinkFound(string? name)
    {
        lock (_lock)
        {
            if (_closed) return;
            if (Status == BridgeStatus.Active) { DeviceName = name; return; }
            _pad.Connect();
            _last = XboxState.Neutral;
            _pad.Submit(_last);
            Status = BridgeStatus.Active;
            DeviceName = name;
        }
        Changed?.Invoke();
    }

    /// <summary>One raw report from PadLink (report ID first). Unknown reports are ignored.</summary>
    public void OnReport(ReadOnlySpan<byte> data)
    {
        var r = PadLinkReport.Parse(data);
        if (r == null) return;
        var s = XboxMapper.Map(r.Value);
        bool changed;
        lock (_lock)
        {
            if (Status != BridgeStatus.Active) return;
            changed = s != _last;
            _last = s;
            _pad.Submit(s); // always submit: the phone already sends only real changes
            ReportsForwarded++;
        }
        if (changed) Changed?.Invoke();
    }

    public void OnPadLinkLost()
    {
        lock (_lock)
        {
            if (Status != BridgeStatus.Active) return;
            _last = XboxState.Neutral;
            _pad.Submit(_last);  // release everything first...
            _pad.Disconnect();   // ...then unplug
            Status = BridgeStatus.WaitingForPadLink;
            DeviceName = null;
        }
        Changed?.Invoke();
    }
}
