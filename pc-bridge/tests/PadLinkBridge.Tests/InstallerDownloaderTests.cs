using System.Net;
using System.Security.Cryptography;
using PadLinkBridge.Core;
using Xunit;

namespace PadLinkBridge.Tests;

/// <summary>
/// "Install driver" on a bad connection. The user's click got "504 Gateway Time-out" from GitHub and the app
/// crashed. These replay that (and worse) against a fake internet and check that the installer still arrives
/// intact, or a plain failure comes back: never an exception, never a broken file run as admin.
/// </summary>
public class InstallerDownloaderTests : IDisposable
{
    private static readonly byte[] Payload = MakePayload();
    private static readonly DriverInstaller Fake = new("ViGEmBus", "fake_setup.exe", Payload.Length,
        Convert.ToHexString(SHA256.HashData(Payload)), "https://example.invalid/page",
        ["https://api.example/asset", "https://github.example/download"]);

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "PadLinkBridgeTests", Guid.NewGuid().ToString("N"));

    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    private static byte[] MakePayload()
    {
        var b = new byte[200_000];
        new Random(7).NextBytes(b);
        b[0] = (byte)'M'; b[1] = (byte)'Z';
        return b;
    }

    /// <summary>A scripted internet: each request takes the next answer for its URL.</summary>
    private sealed class FakeNet : HttpMessageHandler
    {
        private readonly Dictionary<string, Queue<Func<HttpResponseMessage>>> _script = new();
        public readonly List<string> Requests = new();

        public FakeNet On(string url, params Func<HttpResponseMessage>[] answers)
        {
            _script[url] = new Queue<Func<HttpResponseMessage>>(answers);
            return this;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var url = request.RequestUri!.ToString();
            Requests.Add(url);
            if (!_script.TryGetValue(url, out var q) || q.Count == 0) return Task.FromResult(Status(HttpStatusCode.NotFound));
            return Task.FromResult(q.Dequeue()());
        }
    }

    private static HttpResponseMessage Status(HttpStatusCode c) => new(c) { Content = new StringContent("<html>error</html>") };
    private static HttpResponseMessage GatewayTimeout() => Status(HttpStatusCode.GatewayTimeout);
    private static HttpResponseMessage File(byte[] b) => new(HttpStatusCode.OK) { Content = new ByteArrayContent(b) };
    private static HttpResponseMessage Reset() => throw new HttpRequestException("An error occurred while sending the request. Connection was reset.");

    private Task<DownloadResult> Run(FakeNet net, CancellationToken ct = default) =>
        new InstallerDownloader(new HttpClient(net), attemptsPerSource: 3, pause: TimeSpan.Zero).DownloadAsync(Fake, _dir, null, ct);

    [Fact]
    public async Task WorksFirstTime()
    {
        var net = new FakeNet().On("https://api.example/asset", () => File(Payload));
        var r = await Run(net);
        Assert.True(r.Ok);
        Assert.Equal(Payload, await System.IO.File.ReadAllBytesAsync(r.FilePath!));
        Assert.Single(net.Requests);
    }

    [Fact]
    public async Task A504IsRetriedAndThenSucceeds()
    {
        var net = new FakeNet().On("https://api.example/asset", GatewayTimeout, GatewayTimeout, () => File(Payload));
        var r = await Run(net);
        Assert.True(r.Ok);
        Assert.Equal(3, net.Requests.Count);
    }

    [Fact]
    public async Task WhenOneRouteKeepsGiving504TheOtherRouteIsUsed()
    {
        // what the user's PC saw, reversed: one GitHub route down, the other fine
        var net = new FakeNet()
            .On("https://api.example/asset", GatewayTimeout, GatewayTimeout, GatewayTimeout)
            .On("https://github.example/download", () => File(Payload));
        var r = await Run(net);
        Assert.True(r.Ok);
        Assert.Equal(["https://api.example/asset", "https://api.example/asset", "https://api.example/asset", "https://github.example/download"], net.Requests);
    }

    [Fact]
    public async Task EverythingDownGivesAPlainFailureNotACrash()
    {
        var net = new FakeNet()
            .On("https://api.example/asset", GatewayTimeout, GatewayTimeout, GatewayTimeout)
            .On("https://github.example/download", GatewayTimeout, Reset, GatewayTimeout);
        var r = await Run(net); // must not throw
        Assert.False(r.Ok);
        Assert.Null(r.FilePath);
        Assert.Contains("504", r.Message);
        Assert.Equal(6, net.Requests.Count);
        Assert.False(System.IO.File.Exists(Path.Combine(_dir, Fake.FileName)));
        Assert.Empty(Directory.GetFiles(_dir)); // no half files left behind
    }

    [Fact]
    public async Task ConnectionResetIsRetried()
    {
        var net = new FakeNet().On("https://api.example/asset", Reset, () => File(Payload));
        Assert.True((await Run(net)).Ok);
    }

    [Fact]
    public async Task AnErrorPageServedAsTheFileIsNeverAccepted()
    {
        var page = "<html>GitHub is having trouble</html>"u8.ToArray();
        var net = new FakeNet()
            .On("https://api.example/asset", () => File(page), () => File(page), () => File(page))
            .On("https://github.example/download", () => File(page), () => File(page), () => File(page));
        var r = await Run(net);
        Assert.False(r.Ok);
        Assert.Contains("not the official installer", r.Message);
        Assert.Empty(Directory.GetFiles(_dir));
    }

    [Fact]
    public async Task ACutOffDownloadIsRejectedAndDownloadedAgain()
    {
        var cut = Payload.AsSpan(0, Payload.Length / 2).ToArray();
        var net = new FakeNet().On("https://api.example/asset", () => File(cut), () => File(Payload));
        var r = await Run(net);
        Assert.True(r.Ok);
        Assert.Equal(Payload.Length, new FileInfo(r.FilePath!).Length);
    }

    [Fact]
    public async Task SameSizeButWrongContentIsRejected()
    {
        var evil = (byte[])Payload.Clone();
        evil[^1] ^= 0xFF;
        var net = new FakeNet().On("https://api.example/asset", () => File(evil), () => File(evil), () => File(evil))
                               .On("https://github.example/download", () => File(Payload));
        var r = await Run(net);
        Assert.True(r.Ok);
        Assert.Equal(Payload, await System.IO.File.ReadAllBytesAsync(r.FilePath!));
    }

    [Fact]
    public async Task AGoodFileFromEarlierIsReusedWithoutInternet()
    {
        Directory.CreateDirectory(_dir);
        await System.IO.File.WriteAllBytesAsync(Path.Combine(_dir, Fake.FileName), Payload);
        var net = new FakeNet();
        var r = await Run(net);
        Assert.True(r.Ok);
        Assert.Empty(net.Requests);
    }

    [Fact]
    public async Task ABrokenFileFromEarlierIsReplaced()
    {
        Directory.CreateDirectory(_dir);
        await System.IO.File.WriteAllBytesAsync(Path.Combine(_dir, Fake.FileName), [1, 2, 3]);
        var net = new FakeNet().On("https://api.example/asset", () => File(Payload));
        var r = await Run(net);
        Assert.True(r.Ok);
        Assert.Equal(Payload, await System.IO.File.ReadAllBytesAsync(r.FilePath!));
    }

    [Fact]
    public void RealInstallersUseTheRouteThatWorkedFirstAndCarryTheirHash()
    {
        foreach (var d in new[] { DriverInstaller.ViGEmBus, DriverInstaller.HidHide })
        {
            Assert.StartsWith("https://api.github.com/repos/nefarius/", d.Sources[0]);
            Assert.StartsWith("https://github.com/nefarius/", d.Sources[1]);
            Assert.EndsWith(d.FileName, d.Sources[1]);
            Assert.Equal(64, d.Sha256.Length);
            Assert.True(d.Size > 1_000_000);
        }
    }
}
