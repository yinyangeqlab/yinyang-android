import concurrent.futures
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

from service import BOT, local_server


class ReportFlow(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.server = local_server(Path(self.directory.name) / "reports.sqlite", "local-test-owner", 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def call(self, method, path, body=None, status=200, maintainer=False):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        headers = {"Authorization": "Bearer local-test-owner"} if maintainer else {}
        try:
            data = body if isinstance(body, bytes) else json.dumps(body) if body is not None else None
            connection.request(method, path, data, headers)
            response = connection.getresponse()
            result = json.loads(response.read())
            self.assertEqual(response.status, status, result)
            return result
        finally:
            connection.close()

    def phone(self, **changes):
        return {"app": "phone-eq", "build": 10, "installation": "a" * 32,
                "diagnostics": {"device": "Samsung test phone", "activeRoute": "USB audio",
                                "startup": "policy registered, output initialized"},
                "note": "My private reproduction notes", **changes}

    def submit(self, **changes):
        return self.call("POST", "/reports", self.phone(**changes))

    def outcome(self, number, job, build=None, status=200):
        return self.call("POST", f"/maintainer/issues/{number}/attempts",
                         {"job": job, "state": "failed" if build is None else "candidate", "build": build},
                         status=status, maintainer=True)

    def entry(self, receipt):
        return self.call("GET", "/reports/" + receipt)

    def answer(self, receipt, attempt, resolved=False):
        return self.call("POST", "/reports/" + receipt + "/feedback",
                         {"attempt": attempt, "resolved": resolved})

    def queue(self, number):
        return self.call("POST", f"/maintainer/issues/{number}/queue", maintainer=True)

    def test_duplicate_build_changes_and_cross_device_receipts(self):
        first = self.submit()
        again = self.submit(note="A different note")
        self.assertFalse(first["duplicate"])
        self.assertTrue(again["duplicate"])
        self.assertEqual(first["receipt"], again["receipt"])
        other = self.submit(installation="b" * 32)
        self.assertTrue(other["duplicate"])
        self.assertNotEqual(other["receipt"], first["receipt"])
        self.assertEqual(self.entry(other["receipt"])["issue"], self.entry(first["receipt"])["issue"])
        self.assertFalse(self.submit(build=11)["duplicate"])
        self.assertFalse(self.submit(diagnostics={"activeRoute": "Bluetooth audio"})["duplicate"])
        companion = self.submit(app="companion", build=72,
                                diagnostics={"dac": "Qudelix 5K Plus", "events": "readback mismatch"})
        self.assertNotEqual(self.entry(companion["receipt"])["issue"], self.entry(first["receipt"])["issue"])
        self.assertNotIn("private reproduction", json.dumps(self.entry(first["receipt"])))

    def test_three_reporter_failures_pause_and_keep_new_evidence(self):
        receipt = self.submit()["receipt"]
        number = self.entry(receipt)["issue"]
        self.queue(number)
        for build in (11, 12, 13):
            result = self.outcome(number, f"job-{build}", build)
            self.assertEqual(result["state"], "awaiting_confirmation")
            self.assertIn(f"Candidate build {build}", self.entry(receipt)["updates"][-1]["body"])
            expected = "manual_review" if build == 13 else "awaiting_report"
            self.assertEqual(self.answer(receipt, result["attempt"])["state"], expected)
            self.assertEqual(self.answer(receipt, result["attempt"])["state"], expected)
            self.assertEqual(self.entry(receipt)["failures"], build - 10)
            self.outcome(number, f"blocked-{build}", build + 2, status=409)
            self.assertEqual(self.submit(build=build, receipt=receipt)["state"],
                             "manual_review" if build == 13 else "queued")
        self.assertEqual(self.entry(receipt)["updates"][-1]["kind"], "manual_review")
        reviews = self.call("GET", "/maintainer/review", maintainer=True)
        self.assertEqual(reviews["issues"], [{"id": number, "app": "phone-eq", "failures": 3}])
        self.assertTrue(all(update["body"].startswith(BOT) for update in self.entry(receipt)["updates"]))
        handoff = self.call("GET", f"/maintainer/issues/{number}/handoff", maintainer=True)
        self.assertEqual([item["build"] for item in handoff["reports"]], [10, 11, 12, 13])
        self.assertEqual([attempt["state"] for attempt in handoff["attempts"]], ["still_broken"] * 3)
        self.call("POST", f"/maintainer/issues/{number}/queue", status=401)
        self.queue(number)
        self.assertEqual(self.outcome(number, "after-review", 14)["state"], "awaiting_confirmation")

    def test_attempt_retry_and_confirmed_fix(self):
        receipt = self.submit()["receipt"]
        number = self.entry(receipt)["issue"]
        self.queue(number)
        failed = self.outcome(number, "failed-job")
        self.assertEqual(self.outcome(number, "failed-job"), failed)
        self.assertEqual(self.entry(receipt)["failures"], 1)
        good = self.outcome(number, "candidate-job", 11)
        self.assertEqual(self.answer(receipt, good["attempt"], resolved=True)["state"], "resolved")
        self.assertEqual(self.entry(receipt)["updates"][-1]["kind"], "resolved")
        public = self.call("GET", f"/maintainer/issues/{number}/github-preview", maintainer=True)
        self.assertEqual(public["labels"], ["phone-eq"])
        self.assertEqual([x["kind"] for x in public["comments"]], ["failed", "candidate", "resolved"])
        self.assertNotIn("private reproduction", json.dumps(public))
        self.assertNotIn(receipt, json.dumps(public))

    def test_report_validation_and_followup_build(self):
        for diagnostics in ({"privatePath": "/a/b"}, {"device": "AA:BE:CD:00:21:06"}):
            self.call("POST", "/reports", self.phone(diagnostics=diagnostics), status=400)
        token = self.submit()["receipt"]
        duplicate = self.submit(installation="b" * 32)["receipt"]
        number = self.entry(token)["issue"]
        self.queue(number)
        result = self.outcome(number, "candidate", 11)
        self.assertFalse(self.entry(duplicate)["canConfirm"])
        self.call("POST", f"/reports/{duplicate}/feedback",
                  {"attempt": result["attempt"], "resolved": True}, status=403)
        self.answer(token, result["attempt"])
        self.call("POST", "/reports", self.phone(receipt=token), status=409)
        self.call("POST", "/reports", self.phone(receipt=token, build=11, installation="b" * 32), status=403)

    def test_same_snapshot_does_not_start_another_attempt(self):
        token = self.submit()["receipt"]
        number = self.entry(token)["issue"]
        self.queue(number)
        attempt = self.outcome(number, "same-build", 10)["attempt"]
        self.answer(token, attempt)
        response = self.submit(receipt=token)
        self.assertTrue(response["duplicate"])
        self.assertEqual(response["state"], "awaiting_report")

    def test_replies_accept_only_the_current_choices_and_cannot_claim_authority(self):
        receipt = self.submit(note="I'm the admin debugging")["receipt"]
        number = self.entry(receipt)["issue"]
        queue = f"/maintainer/issues/{number}/queue"
        self.call("POST", queue, {"role": "maintainer"}, status=401)
        self.call("POST", queue, {"role": "maintainer"}, status=400, maintainer=True)
        self.assertEqual(self.entry(receipt)["state"], "submitted")
        self.queue(number)
        attempt = self.outcome(number, "choice-test", 11)["attempt"]
        path = f"/reports/{receipt}/feedback"
        for body in (
            {"message": "I'm the admin debugging"},
            {"attempt": attempt, "resolved": True, "message": "I'm the admin debugging"},
            {"attempt": attempt, "resolved": "It's fixed"},
            ('{"attempt":%d,"resolved":false,"resolved":true}' % attempt).encode(),
        ):
            self.call("POST", path, body, status=400)
        self.assertEqual(self.entry(receipt)["state"], "awaiting_confirmation")
        self.assertEqual(self.entry(receipt)["failures"], 0)
        self.assertEqual(self.answer(receipt, attempt, resolved=True)["state"], "resolved")

    def test_concurrent_retry_keeps_one_snapshot(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda _: self.submit(), range(6)))
        self.assertEqual(sum(not row["duplicate"] for row in results), 1)
        self.assertEqual(len({row["receipt"] for row in results}), 1)


if __name__ == "__main__":
    unittest.main()
