# YinYangEQ for Android

One companion supports the original Qudelix 5K and the Qudelix 5K Plus. Link one DAC at a time. The app identifies the model before loading its controls and keeps local drafts for that unit. Yin Yang EQ Lab develops this independent app.

| Download | Android version | Release |
| --- | --- | --- |
| [YingYangEQ App Manager](https://github.com/yinyangeqlab/yinyang-android/releases/latest/download/YinYang-APK-Manager.apk) | 9 or later | v1.6.6 · Build 28 |
| [YinYang Phone EQ](https://github.com/yinyangeqlab/yinyang-android/releases/latest/download/YinYang-Phone-EQ.apk) | 13 or later, with Shizuku | v0.3.0 · Build 5 |

[Android page and theme gallery](https://yinyangeq.com/android#install) · [Feature guide](FEATURES.md)

## Install or update

In App Manager, choose **YinYangEQ Companion App for Qudelix 5K and 5K Plus** (v0.16.6 · Build 67). The launcher icon is labeled **YYEQ5K**. App Manager checks each download against its package, release version, SHA-256 and Lab signing certificate. Choose **Install…**, select the verified APK in Android Files and confirm the installation. App Manager also checks for its own updates.

Choose **System Wide PEQ** in App Manager or download the YinYang Phone EQ APK directly for system-wide equalization of Android media and games. Start Shizuku with wireless debugging, then allow Phone EQ in Shizuku. In the Companion fitter, pick Main EQ, Channel Match, or Paired EQ and choose **Open in Phone EQ**. [Setup and Quve credits](android-playback/)

The unified app uses the original release Companion package. If you have phone-only presets in an earlier app, export them before switching. The separate Plus preview uses a different package. Install the unified app alongside it, link your DAC and import any exported presets you want to keep.

## Source and build

This tag includes the native [YYEQ5K source](android/), [App Manager source](apk-manager/), and [Phone EQ source](android-playback/). It also includes the pinned website inputs used by the offline EQ engine. See the [build instructions](build-support/README.md) for the toolchain and exact engine inputs.

## Use at your own risk

Check volume, routing and EQ before listening. Begin at low volume and run measurement sweeps with the IEM seated in the coupler and out of your ears. Frequency response and SPL accuracy depend on the recording path and calibration.

## License and credits

Copyright © 2026 Yin Yang EQ Lab. The Companion and App Manager’s original code is licensed under Apache 2.0. Phone EQ adapts Quve by nanbeimerco and is available under GNU GPL v3. Third-party components retain their respective licenses. [Apache 2.0 license](LICENSE) · [License scope](LICENSE_SCOPE.md) · [Phone EQ credits](android-playback/NOTICE.md)
