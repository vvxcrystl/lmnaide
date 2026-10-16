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

test("authentication keeps a failure-only, per-account-and-source lockout", async (t) => {
  const { post } = await harness(t, { trustedProxies: "127.0.0.1" });
  assert.equal((await post("register", {
    email: "target@example.com",
    password: "test-password-123",
  }, "198.51.100.1")).status, 200);

  // Probing the account from one source fills that source's bucket: the
  // first ten probes are answered with 409, the eleventh never reaches the
  // database again within the window.
  const probe = (i) => post("register", {
    email: " TARGET@example.com ",
    password: "test-password-123",
  }, "198.51.100.2").then((r) => r.status);
  assert.equal(await probe(1), 409);
  for (let i = 2; i <= 10; i++) assert.equal(await probe(i), 409);
  // The register probe itself is capped: existing-account answers count.
  assert.equal(await probe(11), 429);

  // ...but the account's real owner can still sign in from their own IP
  // while the attack is running; failures are counted per source prefix
  // and successful sign-ins never consume a bucket.
  const owner = await post("login", {
    email: "target@example.com",
    password: "test-password-123",
  }, "198.51.100.1");
  assert.equal(owner.status, 200);

  // Distributed sources each get their own fresh bucket (still answered
  // 401), so an owner is never locked out by other people's guesses.
  for (const ip of ["203.0.113.1", "203.0.113.2", "203.0.113.3"]) {
    for (let i = 1; i <= 3; i++) {
      const result = await post("login", {
        email: "target@example.com",
        password: "wrong-password",
      }, ip);
      assert.equal(result.status, 401);
    }
  }
  const ownerAgain = await post("login", {
    email: "target@example.com",
    password: "test-password-123",
  }, "198.51.100.1");
  assert.equal(ownerAgain.status, 200);

  // Eleven failures from a single source still cap that source.
  for (let i = 1; i <= 11; i++) {
    const result = await post("login", {
      email: "target@example.com",
      password: `wrong-password-${i}`,
    }, "203.0.113.9");
    assert.equal(result.status, i <= 10 ? 401 : 429);
  }
});

test("a long email is rejected by length before the quadratic regex", async (t) => {
  const { post } = await harness(t);
  const dots = "b.".repeat(400_000); // 800 KB, all after the @
  const started = Date.now();
  const result = await post("login", {
    email: `a@${dots}@c`,
    password: "test-password-123",
  });
  assert.equal(result.status, 400);
  assert.ok(Date.now() - started < 2000, `validation took too long (${Date.now() - started}ms)`);
});

test("register or login of a malformed address never consumes a bucket", async (t) => {
  const { post } = await harness(t);
  for (let i = 1; i <= 30; i++) {
    const result = await post("register", {
      email: `bad@${"x".repeat(300)}`,
      password: "test-password-123",
    }, `198.51.100.${i}`);
    assert.equal(result.status, 400);
  }
  // The over-length email must not have created a per-account bucket, and
  // these requests share only the legitimate per-IP buckets above.
  assert.equal((await post("register", {
    email: "brand-new@example.com",
    password: "test-password-123",
  }, "203.0.113.9")).status, 200);
});
