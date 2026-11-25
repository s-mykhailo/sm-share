package com.savchenko.smshare;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * Передача між двома SM Share по Bluetooth (RFCOMM), без пари і без системних діалогів обміну.
 * Приймач слухає, відправник знаходить його пошуком, приймач підтверджує файл, далі йде потік.
 */
final class Bt {

    static final UUID SERVICE = UUID.fromString("5b0e7e1a-8f3c-4c2e-9b6a-53a7c1d0e5f1");
    private static final int MAGIC = 0x534D5348; // "SMSH"
    private static final int BUF = 64 * 1024;

    interface Accept { boolean ask(String name, long size, String from); }

    static volatile boolean listening;
    private static BluetoothServerSocket server;

    private Bt() {}

    static BluetoothAdapter adapter(Context c) {
        BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    // ---------- прийом ----------

    static synchronized void listen(Context c, Accept accept) {
        stopListen();
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) { Share.status("Увімкни Bluetooth"); return; }
        final Context app = c.getApplicationContext();
        final BluetoothServerSocket ss;
        try {
            ss = a.listenUsingInsecureRfcommWithServiceRecord("SM Share", SERVICE);
        } catch (Exception e) {
            Share.status("Bluetooth не запустився: " + e.getMessage());
            return;
        }
        server = ss;
        listening = true;
        Thread t = new Thread(() -> {
            while (listening && server == ss) {
                try {
                    BluetoothSocket s = ss.accept();
                    handle(app, s, accept);
                } catch (Exception e) {
                    break;
                }
            }
            synchronized (Bt.class) { if (server == ss) { listening = false; server = null; } }
        }, "sm-share-bt");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void stopListen() {
        listening = false;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
            server = null;
        }
    }

    private static void handle(Context app, BluetoothSocket s, Accept accept) {
        Receiver.busy = true;
        try (BluetoothSocket sock = s) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), BUF));
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            if (in.readInt() != MAGIC) return;
            String name = in.readUTF();
            String mime = in.readUTF();
            long size = in.readLong();
            if (size < 0) return;
            String from = "";
            try { from = sock.getRemoteDevice().getName(); } catch (Exception ignored) {}
            if (from == null) from = "";
            boolean ok = accept.ask(Receiver.safeName(name), size, from);
            out.writeByte(ok ? 1 : 0);
            out.flush();
            if (!ok) { Share.status("Прийом відхилено"); return; }
            Share.status("Приймаю по Bluetooth...");
            int lvl = Receiver.saveStream(app, name, mime, new Limited(in, size), size);
            out.writeByte(lvl >= Scan.BAD ? 2 : 1);
            out.flush();
        } catch (Exception e) {
            Share.status("Помилка Bluetooth: " + e.getMessage());
        } finally {
            Receiver.busy = false;
        }
    }

    /** Віддає рівно size байтів з потоку. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private long left;

        Limited(InputStream in, long size) { this.in = in; this.left = size; }

        @Override public int read() throws java.io.IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }

    // ---------- передача ----------

    static void send(Context c, BluetoothDevice dev) {
        final Context app = c.getApplicationContext();
        final String name = Share.name, mime = Share.mime;
        final long declared = Share.size;
        final File f = Share.file;
        final Uri u = Share.uri;
        new Thread(() -> {
            BluetoothAdapter a = adapter(app);
            try {
                long size = declared;
                if (size < 0 && u != null) {
                    try (AssetFileDescriptor afd = app.getContentResolver().openAssetFileDescriptor(u, "r")) {
                        if (afd != null) size = afd.getLength();
                    }
                }
                if (size < 0) { Share.status("Невідомий розмір файлу"); return; }
                if (a != null) a.cancelDiscovery();
                Share.status("Підключаюсь...");
                try (BluetoothSocket s = dev.createInsecureRfcommSocketToServiceRecord(SERVICE)) {
                    s.connect();
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), BUF));
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    out.writeInt(MAGIC);
                    out.writeUTF(name);
                    out.writeUTF(mime == null ? "application/octet-stream" : mime);
                    out.writeLong(size);
                    out.flush();
                    Share.status("Чекаю підтвердження на іншому телефоні...");
                    if (in.readByte() != 1) { Share.status("Інший телефон відхилив файл"); return; }

                    InputStream src = f != null ? new FileInputStream(f) : app.getContentResolver().openInputStream(u);
                    if (src == null) { Share.status("Не вдалося прочитати файл"); return; }
                    long sent = 0, last = 0, t0 = System.currentTimeMillis();
                    try (InputStream is = src) {
                        byte[] buf = new byte[BUF];
                        int r;
                        while ((r = is.read(buf)) != -1) {
                            out.write(buf, 0, r);
                            sent += r;
                            if (sent - last > 128 * 1024) {
                                last = sent;
                                double sec = Math.max(0.5, (System.currentTimeMillis() - t0) / 1000.0);
                                Share.status(String.format("Передаю по Bluetooth: %d%% (%.0f КБ/с)",
                                        size > 0 ? sent * 100 / size : 0, sent / 1024.0 / sec));
                            }
                        }
                    }
                    out.flush();
                    int res = in.readByte();
                    Share.status(res == 1 ? "Готово! Файл передано (" + MainActivity.human(sent) + ")"
                            : "Інший телефон видалив файл як небезпечний");
                }
            } catch (Exception e) {
                Share.status("Не вдалося передати: " + e.getMessage()
                        + ". Перевір, що на іншому телефоні натиснуто 'Отримати по Bluetooth'");
            }
        }, "sm-share-bt-send").start();
    }
}
