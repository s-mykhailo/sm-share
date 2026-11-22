package com.savchenko.smshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int PICK_FILE = 1;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView fileTv, statusTv, hintTv;
    private Button stopBtn, nfcBtn;
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

        stopBtn = button("Зупинити", false);
        stopBtn.setOnClickListener(v -> { Share.stop(); refresh(); });
        root.addView(stopBtn);

        nfcBtn = button("Увімкнути NFC", false);
        nfcBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
        root.addView(nfcBtn);

        hintTv = text(13, 0xFF8A8A8A);
        ((LinearLayout.LayoutParams) hintTv.getLayoutParams()).topMargin = dp(22);
        root.addView(hintTv);

        setContentView(root);
    }

    private void refresh() {
        boolean nfcOff = nfc == null || !nfc.isEnabled();
        nfcBtn.setVisibility(nfc != null && !nfc.isEnabled() ? View.VISIBLE : View.GONE);

        if (!Share.active()) {
            fileTv.setText("Файл не вибрано");
            statusTv.setText(nfc == null ? "На цьому телефоні нема NFC" : "Обери, що передати");
            stopBtn.setVisibility(View.GONE);
            hintTv.setText("");
            return;
        }
        fileTv.setText(Share.name + (Share.size >= 0 ? "  ·  " + human(Share.size) : ""));
        stopBtn.setVisibility(View.VISIBLE);
        String url = Share.url();
        if (nfcOff) {
            statusTv.setText("Увімкни NFC");
        } else if (url == null) {
            statusTv.setText("Нема Wi-Fi. Увімкни Wi-Fi або точку доступу");
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
        if (req == PICK_FILE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            useUri(u);
        }
    }

    private void handleIntent(Intent i) {
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
