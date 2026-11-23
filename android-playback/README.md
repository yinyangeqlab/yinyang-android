# YinYang Phone EQ

Phone EQ uses the Quve audio engine by nanbeimerco to apply system-wide EQ to Android media and games. **Home** checks Shizuku and shows playback controls once it is running and authorized. Import an Equalizer APO TXT file or tap **Open in Phone EQ** on a fit in YYEQ5K. Review the filters, name the preset, then tap **Save & select**. Search on-device presets in **Library**, with filters for Main EQ, Channel Match, and Paired EQ. You can browse and save while Shizuku is stopped. When you open a preset from YYEQ5K, **Back to YYEQ5K** returns to the fit. Enable playback notifications, then tap **Start phone EQ**. Use **Stop phone EQ** in the app or **Stop EQ** in its playback notification. [Quve v1.0.2](https://github.com/nanbeimerco/Quve/tree/v1.0.2)

## Set up Shizuku

Install Shizuku and start it from its Wireless debugging setup. Return to Phone EQ, tap **Allow Shizuku**, and approve access in Android. Start Shizuku again after reboot if your device stops it. [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) · [GitHub releases](https://github.com/RikkaApps/Shizuku/releases) · [Official setup guide](https://shizuku.rikka.app/guide/setup/)

Phone EQ needs Android 13 or newer. Playback coverage depends on the phone's audio routing. It stores the confirmed preset on the device and processes audio in memory. The playback notification gives you a Stop action while Phone EQ is active. Playback stops if Shizuku or notification access is lost.

## Build and license

Use JDK 21, Gradle 9.1.0 and Android SDK Platform 36. Set `ANDROID_HOME` to the SDK and run `gradle :app:testDebugUnitTest :app:assembleRelease` in this directory. The unsigned release APK is `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign the release APK with the established Lab key before distributing it or submitting it for Play Protect review. [Google’s Play Protect developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)

Phone EQ uses Quve's stereo DSP, Equalizer APO parser and Shizuku AudioPolicy loopback. The source is available under GNU GPL v3. [Credits and license details](NOTICE.md)
