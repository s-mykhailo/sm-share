package com.savchenko.smshare;

import android.content.Context;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Тримає вибраний файл і маленький HTTP-сервер, з якого другий телефон його качає. */
final class Share {

    interface Listener { void onStatus(String text); }

    static volatile Listener listener;

    static volatile Uri uri;
    static volatile File file;
    static volatile String name;
    static volatile String mime;
    static volatile long size = -1;

    private static Context app;
    private static ServerSocket server;
    static volatile int port;

    /** Швидкий режим (Wi-Fi Direct): дані мережі, яку створив цей телефон. */
    static volatile String directSsid, directPass;
    static final String DIRECT_IP = "192.168.49.1";

    private Share() {}

    static void status(String s) {
        Listener l = listener;
        if (l != null) l.onStatus(s);
    }

    static synchronized void set(Context c, Uri u, File f, String n, long sz, String m) {
        app = c.getApplicationContext();
        uri = u; file = f; name = n; size = sz; mime = m;
        startServer();
    }

    static synchronized void stop() {
        name = null; uri = null; file = null; size = -1;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
            server = null;
        }
    }

    static boolean active() { return name != null && server != null; }

    private static void startServer() {
        if (server != null) return;
        for (int p = 8765; p < 8790; p++) {
            try { server = new ServerSocket(p); port = p; break; } catch (Exception ignored) {}
        }
        if (server == null) { status("Не вдалося запустити сервер"); return; }
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s)).start();
                } catch (Exception e) {
                    break;
                }
            }
        }, "sm-share-server");
        t.setDaemon(true);
        t.start();
    }

    static String ip() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String s = a.getHostAddress();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return s;
                    if (fallback == null && !n.startsWith("rmnet")) fallback = s;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return "file"; }
    }

    static String url() {
        String ip = directSsid != null ? DIRECT_IP : ip();
        if (ip == null || !active()) return null;
        return "http://" + ip + ":" + port + "/d/" + enc(name);
    }

    static byte[] ndefMessage() {
        String u = url();
        if (u == null) return null;
        if (directSsid != null) {
            byte[] pl = (directSsid + "\n" + directPass + "\n" + u).getBytes(StandardCharsets.UTF_8);
            return new NdefMessage(NdefRecord.createExternal("smshare.app", "p2p", pl),
                    NdefRecord.createUri(u)).toByteArray();
        }
        return new NdefMessage(NdefRecord.createUri(u)).toByteArray();
    }

    // ---------- HTTP ----------

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            String line = readLine(in);
            if (line == null) return;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) { /* пропускаємо заголовки */ }
            String[] parts = line.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);

            if (name == null) {
                simple(out, "404 Not Found", "text/plain; charset=utf-8", "Файл більше не роздається");
                return;
            }
            if (path.startsWith("/d/")) {
                serveFile(out, method.equals("HEAD"));
            } else if (path.equals("/") || path.startsWith("/?")) {
                String link = "/d/" + enc(name);
                String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                        + "<body style='background:#111;color:#eee;font-family:sans-serif;text-align:center;padding-top:30vh'>"
                        + "<p>" + escape(name) + "</p><p><a style='color:#fff;font-size:22px' href='" + link
                        + "'>Завантажити</a></p></body>";
                simple(out, "200 OK", "text/html; charset=utf-8", html);
            } else {
                simple(out, "404 Not Found", "text/plain; charset=utf-8", "404");
            }
        } catch (Exception ignored) {}
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void simple(OutputStream out, String code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void serveFile(OutputStream out, boolean headOnly) throws Exception {
        String n = name;
        long total = size;
        String ascii = n.replaceAll("[^A-Za-z0-9._-]", "_");
        StringBuilder head = new StringBuilder("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(mime).append("\r\n")
                .append("Content-Disposition: attachment; filename=\"").append(ascii)
                .append("\"; filename*=UTF-8''").append(enc(n)).append("\r\n")
                .append("Connection: close\r\n");
        if (total >= 0) head.append("Content-Length: ").append(total).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (headOnly) { out.flush(); return; }

        InputStream in = file != null ? new FileInputStream(file) : app.getContentResolver().openInputStream(uri);
        if (in == null) return;
        try (InputStream src = in) {
            byte[] buf = new byte[256 * 1024];
            long sent = 0, lastReport = 0;
            int r;
            status("Передаю...");
            while ((r = src.read(buf)) != -1) {
                out.write(buf, 0, r);
                sent += r;
                if (sent - lastReport > 512 * 1024) {
                    lastReport = sent;
                    status(total > 0 ? "Передаю: " + (sent * 100 / total) + "%" : "Передаю: " + (sent / 1024) + " КБ");
                }
            }
            out.flush();
            status("Готово! Файл передано (" + MainActivity.human(sent) + ")");
        }
    }
}
