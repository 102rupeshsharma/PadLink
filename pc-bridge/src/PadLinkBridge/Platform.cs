using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Win32;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using PadLinkBridge.Core;

namespace PadLinkBridge;

/// <summary>The real virtual Xbox 360 controller (ViGEmBus driver).</summary>
public sealed class ViGEmPad : IVirtualPad, IDisposable
{
    private readonly ViGEmClient _client;
    private IXbox360Controller? _pad;

    /// <summary>Throws <see cref="Nefarius.ViGEm.Client.Exceptions.VigemBusNotFoundException"/> without the driver.</summary>
    public ViGEmPad() => _client = new ViGEmClient();

    public void Connect()
    {
        if (_pad != null) return;
        _pad = _client.CreateXbox360Controller();
        _pad.AutoSubmitReport = false;
        _pad.Connect();
    }

    public void Submit(XboxState s)
    {
        var p = _pad;
        if (p == null) return;
        void Btn(Xbox360Button b, XboxButtons x) => p.SetButtonState(b, s.Has(x));
        Btn(Xbox360Button.A, XboxButtons.A); Btn(Xbox360Button.B, XboxButtons.B);
        Btn(Xbox360Button.X, XboxButtons.X); Btn(Xbox360Button.Y, XboxButtons.Y);
        Btn(Xbox360Button.LeftShoulder, XboxButtons.LeftShoulder); Btn(Xbox360Button.RightShoulder, XboxButtons.RightShoulder);
        Btn(Xbox360Button.Back, XboxButtons.Back); Btn(Xbox360Button.Start, XboxButtons.Start);
        Btn(Xbox360Button.LeftThumb, XboxButtons.LeftThumb); Btn(Xbox360Button.RightThumb, XboxButtons.RightThumb);
        Btn(Xbox360Button.Up, XboxButtons.DpadUp); Btn(Xbox360Button.Down, XboxButtons.DpadDown);
        Btn(Xbox360Button.Left, XboxButtons.DpadLeft); Btn(Xbox360Button.Right, XboxButtons.DpadRight);
        p.SetSliderValue(Xbox360Slider.LeftTrigger, s.LeftTrigger);
        p.SetSliderValue(Xbox360Slider.RightTrigger, s.RightTrigger);
        p.SetAxisValue(Xbox360Axis.LeftThumbX, s.LeftX); p.SetAxisValue(Xbox360Axis.LeftThumbY, s.LeftY);
        p.SetAxisValue(Xbox360Axis.RightThumbX, s.RightX); p.SetAxisValue(Xbox360Axis.RightThumbY, s.RightY);
        p.SubmitReport();
    }

    public void Disconnect()
    {
        try { _pad?.Disconnect(); } catch { /* already gone */ }
        _pad = null;
    }

    public void Dispose() { Disconnect(); _client.Dispose(); }
}

/// <summary>Reads what Windows' own Xbox API (XInput) reports, to prove games see the virtual controller.</summary>
public static class XInput
{
    [StructLayout(LayoutKind.Sequential)]
    public struct Gamepad { public ushort Buttons; public byte LT, RT; public short LX, LY, RX, RY; }
    [StructLayout(LayoutKind.Sequential)]
    public struct State { public uint Packet; public Gamepad Pad; }

    [DllImport("xinput1_4.dll", EntryPoint = "XInputGetState")]
    private static extern int GetStateNative(int user, out State s);

    /// <summary>States of all connected XInput controllers (slot, state).</summary>
    public static List<(int Slot, Gamepad Pad)> Connected()
    {
        var list = new List<(int, Gamepad)>();
        for (var i = 0; i < 4; i++)
        {
            try { if (GetStateNative(i, out var s) == 0) list.Add((i, s.Pad)); } catch (DllNotFoundException) { break; }
        }
        return list;
    }
}

/// <summary>Driver checks and installers (official GitHub releases of the free Nefarius drivers).</summary>
public static class Drivers
{
    public const string ViGEmUrl = "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe";
    public const string HidHideUrl = "https://github.com/nefarius/HidHide/releases/download/v1.5.230.0/HidHide_1.5.230_x64.exe";

    public static bool ViGEmInstalled()
    {
        try { using var c = new ViGEmClient(); return true; } catch { return false; }
    }

    public static string? HidHideCli()
    {
        var candidates = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Nefarius Software Solutions", "HidHide", "x64", "HidHideCLI.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Nefarius Software Solutions", "HidHide", "HidHideCLI.exe"),
        };
        return candidates.FirstOrDefault(File.Exists);
    }

    /// <summary>Downloads an official installer and runs it (Windows asks for admin). True when it finished.</summary>
    public static async Task<bool> DownloadAndRun(string url, IProgress<string> progress)
    {
        var file = Path.Combine(Path.GetTempPath(), Path.GetFileName(new Uri(url).LocalPath));
        progress.Report("Downloading…");
        using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) })
        {
            http.DefaultRequestHeaders.UserAgent.ParseAdd("PadLinkBridge/1.0");
            await using var src = await http.GetStreamAsync(url);
            await using var dst = File.Create(file);
            await src.CopyToAsync(dst);
        }
        progress.Report("Installing… (allow the Windows prompt)");
        try
        {
            using var p = Process.Start(new ProcessStartInfo(file) { UseShellExecute = true, Verb = "runas" });
            if (p == null) return false;
            await p.WaitForExitAsync();
            return p.ExitCode == 0;
        }
        catch (System.ComponentModel.Win32Exception) { return false; } // user said No to the prompt
    }

    /// <summary>
    /// Hides the original PadLink from games with HidHide (so a game never sees two controllers) and
    /// allows PadLink Bridge itself to keep reading it. One admin prompt.
    /// </summary>
    public static async Task<bool> SetHidden(string cli, string instanceId, bool hide)
    {
        var exe = Environment.ProcessPath ?? Application.ExecutablePath;
        var cmd = hide
            ? $"\"{cli}\" --app-reg \"{exe}\" & \"{cli}\" --dev-hide \"{instanceId}\" & \"{cli}\" --cloak-on"
            : $"\"{cli}\" --dev-unhide \"{instanceId}\"";
        try
        {
            using var p = Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{cmd}\"")
            { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden });
            if (p == null) return false;
            await p.WaitForExitAsync();
            return true;
        }
        catch (System.ComponentModel.Win32Exception) { return false; }
    }
}

/// <summary>Small settings file in %APPDATA%\PadLinkBridge.</summary>
public sealed class Settings
{
    public bool HideOriginal { get; set; }
    public string? HiddenInstanceId { get; set; }
    public bool FirstRunDone { get; set; }

    private static string FilePath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PadLinkBridge", "settings.json");

    public static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings(); }
        catch { return new Settings(); }
    }

    public void Save()
    {
        try { Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!); File.WriteAllText(FilePath, JsonSerializer.Serialize(this)); }
        catch { /* not critical */ }
    }
}

/// <summary>"Start with Windows" (current user only, no admin).</summary>
public static class Autostart
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "PadLinkBridge";

    public static bool Enabled
    {
        get { using var k = Registry.CurrentUser.OpenSubKey(Key); return k?.GetValue(Name) is string; }
        set
        {
            using var k = Registry.CurrentUser.CreateSubKey(Key);
            if (value) k.SetValue(Name, $"\"{Environment.ProcessPath}\" --tray");
            else k.DeleteValue(Name, throwOnMissingValue: false);
        }
    }
}
