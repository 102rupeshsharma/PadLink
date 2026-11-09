using System.Drawing.Drawing2D;
using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>
/// PadLink Bridge as an on/off app:
///  - open it → Xbox mode starts (PC games see an Xbox controller once the phone connects);
///  - close it (or click Stop) → Xbox mode stops and PadLink is a normal controller again.
/// No tray, no start with Windows: when the window is closed, nothing of the bridge keeps running.
/// </summary>
public sealed class App : ApplicationContext
{
    public Settings Settings { get; } = Settings.Load();
    public XboxMode Mode { get; }
    public PadLinkWatcher? Watcher { get; private set; }

    private readonly MainForm _form;

    public App()
    {
        // older versions (1.0.0/1.0.1) started with Windows into the tray; this version is started by hand
        try { Autostart.Enabled = false; } catch { }

        Mode = new XboxMode(
            createPad: () => new ViGEmPad(),
            startReading: hub => Watcher = new PadLinkWatcher(hub), // every phone → its own Xbox controller
            driverInstalled: Drivers.ViGEmInstalled);
        _form = new MainForm(this);
        _form.FormClosed += (_, _) => { Mode.Stop(); ExitThread(); };
        MainForm = _form;
        Mode.Start();
        _form.Show();
    }

    /// <summary>Round "PL" icon in [color].</summary>
    public static Icon MakeIcon(Color color)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var b = new SolidBrush(color);
            g.FillEllipse(b, 1, 1, 30, 30);
            using var f = new Font("Segoe UI", 11f, FontStyle.Bold, GraphicsUnit.Pixel);
            var sz = g.MeasureString("PL", f);
            g.DrawString("PL", f, Brushes.White, (32 - sz.Width) / 2, (32 - sz.Height) / 2);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }
}
