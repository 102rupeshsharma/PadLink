using HidSharp;
using HidSharp.Reports;
using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>
/// Finds PadLink among the PC's HID devices and reads its reports on a background thread.
/// Rescans when Windows reports a device change and every 2 s (Bluetooth devices do not always raise
/// a change event). When the read fails (phone disconnected), the engine releases everything and the
/// watcher looks for PadLink again.
/// </summary>
public sealed class PadLinkWatcher : IDisposable
{
    private const uint GamepadUsage = 0x00010005; // Generic Desktop / Game Pad
    private readonly BridgeEngine _engine;
    private readonly object _lock = new();
    private readonly System.Threading.Timer _rescan;
    private HidStream? _stream;
    private string? _path;
    private volatile bool _disposed;
    private readonly EventHandler<DeviceListChangedEventArgs> _onChange;

    public PadLinkWatcher(BridgeEngine engine)
    {
        _engine = engine;
        _onChange = (_, _) => Scan();
        DeviceList.Local.Changed += _onChange;
        _rescan = new System.Threading.Timer(_ => Scan(), null, 0, 2000);
    }

    /// <summary>Device path of the PadLink being read (for HidHide), null when none.</summary>
    public string? CurrentPath { get { lock (_lock) return _path; } }

    /// <summary>All HID devices as the matcher sees them (also used by the self-test).</summary>
    public static List<HidCandidate> Candidates()
    {
        var list = new List<HidCandidate>();
        foreach (var d in DeviceList.Local.GetHidDevices())
        {
            try
            {
                string? name = null;
                try { name = d.GetProductName(); } catch { /* some devices refuse */ }
                var gamepad = false;
                try { gamepad = d.GetReportDescriptor().DeviceItems.Any(i => i.Usages.GetAllValues().Contains(GamepadUsage)); } catch { }
                list.Add(new HidCandidate(d.DevicePath, name, d.VendorID, d.ProductID, gamepad, d.GetMaxInputReportLength()));
            }
            catch { /* device vanished while scanning */ }
        }
        return list;
    }

    private void Scan()
    {
        // runs on a timer / device-change thread: an exception here would close the whole app
        try { ScanOnce(); } catch { /* try again on the next scan */ }
    }

    private void ScanOnce()
    {
        if (_disposed) return;
        lock (_lock) { if (_stream != null) return; } // already reading PadLink
        var pick = DeviceMatcher.Pick(Candidates());
        if (pick == null) return;
        var dev = DeviceList.Local.GetHidDevices().FirstOrDefault(d => d.DevicePath == pick.DevicePath);
        if (dev == null || !dev.TryOpen(out HidStream s)) return;
        lock (_lock)
        {
            if (_stream != null || _disposed) { s.Dispose(); return; }
            _stream = s; _path = pick.DevicePath;
        }
        s.ReadTimeout = Timeout.Infinite;
        _engine.OnPadLinkFound(pick.ProductName ?? "PadLink");
        new Thread(() => ReadLoop(s)) { IsBackground = true, Name = "padlink-read", Priority = ThreadPriority.AboveNormal }.Start();
    }

    private void ReadLoop(HidStream s)
    {
        var buf = new byte[Math.Max(64, PadLinkReport.LengthWithId)];
        try
        {
            while (!_disposed)
            {
                var n = s.Read(buf, 0, buf.Length);
                if (n <= 0) break;
                _engine.OnReport(buf.AsSpan(0, n)); // straight through: no queue, no delay
            }
        }
        catch { /* phone disconnected / Bluetooth off */ }
        finally
        {
            lock (_lock) { if (ReferenceEquals(_stream, s)) { _stream = null; _path = null; } }
            try { s.Dispose(); } catch { }
            _engine.OnPadLinkLost();
        }
    }

    public void Dispose()
    {
        _disposed = true;
        DeviceList.Local.Changed -= _onChange;
        _rescan.Dispose();
        lock (_lock) { try { _stream?.Dispose(); } catch { } }
    }
}
