import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { createApp } from "../src.js";
import { CRYSTLCLOUD_ISSUER, oidcConfig } from "../oidc.js";

const b64 = (value) => Buffer.from(value).toString("base64url");

// A minimal issuer that signs ID tokens with Ed25519, like CrystlCloud Accounts.
// `next` is the identity the next /authorize hands out, plus optional tampering.
async function fakeIssuer(t) {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const codes = new Map();
  const access = new Map();
  const refreshTokens = new Map();
  // `accounts` holds each subject's current profile, so tests can change it at the issuer.
  const issuer = { next: { sub: "sub-1", email: "new@example.com" }, accounts: {}, refreshes: 0 };
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, issuer.url);
    const json = (data, status = 200) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(data));
    };
    if (url.pathname === "/.well-known/openid-configuration")
      return json({
        issuer: issuer.url,
        authorization_endpoint: issuer.url + "/authorize",
        token_endpoint: issuer.url + "/token",
        userinfo_endpoint: issuer.url + "/userinfo",
        jwks_uri: issuer.url + "/jwks",
        scopes_supported: ["openid", "profile", "email", "offline_access"],
      });
    if (url.pathname === "/jwks")
      return json({ keys: [{ ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "EdDSA" }] });
    if (url.pathname === "/authorize") {
      const code = b64(randomBytes(16));
      codes.set(code, { ...issuer.next, params: url.searchParams });
      const back = new URL(url.searchParams.get("redirect_uri"));
      back.search = new URLSearchParams({ code, state: url.searchParams.get("state") });
      res.writeHead(302, { Location: back.href });
      return res.end();
    }
    if (url.pathname === "/token") {
      let raw = "";
      for await (const chunk of req) raw += chunk;
      const form = new URLSearchParams(raw);
      if (form.get("grant_type") === "refresh_token") {
        // Rotates like Better Auth: an old token is rejected.
        const sub = refreshTokens.get(form.get("refresh_token"));
        refreshTokens.delete(form.get("refresh_token"));
        if (!sub) return json({ error: "invalid_grant" }, 400);
        issuer.refreshes++;
        const accessToken = b64(randomBytes(16));
        const refreshToken = b64(randomBytes(16));
        access.set(accessToken, sub);
        refreshTokens.set(refreshToken, sub);
        return json({ access_token: accessToken, refresh_token: refreshToken, token_type: "Bearer" });
      }
      const grant = codes.get(form.get("code"));
      codes.delete(form.get("code"));
      const challenge = createHash("sha256").update(form.get("code_verifier") ?? "").digest("base64url");
      if (
        !grant ||
        req.headers.authorization !== "Basic " + Buffer.from("lmnaide:secret").toString("base64") ||
        challenge !== grant.params.get("code_challenge")
      )
        return json({ error: "invalid_grant" }, 400);
      const claims = {
        iss: issuer.url,
        aud: "lmnaide",
        sub: grant.sub,
        nonce: grant.params.get("nonce"),
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 300,
        ...grant.claims,
      };
      const input = b64(JSON.stringify({ alg: "EdDSA", kid: "k1" })) + "." + b64(JSON.stringify(claims));
      const signature = sign(null, Buffer.from(input), grant.badSignature ? generateKeyPairSync("ed25519").privateKey : privateKey);
      const accessToken = b64(randomBytes(16));
      issuer.accounts[grant.sub] = { sub: grant.sub, email: grant.email, name: grant.name, picture: grant.picture };
      access.set(accessToken, grant.sub);
      const refreshToken = grant.params.get("scope").split(" ").includes("offline_access") ? b64(randomBytes(16)) : undefined;
      if (refreshToken) refreshTokens.set(refreshToken, grant.sub);
      return json({ access_token: accessToken, refresh_token: refreshToken, token_type: "Bearer", id_token: input + "." + b64(signature) });
    }
    if (url.pathname.startsWith("/photos/")) {
      res.writeHead(200, { "Content-Type": "image/jpeg" });
      return res.end("photo-" + url.pathname.slice("/photos/".length));
    }
    if (url.pathname === "/userinfo") {
      const sub = access.get(req.headers.authorization?.replace(/^Bearer /, ""));
      return sub ? json(issuer.accounts[sub]) : json({ error: "invalid_token" }, 401);
    }
    json({ error: "not found" }, 404);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  issuer.url = `http://127.0.0.1:${server.address().port}`;
  return issuer;
}

async function harness(t, options = {}) {
  const issuer = await fakeIssuer(t);
  const app = createApp({
    dbPath: ":memory:",
    publicUrl: "http://127.0.0.1",
    accounts: "oidc",
    oidcIssuer: issuer.url,
    oidcClientId: "lmnaide",
    oidcClientSecret: "secret",
    ...options,
  });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  t.after(() => app.close());
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const api = async (path, body, token) => {
    const response = await fetch(`${base}/api/v1/${path}`, {
      method: body ? "POST" : "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: response.status, ...(await response.json()) };
  };
  const get = (url, cookie) =>
    fetch(new URL(url, base), { redirect: "manual", headers: cookie ? { cookie } : {} });
  // Runs the browser's side of the flow up to the one-time code, without exchanging it.
  const flow = async ({ client = "web", link, keepCookie = true } = {}) => {
    const verifier = b64(randomBytes(32));
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const start = await get(`/api/v1/oidc/start?client=${client}&challenge=${challenge}` + (link ? `&link=${link}` : ""));
    assert.equal(start.status, 302);
    const cookie = start.headers.get("set-cookie").split(";")[0];
    // The issuer redirects to PUBLIC_URL; send that to this test server's port.
    const back = new URL((await get(start.headers.get("location"))).headers.get("location"));
    const callback = back.pathname + back.search;
    const done = await get(callback, keepCookie ? cookie : undefined);
    return { verifier, callback, cookie, location: done.headers.get("location") };
  };
  const codeOf = (location) => location.split("#")[1].replace(/^oidc=/, "");
  const signIn = async (options) => {
    const { verifier, location } = await flow(options);
    return api("oidc/exchange", { code: codeOf(location), verifier });
  };
  return { app, issuer, api, get, flow, codeOf, signIn };
}

test("a new identity gets its own account, and later sign-ins reuse the link", async (t) => {
  const { app, api, signIn } = await harness(t);
  const first = await signIn();
  assert.equal(first.status, 200);
  assert.equal(first.email, "new@example.com");
  const synced = await api("sync", { cursor: 0, changes: [] }, first.token);
  assert.equal(synced.status, 200);

  const again = await signIn();
  assert.equal(again.userId, first.userId);
  assert.notEqual(again.token, first.token);
  assert.deepEqual(
    app.db.prepare("SELECT subject,userId FROM identities").all().map((r) => ({ ...r })),
    [{ subject: "sub-1", userId: first.userId }],
  );
  // The placeholder password can never be used to sign in.
  for (const password of ["!".repeat(10), "0".repeat(10)])
    assert.equal((await api("login", { email: "new@example.com", password })).status, 401);
});

test("an existing user links explicitly and is never matched by email", async (t) => {
  const { app, issuer, api, signIn } = await harness(t);
  // Password registration is closed in OIDC mode, so seed a password user directly.
  app.db.prepare("INSERT INTO users(id,email,password) VALUES('u-owner','owner@example.com',?)")
    .run("a".repeat(32) + ":" + "b".repeat(128));
  const session = "c".repeat(64);
  app.db.prepare("INSERT INTO sessions VALUES(?,?,?)").run(
    createHash("sha256").update(session).digest("hex"), "u-owner", Date.now() + 60000);

  issuer.next = { sub: "sub-owner", email: "owner@example.com" };
  const unlinked = await signIn();
  assert.equal(unlinked.status, 409);
  assert.match(unlinked.error, /already exists/);
  assert.equal(app.db.prepare("SELECT COUNT(*) AS n FROM users").get().n, 1);

  assert.equal((await api("profile", undefined, session)).linked, false);
  const { intent } = await api("oidc/link", {}, session);
  const linked = await signIn({ link: intent });
  assert.deepEqual(linked, { status: 200, linked: true });
  assert.equal((await api("profile", undefined, session)).linked, true);
  assert.equal((await signIn()).userId, "u-owner");

  // The intent was single use, and a second identity can't take over the account.
  const { status } = await fetch(new URL(`/api/v1/oidc/start?client=web&challenge=${"a".repeat(43)}&link=${intent}`,
    `http://127.0.0.1:${app.server.address().port}`), { redirect: "manual" });
  assert.equal(status, 400);
  issuer.next = { sub: "sub-other", email: "other@example.com" };
  const { intent: second } = await api("oidc/link", {}, session);
  assert.equal((await signIn({ link: second })).status, 409);
});

test("a connected account's name and photo come from the issuer", async (t) => {
  const { issuer, api, signIn } = await harness(t);
  const jpeg = (text) => "data:image/jpeg;base64," + Buffer.from(text).toString("base64");
  issuer.next = { sub: "sub-1", email: "new@example.com", name: "Ada Lovelace", picture: issuer.url + "/photos/a" };
  const { token } = await signIn();
  let profile = await api("profile", undefined, token);
  assert.equal(profile.name, "Ada Lovelace");
  assert.equal(profile.photo, jpeg("photo-a"));
  assert.equal((await api("profile", { photo: null }, token)).status, 409);

  // Changes at the issuer show at the next sign-in. Pictures on other origins are never fetched.
  issuer.next = { ...issuer.next, name: "Ada", picture: "http://169.254.169.254/latest/meta-data" };
  profile = await api("profile", undefined, (await signIn()).token);
  assert.deepEqual([profile.name, profile.photo], ["Ada", jpeg("photo-a")]);
  issuer.next = { ...issuer.next, picture: undefined };
  assert.equal((await api("profile", undefined, (await signIn()).token)).photo, null);
});

test("opening the profile re-reads name and photo from the issuer", async (t) => {
  const { app, issuer, api, signIn } = await harness(t);
  const jpeg = (text) => "data:image/jpeg;base64," + Buffer.from(text).toString("base64");
  issuer.next = { sub: "sub-1", email: "new@example.com", name: "Ada", picture: issuer.url + "/photos/a" };
  const { token } = await signIn();
  // Changed at the issuer, with no new lmnaide sign-in.
  issuer.accounts["sub-1"] = { ...issuer.accounts["sub-1"], name: "Ada L", picture: issuer.url + "/photos/b" };
  assert.equal((await api("profile", undefined, token)).name, "Ada", "refreshes wait 15 seconds after a sync");
  const expire = () => app.db.prepare("UPDATE identities SET synced=0").run();
  expire();
  // Two at once share one refresh, since a reused refresh token is rejected.
  const [profile] = await Promise.all([api("profile", undefined, token), api("profile", undefined, token)]);
  assert.deepEqual([profile.name, profile.photo, issuer.refreshes], ["Ada L", jpeg("photo-b"), 1]);
  // The rotated token keeps working.
  issuer.accounts["sub-1"].name = "Ada Lovelace";
  expire();
  assert.equal((await api("profile", undefined, token)).name, "Ada Lovelace");
});

test("the callback rejects bad state, foreign browsers and tampered ID tokens", async (t) => {
  const { app, issuer, api, get, flow, codeOf, signIn } = await harness(t);
  const expired = await get("/api/v1/oidc/callback?code=x&state=unknown");
  assert.equal(expired.headers.get("location"), "/#oidc-error=expired");
  // The web app redirects to start on its own, so an unreachable issuer sends people back.
  const down = createApp({ dbPath: ":memory:", accounts: "oidc", publicUrl: "http://127.0.0.1",
    oidcIssuer: "http://127.0.0.1:9", oidcClientId: "id", oidcClientSecret: "s" });
  await new Promise((resolve) => down.server.listen(0, "127.0.0.1", resolve));
  t.after(() => down.close());
  const unreachable = await fetch(`http://127.0.0.1:${down.server.address().port}/api/v1/oidc/start?client=web&challenge=${"a".repeat(43)}`, { redirect: "manual" });
  assert.equal(unreachable.headers.get("location"), "/#oidc-error=unavailable");

  const foreign = await flow({ keepCookie: false });
  const result = await api("oidc/exchange", { code: codeOf(foreign.location), verifier: foreign.verifier });
  assert.equal(result.status, 400);
  assert.match(result.error, /browser where you started/);

  for (const tamper of [{ claims: { nonce: "wrong" } }, { claims: { aud: "someone-else" } }, { badSignature: true }]) {
    issuer.next = { sub: "sub-1", email: "new@example.com", ...tamper };
    const rejected = await signIn();
    assert.equal(rejected.status, 401, JSON.stringify(tamper));
  }
  assert.equal(app.db.prepare("SELECT COUNT(*) AS n FROM users").get().n, 0);
});

test("one-time codes need the client's verifier and work once", async (t) => {
  const { api, flow, codeOf } = await harness(t);
  const { verifier, location } = await flow({ client: "android" });
  assert.match(location, /^\/oidc-return\.html#/);
  const code = codeOf(location);
  assert.equal((await api("oidc/exchange", { code, verifier: "wrong" })).status, 400);
  // A failed attempt burns the code too.
  assert.equal((await api("oidc/exchange", { code, verifier })).status, 400);

  const next = await flow();
  assert.equal((await api("oidc/exchange", { code: codeOf(next.location), verifier: next.verifier })).status, 200);
  assert.equal((await api("oidc/exchange", { code: codeOf(next.location), verifier: next.verifier })).status, 400);
});

test("OIDC mode reports its options and closes password registration", async (t) => {
  const { api } = await harness(t, { passwordLogin: false });
  assert.deepEqual(await api("config"), {
    status: 200, passwordLogin: false, registration: false, oidc: { name: "CrystlCloud", accountUrl: null },
  });
  assert.equal((await api("register", { email: "a@example.com", password: "password123" })).status, 403);
  assert.equal((await api("login", { email: "a@example.com", password: "password123" })).status, 403);
});

test("local accounts mode keeps password sign-in and has no OIDC routes", async (t) => {
  const app = createApp({ accounts: "local", dbPath: ":memory:" });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  t.after(() => app.close());
  const base = `http://127.0.0.1:${app.server.address().port}/api/v1/`;
  const config = await (await fetch(base + "config")).json();
  assert.deepEqual(config, { passwordLogin: true, registration: true, oidc: null });
  const start = await fetch(base + `oidc/start?client=web&challenge=${"a".repeat(43)}`, { redirect: "manual" });
  assert.equal(start.status, 404);
  const registered = await fetch(base + "register", {
    method: "POST",
    body: JSON.stringify({ email: "a@example.com", password: "password123" }),
  });
  assert.equal(registered.status, 200);
  assert.throws(() => createApp({ accounts: "local", dbPath: ":memory:", passwordLogin: false }), /PASSWORD_LOGIN/);
});

test("CrystlCloud is the default, and a server without its client stops with instructions", () => {
  const keys = ["ACCOUNTS", "OIDC_ISSUER", "OIDC_CLIENT_ID", "OIDC_CLIENT_SECRET", "PUBLIC_URL", "CALENDAR_DOMAIN"];
  const saved = Object.fromEntries(keys.map((key) => [key, process.env[key]]));
  for (const key of keys) delete process.env[key];
  try {
    assert.throws(() => createApp({ dbPath: ":memory:" }), (e) =>
      e.message.includes("OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, PUBLIC_URL or CALENDAR_DOMAIN are not set") &&
      e.message.includes('npm run client -- add "lmnaide" https://calendar.example.com/api/v1/oidc/callback') &&
      e.message.includes("ACCOUNTS=local"));
    // With the address known, the command shows this server's own callback.
    assert.throws(() => createApp({ dbPath: ":memory:", domain: "cal.example.org", oidcClientId: "id" }), (e) =>
      e.message.includes("OIDC_CLIENT_SECRET is not set") &&
      e.message.includes("https://cal.example.org/api/v1/oidc/callback"));
  } finally {
    for (const key of keys) if (saved[key] !== undefined) process.env[key] = saved[key];
  }
  const config = oidcConfig({ accounts: "oidc", clientId: "id", clientSecret: "s", publicUrl: "https://cal.example.org" });
  assert.equal(config.issuer, CRYSTLCLOUD_ISSUER);
  assert.equal(config.name, "CrystlCloud");
  assert.equal(config.accountUrl, "https://accounts.crystlcloud.org/");
});
