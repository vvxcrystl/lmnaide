// Run with Node 24+, Playwright and Chromium installed.
// PLAYWRIGHT_MODULE can point to an existing Playwright installation.
// CHROMIUM_PATH optionally selects an installed browser executable.
import assert from "node:assert/strict";
import { mkdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../../server/src.js";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
const app = createApp({ dbPath: ":memory:" });
await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
const base = "http://127.0.0.1:" + app.server.address().port;
const artifacts = join(tmpdir(), "lmnaide-public-calendar-check");
mkdirSync(artifacts, { recursive: true });
let browser;
try {
  browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
  const api = async (path, body, token) => {
    const res = await fetch(base + "/api/v1/" + path, {
      method: body ? "POST" : "GET",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    assert.ok(res.ok, await res.clone().text());
    return res.json();
  };
  const owner = await api("register", { email: "public-layout@example.test", username: "Crystl", password: "calendar-check-123" });
  const calendar = (name, color, publicDetails) => ({ name, color, visible: true, keywords: "", defaultImportance: null, publicDetails });
  const event = {
    calendarId: "work", title: "Team standup", description: "Plans for the week and project updates.", location: "Office",
    start: Date.UTC(2026, 9, 19, 15, 30), end: Date.UTC(2026, 9, 19, 16), allDay: false,
    color: null, recurrence: "WEEKDAYS", recurrenceUntil: Date.UTC(2026, 9, 30) / 86400000,
    exceptions: [Date.UTC(2026, 9, 21) / 86400000], reminders: [], kind: "EVENT", completions: [], importance: null,
  };
  const change = (kind, id, data) => ({ kind, id, data, baseVersion: 0, deleted: false, operationId: crypto.randomUUID() });
  const initial = await api("sync", { cursor: 0, changes: [
    change("calendar", "work", calendar("Work", -14776091, true)),
    change("calendar", "personal", calendar("Personal", -10006364, true)),
    change("calendar", "private", calendar("Private calendar name", -2818048, false)),
    change("event", "standup", event),
    change("event", "lunch", { ...event, calendarId: "personal", title: "Lunch with Sam", description: "Meet by the patio.", location: "Luna Cafe", recurrence: "NONE", start: Date.UTC(2026, 9, 23, 18), end: Date.UTC(2026, 9, 23, 19) }),
    change("event", "task", { ...event, calendarId: "personal", title: "Book train tickets", kind: "TASK", recurrence: "NONE", start: Date.UTC(2026, 9, 23, 22), end: Date.UTC(2026, 9, 23, 22) }),
    change("event", "trip", { ...event, calendarId: "personal", title: "Weekend away", recurrence: "NONE", allDay: true, start: Date.UTC(2026, 9, 24), end: Date.UTC(2026, 9, 27) }),
    change("event", "private-time", { ...event, calendarId: "private", title: "Secret appointment", description: "Secret notes", location: "Secret address", recurrence: "NONE", start: Date.UTC(2026, 9, 23, 20), end: Date.UTC(2026, 9, 23, 21) }),
    change("event", "private-day", { ...event, calendarId: "private", title: "Secret holiday", recurrence: "NONE", allDay: true, start: Date.UTC(2026, 9, 12), end: Date.UTC(2026, 9, 13) }),
  ] }, owner.token);
  const link = await api("public", { action: "create" }, owner.token);
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: "dark", timezoneId: "America/Regina" });
  // A visitor who already has a signed-in session must still receive only the public projection.
  await context.addInitScript(({ token, email }) => {
    sessionStorage.setItem("token", token);
    sessionStorage.setItem("email", email);
    localStorage.setItem("lmnaide.prefs", JSON.stringify({ view: "WEEK" }));
  }, owner);
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  await page.clock.setFixedTime(new Date("2026-10-23T18:30:00Z"));
  const errors = [], requests = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (request.url().startsWith(base + "/api/")) requests.push({ url: request.url(), method: request.method(), auth: request.headers().authorization });
  });
  await page.goto(base + link.path);
  await page.locator(".month.web .month-cell").first().waitFor();
  assert.equal(await page.locator("#public-name").innerText(), "Crystl's calendar");
  assert.equal(await page.locator("#view-label").innerText(), "Month", "Public preferences are separate from the signed-in calendar");
  assert.equal(await page.locator(".month-cell").count(), 35);
  assert.equal(await page.locator(".timed-chip").filter({ hasText: "Team standup" }).count(), 9, "Skipped recurring occurrence stays absent");
  for (const id of ["create", "add", "manage", "settings", "avatar", "auth"])
    assert.equal(await page.locator("#" + id).isVisible(), false, id);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: join(artifacts, "public-month-desktop.png"), animations: "disabled" });

  const detail = page.locator("#detail");
  await page.locator(".timed-chip").filter({ hasText: "Lunch with Sam" }).click();
  await detail.waitFor({ state: "visible" });
  assert.match(await detail.innerText(), /Luna Cafe/);
  assert.match(await detail.innerText(), /Meet by the patio/);
  for (const action of ["Edit", "Delete", "More options", "Mark completed"])
    assert.equal(await detail.getByRole("button", { name: action, exact: true }).count(), 0, action);
  await page.getByRole("button", { name: "Refresh calendar", exact: true }).evaluate((button) => button.click());
  await page.waitForFunction(() => !document.querySelector("#public-refresh").disabled);
  assert.equal(await detail.isVisible(), true, "Unchanged polling does not dismiss event details");
  await detail.getByRole("button", { name: "Close", exact: true }).click();
  await page.locator(".timed-chip").filter({ hasText: "Crystl is busy" }).click();
  await detail.waitFor({ state: "visible" });
  for (const secret of ["Secret appointment", "Secret notes", "Secret address", "Private calendar name"])
    assert.equal((await page.locator("body").innerText()).includes(secret), false, secret);
  await detail.getByRole("button", { name: "Close", exact: true }).click();

  const chooseView = async (name) => {
    await page.locator("#view-menu").click();
    await page.locator(".menu.popup").getByRole("menuitemradio", { name, exact: true }).click();
  };
  await chooseView("Week");
  await page.locator(".grid .column").first().waitFor();
  assert.equal(await page.locator(".column").count(), 7);
  await page.screenshot({ path: join(artifacts, "public-week-desktop.png"), animations: "disabled" });
  await chooseView("3 days");
  assert.equal(await page.locator(".column").count(), 3);
  await chooseView("Day");
  assert.equal(await page.locator(".column").count(), 1);
  await page.locator(".column").click({ position: { x: 20, y: 420 }, force: true });
  assert.equal(await page.locator(".draft").count(), 0, "Empty time slots cannot create events");
  assert.equal(await page.locator("#editor").isVisible(), false);
  await chooseView("Schedule");
  await page.locator(".schedule .event-card").first().waitFor();
  assert.equal(await page.locator(".schedule .check").count(), 0, "Task completion is read-only");
  await chooseView("Month");
  await page.locator("#next").click();
  assert.match(await page.locator("#period-title").innerText(), /November/);
  await page.locator("#today-pill").click();
  assert.match(await page.locator("#period-title").innerText(), /October/);
  await page.getByRole("checkbox", { name: "Work", exact: true }).click();
  assert.equal(await page.locator(".timed-chip").filter({ hasText: "Team standup" }).count(), 0);
  await page.getByRole("checkbox", { name: "Work", exact: true }).click();
  assert.equal(await page.locator(".timed-chip").filter({ hasText: "Team standup" }).count(), 9);
  assert.equal((await api("sync", { cursor: 0, changes: [] }, owner.token)).cursor, initial.cursor, "Visitor filters never write to the owner's calendar");

  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ colorScheme: "light" });
  await page.locator(".month:not(.web)").waitFor();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.screenshot({ path: join(artifacts, "public-month-mobile.png"), animations: "disabled" });
  await page.locator(".bar").filter({ hasText: "Lunch with Sam" }).click();
  await detail.waitFor({ state: "visible" });
  assert.match(await detail.innerText(), /Meet by the patio/);
  await detail.getByRole("button", { name: "Close", exact: true }).click();
  await page.locator("#period").click();
  await page.locator("#mini").waitFor({ state: "visible" });
  await page.locator("#mini").getByRole("button", { name: /23/ }).first().click();
  await page.locator("#menu").click();
  await page.locator("#views").getByRole("button", { name: "Week", exact: true }).click();
  assert.equal(await page.locator(".column").count(), 7);
  await page.screenshot({ path: join(artifacts, "public-week-mobile.png"), animations: "disabled" });
  await page.locator("#menu").click();
  await page.locator("#views").getByRole("button", { name: "Month", exact: true }).click();
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await page.getByRole("searchbox", { name: "Search events" }).fill("Secret notes");
  await page.waitForTimeout(250);
  assert.equal(await page.locator("#results .event-card").count(), 0);
  // Copy from an anonymous public view, through registration, into a separate account.
  const visitorContext = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: "America/Regina", acceptDownloads: true });
  const visitor = await visitorContext.newPage();
  visitor.on("pageerror", (error) => errors.push(error.message));
  await visitor.clock.setFixedTime(new Date("2026-10-23T18:30:00Z"));
  await visitor.goto(base + link.path);
  await visitor.locator(".timed-chip").filter({ hasText: "Crystl is busy" }).click();
  const [busyDownload] = await Promise.all([
    visitor.waitForEvent("download"), visitor.locator("#add-calendar-default").click(),
  ]);
  const busyFile = readFileSync(await busyDownload.path(), "utf8");
  assert.match(busyFile, /SUMMARY:Crystl is busy/);
  for (const secret of ["Secret appointment", "Secret notes", "Secret address", "Private calendar name"])
    assert.equal(busyFile.includes(secret), false);
  await visitor.locator("#detail").getByRole("button", { name: "Close", exact: true }).click();
  await visitor.locator(".timed-chip").filter({ hasText: "Lunch with Sam" }).click();
  assert.equal(await visitor.locator("#calendar-default-label").innerText(), "Add to calendar");
  const [download] = await Promise.all([
    visitor.waitForEvent("download"), visitor.locator("#add-calendar-default").click(),
  ]);
  assert.equal(download.suggestedFilename(), "event.ics");
  const exported = readFileSync(await download.path(), "utf8");
  assert.match(exported, /SUMMARY:Lunch with Sam/);
  assert.match(exported, /DTSTART:20261023T180000Z/);
  assert.match(exported, /LOCATION:Luna Cafe/);
  const google = new URL(await visitor.locator("#add-google").getAttribute("href"));
  assert.equal(google.searchParams.get("text"), "Lunch with Sam");
  assert.equal(google.searchParams.get("details"), "Meet by the patio.");
  await visitor.getByRole("button", { name: "Choose a calendar app" }).click();
  await visitor.waitForFunction(() => document.activeElement.id === "add-google");
  const menuBounds = await visitor.locator("#calendar-menu").boundingBox();
  assert.ok(menuBounds.y >= 0 && menuBounds.y + menuBounds.height <= 1000);
  await visitor.screenshot({ path: join(artifacts, "public-add-to-calendar-desktop.png") });
  await visitor.locator("#add-lmnaide").click();
  await visitor.locator("#email").fill("public-recipient@example.test");
  await visitor.locator("#password").fill("calendar-copy-123");
  await visitor.locator("#register").click();
  await visitor.locator("#editor[open]").waitFor();
  assert.equal(await visitor.locator("#editor .title-input").inputValue(), "Lunch with Sam");
  assert.equal(await visitor.getByLabel("Add location", { exact: true }).inputValue(), "Luna Cafe");
  assert.equal(await visitor.getByLabel("Add description", { exact: true }).inputValue(), "Meet by the patio.");
  await visitor.locator("#editor").getByRole("button", { name: "Save", exact: true }).click();
  await visitor.locator("#editor").waitFor({ state: "hidden" });
  const recipientToken = await visitor.evaluate(() => sessionStorage.getItem("token"));
  const recipientEvents = (await api("sync", { cursor: 0, changes: [] }, recipientToken)).records.filter((r) => r.kind === "event");
  const copy = recipientEvents.find((r) => r.data.title === "Lunch with Sam");
  assert.ok(copy);
  assert.notEqual(copy.id, "lunch");
  assert.notEqual(copy.data.calendarId, "personal");
  assert.equal(copy.data.start, Date.UTC(2026, 9, 23, 18));
  assert.equal(copy.data.end, Date.UTC(2026, 9, 23, 19));
  assert.equal(copy.data.description, "Meet by the patio.");
  assert.equal(await visitor.evaluate(() => Object.keys(sessionStorage).some((key) => key.startsWith("lmnaide.copy:"))), false);
  await visitor.setViewportSize({ width: 390, height: 844 });
  await visitor.goto(base + link.path);
  await visitor.locator(".bar").filter({ hasText: "Weekend away" }).first().click();
  assert.equal(await visitor.locator("#calendar-default-label").innerText(), "Add to lmnaide");
  await visitor.getByRole("button", { name: "Choose a calendar app" }).click();
  await visitor.waitForFunction(() => document.activeElement.id === "add-google");
  const mobileMenu = await visitor.locator("#calendar-menu").boundingBox();
  assert.ok(mobileMenu.x >= 0 && mobileMenu.x + mobileMenu.width <= 390);
  assert.ok(mobileMenu.y >= 0 && mobileMenu.y + mobileMenu.height <= 844);
  await visitor.screenshot({ path: join(artifacts, "public-add-to-calendar-mobile.png") });
  await visitor.keyboard.press("Escape");
  assert.equal(await visitor.locator("#detail").isVisible(), true);
  await visitor.locator("#add-calendar-default").click();
  await visitor.locator("#editor[open]").waitFor();
  await visitor.locator("#editor").getByRole("button", { name: "Save", exact: true }).click();
  await visitor.locator("#editor").waitFor({ state: "hidden" });
  const dayCopy = (await api("sync", { cursor: 0, changes: [] }, recipientToken)).records.find((r) => r.kind === "event" && r.data.title === "Weekend away");
  assert.equal(dayCopy.data.allDay, true);
  assert.equal(dayCopy.data.start, Date.UTC(2026, 9, 24));
  assert.equal(dayCopy.data.end, Date.UTC(2026, 9, 27));
  assert.equal((await api("sync", { cursor: 0, changes: [] }, owner.token)).cursor, initial.cursor, "Copies never change the owner's calendar");
  await visitorContext.close();
  await api("public", { action: "revoke" }, owner.token);
  await page.locator("#search-page").getByRole("button", { name: "Back", exact: true }).click();
  await page.getByRole("button", { name: "Refresh calendar", exact: true }).click();
  await page.locator("#view [role=status]").waitFor();
  assert.equal(await page.locator("#public-name").textContent(), "Calendar unavailable");
  assert.equal(await page.locator(".timed-chip, .bar, .event-card").count(), 0);
  assert.equal(await page.locator("#visibility").innerText(), "Tasks");
  assert.ok(requests.every((r) => r.method === "GET" && !r.auth && r.url.startsWith(base + "/api/v1/public/")), JSON.stringify(requests));
  assert.deepEqual(errors, []);
  console.log("PASS: shared calendar views, desktop/mobile layouts, details, recurring exceptions, local filters, session isolation, public event downloads and imports, independent saves, no editing or task completion, and revocation.");
  console.log("Screenshots: " + artifacts);
} finally {
  await browser?.close();
  app.close();
}
