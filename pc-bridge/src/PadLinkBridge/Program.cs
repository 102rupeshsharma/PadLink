namespace PadLinkBridge;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length >= 2 && args[0] == "--selftest") return SelfTest.RunToFile(args[1]);

        using var single = new Mutex(true, @"Local\PadLinkBridge.SingleInstance", out var first);
        if (!first)
        {
            MessageBox.Show("PadLink Bridge is already running. Look for the PL icon in the tray (bottom right, next to the clock).", "PadLink Bridge");
            return 0;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new App(startInTray: args.Contains("--tray")));
        return 0;
    }
}
