import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../src.js";

test("usernames migrate, persist, validate and remain private", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "calendar-usernames-"));
  const dbPath = join(dir, "test.sqlite");
  const legacy = new DatabaseSync(dbPath);
  legacy.exec("CREATE TABLE users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password TEXT NOT NULL); INSERT INTO users VALUES('legacy','legacy@example.com','unused')");
  legacy.close();
  let app;
  let base;
  const start = async () => {
    app = createApp({ dbPath });
    await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
    base = "http://127.0.0.1:" + app.server.address().port;
  };
  t.after(() => { app.close(); rmSync(dir, { recursive: true, force: true }); });
  await start();
  assert.equal(app.db.prepare("SELECT username FROM users WHERE id='legacy'").get().username, null);
  const api = async (path, body, token) => {
    const res = await fetch(base + "/api/" + path, {
      method: body ? "POST" : "GET",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, ...await res.json() };
  };
  const register = (email, username) => api("register", { email, username, password: "test-password-123" });
  const a = await register("a@example.com", " Alice_1 ");
  assert.equal(a.username, "Alice_1");
  const b = await register("b@example.com");
  assert.equal(b.username, null);
  assert.equal((await register("c@example.com", "alice_1")).status, 409);
  for (const username of ["ab", "a".repeat(31), "has space", "<script>", 42, {}]) {
    assert.equal((await api("profile", { username }, a.token)).status, 400);
    assert.equal((await register("invalid@example.com", username)).status, 400);
  }
  assert.equal((await api("profile", { username: "Bob_2" })).status, 401);
  assert.equal((await api("profile", { username: "ALICE_1" }, b.token)).status, 409);
  const photo = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";
  await api("profile", { photo }, a.token);
  assert.equal((await api("profile", { username: "Alice_2" }, a.token)).photo, photo);
  assert.equal((await api("profile", { photo: null }, a.token)).username, "Alice_2");
  assert.equal((await api("profile", { username: "Wrong", photo: "invalid" }, a.token)).status, 400);
  assert.equal((await api("profile", null, a.token)).username, "Alice_2");
  assert.equal((await api("profile", null, b.token)).username, null);
  app.close();
  await start();
  assert.equal((await api("profile", null, a.token)).username, "Alice_2");
  assert.equal((await api("login", { email: "a@example.com", password: "test-password-123" })).username, "Alice_2");
  await api("profile", { username: "" }, a.token);
  assert.equal((await api("profile", { username: "alice_2" }, b.token)).username, "alice_2");
});
