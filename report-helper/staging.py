"""Private Android support staging worker for synthetic reports."""

import argparse
import fcntl
import re
import time
from pathlib import Path

from live_smoke import APP_SLUG, REPO, github, installation_token, run_codex
from service import APPS, BOT, Reports

STAGING_NOTE = "Lab staging full-flow test"
BASE = f"/repos/{REPO}/issues"
BOT_LOGIN = APP_SLUG + "[bot]"
MIRRORED = ("candidate", "updated_report", "manual_review", "resolved", "failed")


class Staging:
    def __init__(self, reports, name, app_id, key, checkout, output):
        self.reports = reports
        self.name = name
        self.app_id = app_id
        self.key = key
        self.checkout = checkout
        self.output = output
        self.token = None
        self.refresh_at = 0
        with reports.transaction() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS staging_issues (
                    issue INTEGER PRIMARY KEY REFERENCES issues(id),
                    github_number INTEGER NOT NULL UNIQUE
                );
                CREATE TABLE IF NOT EXISTS staging_comments (
                    event INTEGER PRIMARY KEY REFERENCES events(id)
                );
            """)

    def remote(self, route, method="GET", payload=None):
        if time.monotonic() >= self.refresh_at:
            self.token = installation_token(self.app_id, self.key)
            self.refresh_at = time.monotonic() + 2400
        return github(self.token, route, method, payload)

    def find_marker(self, route, marker):
        separator = "&" if "?" in route else "?"
        for page in range(1, 21):
            rows = self.remote(f"{route}{separator}per_page=100&page={page}")
            for row in rows:
                if (row.get("user") or {}).get("login") == BOT_LOGIN and (row.get("body") or "").endswith("\n\n" + marker):
                    return row
            if len(rows) < 100:
                return None
        raise RuntimeError("Too many GitHub pages to verify the test submission is unique.")

    @staticmethod
    def verify_author(item):
        if (item.get("user") or {}).get("login") != BOT_LOGIN:
            raise RuntimeError("GitHub did not attribute this staging item to the Lab bot.")

    def case_numbers(self):
        with self.reports.transaction() as db:
            return [row["id"] for row in db.execute("""
                SELECT i.id FROM issues i
                WHERE (SELECT r.note FROM reports r WHERE r.issue = i.id ORDER BY r.rowid LIMIT 1) = ?
                ORDER BY i.id
            """, (STAGING_NOTE,))]

    def issue_content(self, number, label, build):
        marker = f"<!-- yyeq-staging:{self.name}:{number} -->"
        title = f"[Staging] {label} report from build {build}"
        body = (f"{BOT}\n\nThis is a synthetic Android reporting test. "
                "Candidate feedback is simulated and no app update is published.\n\n" + marker)
        return title, body, marker

    def ensure_issue(self, number, title, body, marker):
        with self.reports.transaction() as db:
            existing = db.execute("SELECT github_number FROM staging_issues WHERE issue = ?", (number,)).fetchone()
        if existing:
            return existing["github_number"]
        issue = self.find_marker(BASE + "?state=all&sort=created&direction=desc", marker)
        if issue is None:
            issue = self.remote(BASE, "POST", {"title": title, "body": body})
        self.verify_author(issue)
        if issue["title"] != title or issue["body"] != body:
            raise RuntimeError("The returned issue does not match the approved staging content.")
        with self.reports.transaction() as db:
            db.execute("INSERT INTO staging_issues VALUES (?, ?)", (number, issue["number"]))
            self.reports.event(db, number, "github_issue", f"{BOT} Staging issue: {issue['html_url']}")
        print(f"{title} reached GitHub issue #{issue['number']}.", flush=True)
        return issue["number"]

    def review(self, number, app, output):
        run_codex(number, app, self.checkout, output)

    def process_case(self, number):
        handoff = self.reports.handoff(number)
        app = handoff["issue"]["app"]
        label = APPS[app]
        latest = handoff["reports"][-1]
        title, body, marker = self.issue_content(number, label, handoff["reports"][0]["build"])
        remote_number = self.ensure_issue(number, title, body, marker)
        state = handoff["issue"]["state"]
        if state == "submitted":
            self.reports.queue(number)
            state = "queued"
        if state == "queued":
            issue = self.remote(f"{BASE}/{remote_number}")
            self.verify_author(issue)
            if issue["number"] != remote_number or issue["title"] != title or issue["body"] != body:
                raise RuntimeError("The fetched issue is not the expected staging report.")
            round_number = len(handoff["attempts"]) + 1
            output = self.output / f"{self.name}-{number}-{round_number}.txt"
            if not output.is_file() or not output.stat().st_size:
                self.review(remote_number, app, output)
            self.reports.attempt(number, {"job": f"{self.name}-{number}-{round_number}",
                                         "state": "candidate", "build": latest["build"]}, staging=True)
            print(f"Codex completed {label} staging review {round_number}.", flush=True)
        self.mirror(number, remote_number)

    def mirror(self, number, remote_number):
        with self.reports.transaction() as db:
            events = db.execute("""
                SELECT e.id,e.body FROM events e LEFT JOIN staging_comments c ON c.event = e.id
                WHERE e.issue = ? AND c.event IS NULL AND e.kind IN (?,?,?,?,?) ORDER BY e.id
            """, (number, *MIRRORED)).fetchall()
        route = f"{BASE}/{remote_number}/comments"
        for event in events:
            marker = f"<!-- yyeq-staging-reply:{self.name}:{event['id']} -->"
            body = event["body"] + "\n\n" + marker
            comment = self.find_marker(route, marker)
            if comment is None:
                comment = self.remote(route, "POST", {"body": body})
            self.verify_author(comment)
            if comment["body"] != body:
                raise RuntimeError("The returned reply does not match the approved staging update.")
            with self.reports.transaction() as db:
                db.execute("INSERT INTO staging_comments VALUES (?)", (event["id"],))
                self.reports.event(db, number, "github_reply", f"{BOT} GitHub update: {comment['html_url']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--app-id", type=int, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9]{8,32}", args.name) or args.key.stat().st_mode & 0o077:
        parser.error("Use a unique lowercase staging name and an owner-only key.")
    args.output.mkdir(mode=0o700, exist_ok=True)
    with args.db.with_suffix(".worker.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        stage = Staging(Reports(args.db), args.name, args.app_id, args.key, args.checkout, args.output)
        while True:
            for number in stage.case_numbers():
                try:
                    stage.process_case(number)
                except (RuntimeError, OSError, TimeoutError) as error:
                    print(f"Staging case {number}: {error}", flush=True)
                    time.sleep(10)
            time.sleep(2)
