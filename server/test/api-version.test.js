import { test } from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src.js";

const calendar = {
  name: "Personal",
  color: -13388167,
  visible: true,
  keywords: "",
  defaultImportance: null,
};

async function start(t) {
  const app = createApp({ dbPath: ":memory:" });
  await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
  t.after(() => app.close());
  const base = "http://127.0.0.1:" + app.server.address().port;
  const call = async (path, body, token) => {
    const res = await fetch(base + path, {
      method: body ? "POST" : "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, ...(await res.json()) };
  };
  return { base, call };
}

test("unversioned /api paths reach the v1 handlers for pre-v1 clients", async (t) => {
  const { call } = await start(t);
  const { token } = await call("/api/v1/register", {
    email: "alias@example.com",
    password: "test-password-123",
  });
  const push = {
    cursor: 0,
    changes: [
      {
        kind: "calendar",
        id: "personal",
        data: calendar,
        baseVersion: 0,
        deleted: false,
        operationId: crypto.randomUUID(),
      },
    ],
  };

  const legacy = await call("/api/sync", push, token);
  assert.equal(legacy.status, 200);
  assert.equal(legacy.records.length, 1);
  const current = await call("/api/v1/sync", { cursor: 0, changes: [] }, token);
  assert.deepEqual(current.records, legacy.records);

  assert.equal((await call("/api/profile", null, token)).status, 200);
});

test("unknown API versions return 404 with or without a token", async (t) => {
  const { call } = await start(t);
  const { token } = await call("/api/v1/register", {
    email: "versions@example.com",
    password: "test-password-123",
  });

  for (const path of ["/api/v0/sync", "/api/v2/sync", "/api/v2"]) {
    for (const bearer of [undefined, token]) {
      const res = await call(path, { cursor: 0, changes: [] }, bearer);
      assert.equal(res.status, 404);
      assert.equal(typeof res.error, "string");
    }
  }
});

test("health and share pages stay unversioned", async (t) => {
  const { base } = await start(t);

  const health = await fetch(base + "/health");
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { ok: true, version: "0.1.0" });

  const share = await fetch(base + "/s/" + "a".repeat(64));
  assert.equal(share.status, 200);
  assert.match(share.headers.get("content-type"), /text\/html/);
});
