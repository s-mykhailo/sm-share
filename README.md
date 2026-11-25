# SM Share

[Українська](README.uk.md)

Send a file or an installed app (APK) from one Android phone to another by tapping them together. Only the sending phone needs the app: the other phone just opens a download link in its browser.

<p>
  <img src="docs/screenshots/main.jpg" width="250">
</p>

## How it works

1. Pick a file, pick an installed app (its APK is taken from the system), or use "Share" from any app and choose SM Share.
2. SM Share starts a small HTTP server on the phone that serves only that file.
3. The phone emulates an NFC tag (NFC Forum Type 4, via Host Card Emulation) that contains the download link.
4. The other phone taps the back of this one, its browser opens the link and downloads the file over Wi-Fi.

If NFC does not work, the link is shown on screen and can be typed in by hand.

### Fast mode: Wi-Fi Direct (SM Share on both phones)

"Fast: Wi-Fi Direct" makes the sending phone create its own 5 GHz Wi-Fi Direct network (no router, no internet). Tap the phones: NFC passes the network and link, the receiving phone (Android 10+, SM Share open) joins it, confirms the system "connect" prompt, and saves the file to Downloads. Typically 10-30 MB/s, versus about 0.2 MB/s over Bluetooth.

### Without internet or Wi-Fi: Bluetooth

With SM Share on both phones: on the receiver press "Receive via Bluetooth" (it becomes visible for 5 minutes), on the sender press "Send via Bluetooth" and pick the receiver from the list. No pairing and no system share dialog; the receiver confirms the file, SM Share transfers it and saves it to Downloads. Bluetooth Classic is slow (roughly 0.1-0.25 MB/s), so use Wi-Fi Direct for big files.

For a phone without SM Share, pick "Phone without SM Share" in the list: the file goes to the system Bluetooth share (OBEX), you pair the phones and accept the file there.

### Safety check

Every received file is checked before it is kept (offline, only the start of the file is read): fake extensions (`photo.jpg.apk`), executable code hidden under a media name, hidden right-to-left characters in the name, and APKs that ask for risky permissions (SMS, accessibility, device admin, overlay, notification access). Dangerous files are deleted, doubtful ones are kept with a warning and are not opened automatically. This is not a real antivirus: there is no malware signature database, so it will not catch a well made virus. Install apps only from people you trust.

## Requirements

- Sending phone: Android 8+, NFC with HCE (most phones with NFC)
- Receiving phone: NFC turned on, unlocked, any browser
- Both on the same Wi-Fi, or the receiver connected to the sender's hotspot
- To install an APK on the receiver, its browser needs "Install unknown apps" permission

Apps split into several APKs (some Play Store apps) are not listed, because a single APK from them will not install.

## Tech

- Java, Android SDK only, no dependencies, no Gradle
- `NdefHceService` - NFC Type 4 tag emulation (`HostApduService`)
- `Share` - the file server (plain `ServerSocket`) and the NDEF message
- `MainActivity` - UI built in code
- minSdk 26, targetSdk 34, APK size about 25 KB

## Build

Needs JDK 17+ and Android SDK (`platforms;android-34`, `build-tools;34.0.0`).

```sh
export ANDROID_HOME=/path/to/android-sdk
export SM_KS_PASS=your-keystore-password   # a keystore is created on first run if missing
sh build.sh
```

## Author

S-Mykhailo - [@savchenko_mykhailo](https://t.me/savchenko_mykhailo)

## License

GPL-3.0-or-later. You can use, change and share this code, but any modified version you distribute must also be open source under the GPL. See [LICENSE](LICENSE).
