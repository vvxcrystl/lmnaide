import { googleCalendarUrl, lmnaideAppUrl, toICalendar } from "./calendar-export.js";

// Shared by standalone event links and public calendar event details.
export function createCalendarActions({ event, webUrl, downloadUrl, signedIn, beforeImport, onError }) {
  const fileUrl = downloadUrl ? null : URL.createObjectURL(new Blob([
    toICalendar(event, crypto.randomUUID() + "@lmnaide"),
  ], { type: "text/calendar;charset=utf-8" }));
  downloadUrl ??= fileUrl;
  const template = document.createElement("template");
  template.innerHTML = `<section id="calendar-actions" class="shared-section" aria-label="Add to your calendar">
        <div id="calendar-split" class="calendar-split">
          <a id="add-calendar-default" class="calendar-primary" title="Open a calendar file with your calendar app">
            <svg id="calendar-default-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 2v3m10-3v3M4 9h16M6 4h12a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm6 8v6m-3-3h6"/></svg>
            <svg id="calendar-default-logo" class="calendar-default-logo calendar-lemon" viewBox="0 0 24 24" aria-hidden="true" hidden><use href="/lmnaide.svg#logo"/></svg>
            <span id="calendar-default-label">Add to calendar</span>
          </a>
          <button id="calendar-toggle" class="calendar-toggle" type="button" popovertarget="calendar-menu" aria-label="Choose a calendar app" aria-haspopup="menu" aria-controls="calendar-menu" aria-expanded="false">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5"/></svg>
          </button>
        </div>
        <div id="calendar-menu" class="calendar-menu" role="menu" aria-label="Calendar apps" popover="auto">
          <a id="add-google" class="calendar-option" role="menuitem" tabindex="-1" target="_blank" rel="noopener noreferrer">
            <span class="calendar-provider-mark" aria-hidden="true">G</span>
            Google Calendar
          </a>
          <a id="add-lmnaide" class="calendar-option" role="menuitem" tabindex="-1">
            <svg class="calendar-app-logo calendar-lemon" viewBox="0 0 24 24" aria-hidden="true"><use href="/lmnaide.svg#logo"/></svg>
            lmnaide
          </a>
          <a id="add-calendar" class="calendar-option" role="menuitem" tabindex="-1">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m-4-4 4 4 4-4M5 16v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3"/></svg>
            <span>System default<span class="calendar-option-hint">Calendar file (.ics)</span></span>
          </a>
        </div>
        <p class="body-small variant">Saves a copy. Later changes to this shared event won't update your copy.</p>
        <p id="recurring-copy-note" class="body-small variant" hidden>Copies include the repeat pattern, without skipped dates. Other calendar apps add tasks as events.</p>
      </section>`;
  const element = template.content.firstElementChild;
  const $ = (id) => element.querySelector("#" + id);
  const calendarToggle = $("calendar-toggle");
  const calendarMenu = $("calendar-menu");
  const calendarItems = [...calendarMenu.querySelectorAll('[role="menuitem"]')];
  let positionListeners;
  let firstCalendarItem = 0;

  function placeCalendarMenu() {
    if (!calendarMenu.matches(":popover-open")) return;
    const button = element.querySelector("#calendar-split").getBoundingClientRect();
    const gap = 8;
    const below = innerHeight - button.bottom - gap - 16;
    const above = button.top - gap - 16;
    const openBelow = below >= calendarMenu.scrollHeight || below >= above;
    calendarMenu.style.maxHeight = Math.max(48, openBelow ? below : above) + "px";
    calendarMenu.style.left = Math.max(16, Math.min(button.right - calendarMenu.offsetWidth,
      innerWidth - calendarMenu.offsetWidth - 16)) + "px";
    calendarMenu.style.top = (openBelow ? button.bottom + gap
      : Math.max(16, button.top - gap - calendarMenu.offsetHeight)) + "px";
  }

  calendarMenu.addEventListener("beforetoggle", (event) => {
    calendarToggle.setAttribute("aria-expanded", event.newState === "open");
  });
  calendarMenu.addEventListener("toggle", (event) => {
    positionListeners?.abort();
    if (event.newState !== "open") return;
    positionListeners = new AbortController();
    window.addEventListener("resize", placeCalendarMenu, { signal: positionListeners.signal });
    window.addEventListener("scroll", (event) => {
      if (event.target !== calendarMenu) placeCalendarMenu();
    }, { capture: true, signal: positionListeners.signal });
    placeCalendarMenu();
    calendarItems[firstCalendarItem].focus({ preventScroll: true });
    firstCalendarItem = 0;
  });
  calendarToggle.addEventListener("keydown", (event) => {
    if (!["ArrowDown", "ArrowUp"].includes(event.key)) return;
    event.preventDefault();
    firstCalendarItem = event.key === "ArrowUp" ? calendarItems.length - 1 : 0;
    if (calendarMenu.matches(":popover-open")) calendarItems[firstCalendarItem].focus();
    else calendarMenu.showPopover();
  });
  calendarMenu.addEventListener("keydown", (event) => {
    const current = calendarItems.indexOf(document.activeElement);
    let next;
    if (event.key === "ArrowDown") next = (current + 1) % calendarItems.length;
    else if (event.key === "ArrowUp") next = (current - 1 + calendarItems.length) % calendarItems.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = calendarItems.length - 1;
    else if (event.key === "Escape") {
      event.preventDefault();
      calendarMenu.hidePopover();
      calendarToggle.focus();
    } else if (event.key === "Tab") calendarMenu.hidePopover();
    if (next != null) {
      event.preventDefault();
      calendarItems[next].focus();
    }
  });
  calendarMenu.addEventListener("click", (event) => {
    if (event.target.closest('[role="menuitem"]')) calendarMenu.hidePopover();
  });

  const appLink = $("add-lmnaide");
  appLink.href = /Android/i.test(navigator.userAgent) ? lmnaideAppUrl(event, webUrl) : webUrl;
  $("add-google").href = googleCalendarUrl(event);
  $("add-calendar").href = downloadUrl;
  const primary = $("add-calendar-default");
  primary.href = signedIn ? webUrl : downloadUrl;
  if (fileUrl) {
    $("add-calendar").download = "event.ics";
    if (!signedIn) primary.download = "event.ics";
  }
  primary.title = signedIn ? "Review a copy in your lmnaide account" : "Open a calendar file with your calendar app";
  $("calendar-default-label").textContent = signedIn ? "Add to lmnaide" : "Add to calendar";
  $("calendar-default-icon").toggleAttribute("hidden", signedIn);
  $("calendar-default-logo").toggleAttribute("hidden", !signedIn);
  for (const link of [appLink, ...(signedIn ? [primary] : [])]) {
    link.addEventListener("click", (click) => {
      try { beforeImport?.(); }
      catch (error) { click.preventDefault(); onError?.(error); }
    });
  }
  const copyNote = $("recurring-copy-note");
  copyNote.textContent = [
    event.recurrence !== "NONE" ? "Copies include the repeat pattern, without skipped dates." : "",
    event.kind === "TASK" ? "Other calendar apps add tasks as events." : "",
  ].filter(Boolean).join(" ");
  copyNote.hidden = !copyNote.textContent;
  return { element, dispose() {
    calendarMenu.hidePopover();
    positionListeners?.abort();
    if (fileUrl) URL.revokeObjectURL(fileUrl);
  } };
}
