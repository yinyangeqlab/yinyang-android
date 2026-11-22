# Rebuild the Android release

The signed release contains YYEQ5K 0.16.5 (build 66) and YingYangEQ App Manager 1.6.3 (build 25). Use Android SDK Platform 36, Build Tools 36.0.0, a JDK with `javac --release 8`, Python 3, Node 26.12.0, npm 12.2.1 and esbuild 0.28.2.

From this repository's root, stage the pinned offline EQ engine and install esbuild.

```sh
npm install --no-save --package-lock=false esbuild@0.28.2
python3 build-support/prepare-engine.py
```

Two runtime files come from the website. The applicable licenses and corresponding source come from the preceding official Companion APK. The setup verifies each file against [the hashes](engine-sha256.json) before installing it.

Use a separate test signing key for local verification builds. The public builder creates one if signing material is absent. Local signatures differ from the Lab release signature, so the resulting apps cannot replace an installed Lab-signed app. The build script keeps signing files outside Git.

```sh
python3 apk-manager/public_release.py android-local-rebuild
```

The APKs and their catalog are written under `apk-manager/build/public/android-local-rebuild/`. Review the package name, version, build number and archive entries with `aapt2 dump badging` and a ZIP reader. Official APK and catalog checksums are recorded on the release page.
