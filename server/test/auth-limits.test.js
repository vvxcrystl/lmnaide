import { test } from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src.js";

async function harness(t, options = {}) {
  const app = createApp({ dbPath: ":memory:", ...options });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  t.after(() => app.close());
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const post = async (path, body, forwardedFor) => {
    const response = await fetch(`${base}/api/${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(forwardedFor ? { "X-Forwarded-For": forwardedFor } : {}),
      },
      body: JSON.stringify(body),
    });
    return { status: response.status, ...(await response.json()) };
  };
  return { app, post };
}

test("forwarded client IP is used only for an explicitly trusted socket peer", async (t) => {
  const trusted = await harness(t, { trustedProxies: "127.0.0.1/32" });
  for (let i = 1; i <= 35; i++) {
    const result = await trusted.post("register", { email: "", password: "x" }, `198.51.100.${i}`);
    assert.equal(result.status, 400, `trusted forwarded IP ${i} should have its own bucket`);
  }

  const untrusted = await harness(t, { trustedProxies: "" });
  for (let i = 1; i <= 30; i++) {
    const result = await untrusted.post("register", { email: "", password: "x" }, `198.51.100.${i}`);
    assert.equal(result.status, 400);
  }
  assert.equal(
    (await untrusted.post("register", { email: "", password: "x" }, "198.51.100.31")).status,
    429,
  );
});

test("authentication also limits attempts per normalized account email", async (t) => {
  const { post } = await harness(t, { trustedProxies: "127.0.0.1" });
  assert.equal((await post("register", {
    email: "target@example.com",
    password: "test-password-123",
  }, "198.51.100.1")).status, 200);
  for (let i = 2; i <= 10; i++) {
    const result = await post("register", {
      email: " TARGET@example.com ",
      password: "test-password-123",
    }, `198.51.100.${i}`);
    assert.equal(result.status, 409);
  }
  assert.equal((await post("register", {
    email: "target@example.com",
    password: "test-password-123",
  }, "198.51.100.11")).status, 429);
});
