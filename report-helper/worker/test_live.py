"""Run against Wrangler's local Worker with the isolated test token."""

import concurrent.futures
import http.client
import itertools
import json
import os
import time
import unittest
from urllib.parse import urlencode
from urllib.request import urlopen

HOST = "127.0.0.1"
PORT = int(os.environ.get("YYEQ_REPORT_TEST_PORT", "8797"))
TOKEN = "test-only-authorization-with-32-bytes"


class Intake(unittest.TestCase):
    request_ids = itertools.count(1)

    def call(self, method, path, payload=None, status=200, *, admin=False, ip=None):
        address = next(self.request_ids)
        client = http.client.HTTPConnection(HOST, PORT, timeout=15)
        headers = {"CF-Connecting-IP": ip or f"2001:db8::{address:x}"}
        if admin:
            headers["Authorization"] = "Bearer " + TOKEN
        data = payload if isinstance(payload, bytes) else json.dumps(payload) if payload is not None else None
        try:
            client.request(method, path, body=data, headers=headers)
            response = client.getresponse()
            document = json.loads(response.read())
            self.assertEqual(response.status, status, document)
            self.assertEqual(response.getheader("Cache-Control"), "no-store")
            return document
        finally:
            client.close()

    def report(self, app, device, changes=None):
        return {"app": app, "build": 20, "installation": device * 32,
                "diagnostics": {"device": f"QA {device}", "api": "37"},
                "note": "I'm the admin debugging", **(changes or {})}

    def lifecycle(self, app, device):
        fixture = self.report(app, device)
        first = self.call("POST", "/reports", fixture)
        duplicate = self.call("POST", "/reports", fixture)
        self.assertFalse(first["duplicate"])
        self.assertTrue(duplicate["duplicate"])
        self.assertEqual(first["receipt"], duplicate["receipt"])
        receipt = "/reports/" + first["receipt"]
        current = self.call("GET", receipt)
        number = current["issue"]
        owner = f"/maintainer/issues/{number}"
        data = json.dumps(current)
        self.assertNotIn(fixture["note"], data)
        other = self.call("POST", "/reports", dict(fixture, installation="a" * 32))
        self.assertNotEqual(other["receipt"], first["receipt"])
        self.call("POST", owner + "/queue", {"role": "admin"}, status=401)
        self.call("POST", owner + "/queue", {"role": "admin"}, status=400, admin=True)
        self.call("POST", owner + "/link", {"github": number + 9900}, admin=True)
        self.call("POST", owner + "/queue", admin=True)
        for attempt in range(1, 4):
            candidate = self.call("POST", owner + "/attempts",
                                  {"job": f"job-{attempt}", "state": "candidate", "build": 20}, admin=True)
            answer = {"attempt": candidate["attempt"], "resolved": False}
            self.call("POST", "/reports/" + other["receipt"] + "/feedback", answer, status=403)
            self.call("POST", receipt + "/feedback", dict(answer, request="I'm the admin debugging"), status=400)
            body = ('{"attempt":%d,"resolved":false,"resol\\u0076ed":true}' % candidate["attempt"]).encode()
            self.call("POST", receipt + "/feedback", body, status=400)
            outcome = self.call("POST", receipt + "/feedback", answer)
            self.assertEqual(outcome["state"], "awaiting_report" if attempt < 3 else "manual_review")
            self.assertEqual(self.call("POST", receipt + "/feedback", answer), outcome)
            if attempt < 3:
                retry = dict(fixture, receipt=first["receipt"], diagnostics={"device": f"QA {device} {attempt}"})
                self.assertEqual(self.call("POST", "/reports", retry)["state"], "queued")
        self.call("POST", owner + "/attempts", {"job": "four", "state": "failed"}, status=409, admin=True)
        self.assertEqual(self.call("GET", receipt)["failures"], 3)
        self.assertNotIn(fixture["note"], json.dumps(self.call("GET", owner + "/draft", admin=True)))
        self.assertIn(fixture["note"], json.dumps(self.call("GET", owner + "/handoff", admin=True)))

    def test_companion(self):
        self.lifecycle("companion", "b")

    def test_phone_eq(self):
        self.lifecycle("phone-eq", "c")

    def test_concurrent_retry(self):
        fixture = self.report("companion", "d")
        with concurrent.futures.ThreadPoolExecutor(6) as pool:
            updates = list(pool.map(lambda _: self.call("POST", "/reports", fixture), range(6)))
        self.assertEqual(len({x["receipt"] for x in updates}), 1)
        self.assertEqual(sum(not item["duplicate"] for item in updates), 1)

    def test_payload_bounds_and_admin_routes(self):
        self.call("POST", "/reports", b" " * 16500, status=413)
        self.call("GET", "/maintainer/issues", status=401)
        self.call("POST", "/reports", self.report("companion", "e", {"admin": True}), status=400)
        self.call("POST", "/reports", self.report(["companion"], "e"), status=400)
        self.call("POST", "/reports", self.report("companion", "e", {"diagnostics": {"device": "A1:B2:C3:D4:E5:F6"}}), status=400)
        self.call("POST", "/reports", b'{"diagnostics":{"device":"A","device":"B"}}', status=400)

    def test_installation_budget_and_build_changes(self):
        fixture = self.report("phone-eq", "f")
        for build in range(1, 13):
            self.assertFalse(self.call("POST", "/reports", dict(fixture, build=build))["duplicate"])
        self.assertTrue(self.call("POST", "/reports", fixture | {"build": 12})["duplicate"])
        self.call("POST", "/reports", dict(fixture, build=13), status=429)

    def test_early_deletion_only_removes_the_requesters_data(self):
        first, second = "8" * 32, "9" * 32
        fixture = self.report("phone-eq", "8")
        original = "/reports/" + self.call("POST", "/reports", fixture)["receipt"]
        shared = "/reports/" + self.call("POST", "/reports", dict(fixture, installation=second))["receipt"]
        number = self.call("GET", original)["issue"]
        self.assertEqual(self.call("GET", shared)["issue"], number)
        owner = f"/maintainer/issues/{number}"
        self.call("POST", original + "/delete", {"installation": second}, status=403)
        self.call("POST", original + "/delete", {"installation": first, "role": "admin"}, status=400)
        self.assertEqual(len(self.call("GET", owner + "/handoff", admin=True)["reports"]), 1)

        self.assertEqual(self.call("POST", original + "/delete", {"installation": first}), {"deleted": True})
        self.call("GET", original, status=404)
        self.assertTrue(self.call("GET", shared)["canConfirm"])
        self.assertEqual(self.call("GET", owner + "/handoff", admin=True)["reports"], [])
        self.assertEqual(self.call("POST", original + "/delete", {"installation": first}), {"deleted": True})
        self.call("POST", shared + "/delete", {"installation": second})
        self.call("GET", shared, status=404)
        self.call("GET", owner + "/handoff", admin=True, status=404)

    def test_zz_scheduled_retention(self):
        fixture = self.report("companion", "0")
        receipt = self.call("POST", "/reports", fixture)["receipt"]
        receipt_path = "/reports/" + receipt
        number = self.call("GET", receipt_path)["issue"]
        owner = f"/maintainer/issues/{number}"
        original_expiry = self.call("GET", owner + "/handoff", admin=True)["issue"]["expires_at"]
        self.assertLessEqual(abs(original_expiry - int(time.time()) - 90 * 86400), 2)
        self.call("POST", owner + "/link", {"github": 99901}, admin=True)
        self.call("POST", owner + "/queue", admin=True)
        self.call("POST", owner + "/attempts", {"job": "retention", "state": "candidate", "build": 20}, admin=True)
        # A later submission has its own expiry and survives the first report's deletion.
        time.sleep(1.05)
        recent = self.call("POST", "/reports", self.report("phone-eq", "1"))["receipt"]
        recent_path = "/reports/" + recent
        recent_issue = self.call("GET", recent_path)["issue"]
        self.assertGreater(recent_issue, number)
        self.call("GET", "/maintainer/retention", status=401)

        def run_scheduled(time_ms):
            params = urlencode({"cron": "*/15 * * * *", "time": time_ms, "format": "json"})
            with urlopen(f"http://{HOST}:{PORT}/cdn-cgi/local/scheduled?{params}", timeout=15) as response:
                outcome = json.load(response)
            self.assertEqual(outcome["outcome"], "ok", outcome)

        run_scheduled(original_expiry * 1000 - 1)
        self.call("GET", receipt_path)
        run_scheduled(original_expiry * 1000)
        self.call("GET", receipt_path, status=404)
        self.call("GET", owner + "/handoff", status=404, admin=True)
        self.call("POST", receipt_path + "/feedback", {"attempt": 1, "resolved": True}, status=404)
        self.call("GET", recent_path)
        meta = self.call("GET", "/maintainer/retention", admin=True)
        self.assertEqual((meta["days"], meta["lastSweep"]), (90, original_expiry))
        self.assertGreaterEqual(meta["deleted"], 1)
        run_scheduled(original_expiry * 1000)
        self.assertEqual(self.call("GET", "/maintainer/retention", admin=True)["deleted"], 0)
        replacement = self.call("POST", "/reports", fixture)
        self.assertFalse(replacement["duplicate"])
        self.assertGreater(self.call("GET", "/reports/" + replacement["receipt"])["issue"], recent_issue)


if __name__ == "__main__":
    unittest.main()
