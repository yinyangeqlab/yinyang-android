# YinYang EQ Helper preview

The preview accepts reports from the two Android apps on loopback, sends status updates to the original reporter, and gives maintainers a queue for issues that need manual review. The intake alone uses simulated fix results. The separate staging worker connects it to GitHub and Codex for synthetic reports.

Run the local HTTP integration tests from the Android repository root.

```sh
python -B -m unittest discover -s report-helper -v
```

Start the local service with a private maintainer token and a database outside the checkout. The supplied reporting preview APKs use separate app IDs and route through ADB to the loopback service.

```sh
export YYEQ_REPORT_MAINTAINER_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
python -B report-helper/service.py --db /tmp/yyeq-helper-preview.sqlite
```

In another shell:

```sh
adb reverse tcp:8787 tcp:8787
```

Use **Report a bug** in either preview. Phone EQ has a **Review report** button alongside its existing local copy action. The Companion entry is on Devices and in Debug Logs. Use **My reports** from the review screen to see updates and respond.

Maintainer routes accept the token as an Authorization bearer header. Queue a report with `POST /maintainer/issues/<id>/queue` and record a simulated result with `POST /maintainer/issues/<id>/attempts`. A candidate takes `{"job":"demo-1","state":"candidate","build":73}`. A failed attempt takes `{"job":"demo-1","state":"failed"}`. Reuse the job identifier when retrying delivery of the same result.

Use `GET /maintainer/review` for paused issues, `GET /maintainer/issues/<id>/handoff` for private diagnostics and the attempt history, and `GET /maintainer/issues/<id>/github-preview` for the public issue and comment drafts.

## Live staging

Run the intake on a separate loopback port and database, then forward the existing Android previews to that port. Keep the database, maintainer token, GitHub App key, and review outputs outside Git.

```sh
python -B report-helper/service.py --db "$stage_dir/stage.sqlite" --port 8792
adb reverse tcp:8787 tcp:8792
```

Start the staging worker with the verified Issues-only key, a unique lowercase run name, and an isolated Android source directory.

```sh
python -B report-helper/staging.py \
  --db "$stage_dir/stage.sqlite" --name "$stage_name" \
  --app-id "$app_id" --key "$key_path" --checkout "$source_dir" \
  --output "$stage_dir/reviews"
```

Send a report from a preview with the note **Lab staging full-flow test**. The worker recognizes the marker on the initial report, creates one public GitHub issue for the case, runs a read-only Codex review, and posts a simulated candidate made for feedback testing. GitHub receives the app label, build number, and templated updates. Notes, receipt tokens, and device diagnostics remain in the local intake. The posted reply is also available from **My reports**. An updated report continues the same issue once its diagnostic fingerprint changes. Check the maintainers' review queue after the third failed attempt.

Only **It's fixed** and **Still broken** can answer a candidate, using the original reporter's receipt and the current attempt ID. The intake rejects free-text replies, extra fields, and duplicate JSON keys on that route. GitHub comments have no input path into the worker. The staging review uses a verified GitHub issue number and an app-specific prompt defined in code. Report notes, diagnostic text, and GitHub titles and bodies stay outside the prompt. Maintainer actions require the separately configured bearer token.

Use the same run name, database, and review directory when resuming a staging run. The worker checks its existing GitHub issue and comment markers before retrying a delivery.

## Live reporting

Both apps use [reports.yinyangeq.com](https://reports.yinyangeq.com) in builds configured for live reporting. Signed and debug staging databases and receipts are separate from live ones. The Worker and its SQLite Durable Object run independently of the website and the development computer. The custom domain is the only public Worker route.

The app explains what it sends before the user confirms. The Worker keeps diagnostics, optional notes, and receipt credentials private. Reports with the same normalized diagnostics and build share an issue. An installation reuses its receipt for that issue. IP, installation, and service-wide limits bound submissions. Only the original receipt can respond to a current attempt, using one of the two in-app buttons. The third failed attempt goes to manual review.

Use an authenticated `GET /maintainer/issues` to list the private queue and `GET /maintainer/issues/<id>/handoff` for the diagnostics. To open a public issue for a reviewed report or synchronize its templated updates, run the following with the Lab's owner-only credential files.

```sh
python -B report-helper/live_publish.py <id> \
  --maintainer-token-file <private-token-file> --app-id <Lab-app-id> --key <private-App-key>
```

Add `--test` when publishing or synchronizing an explicitly authorized synthetic case. The command authenticates as YinYang EQ Helper, checks exact templates and bot authorship, and reuses issue and comment markers on retries. The GitHub App key stays on the maintainer host. The public issue carries the app and build, followed by templated statuses. Diagnostics, notes, receipt values, GitHub comments, and claims of maintainer identity cannot queue an attempt or trigger Codex. The Worker has no code or release credentials.

A maintainer records genuine candidate results through the authenticated queue and attempts routes. The attempts route takes the same job, state, and build fields as staging. The optional `test` Boolean marks synthetic verification. Repeat the publisher to mirror replies for that case. Automatic patching and publication are separate from this reporting service.

Deploy using the existing Lab Cloudflare account, with the maintainer bearer secret in the provider secret binding. Use the explicit `report-helper/worker/wrangler.jsonc` configuration. Run the Worker locally with SQLite storage, `--test-scheduled`, and a disposable `MAINTAINER_TOKEN` before changing the live service. `report-helper/worker/test_live.py` expects the local Worker on port 8797 and the documented test token in the test source. Live support reports are stored separately from the site's data.

For a code-only update, upload and deploy the Worker version with `wrangler versions upload` and `wrangler versions deploy`. Apply domain and Cron Trigger configuration only when those settings change. [Cloudflare Worker deployment management](https://developers.cloudflare.com/workers/versions-and-deployments/deployment-management/)

### Private report retention

Private reports expire **90 days after the first submission**. Opening a report, resending an identical log, posting an update, or replying to a test build does not extend that date. Both apps show the retention period before sending. If a user opens an expired report in **My reports**, the app removes the unusable local receipt and explains that the report is no longer available.

For early deletion, open **Report a bug → My reports → Delete a report**, pick the report, and confirm **Delete report**. The request requires the private receipt and the originating installation key. It removes that installation's receipt and any diagnostics and notes it submitted to the case. If other installations share the case, their access and submissions remain. If the last installation deletes it, the service also removes the case and its private history. Repeating a completed deletion is safe. Public GitHub issue summaries have separate retention.

At expiry the report stops appearing in both the private maintainer queue and app report status. Cloudflare runs the deletion every 15 minutes. It removes the report, diagnostic snapshots and notes, diagnostic fingerprints, receipt credentials and associated installation hashes, attempt records, and private history from active storage. Deletion does not recycle case numbers. The app keeps a local log fingerprint to block the same installation from resending identical diagnostics from the same build, including after server expiry. This policy applies to the live reporting service. Published GitHub issues retain the public app/build and templated history.

An authenticated `GET /maintainer/retention` returns the configured number of days, the most recent scheduled cutoff as a Unix timestamp in seconds, and the number of cases deleted in that run. The scheduler bounds each sweep to 1,000 cases and catches up on the next run if needed. Cloudflare's [Durable Object point-in-time recovery](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/) can retain previous database versions for up to 30 additional days. After any authorized recovery, verify a scheduled cleanup has run before restoring public access.
