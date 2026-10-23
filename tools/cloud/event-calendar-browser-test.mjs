// Optional browser check. Install Playwright and Chromium, then run:
// node tools/cloud/event-calendar-browser-test.mjs
// PLAYWRIGHT_MODULE can point to an existing Playwright installation.
import assert from "node:assert/strict";
import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createApp } from "../../server/src.js";
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
const app = createApp({ dbPath: ":memory:" });
await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
const base = "http://127.0.0.1:" + app.server.address().port;
const artifacts = join(tmpdir(), "lmnaide-event-calendar-check");
mkdirSync(artifacts, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
try {
  const api = async (path, body, token) => {
    const response = await fetch(base + "/api/v1/" + path, {
      method: body ? "POST" : "GET",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    assert.equal(response.status, 200, await response.clone().text());
    return response.json();
  };
  const owner = await api("register", { email: "owner@example.test", password: "calendar-check-123" });
  const change = (kind, id, data) => ({ kind, id, data, baseVersion: 0, deleted: false, operationId: crypto.randomUUID() });
  const event = {
    calendarId: "owner-calendar", title: "Lunch & planning 🍋", description: "Bring ideas.\nNext month's plan.",
    location: "Café, Montréal", start: Date.UTC(2026, 9, 23, 18), end: Date.UTC(2026, 9, 23, 19),
    allDay: false, color: null, recurrence: "WEEKLY", recurrenceUntil: Date.UTC(2026, 10, 27) / 86400000,
    exceptions: [], reminders: [], kind: "EVENT", completions: [], importance: null,
  };
  const allDay = { ...event, title: "Long weekend", start: Date.UTC(2026, 9, 23), end: Date.UTC(2026, 9, 26), allDay: true, recurrence: "NONE", recurrenceUntil: null };
  await api("sync", { cursor: 0, changes: [
    change("calendar", "owner-calendar", { name: "Owner's private calendar", color: -1, visible: true, keywords: "", defaultImportance: null }),
    change("event", "timed", event), change("event", "all-day", allDay),
  ] }, owner.token);
  const shared = await api("share", { eventId: "timed", action: "create" }, owner.token);
  const sharedDay = await api("share", { eventId: "all-day", action: "create" }, owner.token);
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: "light", timezoneId: "America/Regina", acceptDownloads: true });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(base + shared.path);
  await page.locator("#calendar-actions").waitFor({ state: "visible" });
  assert.equal(await page.locator("#calendar-default-label").textContent(), "Add to calendar");
  assert.ok((await page.locator("#add-calendar-default").getAttribute("href")).endsWith("/event.ics"));
  const menu = page.getByRole("menu", { name: "Calendar apps" });
  const toggle = page.getByRole("button", { name: "Choose a calendar app" });
  const openMenu = async () => {
    await toggle.click();
    await menu.waitFor({ state: "visible" });
    await page.waitForFunction(() => document.activeElement.id === "add-google");
  };
  assert.equal(await menu.isVisible(), false);
  assert.equal(await page.locator('[role="menuitem"]').count(), 3);
  const logo = page.locator("#add-lmnaide svg");
  assert.equal(await logo.locator("use").getAttribute("href"), "/lmnaide.svg#logo");
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  assert.equal(new URL(await page.locator("#add-google").getAttribute("href")).searchParams.get("dates"), "20261023T180000Z/20261023T190000Z");
  await openMenu();
  assert.equal(await logo.evaluate((svg) => getComputedStyle(svg).fill),
    await page.locator("#add-lmnaide").evaluate((link) => getComputedStyle(link).color));
  assert.equal(await logo.evaluate((svg) => getComputedStyle(svg).stroke), "none");
  assert.equal(await toggle.getAttribute("aria-expanded"), "true");
  await page.waitForFunction(() => document.activeElement.id === "add-google");
  await page.keyboard.press("ArrowDown");
  assert.equal(await page.evaluate(() => document.activeElement.id), "add-lmnaide");
  await page.keyboard.press("End");
  assert.equal(await page.evaluate(() => document.activeElement.id), "add-calendar");
  await page.keyboard.press("ArrowDown");
  assert.equal(await page.evaluate(() => document.activeElement.id), "add-google");
  await page.keyboard.press("Escape");
  assert.equal(await menu.isVisible(), false);
  assert.equal(await toggle.getAttribute("aria-expanded"), "false");
  assert.equal(await page.evaluate(() => document.activeElement.id), "calendar-toggle");
  await toggle.press("ArrowUp");
  await page.waitForFunction(() => document.activeElement.id === "add-calendar");
  await page.keyboard.press("Home");
  assert.equal(await page.evaluate(() => document.activeElement.id), "add-google");
  await page.keyboard.press("Tab");
  assert.equal(await menu.isVisible(), false);
  await openMenu();
  await page.locator("#title").click();
  assert.equal(await menu.isVisible(), false, "Clicking outside dismisses the menu");
  await openMenu();
  const bounds = await menu.boundingBox();
  assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
  assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 844);
  await page.screenshot({ path: join(artifacts, "shared-mobile.png"), fullPage: true, animations: "disabled" });
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 320, height: 568 });
  await openMenu();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  const narrow = await menu.boundingBox();
  assert.ok(narrow.x >= 0 && narrow.x + narrow.width <= 320);
  assert.ok(narrow.y >= 0 && narrow.y + narrow.height <= 568, JSON.stringify(narrow));
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 390, height: 844 });
  const [download] = await Promise.all([
    page.waitForEvent("download"), page.locator("#add-calendar-default").click(),
  ]);
  assert.equal(download.suggestedFilename(), "event.ics");
  assert.match(readFileSync(await download.path(), "utf8"), /DTSTART:20261023T180000Z/);
  await openMenu();
  const [systemDownload] = await Promise.all([
    page.waitForEvent("download"), page.locator("#add-calendar").click(),
  ]);
  assert.equal(systemDownload.suggestedFilename(), "event.ics");
  assert.equal(await menu.isVisible(), false);
  await openMenu();
  await page.locator("#add-lmnaide").click();
  await page.locator("#email").fill("recipient@example.test");
  await page.locator("#password").fill("calendar-check-456");
  await page.locator("#register").click();
  await page.locator("#editor[open]").waitFor();
  assert.equal(await page.locator("#editor .title-input").inputValue(), event.title);
  assert.equal(await page.getByLabel("Add location", { exact: true }).inputValue(), event.location);
  assert.equal(await page.getByLabel("Add description", { exact: true }).inputValue(), event.description);
  await page.locator("#editor").getByRole("button", { name: "Save", exact: true }).click();
  await page.locator("#editor").waitFor({ state: "hidden" });
  const recipientToken = await page.evaluate(() => sessionStorage.getItem("token"));
  const saved = await api("sync", { cursor: 0, changes: [] }, recipientToken);
  const copy = saved.records.filter((r) => r.kind === "event" && r.data.title === event.title);
  assert.equal(copy.length, 1);
  for (const key of ["title", "description", "location", "start", "end", "allDay", "recurrence", "recurrenceUntil", "kind"])
    assert.equal(copy[0].data[key], event[key], key);
  assert.notEqual(copy[0].id, "timed");
  assert.notEqual(copy[0].data.calendarId, event.calendarId);
  await page.goto(base + sharedDay.path);
  await page.locator("#calendar-actions").waitFor({ state: "visible" });
  assert.equal(await page.locator("#calendar-default-label").textContent(), "Add to lmnaide");
  const defaultUrl = new URL(await page.locator("#add-calendar-default").getAttribute("href"));
  assert.equal(defaultUrl.origin, base);
  assert.equal(defaultUrl.searchParams.get("addShared"), sharedDay.path.split("/").pop());
  assert.equal(await page.locator("#calendar-default-logo").isVisible(), true);
  assert.equal(await page.locator("#calendar-default-logo").evaluate((svg) => getComputedStyle(svg).fill),
    await page.locator("#add-calendar-default").evaluate((link) => getComputedStyle(link).color));
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.emulateMedia({ colorScheme: "dark" });
  await openMenu();
  await page.screenshot({ path: join(artifacts, "shared-desktop.png"), fullPage: true, animations: "disabled" });
  await page.keyboard.press("Escape");
  await page.getByRole("link", { name: "Add to lmnaide", exact: true }).click();
  await page.locator("#editor[open]").waitFor();
  assert.equal(await page.locator("#editor .title-input").inputValue(), allDay.title);
  await page.locator("#editor").getByRole("button", { name: "Save", exact: true }).click();
  await page.locator("#editor").waitFor({ state: "hidden" });
  const dayCopy = (await api("sync", { cursor: 0, changes: [] }, recipientToken)).records.find((r) => r.kind === "event" && r.data.title === allDay.title);
  assert.equal(dayCopy.data.start, allDay.start);
  assert.equal(dayCopy.data.end, allDay.end);
  assert.equal(dayCopy.data.allDay, true);
  // A stale token or failed session check must not prevent an anonymous event read.
  for (const failedCheck of ["expired", "network"]) {
    const visitor = await context.newPage();
    await visitor.addInitScript((token) => sessionStorage.setItem("token", token),
      failedCheck === "expired" ? "expired-session" : recipientToken);
    if (failedCheck === "network")
      await visitor.route(base + "/api/v1/profile", (route) => route.abort());
    await visitor.goto(base + sharedDay.path);
    await visitor.locator("#calendar-actions").waitFor({ state: "visible" });
    assert.equal(await visitor.locator("#title").textContent(), allDay.title);
    assert.equal(await visitor.locator("#calendar-default-label").textContent(), "Add to calendar");
    assert.ok((await visitor.locator("#add-calendar-default").getAttribute("href")).endsWith("/event.ics"));
    assert.equal(await visitor.locator("#calendar-default-logo").isVisible(), false);
    await visitor.close();
  }
  await page.goto(base + shared.path);
  await page.locator("#calendar-actions").waitFor({ state: "visible" });
  await openMenu();
  await api("share", { eventId: "timed", action: "revoke" }, owner.token);
  await page.getByRole("button", { name: "Refresh", exact: true }).evaluate((button) => button.click());
  await page.getByRole("heading", { name: "Event unavailable", exact: true }).waitFor();
  assert.equal(await page.locator("#calendar-actions").isVisible(), false);
  assert.equal(await menu.isVisible(), false);
  assert.deepEqual(errors, []);
  console.log("PASS: session-aware defaults, expired-session and network fallbacks, split button, keyboard navigation, dismissal, narrow-screen menu placement, downloads, signed-out registration/import, signed-in import, independent saves, all-day dates, revocation, mobile/light and desktop/dark. Artifacts: " + artifacts);
} finally {
  await browser.close();
  await app.close();
}
