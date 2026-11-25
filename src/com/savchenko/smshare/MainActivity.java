package com.savchenko.smshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int PICK_FILE = 1;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView fileTv, statusTv, hintTv;
    private Button stopBtn, nfcBtn, btBtn, fastBtn, btRecvBtn;
    private static final int REQ_PERM = 2, REQ_BT = 3, REQ_DISC = 4;
    private int pendingBt;
    private NfcAdapter nfc;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        nfc = NfcAdapter.getDefaultAdapter(this);
        buildUi();
        Share.listener = text -> ui.post(() -> statusTv.setText(text));
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (nfc != null) CardEmulation.getInstance(nfc)
                    .setPreferredService(this, new ComponentName(this, NdefHceService.class));
        } catch (Exception ignored) {}
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (nfc != null) CardEmulation.getInstance(nfc).unsetPreferredService(this);
        } catch (Exception ignored) {}
    }

    // ---------- UI ----------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private Button button(String text, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(17);
        btn.setTextColor(primary ? Color.BLACK : Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (primary) bg.setColor(Color.WHITE);
        else { bg.setColor(0xFF1C1C1C); bg.setStroke(dp(1), 0xFF3A3A3A); }
        btn.setBackground(bg);
        btn.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.topMargin = dp(12);
        btn.setLayoutParams(lp);
        return btn;
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(0xFF0B0B0B);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = text(34, Color.WHITE);
        title.setText("SM Share");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        fileTv = text(16, 0xFFBDBDBD);
        root.addView(fileTv);

        statusTv = text(20, Color.WHITE);
        statusTv.setTypeface(Typeface.DEFAULT_BOLD);
        ((LinearLayout.LayoutParams) statusTv.getLayoutParams()).bottomMargin = dp(20);
        root.addView(statusTv);

        Button pick = button("Обрати файл", true);
        pick.setOnClickListener(v -> pickFile());
        root.addView(pick);

        Button app = button("Обрати встановлений додаток (APK)", false);
        app.setOnClickListener(v -> pickApp());
        root.addView(app);

        fastBtn = button("Швидко: Wi-Fi Direct (SM Share на обох)", true);
        fastBtn.setOnClickListener(v -> startFast());
        root.addView(fastBtn);

        btBtn = button("Передати через Bluetooth", false);
        btBtn.setOnClickListener(v -> startBtSend());
        root.addView(btBtn);

        btRecvBtn = button("Отримати по Bluetooth", false);
        btRecvBtn.setOnClickListener(v -> startBtReceive());
        root.addView(btRecvBtn);

        stopBtn = button("Зупинити", false);
        stopBtn.setOnClickListener(v -> { Direct.stop(); Share.stop(); Bt.stopListen(); refresh(); });
        root.addView(stopBtn);

        nfcBtn = button("Увімкнути NFC", false);
        nfcBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
        root.addView(nfcBtn);

        hintTv = text(13, 0xFF8A8A8A);
        ((LinearLayout.LayoutParams) hintTv.getLayoutParams()).topMargin = dp(22);
        root.addView(hintTv);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFF0B0B0B);
        sv.addView(root);
        setContentView(sv);
    }

    private void refresh() {
        if (Receiver.busy) return;
        boolean nfcOff = nfc == null || !nfc.isEnabled();
        nfcBtn.setVisibility(nfc != null && !nfc.isEnabled() ? View.VISIBLE : View.GONE);
        btRecvBtn.setVisibility(Bt.listening ? View.GONE : View.VISIBLE);

        if (!Share.active()) {
            fileTv.setText(Bt.listening ? "Приймаю по Bluetooth" : "Файл не вибрано");
            statusTv.setText(Bt.listening ? "Чекаю файл. На іншому телефоні: Передати через Bluetooth"
                    : nfc == null ? "На цьому телефоні нема NFC" : "Обери, що передати");
            stopBtn.setVisibility(Bt.listening ? View.VISIBLE : View.GONE);
            btBtn.setVisibility(View.GONE);
            fastBtn.setVisibility(View.GONE);
            hintTv.setText("");
            return;
        }
        btBtn.setVisibility(View.VISIBLE);
        fastBtn.setVisibility(Share.directSsid == null ? View.VISIBLE : View.GONE);
        if (Share.directSsid != null) {
            statusTv.setText("Швидкий режим готовий. Приклади телефон з SM Share до спини цього");
            hintTv.setText("Другий телефон: Android 10+, SM Share відкритий, NFC увімкнено. Він сам підключиться по Wi-Fi Direct і збереже файл у Завантаження.\n\nРучне підключення:\nМережа: " + Share.directSsid + "\nПароль: " + Share.directPass + "\nПосилання: " + Share.url());
            return;
        }
        fileTv.setText(Share.name + (Share.size >= 0 ? "  ·  " + human(Share.size) : ""));
        stopBtn.setVisibility(View.VISIBLE);
        String url = Share.url();
        if (nfcOff) {
            statusTv.setText("Увімкни NFC");
        } else if (url == null) {
            statusTv.setText("Нема Wi-Fi. Увімкни Wi-Fi або точку доступу, або передай через Bluetooth");
        } else {
            statusTv.setText("Готово. Приклади інший телефон до спини цього");
        }
        hintTv.setText("Другий телефон: розблокований, NFC увімкнено, та сама Wi-Fi або твоя точка доступу.\n"
                + "Якщо NFC не спрацює, відкрий у браузері:\n" + (url == null ? "-" : url));
    }

    static String human(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format("%.1f КБ", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f МБ", b / 1048576.0);
        return String.format("%.2f ГБ", b / 1073741824.0);
    }

    // ---------- швидкий режим (Wi-Fi Direct) ----------

    private void startFast() {
        if (!Share.active()) return;
        String perm = Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.NEARBY_WIFI_DEVICES
                : android.Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_PERM);
            return;
        }
        statusTv.setText("Створюю швидку мережу...");
        Direct.start(this, (ok, msg) -> ui.post(() -> {
            if (!ok) statusTv.setText(msg);
            refresh();
        }));
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startFast();
        else if (req == REQ_PERM) statusTv.setText("Без дозволу швидкий режим не працює");
        else if (req == REQ_BT) {
            boolean all = res.length > 0;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) all = false;
            if (!all) statusTv.setText("Без дозволу Bluetooth не працює");
            else if (pendingBt == 1) startBtSend();
            else if (pendingBt == 2) startBtReceive();
        }
    }

    // ---------- Bluetooth між двома SM Share ----------

    private boolean ensureBt(int action) {
        String[] want = Build.VERSION.SDK_INT >= 31
                ? new String[]{android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE}
                : new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> need = new ArrayList<>();
        for (String p : want) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (need.isEmpty()) return true;
        pendingBt = action;
        requestPermissions(need.toArray(new String[0]), REQ_BT);
        return false;
    }

    private void startBtReceive() {
        if (!ensureBt(2)) return;
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try { startActivityForResult(i, REQ_DISC); } catch (Exception e) { beginListen(); }
    }

    private void beginListen() {
        Bt.listen(this, this::askAccept);
        refresh();
    }

    /** Викликається з фонового потоку: питає, чи приймати файл. */
    private boolean askAccept(String name, long size, String from) {
        final boolean[] ans = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Прийняти файл?")
                .setMessage(name + "\n" + human(size) + (from.isEmpty() ? "" : "\nВід: " + from))
                .setCancelable(false)
                .setPositiveButton("Прийняти", (d, w) -> { ans[0] = true; latch.countDown(); })
                .setNegativeButton("Відхилити", (d, w) -> latch.countDown())
                .show());
        try { latch.await(90, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ans[0];
    }

    private void startBtSend() {
        if (!Share.active()) return;
        if (!ensureBt(1)) return;
        BluetoothAdapter a = Bt.adapter(this);
        if (a == null) { statusTv.setText("На цьому телефоні нема Bluetooth"); return; }
        if (!a.isEnabled()) {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) {}
            statusTv.setText("Увімкни Bluetooth і натисни ще раз");
            return;
        }
        showDevicePicker(a);
    }

    private void showDevicePicker(BluetoothAdapter a) {
        final List<BluetoothDevice> devs = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        ad.add("Телефон без SM Share (системний обмін)");
        devs.add(null);
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                devs.add(d);
                String n = null;
                try { n = d.getName(); } catch (Exception ignored) {}
                ad.add(n != null ? n : d.getAddress());
            }
        } catch (Exception ignored) {}
        final BroadcastReceiver rc = new FoundReceiver(devs, ad);
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rc, f);
        try { a.startDiscovery(); } catch (Exception ignored) {}
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Кому передати? (на ньому: Отримати по Bluetooth)")
                .setAdapter(ad, (d, which) -> {
                    try { a.cancelDiscovery(); } catch (Exception ignored) {}
                    if (which == 0) sendBluetooth();
                    else Bt.send(this, devs.get(which));
                })
                .setOnDismissListener(d -> {
                    try { unregisterReceiver(rc); } catch (Exception ignored) {}
                    try { a.cancelDiscovery(); } catch (Exception ignored) {}
                })
                .show();
    }

    // ---------- Bluetooth: системний обмін (для телефона без SM Share) ----------

    /** Віддає файл системному Bluetooth-обміну (OPP). На другому телефоні додаток не потрібен. */
    private void sendBluetooth() {
        if (!Share.active()) return;
        PackageManager pm = getPackageManager();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(Share.mime != null ? Share.mime : "application/octet-stream");
        send.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(Share.name));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String btPkg = null;
        for (ResolveInfo ri : pm.queryIntentActivities(send, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.toLowerCase().contains("bluetooth")) { btPkg = p; break; }
        }
        try {
            if (btPkg != null) {
                send.setPackage(btPkg);
                startActivity(send);
            } else {
                startActivity(Intent.createChooser(send, "Передати через Bluetooth"));
            }
        } catch (Exception e) {
            statusTv.setText("Не вдалося відкрити Bluetooth-обмін");
        }
    }

    // ---------- вибір ----------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DISC) { beginListen(); return; }
        if (req == PICK_FILE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            useUri(u);
        }
    }

    private void handleIntent(Intent i) {
        if (Receiver.isP2p(i)) { Receiver.receive(this, i); return; }
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) useUri(u);
        }
    }

    private void useUri(Uri u) {
        String n = "file";
        long sz = -1;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) n = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) sz = c.getLong(si);
            }
        } catch (Exception ignored) {}
        String m = getContentResolver().getType(u);
        if (n.toLowerCase().endsWith(".apk")) m = APK_MIME;
        if (m == null) m = "application/octet-stream";
        Share.set(this, u, null, n, sz, m);
        refresh();
    }

    private void pickApp() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            boolean split = ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0;
            if (!system && !split && ai.sourceDir != null) apps.add(ai);
        }
        Collections.sort(apps, (x, y) -> pm.getApplicationLabel(x).toString()
                .compareToIgnoreCase(pm.getApplicationLabel(y).toString()));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = pm.getApplicationLabel(apps.get(i)).toString();

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Який додаток передати?")
                .setItems(labels, (d, which) -> {
                    ApplicationInfo ai = apps.get(which);
                    File f = new File(ai.sourceDir);
                    String n = labels[which].replaceAll("[\\\\/:*?\"<>|]", "_") + ".apk";
                    Share.set(this, null, f, n, f.length(), APK_MIME);
                    refresh();
                })
                .show();
    }
}
