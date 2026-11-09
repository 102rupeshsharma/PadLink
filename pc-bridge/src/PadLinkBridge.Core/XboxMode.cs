namespace PadLinkBridge.Core;

/// <summary>What the window shows.</summary>
public enum XboxModeState
{
    /// <summary>Stopped: PadLink is a normal controller (PCSX2, emulators).</summary>
    Off,
    /// <summary>The Xbox controller driver (ViGEmBus) is not installed yet.</summary>
    DriverMissing,
    /// <summary>Xbox mode on, waiting for the phone to connect.</summary>
    WaitingForPadLink,
    /// <summary>Xbox mode on: games see an Xbox controller.</summary>
    On,
}

/// <summary>
/// Xbox mode as a simple switch. The app is meant to be used like this:
/// open PadLink Bridge → <see cref="Start"/> (Xbox mode on); close it → <see cref="Stop"/> (back to the normal
/// controller). Stop always releases every button and stick, then unplugs the Xbox controller, and nothing can
/// plug it back in afterwards.
/// </summary>
public sealed class XboxMode(Func<IVirtualPad> createPad, Func<BridgeEngine, IDisposable> startReading)
{
    private readonly object _lock = new();
    private IVirtualPad? _pad;
    private IDisposable? _reader;
    private bool _driverMissing;

    /// <summary>The running bridge (null when off).</summary>
    public BridgeEngine? Engine { get; private set; }

    /// <summary>Raised on start/stop and whenever PadLink connects or disconnects (any thread).</summary>
    public event Action? Changed;

    public XboxModeState State
    {
        get
        {
            lock (_lock)
            {
                if (Engine == null) return _driverMissing ? XboxModeState.DriverMissing : XboxModeState.Off;
                return Engine.Status == BridgeStatus.Active ? XboxModeState.On : XboxModeState.WaitingForPadLink;
            }
        }
    }

    public bool IsRunning { get { lock (_lock) return Engine != null; } }

    /// <summary>Turns Xbox mode on. False when the driver is missing (then <see cref="State"/> is DriverMissing).</summary>
    public bool Start()
    {
        lock (_lock)
        {
            if (Engine != null) return true;
            try { _pad = createPad(); }
            catch { _pad = null; _driverMissing = true; }
            if (_pad == null) { _driverMissing = true; }
            else
            {
                _driverMissing = false;
                var engine = new BridgeEngine(_pad);
                engine.Changed += () => Changed?.Invoke();
                Engine = engine;
                try { _reader = startReading(engine); }
                catch { engine.Close(); Engine = null; (_pad as IDisposable)?.Dispose(); _pad = null; throw; }
            }
        }
        Changed?.Invoke();
        return !_driverMissing;
    }

    /// <summary>Turns Xbox mode off: stop reading, release everything, unplug, free the driver. Safe to call twice.</summary>
    public void Stop()
    {
        lock (_lock)
        {
            if (Engine == null) return;
            try { _reader?.Dispose(); } catch { }
            _reader = null;
            Engine.Close(); // neutral first, then unplug; late reports/scans are ignored from now on
            Engine = null;
            try { (_pad as IDisposable)?.Dispose(); } catch { }
            _pad = null;
        }
        Changed?.Invoke();
    }
}
