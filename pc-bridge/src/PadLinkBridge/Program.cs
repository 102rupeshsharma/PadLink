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
        // last safety net: any unexpected error shows a plain message instead of the .NET crash dialog
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) =>
            MessageBox.Show("Something went wrong:\n\n" + e.Exception.Message + "\n\nPadLink Bridge keeps running. Please try again.",
                "PadLink Bridge", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        TaskScheduler.UnobservedTaskException += (_, e) => e.SetObserved();
        Application.Run(new App(startInTray: args.Contains("--tray")));
        return 0;
    }
}
