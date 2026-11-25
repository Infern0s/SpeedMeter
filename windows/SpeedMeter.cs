using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

sealed class SpeedMeter : Form
{
    const string Server = "https://speed.cloudflare.com/";
    readonly Label status = new Label();
    readonly Button start = new Button();
    readonly Button cancel = new Button();
    CancellationTokenSource run;

    SpeedMeter()
    {
        Text = "Замер скорости";
        ClientSize = new Size(480, 245);
        Font = new Font("Segoe UI", 11);
        status.SetBounds(25, 25, 430, 145);
        status.Text = "Сервер Cloudflare\nТест передаёт до 37 МБ данных.";
        start.Text = "Начать";
        start.SetBounds(25, 185, 120, 36);
        cancel.Text = "Отмена";
        cancel.SetBounds(160, 185, 120, 36);
        cancel.Enabled = false;
        start.Click += async (sender, args) => await Measure();
        cancel.Click += (sender, args) => { if (run != null) run.Cancel(); };
        Controls.AddRange(new Control[] { status, start, cancel });
    }

    static string Url(string path)
    {
        return Server + path + "&nonce=" + Guid.NewGuid().ToString("N");
    }

    async Task Measure()
    {
        start.Enabled = false;
        cancel.Enabled = true;
        using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(50)))
        using (var http = new HttpClient())
        {
            run = cts;
            http.Timeout = TimeSpan.FromSeconds(15);
            try
            {
                var samples = new double[5];
                for (int i = 0; i < 6; i++)
                {
                    status.Text = "Измерение задержки…";
                    var sw = Stopwatch.StartNew();
                    await http.GetByteArrayAsync(Url("__down?bytes=1"))
                        .WithCancellation(cts.Token);
                    if (i > 0) samples[i - 1] = sw.Elapsed.TotalMilliseconds;
                }
                Array.Sort(samples);
                string ping = String.Format("Задержка HTTP: {0:F0} мс", samples[2]);
                status.Text = ping + "\nЗагрузка…";
                var timer = Stopwatch.StartNew();
                long received = 0;
                using (var response = await http.GetAsync(Url("__down?bytes=25000000"), HttpCompletionOption.ResponseHeadersRead, cts.Token))
                {
                    response.EnsureSuccessStatusCode();
                    using (var stream = await response.Content.ReadAsStreamAsync())
                    {
                        var buffer = new byte[65536];
                        int count;
                        while ((count = await stream.ReadAsync(buffer, 0, buffer.Length, cts.Token).WithCancellation(cts.Token)) > 0)
                            received += count;
                    }
                }
                if (received != 25000000) throw new IOException("Сервер вернул неполный объём данных.");
                double down = received * 8.0 / timer.Elapsed.TotalSeconds / 1000000;
                status.Text = String.Format("{0}\nЗагрузка: {1:F1} Мбит/с\nОтдача…", ping, down);
                var payload = new byte[12000000];
                new Random().NextBytes(payload);
                timer.Restart();
                using (var body = new ByteArrayContent(payload))
                using (var result = await http.PostAsync(Url("__up?test=1"), body, cts.Token))
                    result.EnsureSuccessStatusCode();
                status.Text = String.Format("{0}\nЗагрузка: {1:F1} Мбит/с\nОтдача: {2:F1} Мбит/с",
                    ping, down, payload.Length * 8.0 / timer.Elapsed.TotalSeconds / 1000000);
            }
            catch (OperationCanceledException)
            {
                status.Text = "Замер остановлен или истекло время ожидания.";
            }
            catch (Exception ex)
            {
                status.Text = "Замер не завершён.\n" + ex.Message;
            }
            finally
            {
                run = null;
                start.Enabled = true;
                cancel.Enabled = false;
            }
        }
    }

    [STAThread]
    static void Main()
    {
        ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new SpeedMeter());
    }
}

static class TaskCancellation
{
    public static async Task<T> WithCancellation<T>(this Task<T> task, CancellationToken token)
    {
        var signal = new TaskCompletionSource<bool>();
        using (token.Register(() => signal.TrySetResult(true)))
        {
            if (await Task.WhenAny(task, signal.Task) != task)
                throw new OperationCanceledException(token);
            return await task;
        }
    }
}
