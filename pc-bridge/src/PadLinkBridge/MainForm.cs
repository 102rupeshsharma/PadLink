using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>
/// The setup / status window: a 3-step checklist a new user can follow without reading anything else.
///  1. Xbox controller driver (ViGEmBus)   [Install]
///  2. PadLink connected                    (connect from the phone)
///  3. Games see an Xbox controller          [Test]
/// plus "Hide the original PadLink from games" (HidHide) and "Start with Windows".
/// Closing the window keeps PadLink Bridge running in the tray.
/// </summary>
public sealed class MainForm : Form
{
    private readonly App _app;
    private readonly Label _step1 = new(), _step2 = new(), _step3 = new(), _live = new(), _hideInfo = new(), _note = new();
    private readonly Button _installVigem = new(), _test = new(), _hide = new(), _installHidHide = new();
    private readonly CheckBox _autostart = new();
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 200 };
    private static readonly Color Bg = Color.FromArgb(18, 20, 26), Card = Color.FromArgb(30, 33, 42), Good = Color.FromArgb(52, 199, 89),
        Warn = Color.FromArgb(255, 176, 32), Txt = Color.FromArgb(235, 238, 245), Dim = Color.FromArgb(150, 156, 170), Accent = Color.FromArgb(76, 111, 255);

    public MainForm(App app)
    {
        _app = app;
        // layout below is in 96-DPI pixels; scale everything with the screen's DPI (125 %, 150 % laptops)
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = "PadLink Bridge";
        BackColor = Bg; ForeColor = Txt;
        Font = new Font("Segoe UI", 10f);
        FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false;
        ClientSize = new Size(560, 600);
        StartPosition = FormStartPosition.CenterScreen;
        Icon = App.MakeIcon(Accent);

        var y = 16;
        Controls.Add(new Label { Text = "PadLink Bridge", Font = new Font("Segoe UI", 18f, FontStyle.Bold), AutoSize = true, Location = new Point(20, y) });
        y += 44;
        Controls.Add(new Label
        {
            Text = "Makes PadLink work like an Xbox controller in every PC game.",
            ForeColor = Dim, Location = new Point(20, y), Size = new Size(520, 44),
        });
        y += 50;
        y = Step(y, "1", _step1, _installVigem, "Install driver", async () => await InstallVigem());
        y = Step(y, "2", _step2, null, null, null);
        y = Step(y, "3", _step3, _test, "Test", () => { RunTest(); return Task.CompletedTask; });

        _live.SetBounds(20, y, 520, 24); _live.ForeColor = Dim; _live.Font = new Font("Consolas", 9.5f);
        Controls.Add(_live); y += 32;

        var hideCard = new Panel { BackColor = Card, Location = new Point(20, y), Size = new Size(520, 92) };
        hideCard.Controls.Add(new Label { Text = "Hide the original PadLink from games (recommended)", Font = new Font("Segoe UI", 10f, FontStyle.Bold), Location = new Point(12, 8), AutoSize = true });
        _hideInfo.SetBounds(12, 32, 360, 54); _hideInfo.ForeColor = Dim; _hideInfo.Font = new Font("Segoe UI", 8.5f);
        hideCard.Controls.Add(_hideInfo);
        Btn(_hide, "Turn on", new Point(384, 36), async () => await ToggleHide()); hideCard.Controls.Add(_hide);
        Btn(_installHidHide, "Install HidHide", new Point(384, 36), async () => await InstallHidHide()); hideCard.Controls.Add(_installHidHide);
        Controls.Add(hideCard); y += 104;

        _autostart.Text = "Start PadLink Bridge with Windows (runs in the tray)";
        _autostart.SetBounds(20, y, 520, 26); _autostart.Checked = Autostart.Enabled;
        _autostart.CheckedChanged += (_, _) => Autostart.Enabled = _autostart.Checked;
        Controls.Add(_autostart); y += 34;

        _note.SetBounds(20, y, 520, 40); _note.ForeColor = Dim; _note.Font = new Font("Segoe UI", 8.5f);
        _note.Text = "Closing this window keeps PadLink Bridge running in the tray (bottom right). Right-click the tray icon → Exit to stop it.";
        Controls.Add(_note);

        _timer.Tick += (_, _) => Refresh2();
        _timer.Start();
        Refresh2();
        FormClosing += (_, e) => { if (e.CloseReason == CloseReason.UserClosing && !_app.Exiting) { e.Cancel = true; Hide(); } };
    }

    private int Step(int y, string n, Label label, Button? button, string? text, Func<Task>? action)
    {
        var card = new Panel { BackColor = Card, Location = new Point(20, y), Size = new Size(520, 66) };
        card.Controls.Add(new Label { Text = n, Font = new Font("Segoe UI", 14f, FontStyle.Bold), ForeColor = Accent, Location = new Point(12, 16), Size = new Size(26, 30) });
        label.SetBounds(44, 6, button == null ? 466 : 334, 56);
        label.Font = new Font("Segoe UI", 9.5f);
        label.TextAlign = ContentAlignment.MiddleLeft;
        card.Controls.Add(label);
        if (button != null && text != null && action != null) { Btn(button, text, new Point(384, 16), action); card.Controls.Add(button); }
        Controls.Add(card);
        return y + 74;
    }

    private static void Btn(Button b, string text, Point at, Func<Task> action)
    {
        b.Text = text; b.Location = at; b.Size = new Size(126, 34);
        b.Font = new Font("Segoe UI", 9.5f);
        b.FlatStyle = FlatStyle.Flat; b.BackColor = Accent; b.ForeColor = Color.White; b.FlatAppearance.BorderSize = 0;
        b.Click += async (_, _) => { b.Enabled = false; try { await action(); } finally { b.Enabled = true; } };
    }

    private void Refresh2()
    {
        var vigem = _app.Pad != null;
        Set(_step1, vigem, "Xbox controller driver installed ✓", "Driver missing (ViGEmBus). Click Install driver and allow the Windows prompt.");
        _installVigem.Visible = !vigem;

        var e = _app.Engine;
        var connected = e?.Status == BridgeStatus.Active;
        Set(_step2, connected, $"PadLink connected ✓  ({e?.DeviceName})",
            "Waiting for PadLink… On the phone open PadLink, tap Connect and open the controller.");

        Set(_step3, vigem && connected, "Games now see an Xbox controller ✓  Start your game.",
            vigem ? "Games will see an Xbox controller as soon as PadLink is connected." : "Needs the driver from step 1.");
        _test.Visible = vigem;

        if (connected && e != null)
        {
            var s = e.Current;
            _live.Text = $"Live: {(s.Buttons == XboxButtons.None ? "no buttons" : s.Buttons.ToString())}  LT {s.LeftTrigger} RT {s.RightTrigger}  " +
                         $"L ({s.LeftX / 328},{s.LeftY / 328})  R ({s.RightX / 328},{s.RightY / 328})";
        }
        else _live.Text = "";

        var cli = Drivers.HidHideCli();
        var hidden = _app.Settings.HideOriginal;
        _installHidHide.Visible = cli == null;
        _hide.Visible = cli != null;
        _hide.Text = hidden ? "Turn off" : "Turn on";
        _hideInfo.Text = cli == null
            ? "Needs the free HidHide driver. Without it some games may see two controllers."
            : hidden ? "On: games only see the Xbox controller." : "Off: some games may see two controllers (PadLink and Xbox). Connect PadLink, then turn on.";
    }

    private static void Set(Label l, bool ok, string okText, string waitText)
    {
        l.Text = ok ? okText : waitText;
        l.ForeColor = ok ? Good : Warn;
    }

    private async Task InstallVigem()
    {
        var progress = new Progress<string>(m => _step1.Text = m);
        var ok = await Drivers.DownloadAndRun(Drivers.ViGEmUrl, progress);
        _app.TryCreatePad();
        if (!ok && _app.Pad == null)
            MessageBox.Show(this, "The driver was not installed. You can also download it yourself:\n" + Drivers.ViGEmUrl, "PadLink Bridge");
    }

    private async Task InstallHidHide()
    {
        var ok = await Drivers.DownloadAndRun(Drivers.HidHideUrl, new Progress<string>(m => _hideInfo.Text = m));
        if (ok) MessageBox.Show(this, "HidHide is installed. Windows may ask you to restart the PC once; then click Turn on here.", "PadLink Bridge");
    }

    private async Task ToggleHide()
    {
        var cli = Drivers.HidHideCli();
        if (cli == null) return;
        var s = _app.Settings;
        if (!s.HideOriginal)
        {
            var path = _app.Watcher?.CurrentPath;
            var id = path == null ? null : DeviceMatcher.InstanceIdFromPath(path);
            if (id == null) { MessageBox.Show(this, "Connect PadLink first (step 2), then turn this on.", "PadLink Bridge"); return; }
            if (await Drivers.SetHidden(cli, id, hide: true)) { s.HideOriginal = true; s.HiddenInstanceId = id; s.Save(); }
        }
        else
        {
            if (s.HiddenInstanceId != null && await Drivers.SetHidden(cli, s.HiddenInstanceId, hide: false)) { s.HideOriginal = false; s.Save(); }
        }
    }

    /// <summary>Plugs a test Xbox controller, presses A and checks that Windows (XInput) really sees it.</summary>
    private void RunTest()
    {
        var result = SelfTest.VirtualControllerCheck();
        MessageBox.Show(this, result.Ok
            ? "Test passed ✓\n\nWindows sees an Xbox controller and the A button reached it. Games will work the same way."
            : "Test failed:\n\n" + result.Message, "PadLink Bridge test", MessageBoxButtons.OK, result.Ok ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
    }
}
