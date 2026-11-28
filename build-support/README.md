# Rebuild the Android release

The signed release contains YYEQ 5K 0.17.4 (build 79), YYEQ PhoneEQ 0.3.8 (build 15), and YYEQ App Manager 1.6.8 (build 30). Use Android SDK Platform 36, Build Tools 36.0.0, JDK 21, Python 3, Node 26.12.0, npm 12.2.1 and esbuild 0.28.2.

From this repository's root, stage the pinned offline EQ engine and install esbuild.

```sh
npm install --no-save --package-lock=false esbuild@0.28.2
python3 build-support/prepare-engine.py
```

Two runtime files come from the website. The applicable licenses and corresponding source come from the preceding official Companion APK. The setup verifies each file against [the hashes](engine-sha256.json) before installing it.

The fitter source pinned in [LIVE_RELEASE.json](../LIVE_RELEASE.json) builds the offline copy. The website packages the same worker during each deploy. The format 1 manifest advertises All-Band Fit support and carries the worker size and SHA-256. Keep the format 1 request and response compatible with installed apps. An incompatible change requires a new format and app update.

Use a separate test signing key for local verification builds. The public builder creates one if signing material is absent. Local signatures differ from the Lab release signature, so the resulting apps cannot replace an installed Lab-signed app. The build script keeps signing files outside Git.

```sh
python3 apk-manager/public_release.py android-local-rebuild
```

The APKs and their catalog are written under `apk-manager/build/public/android-local-rebuild/`. Review the package name, version, build number and archive entries with `aapt2 dump badging` and a ZIP reader. Official APK and catalog checksums are recorded on the release page.

Run `node android/sync/test-fitter.mjs path/to/YinYang-Companion.apk` to compare the APK worker with the pinned website source. Set `YINYANG_FITTER_SITE` to a website checkout's `site` directory to verify its worker against the APK as well.

Build Phone EQ with [Gradle 9.1.0](https://gradle.org/releases/) and its declared Android Gradle Plugin and Shizuku dependencies.

```sh
gradle -p android-playback :app:testDebugUnitTest :app:assembleRelease
```

The unsigned Phone EQ APK is written to `android-playback/app/build/outputs/apk/release/app-release-unsigned.apk`. [Phone EQ source and setup](../android-playback/)

Pass `--phone-eq /path/to/signed-phone-eq.apk` when generating a catalog to include a Phone EQ APK that uses the same signer as App Manager. Older Manager builds skip this optional entry and continue to check for Companion and Manager updates.
