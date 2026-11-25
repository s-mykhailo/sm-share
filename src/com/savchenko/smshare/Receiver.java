package com.savchenko.smshare;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiNetworkSpecifier;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Прийом файлів: Wi-Fi Direct (через NFC) і спільне збереження з перевіркою для всіх способів. */
final class Receiver {

    static volatile boolean busy;
    private static ConnectivityManager.NetworkCallback cb;

    private Receiver() {}

    static boolean isP2p(Intent i) {
        return i != null && NfcAdapter.ACTION_NDEF_DISCOVERED.equals(i.getAction());
    }

    static void receive(Context ctx, Intent i) {
        Context app = ctx.getApplicationContext();
        String[] d = parse(i);
        if (d == null) return;
        if (Build.VERSION.SDK_INT < 29) {
            Share.status("Для швидкого прийому потрібен Android 10+");
            return;
        }
        busy = true;
        Share.status("Підключаюсь до іншого телефона...");
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (cb != null) { try { cm.unregisterNetworkCallback(cb); } catch (Exception ignored) {} }
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                    .setSsid(d[0]).setWpa2Passphrase(d[1]).build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec).build();
            final String url = d[2];
            cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    new Thread(() -> download(app, n, url), "sm-share-recv").start();
                }
                @Override public void onUnavailable() {
                    busy = false;
                    Share.status("Не вдалося підключитися. Прикладіть телефони ще раз");
                }
            };
            cm.requestNetwork(req, cb);
        } catch (Exception e) {
            busy = false;
            Share.status("Помилка підключення: " + e.getMessage());
        }
    }

    private static String[] parse(Intent i) {
        Parcelable[] raw = i.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (raw == null) return null;
        for (Parcelable p : raw) {
            for (NdefRecord r : ((NdefMessage) p).getRecords()) {
                if (r.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE
                        && "smshare.app:p2p".equals(new String(r.getType(), StandardCharsets.UTF_8))) {
                    String[] parts = new String(r.getPayload(), StandardCharsets.UTF_8).split("\n");
                    if (parts.length >= 3) return parts;
                }
            }
        }
        return null;
    }

    private static void download(Context app, Network net, String url) {
        try {
            Share.status("Качаю...");
            HttpURLConnection h = (HttpURLConnection) net.openConnection(new URL(url));
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            String name = URLDecoder.decode(url.substring(url.lastIndexOf('/') + 1), "UTF-8");
            String mime = h.getContentType() != null ? h.getContentType() : "application/octet-stream";
            try (InputStream in = h.getInputStream()) {
                saveStream(app, name, mime, in, h.getContentLengthLong());
            }
        } catch (Exception e) {
            Share.status("Помилка прийому: " + e.getMessage());
        } finally {
            busy = false;
            try {
                ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cb != null) cm.unregisterNetworkCallback(cb);
            } catch (Exception ignored) {}
            cb = null;
        }
    }

    static String safeName(String n) {
        if (n == null) return "file";
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "file";
        return n;
    }

    /**
     * Зберігає потік у Завантаження, перевіряє файл (Scan). Небезпечне видаляє.
     * Повертає рівень перевірки (Scan.OK / WARN / BAD). Кидає виняток, якщо прийом обірвався.
     */
    static int saveStream(Context app, String rawName, String mime, InputStream in, long total) throws Exception {
        ContentResolver cr = app.getContentResolver();
        String name = safeName(rawName);
        if (mime == null) mime = "application/octet-stream";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri out = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (out == null) throw new Exception("не вдалося створити файл");
        try {
            long done = 0, last = 0, t0 = System.currentTimeMillis();
            try (OutputStream os = cr.openOutputStream(out)) {
                byte[] buf = new byte[256 * 1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    os.write(buf, 0, r);
                    done += r;
                    if (done - last > 512 * 1024) {
                        last = done;
                        Share.status(total > 0 ? "Приймаю: " + (done * 100 / total) + "%"
                                : "Приймаю: " + MainActivity.human(done));
                    }
                }
            }
            if (total >= 0 && done != total) throw new Exception("передача обірвалась");
            double sec = Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);

            Share.status("Перевіряю файл...");
            Scan.Result sr = Scan.scan(app, out, name);
            if (sr.level >= Scan.BAD) {
                cr.delete(out, null, null);
                Share.status("Файл " + name + " видалено, він виглядає небезпечним:\n" + sr.text());
                return Scan.BAD;
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(out, cv, null, null);

            String speed = String.format("%s, %.2f МБ/с", MainActivity.human(done), done / 1048576.0 / sec);
            if (sr.level == Scan.WARN) {
                Share.status("Готово, але обережно. " + name + " (" + speed + ") у Завантаженнях.\n" + sr.text());
            } else {
                Share.status("Готово! " + name + " у Завантаженнях (" + speed + "). Перевірка: чисто");
                if (name.toLowerCase().endsWith(".apk")) {
                    try {
                        Intent v = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(out, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(v);
                    } catch (Exception ignored) {}
                }
            }
            return sr.level;
        } catch (Exception e) {
            try { cr.delete(out, null, null); } catch (Exception ignored) {}
            throw e;
        }
    }
}
