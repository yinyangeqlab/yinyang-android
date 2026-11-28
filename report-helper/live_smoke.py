"""Run one operator-authorized Android report smoke test against GitHub and Codex."""

import argparse
import base64
import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from service import BOT, Reports

REPO = "yinyangeqlab/yinyang-android"
OWNER = "yinyangeqlab"
APP_SLUG = "yinyang-eq-helper"
SMOKE_NOTE = "Lab report pipeline smoke test"


def encode(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def app_jwt(app_id, key):
    now = int(time.time())
    payload = json.dumps({"iat": now - 45, "exp": now + 480, "iss": str(app_id)}).encode()
    body = encode(b'{"alg":"RS256","typ":"JWT"}') + "." + encode(payload)
    sign = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key)], input=body.encode(),
                          capture_output=True, check=False, timeout=10)
    if sign.returncode:
        raise RuntimeError("Could not sign the GitHub App identity request.")
    return body + "." + encode(sign.stdout)


def github(token, route, method="GET", payload=None):
    request = Request("https://api.github.com" + route, method=method,
                      data=None if payload is None else json.dumps(payload).encode(),
                      headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                               "Content-Type": "application/json", "X-GitHub-Api-Version": "2022-11-28",
                               "User-Agent": "YinYang-EQ-Helper-smoke"})
    try:
        with urlopen(request, timeout=20) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"GitHub returned HTTP {error.code} for {method} {route}.") from None


def installation_token(app_id, key):
    jwt = app_jwt(app_id, key)
    app = github(jwt, "/app")
    if app["owner"]["login"] != OWNER or app["slug"] != APP_SLUG:
        raise RuntimeError("The private key is not for the expected Lab bot.")
    declared = app["permissions"]
    if declared.get("issues") != "write" or set(declared) - {"issues", "metadata"}:
        raise RuntimeError("The Lab bot requires only Issues write and Metadata read.")
    install = github(jwt, f"/repos/{REPO}/installation")
    if install["account"]["login"] != OWNER or install["repository_selection"] != "selected":
        raise RuntimeError("Install the Lab bot on the Android repository alone.")
    result = github(jwt, f"/app/installations/{install['id']}/access_tokens", "POST",
                    {"repositories": [REPO.split("/")[1]], "permissions": {"issues": "write"}})
    return result["token"]


def maintainer_token():
    result = subprocess.run(["gh", "auth", "token", "--hostname", "github.com", "--user", OWNER],
                            capture_output=True, text=True, check=False, timeout=10)
    token = result.stdout.strip()
    if result.returncode or not token or github(token, "/user").get("login") != OWNER:
        raise RuntimeError("The verified Lab maintainer credential is unavailable.")
    return token


def identify_phone_case(path, number):
    reports = Reports(path)
    handoff = reports.handoff(number)
    if len(handoff["reports"]) != 1 or handoff["reports"][0]["note"] != SMOKE_NOTE:
        raise RuntimeError("This command requires a dedicated app-originated smoke report.")
    app = handoff["issue"]["app"]
    snapshot = handoff["reports"][0]
    if app != "phone-eq" or snapshot["diagnostics"].get("playback") != "off":
        raise RuntimeError("The smoke test is scoped to Phone EQ while playback is off.")
    return reports, snapshot["build"]


OBSERVATIONS = {
    "phone-eq": "Inspect the Phone EQ report form and playback diagnostics. Explain whether an unknown "
                "output route when playback is off requires a fix. Treat the staging result as a simulation. ",
    "companion": "Inspect the Companion device-picker report form and the original 5K and Plus Debug "
                 "logs. Explain what can be reported when no device is linked. Treat the staging result as a simulation. ",
}


def review_prompt(issue_number, app):
    # The issue number is used only as an identifier. Remote text and private diagnostics stay outside the prompt.
    if type(issue_number) is not int or issue_number < 1 or app not in OBSERVATIONS:
        raise ValueError("Expected a verified issue number and app.")
    return (
        f"Read-only integration test for {REPO} issue #{issue_number}.\n"
        f"{OBSERVATIONS[app]}"
        "Use only the staged source files. Do not fetch the issue, its comments or attachments. "
        "Give a concise finding and exact file references, and say whether a code change is warranted. "
        "Do not edit files, create a commit, post comments, start audio, or build or publish a release."
    )


def run_codex(issue_number, app, checkout, output):
    prompt = review_prompt(issue_number, app)
    if output.exists():
        raise RuntimeError("Choose a fresh output file for the Codex review.")
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        completed = Path(temporary) / "review.txt"
        command = ["codex", "-a", "never", "exec", "--ignore-user-config", "--ephemeral",
                   "--skip-git-repo-check", "-C", str(checkout), "-s", "read-only", "-m", "gpt-6.2-sol",
                   "-c", 'model_reasoning_effort="medium"', "--output-last-message", str(completed), "-"]
        result = subprocess.run(command, input=prompt, text=True, capture_output=True, timeout=180, check=False)
        if result.returncode or not completed.is_file() or not completed.stat().st_size:
            print(result.stderr[-2500:])
            raise RuntimeError("The real Codex CLI review did not complete.")
        completed.chmod(0o600)
        os.link(completed, output)


def publish_test(args):
    if not re.fullmatch(r"[a-z0-9]{8,40}", args.tag):
        raise ValueError("Use a unique lowercase smoke tag.")
    reports, build = identify_phone_case(args.db, args.issue)
    if args.lab_transport:
        token = maintainer_token()
        author = OWNER
        introduction = "Automated transport test from Yin Yang EQ Lab."
    else:
        if args.app_id is None or args.key is None:
            raise ValueError("Provide the GitHub App ID and private-key file.")
        if args.key.stat().st_mode & 0o077:
            raise ValueError("The GitHub App key file must be readable only by its owner.")
        token = installation_token(args.app_id, args.key)
        author = APP_SLUG + "[bot]"
        introduction = BOT
    marker = f"<!-- yyeq-report-smoke:{args.tag} -->"
    base = f"/repos/{REPO}/issues"
    issue_title = f"[Integration test] Phone EQ report from build {build}"
    body = (f"{introduction}\n\nThis is a maintainer-authorized integration test from the Android preview. "
            "It carries only the app and build number. The test covered the report flow while EQ was off. "
            "No update has been published.\n\n" + marker)
    candidates = github(token, base + "?state=all&sort=created&direction=desc&per_page=30")
    issue = next((item for item in candidates if marker in (item.get("body") or "")), None)
    if issue is None:
        issue = github(token, base, "POST", {"title": issue_title, "body": body})
    route = f"{base}/{issue['number']}"
    issue = github(token, route)
    if issue["user"]["login"] != author or issue["title"] != issue_title or issue["body"].strip() != body:
        raise RuntimeError("The GitHub issue does not match the verified Lab smoke test.")
    print("App report reached GitHub:", issue["html_url"], flush=True)
    with reports.transaction() as db:
        if not db.execute("SELECT 1 FROM events WHERE issue = ? AND kind = 'github_smoke' AND body LIKE ?",
                          (args.issue, "%" + issue["html_url"])).fetchone():
            reports.event(db, args.issue, "github_smoke", f"{BOT} Test issue created on GitHub. {issue['html_url']}")
    run_codex(issue["number"], "phone-eq", args.checkout, args.output)
    print("Codex reviewed the GitHub report in read-only mode.", flush=True)
    comments = github(token, route + "/comments")
    comment_text = (f"{introduction}\n\nThe Android preview created this test issue and Codex completed a read-only "
                    "diagnostic review. This test did not publish an update. Could you confirm that the app showed "
                    "the report receipt and that this reply is visible?\n\n" + marker)
    comment = next((item for item in comments if item.get("body") == comment_text), None)
    if comment is None:
        comment = github(token, route + "/comments", "POST", {"body": comment_text})
    if comment["user"]["login"] != author:
        raise RuntimeError("The issue reply does not have the expected Lab attribution.")
    with reports.transaction() as db:
        if not db.execute("SELECT 1 FROM events WHERE issue = ? AND kind = 'codex_smoke' AND body LIKE ?",
                          (args.issue, "%" + comment["html_url"])).fetchone():
            reports.event(db, args.issue, "codex_smoke",
                          f"{BOT} Codex reviewed the test report. {comment['html_url']}")
    print("GitHub reply verified:", comment["html_url"], flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--issue", type=int, default=1)
    parser.add_argument("--app-id", type=int)
    parser.add_argument("--key", type=Path)
    parser.add_argument("--lab-transport", action="store_true",
                        help="Use the verified Lab maintainer only for an explicitly selected transport smoke test.")
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    publish_test(parser.parse_args())
