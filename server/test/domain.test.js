import { test } from "node:test";
import assert from "node:assert/strict";
import { resolvePublicUrl } from "../src.js";

test("custom domains become HTTPS origins for public links", () => {
  assert.equal(
    resolvePublicUrl({ domain: "calendar.domain.com" }),
    "https://calendar.domain.com",
  );
  assert.equal(
    resolvePublicUrl({ domain: "Calendar.My-Domain.com" }),
    "https://calendar.my-domain.com",
  );
  assert.equal(resolvePublicUrl(), "");
});

test("explicit public URLs take precedence and retain local development support", () => {
  assert.equal(
    resolvePublicUrl({ domain: "calendar.domain.com", publicUrl: "https://other.example.com/" }),
    "https://other.example.com",
  );
  assert.equal(
    resolvePublicUrl({ publicUrl: "http://localhost:3000" }),
    "http://localhost:3000",
  );
  for (const publicUrl of ["ftp://example.com", "https://user:password@example.com"])
    assert.throws(() => resolvePublicUrl({ publicUrl }), /PUBLIC_URL/);
});

test("invalid custom domain configuration fails before the server starts", () => {
  for (const domain of [
    "https://calendar.domain.com", "calendar.domain.com:443",
    "calendar.domain.com/path", "user@calendar.domain.com",
    "calendar.domain.com,other.domain.com", "calendar.domain.com other.domain.com",
    "*.domain.com", ".domain.com", "calendar..com", "-calendar.domain.com",
    "calendar-.domain.com", "calendar_domain.com", "calendar.domain.com\n",
    `${"a".repeat(64)}.com`, `${"a".repeat(63)}.`.repeat(4) + "com",
  ])
    assert.throws(() => resolvePublicUrl({ domain }), /CALENDAR_DOMAIN/, domain);
});
