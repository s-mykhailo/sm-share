package com.savchenko.smshare;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.security.SecureRandom;

/** Швидкий режим: цей телефон створює Wi-Fi Direct групу (5 ГГц), інший підключається до неї без інтернету. */
final class Direct {

    interface Cb { void done(boolean ok, String msg); }

    private static WifiP2pManager mgr;
    private static WifiP2pManager.Channel ch;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Direct() {}

    static void start(Context c, Cb cb) {
        Context app = c.getApplicationContext();
        mgr = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mgr == null) { cb.done(false, "На цьому телефоні нема Wi-Fi Direct"); return; }
        ch = mgr.initialize(app, Looper.getMainLooper(), null);
        try {
            mgr.removeGroup(ch, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { create(true, cb); }
                @Override public void onFailure(int r) { create(true, cb); }
            });
        } catch (Exception e) {
            cb.done(false, "Нема дозволу на Wi-Fi Direct");
        }
    }

    private static String randomPass() {
        String a = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(a.charAt(r.nextInt(a.length())));
        return sb.toString();
    }

    private static void create(boolean band5, Cb cb) {
        WifiP2pManager.ActionListener l = new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { readInfo(8, cb); }
            @Override public void onFailure(int reason) {
                if (band5 && Build.VERSION.SDK_INT >= 29) create(false, cb);
                else cb.done(false, "Wi-Fi Direct не запустився (код " + reason + "). Увімкни Wi-Fi і геолокацію");
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-sm-Share")
                        .setPassphrase(randomPass())
                        .enablePersistentMode(false)
                        .setGroupOperatingBand(band5 ? WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                                : WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                        .build();
                mgr.createGroup(ch, cfg, l);
            } else {
                mgr.createGroup(ch, l);
            }
        } catch (Exception e) {
            cb.done(false, "Нема дозволу на Wi-Fi Direct");
        }
    }

    private static void readInfo(int tries, Cb cb) {
        try {
            mgr.requestGroupInfo(ch, (WifiP2pGroup g) -> {
                if (g != null && g.getNetworkName() != null && g.getPassphrase() != null) {
                    Share.directSsid = g.getNetworkName();
                    Share.directPass = g.getPassphrase();
                    cb.done(true, null);
                } else if (tries > 0) {
                    ui.postDelayed(() -> readInfo(tries - 1, cb), 500);
                } else {
                    cb.done(false, "Не вдалося створити мережу");
                }
            });
        } catch (Exception e) {
            cb.done(false, "Нема дозволу на Wi-Fi Direct");
        }
    }

    static void stop() {
        Share.directSsid = null;
        Share.directPass = null;
        try { if (mgr != null && ch != null) mgr.removeGroup(ch, null); } catch (Exception ignored) {}
    }
}
