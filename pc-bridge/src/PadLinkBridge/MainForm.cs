using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>
/// One screen, one big status and one button:
///   XBOX MODE ON / WAITING FOR PADLINK / OFF / DRIVER NEEDED     [Stop] or [Start]
/// Closing the window stops Xbox mode (PadLink is a normal controller again).
/// </summary>
public sealed class MainForm : Form
{
    private readonly App _app;
    private readonly Panel _statusCard = new();
    private readonly Label _dot = new(), _title = new(), _detail = new(), _live = new(), _driverText = new(), _hiddenText = new();
    private readonly Button _startStop = new(), _test = new(), _installDriver = new(), _unhide = new();
    private readonly Panel _driverCard = new(), _hiddenCard = new();
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 150 };
    private (XboxModeState, int)? _shown;

    private static readonly Color Bg = Color.FromArgb(18, 20, 26), Card = Color.FromArgb(30, 33, 42), Good = Color.FromArgb(52, 199, 89),
        Warn = Color.FromArgb(255, 176, 32), Off = Color.FromArgb(120, 126, 140), Txt = Color.FromArgb(235, 238, 245),
        Dim = Color.FromArgb(150, 156, 170), Accent = Color.FromArgb(76, 111, 255), Stop = Color.FromArgb(214, 64, 69);

    public MainForm(App app)
    {
        _app = app;
        AutoScaleDimensions = new SizeF(96f, 96f); // layout in 96-DPI pixels, scaled to the screen
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = "PadLink Bridge";
        BackColor = Bg; ForeColor = Txt;
        Font = new Font("Segoe UI", 10f);
        FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false;
        ClientSize = new Size(520, 572);
        StartPosition = FormStartPosition.CenterScreen;
        Icon = App.MakeIcon(Accent);

        Controls.Add(new Label { Text = "PadLink Bridge", Font = new Font("Segoe UI", 18f, FontStyle.Bold), AutoSize = true, Location = new Point(20, 14) });
        Controls.Add(new Label { Text = "Xbox controller mode for PC games and ports", ForeColor = Dim, AutoSize = true, Location = new Point(22, 52) });

        // big status
        _statusCard.SetBounds(20, 86, 480, 222); _statusCard.BackColor = Card;
        _dot.SetBounds(14, 18, 34, 36); _dot.Text = "●"; _dot.Font = new Font("Segoe UI", 16f); _dot.TextAlign = ContentAlignment.MiddleCenter;
        _title.SetBounds(46, 16, 420, 34); _title.Font = new Font("Segoe UI", 15f, FontStyle.Bold);
        _detail.SetBounds(48, 54, 424, 72); _detail.ForeColor = Dim; _detail.Font = new Font("Segoe UI", 9.5f);
        _live.SetBounds(48, 132, 424, 82); _live.ForeColor = Txt; _live.Font = new Font("Consolas", 9f);
        _statusCard.Controls.AddRange([_dot, _title, _detail, _live]);
        Controls.Add(_statusCard);

        Btn(_startStop, "", new Rectangle(20, 320, 360, 46), Accent, () => { StartStop(); return Task.CompletedTask; });
        _startStop.Font = new Font("Segoe UI", 12f, FontStyle.Bold);
        Btn(_test, "Test", new Rectangle(390, 320, 110, 46), Card, () => { RunTest(); return Task.CompletedTask; });
        Controls.AddRange([_startStop, _test]);

        // one-time setup, only shown while the driver is missing
        _driverCard.SetBounds(20, 378, 480, 70); _driverCard.BackColor = Card;
        _driverText.SetBounds(14, 8, 320, 54); _driverText.Font = new Font("Segoe UI", 9.5f);
        Btn(_installDriver, "Install driver", new Rectangle(344, 17, 124, 36), Accent, InstallDriver);
        _driverCard.Controls.AddRange([_driverText, _installDriver]);
        Controls.Add(_driverCard);

        // only when an older version hid PadLink with HidHide: undo it, or the normal controller would not work
        _hiddenCard.SetBounds(20, 378, 480, 70); _hiddenCard.BackColor = Card;
        _hiddenText.SetBounds(14, 8, 320, 54); _hiddenText.Font = new Font("Segoe UI", 9.5f); _hiddenText.ForeColor = Warn;
        _hiddenText.Text = "An older version hid PadLink from games. Click Show PadLink so it works as a normal controller again.";
        Btn(_unhide, "Show PadLink", new Rectangle(344, 17, 124, 36), Accent, Unhide);
        _hiddenCard.Controls.AddRange([_hiddenText, _unhide]);
        Controls.Add(_hiddenCard);

        Controls.Add(new Label
        {
            Text = "How to use\n" +
                   "•  PC games / ports: open PadLink Bridge, then Connect on the phone.\n" +
                   "•  Done playing: close this window. Xbox mode stops.\n" +
                   "•  PCSX2 / emulators: keep PadLink Bridge closed (normal controller).\n" +
                   "•  Two players: connect both phones (second phone = P2 in the app).",
            ForeColor = Dim, Font = new Font("Segoe UI", 9f), Location = new Point(20, 460), Size = new Size(480, 104),
        });

        _app.Mode.Changed += () => { try { BeginInvoke(Refresh2); } catch { /* window closing */ } };
        _timer.Tick += (_, _) => Refresh2();
        _timer.Start();
        Load += (_, _) => Refresh2();
    }

    private static void Btn(Button b, string text, Rectangle r, Color color, Func<Task> action)
    {
        b.Text = text; b.Bounds = r;
        b.FlatStyle = FlatStyle.Flat; b.BackColor = color; b.ForeColor = Color.White; b.FlatAppearance.BorderSize = 0;
        b.Font = new Font("Segoe UI", 9.5f);
        b.Click += async (_, _) =>
        {
            b.Enabled = false;
            try { await action(); }
            catch (Exception e) { MessageBox.Show(b.FindForm(), "Something went wrong:\n\n" + e.Message + "\n\nPlease try again.", "PadLink Bridge", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
            finally { b.Enabled = true; }
        };
    }

    private void Refresh2()
    {
        if (IsDisposed) return;
        var state = _app.Mode.State;
        var players = _app.Mode.Hub?.Players ?? [];

        if (_shown != (state, players.Count))
        {
            _shown = (state, players.Count);
            var (color, title, detail, button, buttonColor) = state switch
            {
                XboxModeState.On when players.Count == 1 => (Good, "XBOX MODE ON",
                    "PC games now see an Xbox controller. Start your game.\nSecond player? Connect the other phone (P2 in the app).\nDone playing? Close this window or click Stop.",
                    "■  Stop Xbox mode", Stop),
                XboxModeState.On => (Good, $"XBOX MODE ON · {players.Count} PLAYERS",
                    $"{players.Count} phones connected: games see {players.Count} Xbox controllers.\nStart your game.\nDone playing? Close this window or click Stop.",
                    "■  Stop Xbox mode", Stop),
                XboxModeState.WaitingForPadLink => (Warn, "WAITING FOR PADLINK",
                    "Phone: PadLink → Connect → Open Controller.\nThe Xbox controller appears when the phone connects.\nTwo players? Connect the second phone too (P2).",
                    "■  Stop Xbox mode", Stop),
                XboxModeState.DriverMissing => (Warn, "DRIVER NEEDED",
                    "Xbox mode needs one free driver (ViGEmBus), installed once.\nClick Install driver below.",
                    "▶  Start Xbox mode", Accent),
                _ => (Off, "XBOX MODE OFF",
                    "PadLink works as a normal controller (PCSX2, emulators).\nClick Start for PC games that need an Xbox controller.",
                    "▶  Start Xbox mode", Accent),
            };
            _dot.ForeColor = color; _title.ForeColor = color; _title.Text = title; _detail.Text = detail;
            _startStop.Text = button; _startStop.BackColor = buttonColor;
            Icon = App.MakeIcon(color == Off ? Accent : color);
            Text = state == XboxModeState.On ? $"PadLink Bridge: Xbox mode ON ({players.Count} player{(players.Count == 1 ? "" : "s")})" : "PadLink Bridge";
        }

        _live.Text = state == XboxModeState.On ? string.Join("\n", players.Select(PlayerLine)) : "";

        var driverMissing = state == XboxModeState.DriverMissing;
        _driverCard.Visible = driverMissing;
        if (driverMissing && !_installDriver.Enabled) { /* keep the progress text */ }
        else if (driverMissing) { _driverText.Text = "One-time setup: install the Xbox controller driver (ViGEmBus, free, about 6 MB)."; _driverText.ForeColor = Warn; }
        _test.Visible = !driverMissing;
        _hiddenCard.Visible = !driverMissing && _app.Settings.HideOriginal && _app.Settings.HiddenInstanceId != null;
    }

    /// <summary>"P1 ● Xbox 1  Pressed: A, LB  LT RT  L 0,0  R 0,0" (one line per connected phone).</summary>
    private static string PlayerLine(PlayerView p)
    {
        var s = p.Current;
        var pressed = s.Buttons == XboxButtons.None ? "-" : s.Buttons.ToString().Replace("Shoulder", "B").Replace("Thumb", "S").Replace("Dpad", "");
        if (s.LeftTrigger > 0) pressed += " LT";
        if (s.RightTrigger > 0) pressed += " RT";
        var slot = p.XboxSlot is int n ? $"Xbox {n}" : "Xbox";
        return $"P{p.Player} ● {slot}  {pressed,-14} L {s.LeftX / 328},{s.LeftY / 328}  R {s.RightX / 328},{s.RightY / 328}";
    }

    private void StartStop()
    {
        if (_app.Mode.IsRunning) _app.Mode.Stop();
        else if (!_app.Mode.Start())
            MessageBox.Show(this, "Install the Xbox controller driver first (Install driver, below).", "PadLink Bridge");
        Refresh2();
    }

    private async Task InstallDriver()
    {
        var r = await Drivers.DownloadAndRun(DriverInstaller.ViGEmBus, new Progress<string>(m => { _driverText.Text = m; _driverText.ForeColor = Warn; }));
        if (_app.Mode.Start()) { Refresh2(); return; }
        if (r.Ok)
            MessageBox.Show(this, "The driver is installed. Restart the PC once, then open PadLink Bridge again.", "PadLink Bridge");
        else
        {
            var answer = MessageBox.Show(this,
                $"{r.Message}\n\nThis is usually a short internet or GitHub problem: try again in a minute.\n\n" +
                $"Or click Yes to open the official ViGEmBus download page, download \"{DriverInstaller.ViGEmBus.FileName}\" and run it yourself.",
                "PadLink Bridge", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
            if (answer == DialogResult.Yes) Drivers.OpenPage(DriverInstaller.ViGEmBus.ReleasePage);
        }
        Refresh2();
    }

    private async Task Unhide()
    {
        var s = _app.Settings;
        var cli = Drivers.HidHideCli();
        if (cli == null || s.HiddenInstanceId == null) { s.HideOriginal = false; s.Save(); Refresh2(); return; }
        if (await Drivers.SetHidden(cli, s.HiddenInstanceId, hide: false)) { s.HideOriginal = false; s.HiddenInstanceId = null; s.Save(); }
        Refresh2();
    }

    /// <summary>Plugs a test Xbox controller, presses A and checks that Windows (XInput) really sees it.</summary>
    private void RunTest()
    {
        var result = SelfTest.VirtualControllerCheck();
        MessageBox.Show(this, result.Ok
            ? "Test passed ✓\n\nWindows sees an Xbox controller and the A button reached it. Games will work the same way."
            : "Test failed:\n\n" + result.Message, "PadLink Bridge test", MessageBoxButtons.OK, result.Ok ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        _timer.Stop();
        base.OnFormClosed(e);
    }
}
