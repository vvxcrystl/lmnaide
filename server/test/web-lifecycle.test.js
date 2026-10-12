import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const source = readFileSync(new URL("../public/app.js", import.meta.url), "utf8");
// Run the production handlers with controlled layout and animation frames. Node has no DOM.
function handlers(names, globals) {
  const code = names.map((name) => {
    const match = source.match(new RegExp(`^function ${name}\\([^]*?^}`, "m"));
    assert.ok(match, `Missing handler ${name}`);
    return match[0];
  }).join("\n");
  return runInNewContext(`${code}\n({${names.join(",")}})`, globals);
}

test("reload sync defers main-screen rendering until startup reveals it", () => {
  const elements = new Map();
  const $ = (id) => {
    if (!elements.has(id)) elements.set(id, { hidden: true, setAttribute() {} });
    return elements.get(id);
  };
  let renders = 0;
  const app = handlers(["render", "signedIn"], {
    $, token: "test-session", prefs: { view: "SCHEDULE" }, wide: { matches: false },
    VIEWS: [["SCHEDULE", "Schedule"]], pickerOpen: false,
    renderDrawer() {}, renderSchedule() { renders++; }, updateTitle() {},
  });
  // Sync and the sidebar's delayed resize can finish before the font/profile request.
  for (let i = 0; i < 20; i++) app.render();
  assert.equal(renders, 0);
  app.signedIn();
  assert.equal($("calendar").hidden, false);
  assert.equal($("auth").hidden, true);
  assert.equal(renders, 1);
  app.render();
  assert.equal(renders, 2, "Visible sync refreshes still render");
});

function scheduleHarness() {
  const frames = [];
  const sched = { anchor: new Date(2026, 9, 11), end: new Date(2027, 3, 30) };
  const prefs = { view: "SCHEDULE" };
  let renders = 0;
  const { onScheduleScroll } = handlers(["onScheduleScroll"], {
    sched, prefs, firstVisibleRow: () => null,
    listLimit: (d) => new Date(d.getFullYear() + 5, d.getMonth(), d.getDate()),
    addMonths: (d, n) => new Date(d.getFullYear(), d.getMonth() + n, 1),
    endOfMonth: (d) => new Date(d.getFullYear(), d.getMonth() + 1, 0),
    requestAnimationFrame: (fn) => frames.push(fn),
    renderSchedule: () => renders++,
  });
  const list = { isConnected: true, clientHeight: 600, scrollHeight: 3000,
    scrollTop: 2400, dataset: {} };
  return { sched, prefs, list, frames, scroll: () => onScheduleScroll.call(list),
    get renders() { return renders; } };
}

test("zero-height and detached schedules cannot expand during reload", () => {
  for (const layout of [
    { clientHeight: 0, scrollHeight: 0, scrollTop: 0 },
    { isConnected: false },
  ]) {
    const app = scheduleHarness();
    Object.assign(app.list, layout);
    const end = app.sched.end.getTime();
    for (let i = 0; i < 20; i++) app.scroll();
    assert.equal(app.frames.length, 0);
    assert.equal(app.sched.end.getTime(), end);
  }
});

test("visible schedules load one next period at the bottom", () => {
  const app = scheduleHarness();
  app.scroll();
  app.scroll();
  assert.equal(app.frames.length, 1);
  assert.equal(app.sched.end.getFullYear(), 2027);
  assert.equal(app.sched.end.getMonth(), 9);
  app.frames[0]();
  assert.equal(app.renders, 1);
});

test("queued growth ignores a replaced schedule or a changed view", () => {
  for (const replace of [
    (app) => { app.list.isConnected = false; },
    (app) => { app.prefs.view = "MONTH"; },
  ]) {
    const app = scheduleHarness();
    app.scroll();
    replace(app);
    app.frames[0]();
    assert.equal(app.renders, 0);
  }
});

test("schedule growth stops away from the bottom and at the five-year limit", () => {
  for (const adjust of [
    (app) => { app.list.scrollTop = 0; },
    (app) => { app.sched.end = new Date(2031, 9, 31); },
  ]) {
    const app = scheduleHarness();
    adjust(app);
    app.scroll();
    assert.equal(app.frames.length, 0);
  }
});

test("calendar refreshes reuse date and time formatters with the same display", () => {
  const formatting = source.slice(source.indexOf("/* ---------- Formatting,"),
    source.indexOf("/* ---------- Colors,"));
  let created = 0;
  const prefs = { use24h: false };
  const app = runInNewContext(`${formatting}\n({fmt,time})`, {
    prefs, pad: (n) => String(n).padStart(2, "0"),
    Intl: { DateTimeFormat: class extends Intl.DateTimeFormat {
      constructor(...args) { super(...args); created++; }
    } },
  });
  const date = new Date(2026, 9, 11, 14, 30);
  const options = { weekday: "long", month: "long", day: "numeric" };
  for (let i = 0; i < 500; i++) {
    assert.equal(app.fmt(date, options), date.toLocaleDateString(undefined, options));
    assert.equal(app.time(date), date.toLocaleTimeString(undefined,
      { hour: "numeric", minute: "2-digit", hour12: true }));
  }
  const wholeHour = new Date(2026, 9, 11, 14);
  assert.equal(app.time(wholeHour), wholeHour.toLocaleTimeString(undefined,
    { hour: "numeric", hour12: true }));
  assert.equal(created, 3, "One date formatter and two time formats suffice");
  prefs.use24h = true;
  assert.equal(app.time(date), "14:30");
  assert.equal(created, 3);
});

test("formatter storage stays bounded as formatting styles change", () => {
  const formatting = source.slice(source.indexOf("/* ---------- Formatting,"),
    source.indexOf("/* ---------- Colors,"));
  const app = runInNewContext(`${formatting}\n({fmt, count: () => dateFormatters.size})`, {});
  const date = new Date(2026, 9, 11);
  for (const timeZone of Intl.supportedValuesOf("timeZone").slice(0, 50))
    app.fmt(date, { month: "long", timeZone });
  assert.equal(app.count(), 32);
});
