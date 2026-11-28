"""Local YinYang EQ Helper intake and issue lifecycle."""

import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
from contextlib import closing, contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

BOT = "I'm YinYang EQ Helper, an automated bot for Yin Yang EQ Lab."
APPS = {"phone-eq": "Phone EQ", "companion": "Companion"}
FIELDS = {
    "phone-eq": {"device", "android", "api", "shizuku", "shizukuVersion", "notifications",
                 "availableOutputs", "activeRoute", "playbackMode", "playback", "startup"},
    "companion": {"device", "android", "api", "dac", "firmware", "transport",
                  "stage", "mode", "events"},
}
SAFE_VALUE = re.compile(r"[A-Za-z0-9 .,_+()\[\]:-]{1,500}\Z")
HARDWARE_ID = re.compile(r"(?i)\b(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}\b")
MAX_REQUEST = 16 * 1024


class Invalid(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def normalized_report(payload):
    if payload.keys() - {"app", "build", "installation", "diagnostics", "note", "receipt"}:
        raise Invalid("Unknown report fields.")
    app, build = payload.get("app"), payload.get("build")
    device = payload.get("installation")
    if not isinstance(app, str) or app not in APPS or type(build) is not int or not 1 <= build <= 2_100_000_000:
        raise Invalid("Unknown app or build.")
    if not isinstance(device, str) or not re.fullmatch(r"[a-f0-9]{32}", device):
        raise Invalid("Missing installation key.")
    diagnostics = payload.get("diagnostics")
    if not isinstance(diagnostics, dict) or not diagnostics or diagnostics.keys() - FIELDS[app]:
        raise Invalid("Unknown diagnostic fields.")
    clean = {}
    for key, value in diagnostics.items():
        if not isinstance(value, str) or not SAFE_VALUE.fullmatch(value) or HARDWARE_ID.search(value):
            raise Invalid("Invalid diagnostic value.")
        clean[key] = " ".join(value.split())
    note = payload.get("note", "")
    if not isinstance(note, str) or len(note) > 600:
        raise Invalid("Keep the description under 600 characters.")
    data = json.dumps(clean, sort_keys=True, separators=(",", ":"))
    return app, build, data, hashlib.sha256(data.encode()).hexdigest(), hashlib.sha256(device.encode()).hexdigest(), note.strip()


class Reports:
    def __init__(self, path):
        self.path = path
        with self.transaction() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS issues (
                    id INTEGER PRIMARY KEY, app TEXT NOT NULL, state TEXT NOT NULL,
                    failures INTEGER NOT NULL DEFAULT 0, active_attempt INTEGER
                );
                CREATE TABLE IF NOT EXISTS snapshots (
                    id INTEGER PRIMARY KEY, app TEXT NOT NULL, build INTEGER NOT NULL,
                    digest TEXT NOT NULL, data TEXT NOT NULL, UNIQUE(app, build, digest)
                );
                CREATE TABLE IF NOT EXISTS reports (
                    issue INTEGER NOT NULL REFERENCES issues(id),
                    snapshot INTEGER NOT NULL REFERENCES snapshots(id), note TEXT NOT NULL,
                    PRIMARY KEY(issue, snapshot)
                );
                CREATE TABLE IF NOT EXISTS receipts (
                    token TEXT PRIMARY KEY, installation TEXT NOT NULL,
                    issue INTEGER NOT NULL REFERENCES issues(id), UNIQUE(installation, issue)
                );
                CREATE TABLE IF NOT EXISTS attempts (
                    id INTEGER PRIMARY KEY, issue INTEGER NOT NULL REFERENCES issues(id),
                    job TEXT NOT NULL, state TEXT NOT NULL, build INTEGER,
                    UNIQUE(issue, job)
                );
                CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY, issue INTEGER NOT NULL REFERENCES issues(id),
                    kind TEXT NOT NULL, body TEXT NOT NULL
                );
            """)

    @contextmanager
    def transaction(self):
        with closing(sqlite3.connect(self.path, timeout=10)) as db:
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys = ON")
            db.execute("BEGIN IMMEDIATE")
            with db:
                yield db

    @staticmethod
    def event(db, issue, kind, body):
        db.execute("INSERT INTO events(issue, kind, body) VALUES (?, ?, ?)", (issue, kind, body))

    @staticmethod
    def issue(db, issue):
        row = db.execute("SELECT * FROM issues WHERE id = ?", (issue,)).fetchone()
        if row is None:
            raise Invalid("Report not found.", 404)
        return row

    @staticmethod
    def lookup(db, token):
        if not isinstance(token, str):
            raise Invalid("Invalid report receipt.", 404)
        receipt = db.execute("SELECT * FROM receipts WHERE token = ?", (token,)).fetchone()
        if receipt is None:
            raise Invalid("Invalid report receipt.", 404)
        return receipt

    @staticmethod
    def set_state(db, issue, state):
        db.execute("UPDATE issues SET state = ? WHERE id = ?", (state, issue))

    @staticmethod
    def primary_receipt(db, issue):
        return db.execute("SELECT token FROM receipts WHERE issue = ? ORDER BY rowid LIMIT 1", (issue,)).fetchone()["token"]

    def submit(self, payload):
        app, build, data, digest, installation, note = normalized_report(payload)
        token = payload.get("receipt")
        with self.transaction() as db:
            db.execute("INSERT OR IGNORE INTO snapshots(app, build, digest, data) VALUES (?, ?, ?, ?)",
                       (app, build, digest, data))
            snapshot = db.execute("SELECT id FROM snapshots WHERE app = ? AND build = ? AND digest = ?",
                                  (app, build, digest)).fetchone()["id"]
            if token is not None:
                receipt = self.lookup(db, token)
                issue = self.issue(db, receipt["issue"])
                if receipt["installation"] != installation or issue["app"] != app:
                    raise Invalid("This report belongs to another installation or app.", 403)
                if issue["state"] == "awaiting_report":
                    attempt = db.execute("SELECT build FROM attempts WHERE id = ?",
                                         (issue["active_attempt"],)).fetchone()
                    if attempt and attempt["build"] != build:
                        raise Invalid("Send the updated report from the build you tested.", 409)
            else:
                existing = db.execute("SELECT issue FROM reports WHERE snapshot = ? ORDER BY issue LIMIT 1",
                                      (snapshot,)).fetchone()
                if existing:
                    issue = self.issue(db, existing["issue"])
                else:
                    number = db.execute("INSERT INTO issues(app, state) VALUES (?, 'submitted')", (app,)).lastrowid
                    issue = self.issue(db, number)
                    self.event(db, number, "received", f"{BOT} Report received for {APPS[app]} build {build}.")
                token = secrets.token_urlsafe(24)
                db.execute("INSERT OR IGNORE INTO receipts VALUES (?, ?, ?)", (token, installation, issue["id"]))
                token = db.execute("SELECT token FROM receipts WHERE installation = ? AND issue = ?",
                                   (installation, issue["id"])).fetchone()["token"]
            number = issue["id"]
            added = db.execute("INSERT OR IGNORE INTO reports VALUES (?, ?, ?)", (number, snapshot, note)).rowcount
            if (added and token == payload.get("receipt") and issue["state"] == "awaiting_report"
                    and token == self.primary_receipt(db, number)):
                self.set_state(db, number, "queued")
                self.event(db, number, "queued", f"{BOT} The updated report is ready for another attempt.")
            return {"receipt": token, "duplicate": not bool(added), "state": self.issue(db, number)["state"]}

    def status(self, token):
        with self.transaction() as db:
            issue = self.issue(db, self.lookup(db, token)["issue"])
            attempt = db.execute("SELECT id, build FROM attempts WHERE id = ?",
                                 (issue["active_attempt"],)).fetchone()
            messages = db.execute("SELECT kind, body FROM events WHERE issue = ? ORDER BY id",
                                  (issue["id"],))
            return {"issue": issue["id"], "state": issue["state"], "failures": issue["failures"],
                    "canConfirm": token == self.primary_receipt(db, issue["id"]),
                    "attempt": dict(attempt) if attempt else None, "updates": [dict(row) for row in messages]}

    def queue(self, number):
        with self.transaction() as db:
            issue = self.issue(db, number)
            if issue["state"] == "queued":
                return {"state": "queued"}
            if issue["state"] not in {"submitted", "manual_review"}:
                raise Invalid("The report cannot be queued in its current state.", 409)
            db.execute("UPDATE issues SET state = 'queued', failures = 0 WHERE id = ?", (number,))
            self.event(db, number, "queued", f"{BOT} The Lab queued this report for a fix attempt.")
            return {"state": "queued"}

    def failure(self, db, issue, next_state):
        failures = issue["failures"] + 1
        state = "manual_review" if failures >= 3 else next_state
        db.execute("UPDATE issues SET failures = ?, state = ? WHERE id = ?", (failures, state, issue["id"]))
        if state == "manual_review":
            self.event(db, issue["id"], "manual_review",
                       f"{BOT} Three fix attempts failed. A Lab maintainer will review this report before another attempt.")
        return state

    def attempt(self, number, payload, *, staging=False):
        if payload.keys() - {"job", "state", "build"}:
            raise Invalid("Unknown fix attempt fields.")
        job, state, build = payload.get("job"), payload.get("state"), payload.get("build")
        if not isinstance(job, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", job):
            raise Invalid("Missing workflow job identifier.")
        if state not in ("candidate", "failed") or (state == "candidate" and
                (type(build) is not int or not 1 <= build <= 2_100_000_000)) or (state == "failed" and build is not None):
            raise Invalid("Provide a candidate build or a failed attempt.")
        with self.transaction() as db:
            issue = self.issue(db, number)
            existing = db.execute("SELECT id FROM attempts WHERE issue = ? AND job = ?", (number, job)).fetchone()
            if existing:
                return {"attempt": existing["id"], "state": issue["state"]}
            if issue["state"] != "queued":
                raise Invalid("No fix attempt is queued for this report.", 409)
            attempt = db.execute("INSERT INTO attempts(issue, job, state, build) VALUES (?, ?, ?, ?)",
                                 (number, job, state, build)).lastrowid
            db.execute("UPDATE issues SET active_attempt = ? WHERE id = ?", (attempt, number))
            if state == "candidate":
                state = "awaiting_confirmation"
                self.set_state(db, number, state)
                message = (
                    f"{BOT} Codex reviewed the staging report. Use the installed preview build {build} to test "
                    "the feedback flow. This is a simulated candidate. Does the report flow work?"
                    if staging else
                    f"{BOT} Candidate build {build} is ready to test. Could you repeat the same steps and tell us if it works?"
                )
                self.event(db, number, "candidate", message)
            else:
                self.event(db, number, "failed", f"{BOT} This attempt did not produce a testable fix.")
                state = self.failure(db, issue, "queued")
            return {"attempt": attempt, "state": state}

    def feedback(self, token, payload):
        attempt, resolved = payload.get("attempt"), payload.get("resolved")
        if payload.keys() != {"attempt", "resolved"} or type(resolved) is not bool or type(attempt) is not int:
            raise Invalid("Choose whether the tested build resolved the issue.")
        with self.transaction() as db:
            issue = self.issue(db, self.lookup(db, token)["issue"])
            if token != self.primary_receipt(db, issue["id"]):
                raise Invalid("Only the original reporter can confirm this attempt.", 403)
            if issue["active_attempt"] != attempt:
                raise Invalid("This test build is no longer the current attempt.", 409)
            expected = "resolved" if resolved else "still_broken"
            recorded = db.execute("SELECT state FROM attempts WHERE id = ?", (attempt,)).fetchone()
            if recorded["state"] == expected:
                return {"state": issue["state"]}
            if issue["state"] != "awaiting_confirmation":
                raise Invalid("A response has already been recorded for this build.", 409)
            db.execute("UPDATE attempts SET state = ? WHERE id = ?", (expected, attempt))
            if resolved:
                self.set_state(db, issue["id"], "resolved")
                self.event(db, issue["id"], "resolved", f"{BOT} Thanks for confirming the fix.")
                return {"state": "resolved"}
            self.event(db, issue["id"], "updated_report",
                       f"{BOT} Please send updated diagnostics from the build you just tried.")
            return {"state": self.failure(db, issue, "awaiting_report")}

    def handoff(self, number):
        with self.transaction() as db:
            issue = self.issue(db, number)
            attempts = db.execute("SELECT id, job, state, build FROM attempts WHERE issue = ? ORDER BY id", (number,))
            reports = db.execute("""SELECT snapshots.build, snapshots.data, reports.note
                                    FROM reports JOIN snapshots ON reports.snapshot = snapshots.id
                                    WHERE reports.issue = ? ORDER BY reports.rowid""", (number,))
            return {"issue": dict(issue), "attempts": [dict(row) for row in attempts],
                    "reports": [{"build": row["build"], "diagnostics": json.loads(row["data"]),
                                 "note": row["note"]} for row in reports]}

    def github_preview(self, number):
        with self.transaction() as db:
            issue = self.issue(db, number)
            original = db.execute("""SELECT snapshots.build FROM reports JOIN snapshots ON reports.snapshot = snapshots.id
                                     WHERE reports.issue = ? ORDER BY reports.rowid LIMIT 1""", (number,)).fetchone()
            label = APPS[issue["app"]]
            updates = db.execute("""SELECT kind, body FROM events WHERE issue = ?
                                 AND kind IN ('candidate', 'failed', 'updated_report', 'manual_review', 'resolved') ORDER BY id""",
                                 (number,))
            return {
                "title": f"{label} report from build {original['build']}",
                "body": f"{BOT}\n\nThe Lab received a diagnostic report for {label}. "
                        "This issue tracks test builds and the reporter's confirmation.",
                "labels": [issue["app"]],
                "comments": [dict(row) for row in updates],
            }

    def manual_reviews(self):
        with self.transaction() as db:
            return [dict(row) for row in db.execute(
                "SELECT id, app, failures FROM issues WHERE state = 'manual_review' ORDER BY id")]


def unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Invalid("Duplicate JSON field.")
        result[key] = value
    return result


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Request paths can contain the report receipt.
        pass

    def reply(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def payload(self):
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= MAX_REQUEST:
            raise Invalid("Expected a bounded JSON report.", 413)
        data = json.loads(self.rfile.read(length), object_pairs_hook=unique_json_object)
        if not isinstance(data, dict):
            raise Invalid("Expected a JSON object.")
        return data

    def route(self, method):
        path = urlsplit(self.path).path
        reports = self.server.reports
        if method == "POST" and path == "/reports":
            return reports.submit(self.payload())
        receipt = re.fullmatch(r"/reports/([A-Za-z0-9_-]{32})(/feedback)?", path)
        if receipt and method == "GET" and not receipt[2]:
            return reports.status(receipt[1])
        if receipt and method == "POST" and receipt[2]:
            return reports.feedback(receipt[1], self.payload())
        admin = re.fullmatch(r"/maintainer/issues/(\d{1,9})/(queue|attempts|handoff|github-preview)", path)
        if admin or path == "/maintainer/review":
            if not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {self.server.admin_token}"):
                raise Invalid("Maintainer access required.", 401)
        if method == "GET" and path == "/maintainer/review":
            return {"issues": reports.manual_reviews()}
        if admin:
            number, action = int(admin[1]), admin[2]
            if method == "GET" and action == "handoff":
                return reports.handoff(number)
            if method == "GET" and action == "github-preview":
                return reports.github_preview(number)
            if method == "POST" and action == "queue":
                if int(self.headers.get("Content-Length", "0")) != 0:
                    raise Invalid("Queue requests take no body.")
                return reports.queue(number)
            if method == "POST" and action == "attempts":
                return reports.attempt(number, self.payload())
        raise Invalid("Not found.", 404)

    def dispatch(self, method):
        try:
            data = self.route(method)
        except Invalid as error:
            self.reply(error.status, {"error": str(error)})
        except (UnicodeError, ValueError):
            self.reply(400, {"error": "Invalid JSON request."})
        else:
            self.reply(200, data)

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")


def local_server(path, token, port):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.reports = Reports(path)
    server.admin_token = token
    return server


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True)
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    token = os.environ.get("YYEQ_REPORT_MAINTAINER_TOKEN", "")
    if len(token) < 24:
        parser.error("Set YYEQ_REPORT_MAINTAINER_TOKEN to a private value of at least 24 characters.")
    with local_server(args.db, token, args.port) as service:
        service.serve_forever()
