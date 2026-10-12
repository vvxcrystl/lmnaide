import { test } from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src.js";

test("profile photos are private to each account and validated", async (t) => {
  const app = createApp({ dbPath: ":memory:" });
  await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
  t.after(() => app.close());
  const base = "http://127.0.0.1:" + app.server.address().port;
  const api = async (path, body, token, method) => {
    const res = await fetch(base + "/api/v1/" + path, {
      method: method ?? (body ? "POST" : "GET"),
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, ...(await res.json()) };
  };
  const register = (email) =>
    api("register", { email, password: "test-password-123" }).then(
      (r) => r.token,
    );
  const a = await register("a@example.com");
  const b = await register("b@example.com");
  const photo = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";

  assert.equal((await api("profile")).status, 401);
  assert.deepEqual(await api("profile", null, a), { status: 200, photo: null });
  assert.equal((await api("profile", { photo }, a)).photo, photo);
  assert.equal((await api("profile", null, a)).photo, photo);
  assert.equal((await api("profile", null, b)).photo, null);

  for (const bad of [
    "javascript:alert(1)",
    "data:image/svg+xml;base64,PHN2Zz4=",
    5,
    "data:image/png;base64," + "A".repeat(200001),
  ])
    assert.equal((await api("profile", { photo: bad }, a)).status, 400);
  assert.equal((await api("profile", null, a)).photo, photo);

  assert.equal((await api("profile", { photo: null }, a)).photo, null);
});
