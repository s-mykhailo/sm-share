package com.savchenko.smshare;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Базова перевірка отриманого файлу, без інтернету і без баз сигнатур.
 * Це не повноцінний антивірус: ловить підроблені розширення, замаскований виконуваний код
 * і підозрілі APK. Читає лише початок файлу, тому швидкість передачі не страждає.
 */
final class Scan {

    static final int OK = 0, WARN = 1, BAD = 2;

    static final class Result {
        int level;
        final List<String> reasons = new ArrayList<>();

        void add(int lvl, String r) {
            if (lvl > level) level = lvl;
            reasons.add(r);
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (String r : reasons) sb.append("- ").append(r).append("\n");
            return sb.toString().trim();
        }
    }

    private static final Set<String> EXEC = new HashSet<>(Arrays.asList(
            "exe", "bat", "cmd", "scr", "com", "msi", "jar", "js", "vbs", "ps1", "sh", "dex", "so",
            "lnk", "apks", "xapk", "apkm", "pif", "reg", "hta"));
    private static final Set<String> MEDIA = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "pdf", "txt", "csv", "mp3", "mp4",
            "mkv", "avi", "wav", "flac", "ogg", "m4a"));
    private static final Map<String, String> RISKY = new HashMap<>();

    static {
        String p = "android.permission.";
        RISKY.put(p + "SEND_SMS", "SMS");
        RISKY.put(p + "READ_SMS", "SMS");
        RISKY.put(p + "RECEIVE_SMS", "SMS");
        RISKY.put(p + "BIND_ACCESSIBILITY_SERVICE", "доступність");
        RISKY.put(p + "BIND_DEVICE_ADMIN", "адмін пристрою");
        RISKY.put(p + "SYSTEM_ALERT_WINDOW", "поверх екрана");
        RISKY.put(p + "REQUEST_INSTALL_PACKAGES", "встановлення додатків");
        RISKY.put(p + "READ_CALL_LOG", "журнал дзвінків");
        RISKY.put(p + "PROCESS_OUTGOING_CALLS", "дзвінки");
        RISKY.put(p + "BIND_NOTIFICATION_LISTENER_SERVICE", "читання сповіщень");
        RISKY.put(p + "MANAGE_EXTERNAL_STORAGE", "усі файли");
    }

    private Scan() {}

    static Result scan(Context c, Uri uri, String name) {
        Result r = new Result();
        String n = name == null ? "" : name;
        String low = n.toLowerCase();
        int dot = low.lastIndexOf('.');
        String ext = dot >= 0 ? low.substring(dot + 1) : "";

        if (n.indexOf('‮') >= 0)
            r.add(BAD, "в імені прихований символ, що підміняє розширення");
        if (low.matches(".*\\.(jpg|jpeg|png|gif|pdf|doc|docx|xls|xlsx|mp3|mp4|txt|zip)\\.(apk|exe|bat|scr|js|vbs|sh|jar|cmd|com|ps1|lnk)$"))
            r.add(BAD, "подвійне розширення, файл видає себе за безпечний");

        byte[] h = new byte[8];
        int got = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int x;
                while (got < h.length && (x = in.read(h, got, h.length - got)) > 0) got += x;
            }
        } catch (Exception e) {
            r.add(WARN, "не вдалося прочитати файл для перевірки");
            return r;
        }
        if (got == 0) {
            r.add(WARN, "файл порожній");
            return r;
        }

        boolean mz = h[0] == 'M' && h[1] == 'Z';
        boolean elf = got >= 4 && h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        boolean dex = got >= 4 && h[0] == 'd' && h[1] == 'e' && h[2] == 'x' && h[3] == '\n';
        boolean script = h[0] == '#' && h[1] == '!';
        boolean zip = got >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;

        if ((mz || elf || dex || script) && !EXEC.contains(ext)) {
            if (ext.isEmpty()) r.add(WARN, "виконуваний файл без розширення");
            else r.add(BAD, "всередині виконуваний код, а розширення ." + ext);
        }
        if (zip && MEDIA.contains(ext))
            r.add(WARN, "всередині архів, а не ." + ext);
        if (EXEC.contains(ext))
            r.add(WARN, "виконуваний файл (." + ext + "), відкривай тільки якщо знаєш звідки він");
        if (ext.equals("apk")) apk(c, uri, r);
        return r;
    }

    private static void apk(Context c, Uri u, Result r) {
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(u, "r")) {
            PackageManager pm = c.getPackageManager();
            String path = "/proc/self/fd/" + pfd.getFd();
            int flags = PackageManager.GET_PERMISSIONS
                    | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES);
            PackageInfo pi = pm.getPackageArchiveInfo(path, flags);
            if (pi == null) {
                r.add(WARN, "не вдалося прочитати APK (можливо, пошкоджений)");
                return;
            }
            Set<String> hit = new LinkedHashSet<>();
            if (pi.requestedPermissions != null)
                for (String p : pi.requestedPermissions) {
                    String s = RISKY.get(p);
                    if (s != null) hit.add(s);
                }
            if (!hit.isEmpty())
                r.add(WARN, "додаток просить небезпечні дозволи: " + String.join(", ", hit));
            try {
                PackageInfo inst = pm.getPackageInfo(pi.packageName, flags);
                Set<String> a = sigs(pi), b = sigs(inst);
                if (!a.isEmpty() && !b.isEmpty()) {
                    boolean same = false;
                    for (String x : a) if (b.contains(x)) same = true;
                    if (!same) r.add(WARN, "підпис не збігається з уже встановленим " + pi.packageName
                            + ", поверх нього не встановиться");
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        } catch (Exception e) {
            r.add(WARN, "не вдалося перевірити APK");
        }
    }

    private static Set<String> sigs(PackageInfo pi) {
        Set<String> out = new HashSet<>();
        Signature[] arr = null;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            arr = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners()
                    : pi.signingInfo.getSigningCertificateHistory();
        } else if (pi.signatures != null) {
            arr = pi.signatures;
        }
        if (arr != null) for (Signature s : arr) out.add(s.toCharsString());
        return out;
    }
}
