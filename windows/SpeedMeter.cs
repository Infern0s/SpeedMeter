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
    const string Server = "https://fra.speedtest.clouvider.net/backend/";
    readonly Label status = new Label();
    readonly Label latency;
    readonly Label download;
    readonly Label upload;
    readonly Panel track = new Panel();
    readonly Panel fill = new Panel();
    readonly System.Windows.Forms.Timer animation = new System.Windows.Forms.Timer();
    int targetProgress;
    readonly Button start = new Button();
    readonly Button cancel = new Button();
    CancellationTokenSource run;

    SpeedMeter()
    {
        Text = "SpeedMeter";
        ClientSize = new Size(600, 355);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(13, 18, 30);
        ForeColor = Color.FromArgb(234, 242, 255);
        Font = new Font("Segoe UI", 11);
        Controls.Add(MakeLabel("SpeedMeter", 25, 22, 550, 48, 28, ForeColor));
        Controls.Add(MakeLabel("LibreSpeed  /  Clouvider · Франкфурт", 27, 75, 546, 24, 11, Color.LightSteelBlue));
        latency = AddMetric(26, "Задержка HTTP", "мс", Color.FromArgb(166, 152, 246));
        download = AddMetric(211, "Загрузка", "Мбит/с", Color.FromArgb(97, 234, 199));
        upload = AddMetric(396, "Отдача", "Мбит/с", Color.FromArgb(119, 192, 252));
        status.SetBounds(27, 226, 548, 42);
        status.Text = "Готов к замеру · до 30 МБ за тест";
        status.ForeColor = Color.LightSteelBlue;
        track.SetBounds(27, 274, 546, 5);
        track.BackColor = Color.FromArgb(45, 55, 74);
        fill.SetBounds(0, 0, 0, 5);
        fill.BackColor = Color.FromArgb(97, 234, 199);
        track.Controls.Add(fill);
        animation.Interval = 16;
        animation.Tick += (sender, args) =>
        {
            int delta = track.Width * targetProgress / 100 - fill.Width;
            if (delta != 0) fill.Width += Math.Sign(delta) * Math.Max(1, Math.Abs(delta) / 9);
        };
        animation.Start();
        start.Text = "Начать замер";
        start.SetBounds(27, 300, 152, 36);
        start.BackColor = fill.BackColor;
        start.ForeColor = BackColor;
        cancel.Text = "Отмена";
        cancel.SetBounds(192, 300, 113, 36);
        cancel.BackColor = track.BackColor;
        foreach (var button in new[] { start, cancel })
        {
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderSize = 0;
        }
        cancel.Enabled = false;
        start.Click += async (sender, args) => await Measure();
        cancel.Click += (sender, args) => { if (run != null) run.Cancel(); };
        FormClosed += (sender, args) => { if (run != null) run.Cancel(); animation.Dispose(); };
        Controls.AddRange(new Control[] { status, track, start, cancel });
    }

    static Label MakeLabel(string text, int x, int y, int width, int height, int size, Color color)
    {
        var label = new Label { Text = text, ForeColor = color, Font = new Font("Segoe UI", size) };
        label.SetBounds(x, y, width, height);
        return label;
    }

    Label AddMetric(int x, string name, string unit, Color accent)
    {
        var card = new Panel { BackColor = Color.FromArgb(23, 31, 49) };
        card.SetBounds(x, 113, 176, 102);
        var value = MakeLabel("—", 11, 30, 152, 45, 26, accent);
        card.Controls.AddRange(new Control[] {
            MakeLabel(name, 12, 10, 150, 22, 10, Color.LightSteelBlue),
            value,
            MakeLabel(unit, 12, 76, 150, 20, 9, Color.LightSteelBlue)
        });
        Controls.Add(card);
        return value;
    }
    static string Url(string path)
    {
        return Server + path + (path.EndsWith("?") ? "" : "&") + "nonce=" + Guid.NewGuid().ToString("N");
    }

    async Task Measure()
    {
        targetProgress = 12;
        latency.Text = download.Text = upload.Text = "—";
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
                    await http.GetByteArrayAsync(Url("empty.php?"))
                        .WithCancellation(cts.Token);
                    if (i > 0) samples[i - 1] = sw.Elapsed.TotalMilliseconds;
                }
                Array.Sort(samples);

                latency.Text = samples[2].ToString("F0");
                targetProgress = 43;
                status.Text = "Загрузка…";
                var timer = Stopwatch.StartNew();
                long received = 0;
                using (var response = await http.GetAsync(Url("garbage.php?ckSize=24"), HttpCompletionOption.ResponseHeadersRead, cts.Token))
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
                if (received != 25165824) throw new IOException("Сервер вернул неполный объём данных.");
                double down = received * 8.0 / timer.Elapsed.TotalSeconds / 1000000;
                download.Text = down.ToString("F1");
                targetProgress = 76;
                status.Text = "Отдача…";
                var payload = new byte[4000000];
                new Random().NextBytes(payload);
                timer.Restart();
                using (var body = new ByteArrayContent(payload))
                using (var result = await http.PostAsync(Url("empty.php?"), body, cts.Token))
                    result.EnsureSuccessStatusCode();
                upload.Text = (payload.Length * 8.0 / timer.Elapsed.TotalSeconds / 1000000).ToString("F1");
                targetProgress = 100;
                status.Text = "Замер завершён";
            }
            catch (OperationCanceledException)
            {
                targetProgress = 0;
                status.Text = "Замер остановлен или истекло время ожидания.";
            }
            catch (Exception ex)
            {
                targetProgress = 0;
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
