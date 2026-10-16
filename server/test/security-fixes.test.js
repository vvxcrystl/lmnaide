import { test } from "node:test";
import assert from "node:assert/strict";
import { createApp, ipBucketKey } from "../src.js";
import { rmSync } from "node:fs";
import { randomBytes, scryptSync } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";

async function harness(t, options = {}) {
  const app = createApp({ dbPath: ":memory:", ...options });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  t.after(() => app.close());
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const call = async (path, { method, body, headers } = {}) => {
    const response = await fetch(base + path, {
      method: method ?? (body ? "POST" : "GET"),
      headers: { "Content-Type": "application/json", ...(headers ?? {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    let json = null;
    try {
      json = await response.json();
    } catch {}
    return { status: response.status, json };
  };
  const signIn = async (email, password) => {
    await call("/api/v1/register", {
      body: { email, password },
      headers: { "X-Forwarded-For": "198.51.100.10" },
    });
    return (
      await call("/api/v1/login", {
        body: { email, password },
        headers: { "X-Forwarded-For": "198.51.100.10" },
      })
    ).json.token;
  };
  return { app, call, signIn };
}

test("IPv6 /64 collapse buckets a delegated prefix, IPv4 stays per-address", () => {
  assert.equal(ipBucketKey("203.0.113.9"), "203.0.113.9");
  assert.equal(ipBucketKey("2001:db8:1:2:3:4:5:6"), "v6/8193:3512:1:2");
  assert.equal(
    ipBucketKey("2001:db8:1:2:ffff:ffff:ffff:ffff"),
    ipBucketKey("2001:db8:1:2:1:1:1:1"),
  );
  assert.notEqual(
    ipBucketKey("2001:db8:1:2::1"),
    ipBucketKey("2001:db8:1:3::1"),
  );
  assert.equal(ipBucketKey("::1"), "v6/0:0:0:0");
  assert.equal(ipBucketKey("::"), "v6/0:0:0:0");
  assert.equal(ipBucketKey("1:2:3:4:5:6:7::"), "v6/1:2:3:4");
  // Malformed or never-normalized shapes keep per-address granularity.
  assert.equal(ipBucketKey("::ffff:1.2.3.4"), "::ffff:1.2.3.4");
  assert.equal(ipBucketKey("fe80::1%eth0").startsWith("v6/"), true);
  assert.equal(ipBucketKey("not-an-ip"), "not-an-ip");
});

test("rotating interface IDs inside one /64 no longer refreshes the IP bucket", async (t) => {
  const { call } = await harness(t, {});
  let statuses = new Set();
  for (let i = 0; i < 30; i++) {
    const result = await call("/api/v1/register", {
      body: { email: "", password: "x" },
      headers: {
        "X-Forwarded-For": `2001:db8:1:2:a:b:0:${(i % 16).toString(16)}`,
      },
    });
    statuses.add(result.status);
  }
  assert.deepEqual([...statuses], [400]);
  assert.equal(
    (
      await call("/api/v1/register", {
        body: { email: "", password: "x" },
        headers: { "X-Forwarded-For": "2001:db8:1:2:ffff::1" },
      })
    ).status,
    429,
  );
  assert.equal(
    (
      await call("/api/v1/register", {
        body: { email: "", password: "x" },
        headers: { "X-Forwarded-For": "2001:db8:1:3:1::1" },
      })
    ).status,
    400,
    "a different /64 prefix still gets a fresh bucket",
  );
});

test("protocol-relative request targets are rejected as 400, not 500", async (t) => {
  const { app, call } = await harness(t);
  const res = await fetch(
    `http://127.0.0.1:${app.server.address().port}//..%2f..%2fsrc.js`,
  );
  assert.equal(res.status, 400);
  assert.deepEqual(await res.json(), { error: "Invalid request" });
  // Normal paths keep working.
  assert.equal((await call("/health")).status, 200);
});

test("an oversized operationId is rejected and never echoed whole", async (t) => {
  const { call, signIn } = await harness(t);
  const token = await signIn("probe@example.com", "test-password-123");
  const event = {
    title: "t",
    description: "",
    location: "",
    calendarId: "cal",
    start: Date.now(),
    end: Date.now(),
    allDay: false,
    importance: "LOW",
    recurrence: "NONE",
    kind: "EVENT",
    exceptions: [],
    completions: [],
    reminders: [],
  };
  const result = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "op" + "x".repeat(300_000),
          baseVersion: 0,
          kind: "event",
          id: "e1",
          deleted: false,
          data: event,
        },
      ],
    },
    headers: { Authorization: "Bearer " + token },
  });
  assert.equal(result.status, 400);
  assert.match(result.json.error, /Invalid operation|Invalid event/);
  assert.ok(
    !("operationId" in result.json) || result.json.operationId.length <= 250,
    "the echoed operationId must be capped",
  );
});

test("storage quota blocks writes but lets a full account keep deleting", async (t) => {
  const { call, signIn, app } = await harness(t, { quotaBytes: 9000 });
  const token = await signIn("filler@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const baseChange = (id, deleted) => ({
    operationId: "fill-" + id,
    baseVersion: deleted ? 1 : 0,
    kind: "event",
    id,
    deleted: !!deleted,
    data: {
      title: "t",
      description: "d".repeat(10_000),
      location: "",
      calendarId: "cal",
      start: Date.now(),
      end: Date.now(),
      allDay: false,
      importance: "LOW",
      recurrence: "NONE",
      kind: "EVENT",
      exceptions: [],
      completions: [],
      reminders: [],
    },
  });
  // Seed the parent calendar (small), then try to store a >9 KB event.
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: {
          cursor: 0,
          changes: [
            {
              operationId: "seed-cal",
              baseVersion: 0,
              kind: "calendar",
              id: "cal",
              deleted: false,
              data: { name: "C", keywords: "", color: -1, visible: true },
            },
          ],
        },
        headers: auth,
      })
    ).status,
    200,
  );
  const over = await call("/api/v1/sync", {
    body: { cursor: 0, changes: [baseChange("big")] },
    headers: auth,
  });
  assert.equal(over.status, 413);
  // Fake tombstones (unknown or already-deleted ids) still grow tombstone
  // and journal rows, so a full deletion-only batch is charged too.
  const fakeTombstones = Array.from({ length: 1000 }, (_, i) => ({
    operationId: "fake-" + i,
    baseVersion: 0,
    kind: "event",
    id: "ghost-" + i,
    deleted: true,
    data: null,
  }));
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: { cursor: 0, changes: fakeTombstones },
        headers: auth,
      })
    ).status,
    413,
  );
  // A deletion of a live record is free regardless of quota state...
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: {
          cursor: 0,
          changes: [
            {
              operationId: "bye-cal",
              baseVersion: 1,
              kind: "calendar",
              id: "cal",
              deleted: true,
              data: null,
            },
          ],
        },
        headers: auth,
      })
    ).status,
    200,
  );
  // ...and tombstoning the only bulk data frees enough space for small
  // writes to resume.
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: {
          cursor: 0,
          changes: [
            {
              operationId: "cal2",
              baseVersion: 0,
              kind: "calendar",
              id: "cal2",
              deleted: false,
              data: { name: "C2", keywords: "", color: -1, visible: true },
            },
          ],
        },
        headers: auth,
      })
    ).status,
    200,
  );
  void app;
});

test("a full account recovers after deleting its bulk data", async (t) => {
  const { app, call, signIn } = await harness(t, { quotaBytes: 30000 });
  const token = await signIn("recover@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const bulk = (id, bytes) => ({
    operationId: "bulk-" + id,
    baseVersion: 0,
    kind: "event",
    id,
    deleted: false,
    data: {
      title: "t",
      description: "d".repeat(bytes),
      location: "",
      calendarId: "cal",
      start: Date.now(),
      end: Date.now(),
      allDay: false,
      importance: "LOW",
      recurrence: "NONE",
      kind: "EVENT",
      exceptions: [],
      completions: [],
      reminders: [],
    },
  });
  const seed = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "seed",
          baseVersion: 0,
          kind: "calendar",
          id: "cal",
          deleted: false,
          data: { name: "C", keywords: "", color: -1, visible: true },
        },
      ],
    },
    headers: auth,
  });
  assert.equal(seed.status, 200);
  // A single ~10 KB event fits; a second does not.
  assert.equal((await call("/api/v1/sync", { body: { cursor: 0, changes: [bulk("big1", 10_000)] }, headers: auth })).status, 200);
  assert.equal((await call("/api/v1/sync", { body: { cursor: 0, changes: [bulk("big2", 12_000)] }, headers: auth })).status, 413);
  // Deleting the stored bulk record is free, and after it the account has
  // room for the same-sized record again.
  const big1Version = app.db
    .prepare("SELECT version FROM records WHERE id='big1'")
    .get().version;
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: {
          cursor: 0,
          changes: [
            {
              operationId: "drop-big1",
              baseVersion: big1Version,
              kind: "event",
              id: "big1",
              deleted: true,
              data: null,
            },
          ],
        },
        headers: auth,
      })
    ).status,
    200,
  );
  assert.equal((await call("/api/v1/sync", { body: { cursor: 0, changes: [bulk("big3", 10_000)] }, headers: auth })).status, 200);
});

test("stale-version deletions cannot journal past the quota", async (t) => {
  const { app, call, signIn } = await harness(t, { quotaBytes: 20_000 });
  const token = await signIn("stale@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "seed-cal",
          baseVersion: 0,
          kind: "calendar",
          id: "cal",
          deleted: false,
          data: { name: "C", keywords: "", color: -1, visible: true },
        },
      ],
    },
    headers: auth,
  });
  // A full batch of deletions that claim a stale base version never
  // removes anything: they are just conflict rows and journal entries,
  // and must be charged like writes.
  const stale = Array.from({ length: 1000 }, (_, i) => ({
    operationId: "stale-" + i,
    baseVersion: 999999,
    kind: "calendar",
    id: "cal",
    deleted: true,
    data: null,
  }));
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: { cursor: 0, changes: stale },
        headers: auth,
      })
    ).status,
    413,
  );
  assert.equal(
    app.db.prepare("SELECT deleted FROM records WHERE id='cal'").get().deleted,
    0,
    "the live record is untouched",
  );
});

test("replays of already-synced operations stay free when the account is full", async (t) => {
  const { app, call, signIn } = await harness(t, { quotaBytes: 1500 });
  const token = await signIn("replay@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const seed = () =>
    call("/api/v1/sync", {
      body: {
        cursor: 0,
        changes: [
          {
            operationId: "seed-cal",
            baseVersion: 0,
            kind: "calendar",
            id: "cal",
            deleted: false,
            data: { name: "C", keywords: "", color: -1, visible: true },
          },
        ],
      },
      headers: auth,
    });
  assert.equal((await seed()).status, 200);
  // The retried operation journals nothing new and stays free even while
  // the account is over quota: a charged replay (usage 477 + 655 x 2)
  // would push this 1500-byte quota to a 413.
  assert.equal((await seed()).status, 200);
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM operations WHERE operationId='seed-cal'")
      .get().n,
    1,
  );
  // Quota enforcement itself still works: a fresh write at this tiny
  // quota is rejected.
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: {
          cursor: 0,
          changes: [
            {
              operationId: "fresh-1",
              baseVersion: 0,
              kind: "calendar",
              id: "cal2",
              deleted: false,
              data: { name: "C2", keywords: "", color: -1, visible: true },
            },
          ],
        },
        headers: auth,
      })
    ).status,
    413,
  );
});

test("deletions in a group with any charged member are charged", async (t) => {
  const { app, call, signIn } = await harness(t, { quotaBytes: 1_500_000 });
  const token = await signIn("group@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  // 999 live calendars + one calendar that the group will upsert first.
  const upsertTarget = { name: "X", keywords: "", color: -1, visible: true };
  const calendars = Array.from({ length: 1000 }, (_, i) => ({
    operationId: "seed-" + i,
    baseVersion: 0,
    kind: "calendar",
    id: "cal" + (i === 999 ? "x" : i),
    deleted: false,
    data: i === 999 ? upsertTarget : { name: "C" + i, keywords: "", color: -1, visible: true },
  }));
  const seeded = await call("/api/v1/sync", {
    body: { cursor: 0, changes: calendars },
    headers: auth,
  });
  assert.equal(seeded.status, 200);
  const versionOf = (id) =>
    app.db.prepare("SELECT version FROM records WHERE id=?").get(id).version;
  const member = (id, version, deleted) => ({
    group: "atomic",
    operationId: "gp-" + id,
    baseVersion: version,
    kind: "calendar",
    id,
    deleted: !!deleted,
    data: deleted ? null : { ...upsertTarget },
  });
  // One group mixes a charged write (calx) with deletions of the same
  // batch's other calendars. Under the charging rule the whole group
  // costs the deletions' 600 each plus the write, which must trip the
  // quota instead of journaling a possible rollback for near-free.
  const group = () => [
    { ...member("calx", versionOf("calx"), false), operationId: "gp-upsert" },
    ...Array.from({ length: 999 }, (_, i) =>
      member("cal" + i, versionOf("cal" + i), true),
    ),
  ];
  for (let round = 0; round < 3; round++) {
    const batch = group();
    for (const c of batch) c.operationId = c.operationId + "-" + round;
    const result = await call("/api/v1/sync", {
      body: { cursor: 0, changes: batch }, headers: auth,
    });
    assert.equal(result.status, 413, `round ${round} must be charged`);
  }
  // Nothing was deleted; the journal stays bounded by its row cap.
  assert.equal(
    app.db.prepare("SELECT COUNT(*) AS n FROM records WHERE deleted=0").get().n,
    1000,
  );
  assert.equal(
    app.db.prepare("SELECT deleted FROM records WHERE id='calx'").get().deleted,
    0,
    "calx stays live",
  );
});

test("a rollback from a grouped same-key write-and-delete journals without deleting", async (t) => {
  const { app, call, signIn } = await harness(t);
  const token = await signIn("rollback@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const calendar = { name: "C", keywords: "", color: -1, visible: true };
  const seeded = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "rb-seed",
          baseVersion: 0,
          kind: "calendar",
          id: "calx",
          deleted: false,
          data: calendar,
        },
      ],
    },
    headers: auth,
  });
  assert.equal(seeded.status, 200);
  const before = app.db
    .prepare("SELECT version FROM records WHERE id='calx'")
    .get().version;
  // The write applies first and bumps the row, so the deletion of the same
  // record would conflict and roll the whole group back; charging applies
  // under the group rule (and, for the same-batch write, the batch-write
  // rule below agrees).
  const result = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          group: "g",
          operationId: "rb-write",
          baseVersion: before,
          kind: "calendar",
          id: "calx",
          deleted: false,
          data: { name: "C2", keywords: "", color: -1, visible: true },
        },
        {
          group: "g",
          operationId: "rb-delete",
          baseVersion: before,
          kind: "calendar",
          id: "calx",
          deleted: true,
          data: null,
        },
      ],
    },
    headers: auth,
  });
  assert.equal(result.status, 200);
  // Both members are reported as conflicts.
  assert.deepEqual(result.json.conflicts, ["calx", "calx"]);
  // The group rolled back: the row keeps its original version, and both
  // members are journaled although nothing was written or deleted.
  assert.equal(
    app.db.prepare("SELECT version FROM records WHERE id='calx'").get().version,
    before,
  );
  assert.equal(
    app.db
      .prepare(
        "SELECT COUNT(*) AS n FROM operations WHERE operationId IN ('rb-write','rb-delete')",
      )
      .get().n,
    2,
  );
});

test("a deletion of a record its batch replays as a write is charged", async (t) => {
  const { app, call, signIn } = await harness(t, { quotaBytes: 1600 });
  const token = await signIn("rvwrite@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const write = {
    operationId: "rv-seed",
    baseVersion: 0,
    kind: "calendar",
    id: "calx",
    deleted: false,
    data: { name: "C", keywords: "", color: -1, visible: true },
  };
  assert.equal(
    (await call("/api/v1/sync", { body: { cursor: 0, changes: [write] }, headers: auth }))
      .status,
    200,
  );
  const current = app.db
    .prepare("SELECT version FROM records WHERE id='calx'")
    .get().version;
  // Ungrouped pair so only the batch-write rule can charge: the write is a
  // journaled replay (skipped, free), and the deletion matches the row's
  // current version. Charging 600 for the deletion pushes usage past the
  // 1600-byte quota (about 455 + 1200), while a freed deletion would go
  // through and really delete the row.
  const result = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        { ...write, operationId: "rv-seed" },
        {
          operationId: "rv-delete",
          baseVersion: current,
          kind: "calendar",
          id: "calx",
          deleted: true,
          data: null,
        },
      ],
    },
    headers: auth,
  });
  assert.equal(result.status, 413);
  assert.equal(
    app.db.prepare("SELECT deleted FROM records WHERE id='calx'").get().deleted,
    0,
    "nothing applied",
  );
});

test("the operation journal is row-capped per account between prunes", async (t) => {
  const { app, call, signIn } = await harness(t);
  const token = await signIn("journal@example.com", "test-password-123");
  const userId = app.db
    .prepare("SELECT id FROM users WHERE email=?")
    .get("journal@example.com").id;
  const base = Date.now();
  const insert = app.db.prepare(
    "INSERT INTO operations(userId,operationId,fingerprint,created) VALUES(?,?,?,?)",
  );
  for (let i = 0; i < 10400; i++) {
    insert.run(userId, "op" + String(i).padStart(6, "0"), "fp", base - i);
  }
  await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "cap-trigger",
          baseVersion: 0,
          kind: "calendar",
          id: "cal",
          deleted: false,
          data: { name: "C", keywords: "", color: -1, visible: true },
        },
      ],
    },
    headers: { Authorization: "Bearer " + token },
  });
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM operations WHERE userId=?")
      .get(userId).n,
    10000,
  );
  assert.equal(
    app.db
      .prepare(
        "SELECT COUNT(*) AS n FROM operations WHERE userId=? AND operationId='cap-trigger'",
      )
      .get(userId).n,
    1,
    "the newest journal row is never the one trimmed",
  );
});

test("edit history is row-capped per account between the 90-day prunes", async (t) => {
  const { app, signIn, call } = await harness(t);
  const token = await signIn("capped@example.com", "test-password-123");
  const userId = app.db
    .prepare("SELECT id FROM users WHERE email=?")
    .get("capped@example.com").id;
  const base = Date.now();
  const insert = app.db.prepare(
    "INSERT INTO history VALUES(?,?,?,?,?,?,?,?)",
  );
  for (let i = 0; i < 10400; i++) {
    insert.run(
      "h" + String(i).padStart(6, "0"),
      userId,
      "event",
      "e" + i,
      "{}",
      0,
      base - i,
      "conflict",
    );
  }
  await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "cap-trigger",
          baseVersion: 0,
          kind: "calendar",
          id: "cal",
          deleted: false,
          data: { name: "C", keywords: "", color: -1, visible: true },
        },
      ],
    },
    headers: { Authorization: "Bearer " + token },
  });
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM history WHERE userId=?")
      .get(userId).n,
    10000,
  );
  // The 400 oldest rows (largest i) were trimmed; the recent ones remain.
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM history WHERE userId=? AND id='h010399'")
      .get(userId).n,
    0,
  );
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM history WHERE userId=? AND id='h010000'")
      .get(userId).n,
    0,
  );
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM history WHERE userId=? AND id='h009999'")
      .get(userId).n,
    1,
  );
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM history WHERE userId=? AND id='h000000'")
      .get(userId).n,
    1,
  );
});

test("sync requests are rate limited per signed-in account", async (t) => {
  const { call, signIn } = await harness(t);
  const token = await signIn("polled@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const statuses = [];
  for (let i = 0; i < 92; i++) {
    const { status } = await call("/api/v1/sync", {
      body: { cursor: 0, changes: [] },
      headers: auth,
    });
    statuses.push(status);
  }
  assert.equal(statuses.filter((s) => s === 200).length, 90);
  assert.equal(statuses.slice(90).every((s) => s === 429), true);
});

test("signing in prunes edit history and the operation journal past 90 days", async (t) => {
  const { app, signIn, call } = await harness(t);
  await signIn("prune@example.com", "test-password-123");
  const cutoff = Date.now() - 90 * 86400000;
  app.db
    .prepare("INSERT INTO history VALUES(?,?,?,?,?,?,?,?)")
    .run("old-h1", "u", "event", "e1", "{}", 0, cutoff - 1000, "conflict");
  app.db
    .prepare("INSERT INTO history VALUES(?,?,?,?,?,?,?,?)")
    .run("new-h1", "u", "event", "e2", "{}", 0, Date.now(), "conflict");
  app.db
    .prepare("INSERT INTO operations VALUES(?,?,?,?)")
    .run("u", "old-op", "fp", cutoff - 1000);
  app.db
    .prepare("INSERT INTO operations VALUES(?,?,?,?)")
    .run("u", "new-op", "fp", Date.now());
  await call("/api/v1/login", {
    body: { email: "prune@example.com", password: "test-password-123" },
  });
  assert.deepEqual(
    app.db.prepare("SELECT id FROM history ORDER BY id").all().map((r) => r.id),
    ["new-h1"],
  );
  assert.deepEqual(
    app.db
      .prepare("SELECT operationId FROM operations ORDER BY operationId")
      .all()
      .map((r) => r.operationId),
    ["new-op"],
  );
});

test("STORAGE_QUOTA_BYTES=0 lifts the ceiling entirely", async (t) => {
  const { call, signIn } = await harness(t, { quotaBytes: 0 });
  const token = await signIn("lifter@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const seed = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "lifted-cal",
          baseVersion: 0,
          kind: "calendar",
          id: "cxl",
          deleted: false,
          data: { name: "C", keywords: "", color: -1, visible: true },
        },
      ],
    },
    headers: auth,
  });
  assert.equal(seed.status, 200);
  const result = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "lifted-1",
          baseVersion: 0,
          kind: "event",
          id: "cxe",
          deleted: false,
          data: {
            title: "t",
            description: "d".repeat(8000),
            location: "",
            calendarId: "cxl",
            start: Date.now(),
            end: Date.now(),
            allDay: false,
            importance: "LOW",
            recurrence: "NONE",
            kind: "EVENT",
            exceptions: [],
            completions: [],
            reminders: [],
          },
        },
      ],
    },
    headers: auth,
  });
  assert.equal(result.status, 200, "no quota configured, an 8 KB note is accepted");
  assert.deepEqual(result.json.conflicts, []);
});

test("legacy operation journals are backfilled, not wiped, on upgrade", async (t) => {
  const dbPath = join(tmpdir(), `lmnaide-legacy-${process.pid}-${Date.now()}.sqlite`);
  t.after(() => rmSync(dbPath, { force: true }));
  // Hand-build the pre-upgrade schema: operations without a created column.
  const legacy = new DatabaseSync(dbPath);
  legacy.exec(`
    CREATE TABLE users(id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, password TEXT NOT NULL);
    CREATE TABLE operations(userId TEXT NOT NULL, operationId TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(userId, operationId));
  `);
  const salt = randomBytes(16).toString("hex");
  const key = scryptSync("test-password-123", salt, 64).toString("hex");
  legacy.prepare("INSERT INTO users VALUES(?,?,?)").run(
    "u",
    "legacy@example.com",
    `${salt}:${key}`,
  );
  legacy.prepare("INSERT INTO operations VALUES(?,?,?)").run(
    "u",
    "inflight-op",
    "fp",
  );
  legacy.close();

  const app = createApp({ dbPath });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  t.after(() => app.close());

  const migrated = app.db
    .prepare("SELECT operationId, created FROM operations")
    .all();
  assert.equal(migrated.length, 1);
  assert.equal(migrated[0].operationId, "inflight-op");
  assert.ok(
    migrated[0].created > Date.now() - 60000,
    "created was backfilled to the upgrade time",
  );
  // Sign-in prunes only rows older than 90 days; the backfilled row stays,
  // so an in-flight offline retry keeps its idempotency record.
  const response = await fetch(
    `http://127.0.0.1:${app.server.address().port}/api/v1/login`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: "legacy@example.com",
        password: "test-password-123",
      }),
    },
  );
  assert.equal(response.status, 200);
  assert.equal(
    app.db
      .prepare("SELECT COUNT(*) AS n FROM operations WHERE operationId='inflight-op'")
      .get().n,
    1,
  );
});

test("STORAGE_QUOTA_BYTES=0 as an env var lifts the ceiling entirely", async (t) => {
  process.env.STORAGE_QUOTA_BYTES = "0";
  t.after(() => delete process.env.STORAGE_QUOTA_BYTES);
  const { call, signIn } = await harness(t);
  const token = await signIn("envlift@example.com", "test-password-123");
  const auth = { Authorization: "Bearer " + token };
  const seed = await call("/api/v1/sync", {
    body: {
      cursor: 0,
      changes: [
        {
          operationId: "seed",
          baseVersion: 0,
          kind: "calendar",
          id: "cal",
          deleted: false,
          data: { name: "C", keywords: "", color: -1, visible: true },
        },
      ],
    },
    headers: auth,
  });
  assert.equal(seed.status, 200);
  const bulk = (id) =>
    call("/api/v1/sync", {
      body: {
        cursor: 0,
        changes: [
          {
            operationId: "ev-" + id,
            baseVersion: 0,
            kind: "event",
            id,
            deleted: false,
            data: {
              title: "t",
              description: "d".repeat(10_000),
              location: "",
              calendarId: "cal",
              start: Date.now(),
              end: Date.now(),
              allDay: false,
              importance: "LOW",
              recurrence: "NONE",
              kind: "EVENT",
              exceptions: [],
              completions: [],
              reminders: [],
            },
          },
        ],
      },
      headers: auth,
    });
  // Two bulk writes in a row would fail any small quota.
  assert.equal((await bulk("e1")).status, 200);
  assert.equal((await bulk("e2")).status, 200);
});

test("an invalid STORAGE_QUOTA_BYTES falls back to the 256 MiB default", async (t) => {
  process.env.STORAGE_QUOTA_BYTES = "not-a-number";
  t.after(() => delete process.env.STORAGE_QUOTA_BYTES);
  const { call, signIn } = await harness(t);
  const token = await signIn("envfall@example.com", "test-password-123");
  assert.equal(
    (
      await call("/api/v1/sync", {
        body: {
          cursor: 0,
          changes: [
            {
              operationId: "fallback-1",
              baseVersion: 0,
              kind: "calendar",
              id: "cal",
              deleted: false,
              data: { name: "C", keywords: "", color: -1, visible: true },
            },
          ],
        },
        headers: { Authorization: "Bearer " + token },
      })
    ).status,
    200,
  );
});
