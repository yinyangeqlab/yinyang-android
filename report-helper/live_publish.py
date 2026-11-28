"""Publish or synchronize an approved report using the Lab’s Issues-only bot."""

import argparse
import fcntl
import json
import socket
import time
import urllib.error
import urllib.request
from pathlib import Path

from live_smoke import APP_SLUG, REPO, github, installation_token
from service import BOT

ORIGIN = "https://reports.yinyangeq.com"
BASE = f"/repos/{REPO}/issues"
BOT_LOGIN = APP_SLUG + "[bot]"


class RejectRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def maintainer(path, token, method="GET", payload=None):
    request = urllib.request.Request(
        ORIGIN + path, method=method, data=None if payload is None else json.dumps(payload).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "User-Agent": "YinYang-EQ-Helper/1.0"},
    )
    opener = urllib.request.build_opener(RejectRedirect())
    for attempt in range(5):
        try:
            with opener.open(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"The live report service returned HTTP {error.code}.") from None
        except urllib.error.URLError as error:
            # DNS failures happen before a request can be sent, including POST actions.
            if not isinstance(error.reason, socket.gaierror):
                raise
            if attempt == 4:
                raise RuntimeError("The live report hostname could not be resolved.") from None
            time.sleep(2)


def verify(item, text, title=None):
    if ((item.get("user") or {}).get("login") != BOT_LOGIN or item.get("body") != text
            or (title is not None and item.get("title") != title)):
        raise RuntimeError("The GitHub item did not match the approved bot template.")


def existing(bot, route, marker):
    separator = "&" if "?" in route else "?"
    for page in range(1, 21):
        items = github(bot, f"{route}{separator}per_page=100&page={page}")
        for item in items:
            if ((item.get("user") or {}).get("login") == BOT_LOGIN
                    and (item.get("body") or "").endswith("\n\n" + marker)):
                return item
        if len(items) < 100:
            return None
    raise RuntimeError("Could not verify that a matching publication is unique.")


def publish(number, token, bot, *, test):
    route = f"/maintainer/issues/{number}"
    draft = maintainer(route + "/draft", token)
    app = draft["label"]
    marker = f"<!-- yyeq-live-report:v1:{number} -->"
    name = f"{app} report from build {draft['build']}"
    title = f"[Reporting test] {name}" if test else name
    message = ("This synthetic report tests the live Android reporting service. "
               "The test does not publish an app update.") if test else (
               f"The Lab received a diagnostic report for {app}. "
               "This issue tracks the Lab's updates. Use My reports in the app to respond to a test build.")
    body = f"{BOT}\n\n{message}\n\n{marker}"
    if draft["github"]:
        item = github(bot, f"{BASE}/{draft['github']}")
    else:
        item = existing(bot, BASE + "?state=all&sort=created&direction=desc", marker)
        if item is None:
            item = github(bot, BASE, "POST", {"title": title, "body": body})
    verify(item, body, title)
    linked = maintainer(route + "/link", token, "POST", {"github": item["number"]})
    if linked["github"] != item["number"]:
        raise RuntimeError("The live receipt did not link to the verified GitHub issue.")
    comments = f"{BASE}/{item['number']}/comments"
    for event in draft["updates"]:
        suffix = f"<!-- yyeq-live-reply:v1:{number}:{event['id']} -->"
        text = event["body"] + "\n\n" + suffix
        posted = existing(bot, comments, suffix)
        if posted is None:
            posted = github(bot, comments, "POST", {"body": text})
        verify(posted, text)
    print(item["html_url"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("issue", type=int, help="A live report ID approved for public tracking")
    parser.add_argument("--maintainer-token-file", type=Path, required=True)
    parser.add_argument("--app-id", type=int, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--test", action="store_true", help="Identify a synthetic live report")
    args = parser.parse_args()
    if any(file.stat().st_mode & 0o077 for file in (args.key, args.maintainer_token_file)):
        parser.error("Both credential files must be readable only by their owner.")
    with args.maintainer_token_file.with_suffix(".publish.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        token = args.maintainer_token_file.read_text().strip()
        publish(args.issue, token, installation_token(args.app_id, args.key), test=args.test)
