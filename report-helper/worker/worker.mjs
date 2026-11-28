import { DurableObject } from "cloudflare:workers";

const BOT = "I'm YinYang EQ Helper, an automated bot for Yin Yang EQ Lab.";
const REPO_URL = "https://github.com/yinyangeqlab/yinyang-android/issues/";
const APPS = { "phone-eq": "Phone EQ", companion: "Companion" };
const FIELDS = {
  "phone-eq": new Set(["device", "android", "api", "shizuku", "shizukuVersion", "notifications",
    "availableOutputs", "activeRoute", "playbackMode", "playback", "startup"]),
  companion: new Set(["device", "android", "api", "dac", "firmware", "transport", "stage", "mode", "events"]),
};
const MAX_BODY = 16 * 1024;
const DAY = 86400;
const REPORT_RETENTION_DAYS = 90;
const REPORT_RETENTION = REPORT_RETENTION_DAYS * DAY;
const PURGE_BATCH = 250;
const STORE_NAME = "report-lifecycle-v1";
const RECORD_KINDS = ["candidate", "failed", "updated_report", "manual_review", "resolved"];
const encoder = new TextEncoder();
const receiptRoute = /^\/reports\/([\w-]{32})(\/(?:feedback|delete))?$/;
const ownerRoute = /^\/maintainer\/issues\/([1-9]\d{0,8})\/(handoff|draft|link|queue|attempts)$/;

class Rejected extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

function json(value, status = 200) {
  return Response.json(value, {
    status,
    headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
  });
}

function guarded(request, action) {
  return Promise.resolve().then(action).catch(async error => {
    if (request.body && !request.bodyUsed) await request.body.cancel().catch(() => {});
    if (error instanceof Rejected) return json({ error: error.message }, error.status);
    return json({ error: "The report service is temporarily unavailable." }, 503);
  });
}

function matchesRoute(method, path) {
  const receipt = receiptRoute.exec(path);
  const owner = ownerRoute.exec(path);
  return (path === "/reports" && method === "POST")
    || (receipt && method === (receipt[2] ? "POST" : "GET"))
    || (["/maintainer/issues", "/maintainer/retention"].includes(path) && method === "GET")
    || (owner && method === (["handoff", "draft"].includes(owner[2]) ? "GET" : "POST"));
}

async function requireMaintainer(request, expected) {
  const candidate = request.headers.get("Authorization") ?? "";
  if (!expected || expected.length < 32 ||
      !crypto.subtle.timingSafeEqual(
        await crypto.subtle.digest("SHA-256", encoder.encode(candidate)),
        await crypto.subtle.digest("SHA-256", encoder.encode("Bearer " + expected)),
      )) {
    throw new Rejected("Maintainer access required.", 401);
  }
}

function object(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

function keys(value, allowed, required = []) {
  return object(value) && Object.keys(value).every(key => allowed.includes(key))
    && required.every(key => Object.hasOwn(value, key));
}

function uniqueJson(text) {
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Rejected("Invalid JSON request.");
  }
  // The parsed JSON is valid, so a string followed by a colon is an object member name.
  const objects = [];
  for (const token of text.matchAll(/"(?:\\[\s\S]|[^"\\])*"|[{}\[\]]/g)) {
    const word = token[0];
    if (word === "{") objects.push(new Set());
    else if (word === "[") objects.push(null);
    else if (word === "}" || word === "]") objects.pop();
    else if (objects.at(-1) && /^\s*:/.test(text.slice(token.index + word.length))) {
      const name = JSON.parse(word);
      if (objects.at(-1).has(name)) throw new Rejected("Duplicate JSON field.");
      objects.at(-1).add(name);
    }
  }
  if (!object(data)) throw new Rejected("Expected a JSON object.");
  return data;
}

async function readReportBytes(request) {
  if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BODY) {
    throw new Rejected("Expected a bounded JSON request.", 413);
  }
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const buffer = new Uint8Array(MAX_BODY);
  let length = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    if (length + value.length > MAX_BODY) {
      await reader.cancel();
      throw new Rejected("Expected a bounded JSON request.", 413);
    }
    buffer.set(value, length);
    length += value.length;
  }
  return buffer.subarray(0, length);
}

async function body(request) {
  const bytes = await readReportBytes(request);
  try {
    return uniqueJson(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch (error) {
    if (error instanceof Rejected) throw error;
    throw new Rejected("Invalid JSON request.");
  }
}

function buildNumber(value) {
  return Number.isInteger(value) && value >= 1 && value <= 2_100_000_000;
}

function validInstallation(value) {
  return typeof value === "string" && /^[a-f0-9]{32}$/.test(value);
}

const hash = async value => Array.from(
  new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))),
  byte => byte.toString(16).padStart(2, "0"),
).join("");

function newReceipt() {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(24))))
    .replaceAll("+", "-").replaceAll("/", "_");
}

async function normalizedReport(payload) {
  if (!keys(payload, ["app", "build", "installation", "diagnostics", "note", "receipt"],
    ["app", "build", "installation", "diagnostics"])) {
    throw new Rejected("Unknown or missing report fields.");
  }
  const { app, build, installation, diagnostics, note = "", receipt } = payload;
  if (typeof app !== "string" || !Object.hasOwn(FIELDS, app) || !buildNumber(build)) {
    throw new Rejected("Unknown app or build.");
  }
  if (!validInstallation(installation)) {
    throw new Rejected("Missing installation key.");
  }
  if (receipt !== undefined && (typeof receipt !== "string" || !/^[\w-]{32}$/.test(receipt))) {
    throw new Rejected("Invalid report receipt.", 404);
  }
  if (!object(diagnostics) || !Object.keys(diagnostics).length) throw new Rejected("Invalid diagnostics.");
  if (typeof note !== "string" || [...note].length > 600) throw new Rejected("Keep the description under 600 characters.");
  const fields = {};
  for (const key of Object.keys(diagnostics).sort()) {
    const value = diagnostics[key];
    if (!FIELDS[app].has(key) || typeof value !== "string" || value.length > 500 ||
        !value.trim() || /[^A-Za-z0-9 .,_+()\[\]:-]/.test(value) ||
        /\b(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}\b/i.test(value)) {
      throw new Rejected("Invalid diagnostic fields or values.");
    }
    fields[key] = value.trim().replace(/ +/g, " ");
  }
  const data = JSON.stringify(fields);
  return { app, build, installation: await hash(installation), data, digest: await hash(data), note: note.trim(), receipt };
}

export default {
  fetch(request, env) {
    return guarded(request, async () => {
      const path = new URL(request.url).pathname;
      if (!matchesRoute(request.method, path)) throw new Rejected("Not found.", 404);
      if (!env.MAINTAINER_TOKEN || env.MAINTAINER_TOKEN.length < 32) {
        throw new Rejected("The report service is temporarily unavailable.", 503);
      }
      if (path.startsWith("/maintainer/")) {
        await requireMaintainer(request, env.MAINTAINER_TOKEN);
      } else {
        const limiter = request.method === "GET" ? env.READ_LIMIT : env.WRITE_LIMIT;
        const ip = request.headers.get("CF-Connecting-IP") || "unknown";
        if (!(await limiter.limit({ key: ip })).success) throw new Rejected("Please wait before trying again.", 429);
      }
      return env.REPORTS.getByName(STORE_NAME).fetch(request);
    });
  },
  async scheduled(controller, env) {
    await env.REPORTS.getByName(STORE_NAME).purgeExpired(Math.floor(controller.scheduledTime / 1000));
  },
};

export class ReportStore extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS issues (
        id INTEGER PRIMARY KEY, app TEXT NOT NULL, build INTEGER NOT NULL, state TEXT NOT NULL,
        failures INTEGER NOT NULL DEFAULT 0, active_attempt INTEGER, github INTEGER UNIQUE,
        expires_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS snapshots (
        id INTEGER PRIMARY KEY, issue INTEGER NOT NULL REFERENCES issues(id), app TEXT NOT NULL,
        build INTEGER NOT NULL, digest TEXT NOT NULL, installation TEXT NOT NULL,
        diagnostics TEXT, note TEXT, created INTEGER NOT NULL,
        UNIQUE(issue, build, digest)
      );
      CREATE INDEX IF NOT EXISTS dedup ON snapshots(app,build,digest);
      CREATE INDEX IF NOT EXISTS quota ON snapshots(created,installation);
      CREATE INDEX IF NOT EXISTS installation_quota ON snapshots(installation,created);
      CREATE TABLE IF NOT EXISTS receipts (
        token TEXT PRIMARY KEY, installation TEXT NOT NULL, issue INTEGER NOT NULL REFERENCES issues(id),
        created INTEGER NOT NULL,
        UNIQUE(installation, issue)
      );
      CREATE INDEX IF NOT EXISTS receipt_issues ON receipts(issue);
      CREATE INDEX IF NOT EXISTS receipt_quota ON receipts(created,installation);
      CREATE TABLE IF NOT EXISTS attempts (
        id INTEGER PRIMARY KEY, issue INTEGER NOT NULL REFERENCES issues(id), job TEXT NOT NULL,
        state TEXT NOT NULL, build INTEGER, UNIQUE(issue, job)
      );
      CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY, issue INTEGER NOT NULL REFERENCES issues(id), kind TEXT NOT NULL, body TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS issue_events ON events(issue,id);
      CREATE TABLE IF NOT EXISTS retention_control (
        id INTEGER PRIMARY KEY CHECK(id = 1), last_issue INTEGER NOT NULL,
        last_sweep INTEGER, deleted INTEGER NOT NULL DEFAULT 0
      );
    `);
    ctx.storage.transactionSync(() => {
      if (!this.sql.exec("PRAGMA table_info(issues)").toArray().some(column => column.name === "expires_at")) {
        this.sql.exec("ALTER TABLE issues ADD COLUMN expires_at INTEGER");
        this.sql.exec(`
          UPDATE issues SET expires_at =
            COALESCE((SELECT min(created) FROM snapshots WHERE issue = issues.id), ?) + ?
        `, this.now(), REPORT_RETENTION);
      }
      this.sql.exec("CREATE INDEX IF NOT EXISTS issue_expiry ON issues(expires_at,id)");
      // IDs appear in durable GitHub markers, so deleting the newest case must never recycle its ID.
      this.sql.exec(`
        INSERT OR IGNORE INTO retention_control(id,last_issue)
        SELECT 1, COALESCE(max(id),0) FROM issues
      `);
    });
  }

  row(query, ...args) { return this.sql.exec(query, ...args).toArray()[0]; }

  now() { return Math.floor(Date.now() / 1000); }

  deleteCases(selection, ...parameters) {
    for (const table of ["snapshots", "receipts", "attempts", "events"]) {
      this.sql.exec(`DELETE FROM ${table} WHERE issue IN (${selection})`, ...parameters);
    }
    return this.sql.exec(`DELETE FROM issues WHERE id IN (${selection}) RETURNING id`, ...parameters).toArray().length;
  }

  purgeExpired(cutoff) {
    if (!Number.isSafeInteger(cutoff) || cutoff < 1) throw new Error("Invalid scheduled cutoff.");
    let deleted = 0;
    // A service outage can leave a backlog. Each invocation processes up to one thousand cases.
    for (let batch = 0; batch < 4; batch++) {
      const removed = this.ctx.storage.transactionSync(() => this.deleteCases(
        "SELECT id FROM issues WHERE expires_at <= ? ORDER BY expires_at,id LIMIT ?", cutoff, PURGE_BATCH,
      ));
      deleted += removed;
      if (removed < PURGE_BATCH) break;
    }
    this.sql.exec("UPDATE retention_control SET last_sweep = ?, deleted = ? WHERE id = 1 AND (last_sweep IS NULL OR last_sweep <= ?)",
      cutoff, deleted, cutoff);
  }

  issue(number) {
    const issue = this.row("SELECT * FROM issues WHERE id = ? AND expires_at > ?", number, this.now());
    if (!issue) throw new Rejected("Report not found.", 404);
    return issue;
  }

  receipt(token) {
    const receipt = this.row("SELECT * FROM receipts WHERE token = ?", token);
    if (!receipt) throw new Rejected("Invalid report receipt.", 404);
    return receipt;
  }

  deleteReport(token, installation) {
    const receipt = this.row("SELECT * FROM receipts WHERE token = ?", token);
    if (!receipt) return { deleted: true };
    if (receipt.installation !== installation) throw new Rejected("This report belongs to another installation.", 403);
    this.sql.exec("DELETE FROM receipts WHERE token = ?", token);
    if (this.row("SELECT 1 FROM receipts WHERE issue = ? LIMIT 1", receipt.issue)) {
      this.sql.exec("DELETE FROM snapshots WHERE issue = ? AND installation = ?", receipt.issue, installation);
    } else {
      this.deleteCases("SELECT id FROM issues WHERE id = ?", receipt.issue);
    }
    return { deleted: true };
  }

  primary(issue, token) {
    return this.row("SELECT token FROM receipts WHERE issue = ? ORDER BY rowid LIMIT 1", issue)?.token === token;
  }

  event(issue, kind, text) {
    this.sql.exec("INSERT INTO events(issue,kind,body) VALUES (?,?,?)", issue, kind, `${BOT} ${text}`);
  }

  state(number, state) {
    this.sql.exec("UPDATE issues SET state = ? WHERE id = ?", state, number);
    return { state };
  }

  quota(installation, now, added) {
    const dayStart = now - now % DAY;
    const { total, own } = this.row(`
      SELECT (SELECT count(*) FROM snapshots WHERE created >= ?)
           + (SELECT count(*) FROM receipts WHERE created >= ?) AS total,
             (SELECT count(*) FROM snapshots WHERE installation = ? AND created >= ?)
           + (SELECT count(*) FROM receipts WHERE installation = ? AND created >= ?) AS own
    `, dayStart, dayStart, installation, dayStart, installation, dayStart);
    if (total + added > 1000 || own + added > 24) {
      throw new Rejected("Today’s new report limit was reached. Try again tomorrow.", 429);
    }
  }

  submit(report) {
    const { app, build, data, digest, installation, note, receipt } = report;
    let issue, token = receipt;
    if (receipt) {
      const existing = this.receipt(receipt);
      issue = this.issue(existing.issue);
      if (installation !== existing.installation || issue.app !== app || !this.primary(issue.id, token)) {
        throw new Rejected("This report belongs to another installation or app.", 403);
      }
      if (issue.state === "awaiting_report" &&
          this.row("SELECT build FROM attempts WHERE id = ?", issue.active_attempt)?.build !== build) {
        throw new Rejected("Send the updated report from the build you tested.", 409);
      }
    } else {
      const snapshot = this.row(`
        SELECT snapshots.issue FROM snapshots JOIN issues ON issues.id = snapshots.issue
        WHERE snapshots.app = ? AND snapshots.build = ? AND snapshots.digest = ? AND issues.expires_at > ?
        ORDER BY snapshots.id LIMIT 1
      `, app, build, digest, this.now());
      if (snapshot) issue = this.issue(snapshot.issue);
    }
    const already = issue && this.row("SELECT 1 FROM snapshots WHERE issue = ? AND build = ? AND digest = ?", issue.id, build, digest);
    if (receipt && !already && !["awaiting_report", "manual_review"].includes(issue.state)) {
      throw new Rejected("This report is not accepting updated diagnostics.", 409);
    }
    const prior = issue && this.row("SELECT token FROM receipts WHERE issue = ? AND installation = ?", issue.id, installation);
    const now = this.now();
    const added = Number(!already) + Number(!receipt && !prior);
    if (added) this.quota(installation, now, added);
    if (!issue) {
      const { last_issue: number } = this.row("UPDATE retention_control SET last_issue = last_issue + 1 WHERE id = 1 RETURNING last_issue");
      issue = this.row("INSERT INTO issues(id,app,build,state,expires_at) VALUES (?,?,?,'submitted',?) RETURNING *",
        number, app, build, now + REPORT_RETENTION);
      this.event(issue.id, "received", `Report received for ${APPS[app]} build ${build}.`);
    }
    if (!token) {
      token = prior?.token || newReceipt();
      if (!prior) this.sql.exec("INSERT INTO receipts VALUES (?,?,?,?)", token, installation, issue.id, now);
    }
    if (!already) {
      this.sql.exec("INSERT INTO snapshots(issue,app,build,digest,installation,diagnostics,note,created) VALUES (?,?,?,?,?,?,?,?)",
        issue.id, app, build, digest, installation, data, note, now);
      if (receipt && issue.state === "awaiting_report") {
        this.state(issue.id, "queued");
        this.event(issue.id, "queued", "The updated report is ready for another attempt.");
      }
    }
    return { receipt: token, duplicate: Boolean(already), state: this.issue(issue.id).state };
  }

  status(token) {
    const issue = this.issue(this.receipt(token).issue);
    const attempt = this.row("SELECT id,build FROM attempts WHERE id = ?", issue.active_attempt) ?? null;
    const updates = this.sql.exec("SELECT kind,body FROM events WHERE issue = ? ORDER BY id", issue.id).toArray();
    return { issue: issue.id, state: issue.state, failures: issue.failures, canConfirm: this.primary(issue.id, token), attempt, updates };
  }

  failure(issue, next) {
    const count = issue.failures + 1;
    const state = count >= 3 ? "manual_review" : next;
    this.sql.exec("UPDATE issues SET state = ?, failures = ? WHERE id = ?", state, count, issue.id);
    if (state === "manual_review") this.event(issue.id, state, "Three fix attempts failed. A Lab maintainer will review this report before another attempt.");
    return { state };
  }

  feedback(token, payload) {
    if (!keys(payload, ["attempt", "resolved"], ["attempt", "resolved"])
        || !Number.isInteger(payload.attempt) || typeof payload.resolved !== "boolean") {
      throw new Rejected("Choose whether the tested build resolved the issue.");
    }
    const issue = this.issue(this.receipt(token).issue);
    if (!this.primary(issue.id, token)) throw new Rejected("Only the original reporter can confirm this attempt.", 403);
    if (issue.active_attempt !== payload.attempt) throw new Rejected("This test build is no longer the current attempt.", 409);
    const state = payload.resolved ? "resolved" : "still_broken";
    if (this.row("SELECT state FROM attempts WHERE id = ?", payload.attempt)?.state === state) return { state: issue.state };
    if (issue.state !== "awaiting_confirmation") throw new Rejected("A response has already been recorded for this build.", 409);
    this.sql.exec("UPDATE attempts SET state = ? WHERE id = ?", state, payload.attempt);
    if (payload.resolved) {
      this.event(issue.id, "resolved", "Thanks for confirming the fix.");
      return this.state(issue.id, state);
    }
    this.event(issue.id, "updated_report", "Please send updated diagnostics from the build you just tried.");
    return this.failure(issue, "awaiting_report");
  }

  owner(number, action, payload) {
    const issue = this.issue(number);
    if (action === "handoff") {
      const reports = this.sql.exec("SELECT build,diagnostics,note FROM snapshots WHERE issue = ? ORDER BY id", number)
        .toArray().map(({ build, diagnostics, note }) => ({ build, diagnostics: JSON.parse(diagnostics ?? "null"), note }));
      return { issue, reports };
    }
    if (action === "draft") {
      const updates = this.sql.exec("SELECT id,body FROM events WHERE issue = ? AND kind IN (?,?,?,?,?) ORDER BY id", number, ...RECORD_KINDS).toArray();
      return { ...issue, label: APPS[issue.app], updates };
    }
    if (action === "link") {
      if (!keys(payload, ["github"], ["github"]) || !buildNumber(payload.github)) throw new Rejected("Invalid GitHub issue number.");
      if (issue.github && issue.github !== payload.github) throw new Rejected("The report already has a linked GitHub issue.", 409);
      if (!issue.github) {
        this.sql.exec("UPDATE issues SET github = ? WHERE id = ?", payload.github, number);
        this.event(number, "github_issue", `Track this report on GitHub: ${REPO_URL}${payload.github}`);
      }
      return { github: payload.github };
    }
    if (action === "queue") {
      if (issue.state === "queued") return { state: issue.state };
      if (!["submitted", "manual_review"].includes(issue.state)) throw new Rejected("The report cannot be queued now.", 409);
      this.sql.exec("UPDATE issues SET state = 'queued', failures = 0 WHERE id = ?", number);
      this.event(number, "queued", "The Lab queued this report for a fix attempt.");
      return { state: "queued" };
    }
    const { job, state, build = null, test = false } = payload;
    if (!keys(payload, ["job", "state", "build", "test"], ["job", "state"]) ||
        typeof job !== "string" || !/^[\w-]{1,100}$/.test(job) || typeof test !== "boolean" ||
        !["candidate", "failed"].includes(state) || (state === "candidate" ? !buildNumber(build) : build !== null)) {
      throw new Rejected("Provide a candidate build or a failed attempt.");
    }
    const prior = this.row("SELECT id FROM attempts WHERE issue = ? AND job = ?", number, job);
    if (prior) return { attempt: prior.id, state: issue.state };
    if (issue.state !== "queued") throw new Rejected("No fix attempt is queued for this report.", 409);
    const { id } = this.row("INSERT INTO attempts(issue,job,state,build) VALUES (?,?,?,?) RETURNING id", number, job, state, build);
    this.sql.exec("UPDATE issues SET active_attempt = ? WHERE id = ?", id, number);
    if (state === "failed") {
      this.event(number, "failed", "This attempt did not produce a testable fix.");
      return { attempt: id, ...this.failure(issue, "queued") };
    }
    this.event(number, "candidate", test
      ? `This is a reporting test using build ${build}. Did the report flow work?`
      : `Candidate build ${build} is ready to test. Could you repeat the same steps and tell us if it works?`);
    return { attempt: id, ...this.state(number, "awaiting_confirmation") };
  }

  fetch(request) {
    return guarded(request, async () => {
      const path = new URL(request.url).pathname;
      if (!matchesRoute(request.method, path)) throw new Rejected("Not found.", 404);
      const receipt = receiptRoute.exec(path);
      const owner = ownerRoute.exec(path);
      if (path === "/reports") {
        const report = await normalizedReport(await body(request));
        return json(this.ctx.storage.transactionSync(() => this.submit(report)));
      }
      if (receipt) {
        if (!receipt[2]) return json(this.status(receipt[1]));
        const payload = await body(request);
        if (receipt[2] === "/delete") {
          if (!keys(payload, ["installation"], ["installation"]) || !validInstallation(payload.installation)) {
            throw new Rejected("Missing or invalid installation key.");
          }
          const installation = await hash(payload.installation);
          return json(this.ctx.storage.transactionSync(() => this.deleteReport(receipt[1], installation)));
        }
        return json(this.ctx.storage.transactionSync(() => this.feedback(receipt[1], payload)));
      }
      if (path === "/maintainer/issues") {
        return json({ issues: this.sql.exec(
          "SELECT id,app,build,state,failures,github FROM issues WHERE expires_at > ? AND state IN ('submitted','queued','manual_review') ORDER BY id DESC LIMIT 100",
          this.now(),
        ).toArray() });
      }
      if (path === "/maintainer/retention") {
        const { last_sweep, deleted } = this.row("SELECT last_sweep,deleted FROM retention_control WHERE id = 1");
        return json({ days: REPORT_RETENTION_DAYS, lastSweep: last_sweep, deleted });
      }
      const payload = ["link", "attempts"].includes(owner[2]) ? await body(request) : {};
      if (owner[2] === "queue" && (await readReportBytes(request)).length) {
        throw new Rejected("Queue requests take no body.");
      }
      return json(this.ctx.storage.transactionSync(() => this.owner(Number(owner[1]), owner[2], payload)));
    });
  }
}
