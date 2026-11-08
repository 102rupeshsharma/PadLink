using System.Drawing.Drawing2D;
using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>
/// The running bridge: tray icon, the virtual Xbox controller, the PadLink watcher and the window.
/// </summary>
public sealed class App : ApplicationContext
{
    public Settings Settings { get; } = Settings.Load();
    public ViGEmPad? Pad { get; private set; }
    public BridgeEngine? Engine { get; private set; }
    public PadLinkWatcher? Watcher { get; private set; }
    public bool Exiting { get; private set; }

    private readonly NotifyIcon _tray;
    private readonly MainForm _form;
    private readonly SynchronizationContext _ui;
    private BridgeStatus _shown = BridgeStatus.WaitingForPadLink;

    public App(bool startInTray)
    {
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        // first run: start with Windows by default (the user can untick it); before the window reads it
        if (!Settings.FirstRunDone) { Autostart.Enabled = true; Settings.FirstRunDone = true; Settings.Save(); }
        TryCreatePad();
        _form = new MainForm(this);
        _tray = new NotifyIcon { Text = "PadLink Bridge: waiting for PadLink", Visible = true, Icon = MakeIcon(Color.Gray) };
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open PadLink Bridge", null, (_, _) => ShowWindow());
        menu.Items.Add("Exit", null, (_, _) => Exit());
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => ShowWindow();

        if (!startInTray || Pad == null) ShowWindow();
    }

    /// <summary>Creates the virtual controller once the driver exists (also after installing it).</summary>
    public void TryCreatePad()
    {
        if (Pad != null) return;
        try { Pad = new ViGEmPad(); } catch { Pad = null; return; }
        Engine = new BridgeEngine(Pad);
        Engine.Changed += () => _ui.Post(_ => OnEngineChanged(), null);
        Watcher = new PadLinkWatcher(Engine);
    }

    private void OnEngineChanged()
    {
        var e = Engine;
        if (e == null || e.Status == _shown) return;
        _shown = e.Status;
        if (e.Status == BridgeStatus.Active)
        {
            _tray.Icon = MakeIcon(Color.FromArgb(52, 199, 89));
            _tray.Text = "PadLink Bridge: Xbox controller active";
            _tray.ShowBalloonTip(2500, "PadLink connected", "Games now see an Xbox controller.", ToolTipIcon.Info);
        }
        else
        {
            _tray.Icon = MakeIcon(Color.Gray);
            _tray.Text = "PadLink Bridge: waiting for PadLink";
        }
    }

    public void ShowWindow()
    {
        _form.Show();
        if (_form.WindowState == FormWindowState.Minimized) _form.WindowState = FormWindowState.Normal;
        _form.Activate();
    }

    private void Exit()
    {
        Exiting = true;
        Watcher?.Dispose();
        Engine?.OnPadLinkLost(); // release everything and unplug before leaving
        Pad?.Dispose();
        _tray.Visible = false;
        _tray.Dispose();
        _form.Close();
        ExitThread();
    }

    /// <summary>Round "PL" icon in [color] (gray = waiting, green = active).</summary>
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
