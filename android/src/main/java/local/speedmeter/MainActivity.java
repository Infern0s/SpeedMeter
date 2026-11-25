package local.speedmeter;

import android.app.Activity;
import android.os.Bundle;
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
    private static final String SERVER = "https://speed.cloudflare.com/";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button start;
    private Button stop;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad * 2, pad, pad);
        status = new TextView(this);
        status.setTextSize(20);
        status.setText("Сервер Cloudflare\nТест передаёт до 37 МБ данных.");
        start = new Button(this);
        start.setText("Начать");
        stop = new Button(this);
        stop.setText("Отмена");
        stop.setEnabled(false);
        layout.addView(status);
        layout.addView(start);
        layout.addView(stop);
        setContentView(layout);
        start.setOnClickListener(v -> {
            cancelled = false;
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

    private void show(String value) {
        runOnUiThread(() -> { if (!isDestroyed() && !cancelled) status.setText(value); });
    }

    private void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Замер остановлен.");
    }

    private long transfer(String path, byte[] upload) throws IOException {
        check();
        HttpURLConnection request = (HttpURLConnection) new URL(SERVER + path + "&nonce=" + UUID.randomUUID()).openConnection();
        connection = request;
        request.setConnectTimeout(8000);
        request.setReadTimeout(8000);
        request.setUseCaches(false);
        request.setInstanceFollowRedirects(false);
        try {
            if (upload != null) {
                request.setRequestMethod("POST");
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
            connection = null;
            request.disconnect();
        }
    }

    private void measure() {
        String result;
        try {
            show("Измерение задержки…");
            long[] samples = new long[5];
            for (int i = 0; i < 6; i++) {
                long started = SystemClock.elapsedRealtime();
                transfer("__down?bytes=1", null);
                if (i > 0) samples[i - 1] = SystemClock.elapsedRealtime() - started;
            }
            Arrays.sort(samples);
            String ping = "Задержка HTTP: " + samples[2] + " мс";
            show(ping + "\nЗагрузка…");
            long started = SystemClock.elapsedRealtime();
            long received = transfer("__down?bytes=25000000", null);
            if (received != 25000000) throw new IOException("Сервер вернул неполный объём данных.");
            double down = rate(received, started);
            String partial = String.format(Locale.getDefault(), "%s\nЗагрузка: %.1f Мбит/с", ping, down);
            show(partial + "\nОтдача…");
            byte[] payload = new byte[12000000];
            new Random().nextBytes(payload);
            started = SystemClock.elapsedRealtime();
            transfer("__up?test=1", payload);
            result = String.format(Locale.getDefault(), "%s\nОтдача: %.1f Мбит/с", partial, rate(payload.length, started));
        } catch (IOException | RuntimeException e) {
            result = "Замер не завершён.\n" + e.getMessage();
        }
        String finalResult = result;
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            status.setText(cancelled ? "Замер остановлен." : finalResult);
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
