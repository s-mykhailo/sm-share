package com.savchenko.smshare;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

/** Прикидається NFC-міткою (NFC Forum Type 4), на якій записане посилання на файл. */
public class NdefHceService extends HostApduService {

    private static final byte[] AID = {(byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01};
    private static final byte[] OK = {(byte) 0x90, 0x00};
    private static final byte[] NOT_FOUND = {(byte) 0x6A, (byte) 0x82};
    private static final byte[] WRONG_OFFSET = {(byte) 0x6B, 0x00};
    private static final byte[] UNKNOWN = {(byte) 0x6D, 0x00};

    // Capability Container: версія 2.0, NDEF-файл E104 до 1024 байт, тільки читання
    private static final byte[] CC = {
            0x00, 0x0F, 0x20, 0x00, (byte) 0xFF, 0x00, (byte) 0xFF,
            0x04, 0x06, (byte) 0xE1, 0x04, 0x04, 0x00, 0x00, (byte) 0xFF
    };

    private int selected = 0; // 0 - нічого, 1 - CC, 2 - NDEF
    private byte[] ndefFile;

    @Override
    public byte[] processCommandApdu(byte[] a, Bundle extras) {
        if (a == null || a.length < 4) return UNKNOWN;
        int ins = a[1] & 0xFF;
        int p1 = a[2] & 0xFF;

        if (ins == 0xA4) { // SELECT
            if (p1 == 0x04) {
                if (a.length >= 5 + AID.length && (a[4] & 0xFF) == AID.length) {
                    for (int i = 0; i < AID.length; i++) if (a[5 + i] != AID[i]) return NOT_FOUND;
                    selected = 0;
                    return OK;
                }
                return NOT_FOUND;
            }
            if (a.length >= 7) {
                int fid = ((a[5] & 0xFF) << 8) | (a[6] & 0xFF);
                if (fid == 0xE103) { selected = 1; return OK; }
                if (fid == 0xE104) {
                    byte[] msg = Share.ndefMessage();
                    if (msg == null) { selected = 0; return NOT_FOUND; }
                    ndefFile = new byte[msg.length + 2];
                    ndefFile[0] = (byte) (msg.length >> 8);
                    ndefFile[1] = (byte) msg.length;
                    System.arraycopy(msg, 0, ndefFile, 2, msg.length);
                    selected = 2;
                    Share.status("Телефон прикладено, відкриваю посилання на ньому...");
                    return OK;
                }
            }
            return NOT_FOUND;
        }

        if (ins == 0xB0) { // READ BINARY
            byte[] src = selected == 1 ? CC : selected == 2 ? ndefFile : null;
            if (src == null) return NOT_FOUND;
            int off = ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
            int le = a.length > 4 ? (a[4] & 0xFF) : 0;
            if (le == 0) le = 256;
            if (off > src.length) return WRONG_OFFSET;
            int n = Math.min(le, src.length - off);
            byte[] r = new byte[n + 2];
            System.arraycopy(src, off, r, 0, n);
            r[n] = (byte) 0x90;
            r[n + 1] = 0x00;
            return r;
        }
        return UNKNOWN;
    }

    @Override
    public void onDeactivated(int reason) {
        selected = 0;
    }
}
