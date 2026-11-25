package local.speedmeter;

import android.app.Activity;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String SERVER = "https://fra.speedtest.clouvider.net/backend/";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button start;
    private Button stop;
    private volatile boolean cancelled;
    private ProgressBar progress;
    private TextView latencyValue, downloadValue, uploadValue;
    private static final int INK = Color.rgb(13, 18, 30);
    private static final int CARD = Color.rgb(23, 31, 49);
    private static final int TEXT = Color.rgb(234, 242, 255);
    private static final int MUTED = Color.rgb(170, 189, 218);
    private static final int MINT = Color.rgb(97, 234, 199);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(INK);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(45), dp(22), dp(24));
        scroll.addView(layout);
        layout.addView(label("SpeedMeter", 36, TEXT, true));
        layout.addView(label("LibreSpeed  /  Clouvider · Франкфурт", 15, MUTED, false));
        latencyValue = metric(layout, "Задержка HTTP", "мс", Color.rgb(166, 152, 246));
        downloadValue = metric(layout, "Загрузка", "Мбит/с", MINT);
        uploadValue = metric(layout, "Отдача", "Мбит/с", Color.rgb(119, 192, 252));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(MINT));
        LinearLayout.LayoutParams meter = new LinearLayout.LayoutParams(-1, dp(12));
        meter.topMargin = dp(15);
        layout.addView(progress, meter);
        status = label("Готов к замеру · до 30 МБ за тест", 14, MUTED, false);
        status.setPadding(0, dp(10), 0, dp(12));
        layout.addView(status);
        start = new Button(this);
        start.setText("Начать замер");
        start.setTextColor(INK);
        start.setBackgroundTintList(ColorStateList.valueOf(MINT));
        stop = new Button(this);
        stop.setText("Отмена");
        stop.setTextColor(TEXT);
        stop.setBackgroundTintList(ColorStateList.valueOf(CARD));
        stop.setEnabled(false);
        layout.addView(start);
        layout.addView(stop);
        setContentView(scroll);
        start.setOnClickListener(v -> {
            cancelled = false;
            latencyValue.setText("—"); downloadValue.setText("—"); uploadValue.setText("—");
            start.setEnabled(false);
            stop.setEnabled(true);
            worker.execute(this::measure);
        });
        stop.setOnClickListener(v -> {
            stop.setEnabled(false);
            cancelled = true;
            status.setText("Остановка…");
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView metric(LinearLayout layout, String title, String unit, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(9), dp(15), dp(9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2);
        bounds.topMargin = dp(12);
        layout.addView(card, bounds);
        card.addView(label(title, 14, MUTED, false));
        TextView value = label("—", 29, accent, true);
        value.setContentDescription(title + ", " + unit);
        card.addView(value);
        card.addView(label(unit, 12, MUTED, false));
        return value;
    }

    private void updateProgress(int value) {
        if (android.os.Build.VERSION.SDK_INT >= 24) progress.setProgress(value, true);
        else progress.setProgress(value);
    }

    private void show(String message, int step, TextView metric, String value) {
        runOnUiThread(() -> {
            if (isDestroyed() || cancelled) return;
            status.setText(message);
            updateProgress(step);
            if (metric != null) metric.setText(value);
        });
    }
    private void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Замер остановлен.");
    }

    private long transfer(String path, byte[] upload) throws IOException {
        check();
        HttpURLConnection request = (HttpURLConnection) new URL(SERVER + path + (path.endsWith("?") ? "" : "&") + "nonce=" + UUID.randomUUID()).openConnection();

        request.setConnectTimeout(8000);
        request.setReadTimeout(8000);
        request.setUseCaches(false);
        request.setInstanceFollowRedirects(false);
        try {
            if (upload != null) {
                request.setRequestMethod("POST");
                request.setRequestProperty("Content-Type", "application/octet-stream");
                request.setDoOutput(true);
                request.setFixedLengthStreamingMode(upload.length);
                try (OutputStream out = request.getOutputStream()) {
                    for (int offset = 0; offset < upload.length; offset += 65536) {
                        check();
                        out.write(upload, offset, Math.min(65536, upload.length - offset));
                    }
                }
            }
            int code = request.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Ответ сервера HTTP " + code);
            long count = 0;
            if (upload == null) {
                try (InputStream input = request.getInputStream()) {
                    byte[] buffer = new byte[65536];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        check();
                        count += size;
                    }
                }
            }
            return count;
        } finally {

            request.disconnect();
        }
    }

    private void measure() {
        String result;
        try {
            show("Измерение задержки…", 12, null, null);
            long[] samples = new long[5];
            for (int i = 0; i < 6; i++) {
                long started = SystemClock.elapsedRealtime();
                transfer("empty.php?", null);
                if (i > 0) samples[i - 1] = SystemClock.elapsedRealtime() - started;
            }
            Arrays.sort(samples);

            show("Загрузка…", 43, latencyValue, Long.toString(samples[2]));
            long started = SystemClock.elapsedRealtime();
            long received = transfer("garbage.php?ckSize=24", null);
            if (received != 25165824) throw new IOException("Сервер вернул неполный объём данных.");
            double down = rate(received, started);

            show("Отдача…", 76, downloadValue, String.format(Locale.getDefault(), "%.1f", down));
            byte[] payload = new byte[4000000];
            new Random().nextBytes(payload);
            started = SystemClock.elapsedRealtime();
            transfer("empty.php?", payload);
            show("Замер завершён", 100, uploadValue, String.format(Locale.getDefault(), "%.1f", rate(payload.length, started)));
            result = null;
        } catch (IOException | RuntimeException e) {
            result = "Замер не завершён.\n" + e.getMessage();
        }
        String finalResult = result;
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (cancelled || finalResult != null) {
                status.setText(cancelled ? "Замер остановлен." : finalResult);
                updateProgress(0);
            }
            start.setEnabled(true);
            stop.setEnabled(false);
        });
    }

    private static double rate(long bytes, long started) {
        return bytes * 0.008 / Math.max(1, SystemClock.elapsedRealtime() - started);
    }

    @Override protected void onDestroy() {
        cancelled = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
