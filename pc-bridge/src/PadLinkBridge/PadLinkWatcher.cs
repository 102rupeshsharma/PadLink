using HidSharp;
using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>
/// Finds every PadLink phone among the PC's HID devices and reads each one on its own background
/// thread (two players = two threads, so one phone can never slow down the other). Rescans when
/// Windows reports a device change and every 2 s (Bluetooth devices do not always raise a change
/// event). When a phone's read fails (disconnected), only that player's controller is released and
/// unplugged; the watcher keeps looking for it.
/// </summary>
public sealed class PadLinkWatcher : IDisposable
{
    private const uint GamepadUsage = 0x00010005; // Generic Desktop / Game Pad
    private readonly PadHub _hub;
    private readonly object _lock = new();
    private readonly System.Threading.Timer _rescan;
    private readonly Dictionary<string, HidStream> _open = new();
    private readonly EventHandler<DeviceListChangedEventArgs> _onChange;
    private volatile bool _disposed;

    public PadLinkWatcher(PadHub hub)
    {
        _hub = hub;
        _onChange = (_, _) => Scan();
        DeviceList.Local.Changed += _onChange;
        _rescan = new System.Threading.Timer(_ => Scan(), null, 0, 2000);
    }

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
        var picks = DeviceMatcher.PickAll(Candidates());
        lock (_lock) picks = picks.Where(p => !_open.ContainsKey(p.DevicePath)).ToList();
        if (picks.Count == 0) return;

        var devices = DeviceList.Local.GetHidDevices().ToList();
        foreach (var pick in picks)
        {
            var dev = devices.FirstOrDefault(d => d.DevicePath == pick.DevicePath);
            if (dev == null || !dev.TryOpen(out HidStream s)) continue;
            lock (_lock)
            {
                if (_disposed || _open.ContainsKey(pick.DevicePath)) { s.Dispose(); continue; }
                _open[pick.DevicePath] = s;
            }
            s.ReadTimeout = Timeout.Infinite;
            if (!_hub.OnDeviceFound(pick.DevicePath, pick.ProductName ?? "PadLink"))
            {
                lock (_lock) _open.Remove(pick.DevicePath);
                s.Dispose();
                continue;
            }
            var path = pick.DevicePath;
            new Thread(() => ReadLoop(path, s)) { IsBackground = true, Name = "padlink-read", Priority = ThreadPriority.AboveNormal }.Start();
        }
    }

    private void ReadLoop(string path, HidStream s)
    {
        var buf = new byte[Math.Max(64, PadLinkReport.LengthWithId)];
        try
        {
            while (!_disposed)
            {
                var n = s.Read(buf, 0, buf.Length);
                if (n <= 0) break;
                _hub.OnReport(path, buf.AsSpan(0, n)); // straight through: no queue, no delay
            }
        }
        catch { /* phone disconnected / Bluetooth off */ }
        finally
        {
            lock (_lock) { if (_open.TryGetValue(path, out var cur) && ReferenceEquals(cur, s)) _open.Remove(path); }
            try { s.Dispose(); } catch { }
            _hub.OnDeviceLost(path);
        }
    }

    public void Dispose()
    {
        _disposed = true;
        DeviceList.Local.Changed -= _onChange;
        _rescan.Dispose();
        lock (_lock)
        {
            foreach (var s in _open.Values) { try { s.Dispose(); } catch { } }
            _open.Clear();
        }
    }
}
