package com.savchenko.smshare;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.ArrayAdapter;

import java.util.List;

/** Ловить знайдені Bluetooth-пристрої під час пошуку. Окремий клас, щоб не тримати MainActivity. */
final class FoundReceiver extends BroadcastReceiver {

    private final List<BluetoothDevice> devs;
    private final ArrayAdapter<String> ad;

    FoundReceiver(List<BluetoothDevice> devs, ArrayAdapter<String> ad) {
        this.devs = devs;
        this.ad = ad;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        BluetoothDevice d = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (d == null) return;
        for (BluetoothDevice x : devs) if (x != null && x.getAddress().equals(d.getAddress())) return;
        String n = null;
        try { n = d.getName(); } catch (Exception ignored) {}
        devs.add(d);
        ad.add(n != null ? n : d.getAddress());
    }
}
