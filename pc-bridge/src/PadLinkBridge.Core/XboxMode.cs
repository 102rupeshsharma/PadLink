namespace PadLinkBridge.Core;

/// <summary>What the window shows.</summary>
public enum XboxModeState
{
    /// <summary>Stopped: PadLink is a normal controller (PCSX2, emulators).</summary>
    Off,
    /// <summary>The Xbox controller driver (ViGEmBus) is not installed yet.</summary>
    DriverMissing,
    /// <summary>Xbox mode on, no phone connected yet.</summary>
    WaitingForPadLink,
    /// <summary>Xbox mode on: games see one Xbox controller per connected phone.</summary>
    On,
}

/// <summary>
/// Xbox mode as a simple switch. Open PadLink Bridge → <see cref="Start"/>; close it → <see cref="Stop"/>
/// (back to the normal controller). Every connected phone gets its own Xbox controller (<see cref="PadHub"/>).
/// Stop releases every button and stick of every player, then unplugs all Xbox controllers, and nothing can
/// plug them back in afterwards.
/// </summary>
public sealed class XboxMode(Func<IVirtualPad> createPad, Func<PadHub, IDisposable> startReading, Func<bool>? driverInstalled = null)
{
    private readonly object _lock = new();
    private IDisposable? _reader;
    private bool _driverMissing;

    /// <summary>The running hub (null when off).</summary>
    public PadHub? Hub { get; private set; }

    /// <summary>Raised on start/stop and whenever a phone connects, disconnects or presses something (any thread).</summary>
    public event Action? Changed;

    public XboxModeState State
    {
        get
        {
            lock (_lock)
            {
                if (Hub == null) return _driverMissing ? XboxModeState.DriverMissing : XboxModeState.Off;
                return Hub.Count > 0 ? XboxModeState.On : XboxModeState.WaitingForPadLink;
            }
        }
    }

    public bool IsRunning { get { lock (_lock) return Hub != null; } }

    private bool DriverInstalled()
    {
        if (driverInstalled != null) return driverInstalled();
        try { (createPad() as IDisposable)?.Dispose(); return true; } catch { return false; }
    }

    /// <summary>Turns Xbox mode on. False when the driver is missing (then <see cref="State"/> is DriverMissing).</summary>
    public bool Start()
    {
        lock (_lock)
        {
            if (Hub != null) return true;
            _driverMissing = !DriverInstalled();
            if (!_driverMissing)
            {
                var hub = new PadHub(createPad);
                hub.Changed += () => Changed?.Invoke();
                Hub = hub;
                try { _reader = startReading(hub); }
                catch { hub.CloseAll(); Hub = null; throw; }
            }
        }
        Changed?.Invoke();
        return !_driverMissing;
    }

    /// <summary>Turns Xbox mode off: stop reading, release everything, unplug every controller. Safe to call twice.</summary>
    public void Stop()
    {
        lock (_lock)
        {
            if (Hub == null) return;
            try { _reader?.Dispose(); } catch { }
            _reader = null;
            Hub.CloseAll();
            Hub = null;
        }
        Changed?.Invoke();
    }
}
