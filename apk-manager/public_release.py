"""Build and verify the two public GitHub release assets. All output stays local."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

import delta

ROOT = Path(__file__).resolve().parent
SIGNING = ROOT / "state/public-signing"
ENDPOINT = "https://github.com/yinyangeqlab/yinyang-android/releases"
ANDROID = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", ANDROID[1:-1])
COMPANION = "com.yinyangeq.android.companion"
MANAGER = "com.yinyangeq.android.manager"
COMPANION_TITLE = "YYEQ5K"
MANAGER_TITLE = "YingYangEQ App Manager"
COMPANION_DOWNLOAD_NAME = "YinYangEQ Q5K Companion App.apk"
APPS = (
    (COMPANION, COMPANION_TITLE, "YinYang-Companion.apk", ROOT.parent / "android", 26, COMPANION_TITLE),
    (MANAGER, MANAGER_TITLE, "YinYang-APK-Manager.apk", ROOT, 28, "App Manager"),
)


def signing_material():
    key = SIGNING / "release.p12"
    password = SIGNING / "release.pass"
    if not SIGNING.exists():
        with tempfile.TemporaryDirectory(dir=ROOT / "state") as temp:
            staged = Path(temp) / "signing"
            staged.mkdir(mode=0o700)
            (staged / password.name).write_text(secrets.token_urlsafe(40) + "\n")
            subprocess.run(["keytool", "-genkeypair", "-keystore", staged / key.name, "-storetype", "PKCS12",
                            "-storepass:file", staged / password.name, "-alias", "lab", "-keyalg", "RSA",
                            "-keysize", "3072", "-validity", "10000", "-dname", "CN=Yin Yang EQ Lab"], check=True)
            staged.rename(SIGNING)
    if not key.is_file() or not password.is_file():
        raise SystemExit("Restore the original public release key and password before building.")
    cert = subprocess.check_output(["keytool", "-exportcert", "-keystore", key,
                                    "-storepass:file", password, "-alias", "lab"])
    return key, password, hashlib.sha256(cert).hexdigest()


def manifest(source, app_id, destination):
    tree = ET.parse(source)
    root = tree.getroot()
    original = root.attrib["package"]
    root.set("package", app_id)
    app = root.find("application")
    for component in app:
        name = component.get(ANDROID + "name", "")
        if name.startswith("."):
            component.set(ANDROID + "name", original + name)
    if app_id == MANAGER:
        app.set(ANDROID + "label", MANAGER_TITLE)
        queries = root.find("queries")
        queries.clear()
        ET.SubElement(queries, "package", {ANDROID + "name": COMPANION})
        root.remove(next(node for node in root.findall("uses-permission")
                         if node.get(ANDROID + "name") == "android.permission.REQUEST_INSTALL_PACKAGES"))
        app.remove(app.find("provider"))
    tree.write(destination, encoding="utf-8", xml_declaration=True)
    return original, app.get(ANDROID + "label")


def build_apk(app, key, password, pin, output):
    app_id, _, asset, source, minimum, _ = app
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", "/opt/android-sdk"))
    tools = sdk / "build-tools/36.0.0"
    platform = sdk / "platforms/android-36/android.jar"
    with tempfile.TemporaryDirectory(dir=output) as temp:
        work = Path(temp)
        for directory in ("assets", "java", "classes", "dex"):
            (work / directory).mkdir()
        shutil.copytree(source / "brand", work / "res")
        original, label = manifest(source / "AndroidManifest.xml", app_id, work / "AndroidManifest.xml")
        if app_id == MANAGER:
            (work / "res/xml").mkdir(exist_ok=True)
            (work / "res/xml/transport.xml").write_text(
                '<network-security-config><base-config cleartextTrafficPermitted="false">'
                '<trust-anchors><certificates src="system"/></trust-anchors>'
                '</base-config></network-security-config>')
            pins = [dict(package=package, label=label, asset=asset, signer=pin, notificationLabel=short_label)
                    for package, label, asset, _, _, short_label in APPS]
            next(app for app in pins if app["package"] == COMPANION)["downloadName"] = COMPANION_DOWNLOAD_NAME
            (work / "assets/release-feed.json").write_text(json.dumps(dict(public=True, endpoint=ENDPOINT, apps=pins)))
        if app_id == MANAGER:
            code, version = "25", "1.6.3"
        else:
            versions = re.search(r"--version-code (\d+) --version-name ([\d.]+)", (source / "build.sh").read_text())
            code, version = versions.groups()

        def run(*args):
            subprocess.run(args, cwd=work, check=True)

        if app_id == COMPANION:
            run("node", source / "sync/build.mjs", work / "assets")
        run(tools / "aapt2", "compile", "--dir", "res", "-o", "resources.zip")
        run(tools / "aapt2", "link", "resources.zip", "-A", "assets", "-I", platform,
            "--manifest", "AndroidManifest.xml", "--java", "java", "--custom-package", original,
            "--version-code", code, "--version-name", version, "-o", "base.apk")
        run("javac", "--release", "8", "-Xlint:-options", "-cp", platform, "-d", "classes",
            *sorted((source / "src").glob("*.java")),
            *(sorted((source / "plus/src").glob("*.java")) if app_id == COMPANION else []),
            *([source / "channel/public/UpdateChannel.java"] if app_id == MANAGER else []), *work.glob("java/**/R.java"))
        run("jar", "cf", "code.jar", "-C", "classes", ".")
        if app_id == MANAGER:
            # Remove the unused private-edition installer and APK staging code.
            (work / "keep.pro").write_text(
                "-dontobfuscate\n"
                f"-keep class {original}.MainActivity {{ public <init>(); public <methods>; protected <methods>; }}\n"
                f"-keep class {original}.Checks {{ public <init>(); public <methods>; }}\n"
            )
            run("java", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.R8",
                "--lib", platform, "--min-api", str(minimum), "--output", "dex",
                "--pg-conf", "keep.pro", "code.jar")
        else:
            run(tools / "d8", "--lib", platform, "--min-api", str(minimum), "--output", "dex", "code.jar")
        run("jar", "uf", "base.apk", "-C", "dex", "classes.dex")
        run(tools / "zipalign", "-f", "4", "base.apk", "aligned.apk")
        run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "lab", "--ks-pass",
            "file:" + str(password), "--v4-signing-enabled", "false", "--out", "release.apk", "aligned.apk")
        completed = work / "release.apk"
        certs = subprocess.check_output([tools / "apksigner", "verify", "--print-certs", completed], text=True)
        if re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", certs) != [pin]:
            raise ValueError("Release signing verification failed")
        info = subprocess.check_output([tools / "aapt2", "dump", "badging", completed], text=True)
        if (f"package: name='{app_id}' versionCode='{code}'" not in info
                or f"application-label:'{label}'" not in info or "application-debuggable" in info):
            raise ValueError("Unexpected manifest in public release")
        if app_id == MANAGER:
            expected_permissions = {
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS",
            }
            if set(re.findall(r"uses-permission: name='([^']+)'", info)) != expected_permissions:
                raise ValueError("Unexpected public Manager permissions")
        with zipfile.ZipFile(completed) as apk:
            assets = [n for n in apk.namelist() if n.startswith("assets/")]
            if set(assets) != ({"assets/release-feed.json"} if app_id == MANAGER else {
                    "assets/"+n for n in ("library.html","COPYING","LICENSE","RUNTIME-NOTICES.txt","source.tar.gz","fitter-version.txt","headphone-catalog.json")}):
                raise ValueError("Unexpected public assets")
            if app_id == MANAGER and json.loads(apk.read(assets[0])) != dict(public=True, endpoint=ENDPOINT, apps=pins):
                raise ValueError("Unexpected public channel configuration")
            if app_id == MANAGER:
                code_bytes = apk.read("classes.dex")
                for symbol in (b"ApkPatch;", b"/Apks;", b"canRequestPackageInstalls",
                               b"MANAGE_UNKNOWN_APP_SOURCES"):
                    if symbol in code_bytes:
                        raise ValueError("Private APK delivery code remains in the public Manager")
                forbidden = ["tailscale", ".ts.net", "org.sharur.remote", "dev.alacarte.remote",
                             "pairing.json", "private-feed.json", "bearer ", "BEGIN PRIVATE KEY"]
                personal = ROOT / "state/pairing.json"
                if personal.is_file():
                    pairing = json.loads(personal.read_text())
                    forbidden.extend([pairing["token"], pairing["endpoint"]])
                needles = [item.lower().encode(encoding) for item in forbidden
                           for encoding in ("utf-8", "utf-16le") if item]
                for entry in apk.namelist():
                    data = entry.lower().encode() + apk.read(entry).lower()
                    if any(needle in data for needle in needles):
                        raise ValueError("Public Manager contains private channel material")
            if any(n.endswith((".p12", ".crt", ".key")) for n in apk.namelist()):
                raise ValueError("Unexpected key or certificate resource")
        run(tools / "zipalign", "-c", "4", completed)
        completed.replace(output / asset)
    return dict(package=app_id, code=int(code), version=version, bytes=(output/asset).stat().st_size,
                sha256=delta.digest(output / asset))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", help="Immutable release or local review tag")
    parser.add_argument("--manager-only", action="store_true", help="Build one locally reviewed Manager without a publication catalog")
    parser.add_argument("--previous", type=Path, help="Directory containing the previous published release assets")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", args.tag):
        parser.error("Invalid release tag")
    os.umask(0o077)
    (ROOT / "state").mkdir(exist_ok=True)
    releases = ROOT / "build/public"
    releases.mkdir(exist_ok=True, parents=True)
    output = releases / args.tag
    if output.exists():
        parser.error("Output already exists. Use a fresh preview or release tag.")
    key, password, pin = signing_material()
    with tempfile.TemporaryDirectory(dir=releases) as tmp:
        staging = Path(tmp) / "assets"
        staging.mkdir()
        rows = []
        for app in (APPS[1:] if args.manager_only else APPS):
            row = build_apk(app, key, password, pin, staging)
            if args.previous and (args.previous / app[2]).is_file():
                patch = delta.build(args.previous / app[2], staging / app[2], staging)
                if patch:
                    row["patch"] = patch
            rows.append(row)
        if not args.manager_only:
            catalog = dict(schema=1, tag=args.tag, apps=rows)
            (staging / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
        staging.rename(output)
    print("Verified release assets in", output)


if __name__ == "__main__":
    main()
