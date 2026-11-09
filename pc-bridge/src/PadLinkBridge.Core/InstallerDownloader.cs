using System.Net.Http.Headers;
using System.Security.Cryptography;

namespace PadLinkBridge.Core;

/// <summary>An official driver installer: where to get it and exactly what it must be.</summary>
public sealed record DriverInstaller(string Name, string FileName, long Size, string Sha256, string ReleasePage, IReadOnlyList<string> Sources)
{
    /// <summary>ViGEmBus 1.22.0 (virtual Xbox controllers), signed by Nefarius Software Solutions e.U.</summary>
    public static readonly DriverInstaller ViGEmBus = new(
        "ViGEmBus", "ViGEmBus_1.22.0_x64_x86_arm64.exe", 6278576,
        "89220a7865076b342892f98865f3499fb7c4cfd673159e89d352c360fd014c6a",
        "https://github.com/nefarius/ViGEmBus/releases/tag/v1.22.0",
        [
            // GitHub's API serves the file even when the normal download link answers 504
            "https://api.github.com/repos/nefarius/ViGEmBus/releases/assets/133623750",
            "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe",
        ]);

    /// <summary>HidHide 1.5.230 (hides the original PadLink from games), signed by Nefarius Software Solutions e.U.</summary>
    public static readonly DriverInstaller HidHide = new(
        "HidHide", "HidHide_1.5.230_x64.exe", 8078016,
        "f4bbbcb82e6258641b887c74bc81c4c5f66e4aa811808dfc304347687b7605f6",
        "https://github.com/nefarius/HidHide/releases/tag/v1.5.230.0",
        [
            "https://api.github.com/repos/nefarius/HidHide/releases/assets/175175472",
            "https://github.com/nefarius/HidHide/releases/download/v1.5.230.0/HidHide_1.5.230_x64.exe",
        ]);
}

public readonly record struct DownloadResult(bool Ok, string? FilePath, string Message);

/// <summary>
/// Downloads an installer robustly: every source in turn, a few attempts each with a short pause, and the file
/// is only accepted when its size and SHA-256 match the official installer (never runs a broken or wrong file).
/// Never throws for network problems; returns a result the window can show.
/// </summary>
public sealed class InstallerDownloader(HttpClient http, int attemptsPerSource = 3, TimeSpan? pause = null)
{
    private readonly TimeSpan _pause = pause ?? TimeSpan.FromSeconds(2);

    public async Task<DownloadResult> DownloadAsync(DriverInstaller d, string folder, IProgress<string>? progress = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(folder);
        var target = Path.Combine(folder, d.FileName);
        if (File.Exists(target) && await MatchesAsync(target, d, ct)) return new(true, target, "Already downloaded");

        var lastError = "no source tried";
        var total = d.Sources.Count * attemptsPerSource;
        var n = 0;
        foreach (var url in d.Sources)
        {
            for (var a = 1; a <= attemptsPerSource; a++)
            {
                n++;
                progress?.Report(n == 1 ? $"Downloading {d.Name}…" : $"Downloading {d.Name}… (try {n} of {total})");
                var tmp = target + ".part";
                try
                {
                    using var req = new HttpRequestMessage(HttpMethod.Get, url);
                    req.Headers.UserAgent.ParseAdd("PadLinkBridge/1.1.0");
                    req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/octet-stream"));
                    using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
                    if (!resp.IsSuccessStatusCode)
                    {
                        lastError = $"the server answered {(int)resp.StatusCode} ({resp.ReasonPhrase})";
                    }
                    else
                    {
                        await using (var src = await resp.Content.ReadAsStreamAsync(ct))
                        await using (var dst = File.Create(tmp))
                            await src.CopyToAsync(dst, ct);
                        if (await MatchesAsync(tmp, d, ct))
                        {
                            File.Move(tmp, target, overwrite: true);
                            return new(true, target, "Downloaded");
                        }
                        lastError = "the downloaded file was incomplete or not the official installer";
                    }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception e) { lastError = e.Message; } // timeouts, resets, no internet
                finally { try { File.Delete(tmp); } catch { } }

                if (n < total) await Task.Delay(_pause, ct);
            }
        }
        return new(false, null, lastError);
    }

    public static async Task<bool> MatchesAsync(string file, DriverInstaller d, CancellationToken ct = default)
    {
        var info = new FileInfo(file);
        if (!info.Exists || info.Length != d.Size) return false;
        await using var s = File.OpenRead(file);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(s, ct));
        return hash.Equals(d.Sha256, StringComparison.OrdinalIgnoreCase);
    }
}
