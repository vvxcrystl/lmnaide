// OpenID Connect relying party for "Continue with CrystlCloud" (or any
// compatible issuer). Uses only Node's built-in crypto and fetch.
import { createHash, createPublicKey, randomBytes, verify } from "node:crypto";

const b64url = (buffer) => Buffer.from(buffer).toString("base64url");
export const randomToken = () => b64url(randomBytes(32));
export const s256 = (verifier) => b64url(createHash("sha256").update(verifier).digest());

// Ed25519 is what CrystlCloud Accounts signs with. RS256 and ES256 cover other issuers.
const ALGORITHMS = {
  EdDSA: (data, key, sig) => verify(null, data, key, sig),
  RS256: (data, key, sig) => verify("sha256", data, key, sig),
  ES256: (data, key, sig) => verify("sha256", data, { key, dsaEncoding: "ieee-p1363" }, sig),
};

/** Reads and checks the OIDC settings. Returns null when the server uses local accounts only. */
export function oidcConfig({ accounts, issuer, clientId, clientSecret, name, publicUrl }) {
  if (!accounts || accounts === "local") return null;
  if (accounts !== "oidc") throw new Error("ACCOUNTS must be local or oidc");
  const missing = [
    ["OIDC_ISSUER", issuer],
    ["OIDC_CLIENT_ID", clientId],
    ["OIDC_CLIENT_SECRET", clientSecret],
    ["PUBLIC_URL or CALENDAR_DOMAIN", publicUrl],
  ].filter(([, value]) => !value).map(([key]) => key);
  if (missing.length) throw new Error(`ACCOUNTS=oidc needs ${missing.join(", ")}`);
  const url = new URL(issuer);
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.protocol !== "https:" && !(local && url.protocol === "http:"))
    throw new Error("OIDC_ISSUER must use https");
  return {
    issuer: issuer.replace(/\/$/, ""),
    clientId,
    clientSecret,
    name: name || "CrystlCloud",
    redirectUri: new URL("/api/v1/oidc/callback", publicUrl).href,
  };
}

export function createOidcClient(config) {
  let discovery = null;
  let keys = null;
  const getJson = async (url, init = {}) => {
    const res = await fetch(url, { ...init, signal: AbortSignal.timeout(10000) });
    const data = await res.json().catch(() => null);
    if (!res.ok || !data || typeof data !== "object")
      throw new Error(`OIDC request to ${new URL(url).pathname} failed with ${res.status}`);
    return data;
  };
  const metadata = async () => {
    if (discovery) return discovery;
    const data = await getJson(`${config.issuer}/.well-known/openid-configuration`);
    if (data.issuer !== config.issuer)
      throw new Error(`Discovery issuer ${data.issuer} does not match OIDC_ISSUER`);
    return (discovery = data);
  };
  const keyFor = async (kid) => {
    // Refetch once for an unknown key ID so key rotation works without a restart.
    for (const fresh of [false, true]) {
      if (fresh || !keys) keys = (await getJson((await metadata()).jwks_uri)).keys ?? [];
      const jwk = keys.find((k) => k.kid === kid) ?? (!kid && keys.length === 1 ? keys[0] : null);
      if (jwk) return createPublicKey({ key: jwk, format: "jwk" });
    }
    throw new Error("ID token was signed with an unknown key");
  };

  return {
    async authorizeUrl({ state, nonce, verifier }) {
      const url = new URL((await metadata()).authorization_endpoint);
      url.search = new URLSearchParams({
        response_type: "code",
        client_id: config.clientId,
        redirect_uri: config.redirectUri,
        scope: "openid profile email",
        state,
        nonce,
        code_challenge: s256(verifier),
        code_challenge_method: "S256",
      }).toString();
      return url.href;
    },

    /** Swaps the code for tokens, verifies the ID token, then reads the profile from userinfo. */
    async signIn({ code, verifier, nonce }) {
      const meta = await metadata();
      const basic = Buffer.from(
        `${encodeURIComponent(config.clientId)}:${encodeURIComponent(config.clientSecret)}`,
      ).toString("base64");
      const tokens = await getJson(meta.token_endpoint, {
        method: "POST",
        headers: {
          Authorization: `Basic ${basic}`,
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: new URLSearchParams({
          grant_type: "authorization_code",
          code,
          redirect_uri: config.redirectUri,
          code_verifier: verifier,
        }),
      });
      if (typeof tokens.id_token !== "string" || typeof tokens.access_token !== "string")
        throw new Error("Token response is missing id_token or access_token");
      const claims = await this.verifyIdToken(tokens.id_token, nonce);
      const info = await getJson(meta.userinfo_endpoint, {
        headers: { Authorization: `Bearer ${tokens.access_token}` },
      });
      if (info.sub !== claims.sub) throw new Error("userinfo sub does not match the ID token");
      return {
        subject: claims.sub,
        email: typeof info.email === "string" ? info.email.trim().toLowerCase() : "",
      };
    },

    async verifyIdToken(token, nonce) {
      const parts = token.split(".");
      if (parts.length !== 3) throw new Error("ID token is not a JWS");
      const [header, payload] = parts.slice(0, 2).map((p) => JSON.parse(Buffer.from(p, "base64url")));
      const check = ALGORITHMS[header.alg];
      if (!check) throw new Error(`ID token algorithm ${header.alg} is not allowed`);
      const signed = Buffer.from(`${parts[0]}.${parts[1]}`);
      if (!check(signed, await keyFor(header.kid), Buffer.from(parts[2], "base64url")))
        throw new Error("ID token signature is invalid");
      const audiences = [payload.aud].flat();
      const now = Date.now() / 1000;
      if (payload.iss !== config.issuer) throw new Error("ID token issuer does not match");
      if (!audiences.includes(config.clientId)) throw new Error("ID token audience does not match");
      if (audiences.length > 1 && payload.azp !== config.clientId)
        throw new Error("ID token authorized party does not match");
      if (typeof payload.exp !== "number" || payload.exp < now - 60) throw new Error("ID token expired");
      if (payload.nonce !== nonce) throw new Error("ID token nonce does not match");
      if (typeof payload.sub !== "string" || !payload.sub) throw new Error("ID token has no subject");
      return payload;
    },
  };
}
