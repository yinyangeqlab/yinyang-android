# YinYangEQ for Android

One companion supports the original Qudelix 5K and the Qudelix 5K Plus. Link one DAC at a time. The app identifies the model before loading its controls and keeps local drafts for that unit. Yin Yang EQ Lab develops this independent app.

| Download | Android version | Release |
| --- | --- | --- |
| [YingYangEQ App Manager](https://github.com/yinyangeqlab/yinyang-android/releases/latest/download/YinYang-APK-Manager.apk) | 9 or later | v1.6.8 · Build 30 |
| [YinYang Phone EQ](https://github.com/yinyangeqlab/yinyang-android/releases/latest/download/YinYang-Phone-EQ.apk) | 13 or later, with Shizuku | v0.3.7 · Build 14 |

[Android page and theme gallery](https://yinyangeq.com/android#install) · [Feature guide](FEATURES.md)

## Install or update

In App Manager, choose **YinYangEQ Companion App for Qudelix 5K and 5K Plus** (v0.17.3 · Build 78). The launcher icon is labeled **YYEQ 5K**. App Manager checks each download against its package, release version, SHA-256 and Lab signing certificate. Choose **Install…**, select the verified APK in Android Files and confirm the installation. App Manager also checks for its own updates. Its list starts with App Manager, followed by Companion and Phone EQ. Each listing shows the Android app name. Use **Launch app** to open a verified installed Companion or Phone EQ.

Choose **System Wide PEQ** in App Manager or download the YinYang Phone EQ APK directly for system-wide equalization of Android media and games. Start Shizuku with wireless debugging, then allow Phone EQ in Shizuku. In the Companion fitter, pick Main EQ, Channel Match, or Paired EQ and choose **Open in Phone EQ**. [Setup and Quve credits](android-playback/)

Companion uses the same five fitting methods as Personal EQ Tuning on the website, including All-Band Fit. It checks for a compatible fitter update when you open Auto EQ online and can generate EQ offline with its bundled fitter. Your measurement and target stay on the device during fitting.

Android lists the three apps as **YYEQ App Manager**, **YYEQ 5K**, and **YYEQ PhoneEQ**. Phone EQ can back up saved presets from **Library**. Companion can back up saved presets and measurements from **Devices → Local library**. Save a backup to a shared folder before uninstalling if you want to restore it later.

The unified app uses the original release Companion package. If you have phone-only presets in an earlier app, export them before switching. The separate Plus preview uses a different package. Install the unified app alongside it, link your DAC and import any exported presets you want to keep.

Both apps offer optional in-app bug reporting. Review the diagnostics before sending, then use **My reports** for updates or to delete a report before its 90-day expiry. [Android reporting privacy notice](https://yinyangeq.com/privacy#privacy-android-reporting)

## Source and build

This tag includes the native [YYEQ5K source](android/), [App Manager source](apk-manager/), and [Phone EQ source](android-playback/). It also includes the pinned website inputs used by the offline EQ engine. See the [build instructions](build-support/README.md) for the toolchain and exact engine inputs.

## Use at your own risk

Check volume, routing and EQ before listening. Begin at low volume and run measurement sweeps with the IEM seated in the coupler and out of your ears. Frequency response and SPL accuracy depend on the recording path and calibration.

## License and credits

Copyright © 2026 Yin Yang EQ Lab. The Companion and App Manager’s original code is licensed under Apache 2.0. Phone EQ adapts Quve by nanbeimerco and is available under GNU GPL v3. Third-party components retain their respective licenses. [Apache 2.0 license](LICENSE) · [License scope](LICENSE_SCOPE.md) · [Phone EQ credits](android-playback/NOTICE.md)
