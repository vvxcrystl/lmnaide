# Cloud validation — October 11, 2026

All implementation work is on the `cloud` branch. Tests used dedicated throwaway accounts and the separate `dev.lmnaide.calendar.cloud` package.

## Automated checks

- Node.js: **11 tests passed**, covering account isolation/authentication/logout, idempotent retries, stale-edit history, deletion cascades, transactional validation, server restart persistence, contact stripping, and calendar recurrence/date behavior.
- Android: **55 unit tests passed per edition** (Cloud and Offline). The opt-in performance benchmark was skipped in each edition, as intended.
- Android Cloud and Offline debug APKs built. The minified Cloud release APK built, unsigned.
- Cloud and Offline lint completed with no errors; warnings remain, including existing resources/deprecations and intentional durable preference commits.
- Node.js 24 Docker image built and ran; `/health` returned success. Local and production Compose configurations validated. Caddy accepted the production HTTPS configuration.

## ADB devices

| Device | Platform | Checks |
| --- | --- | --- |
| `emulator-5554` — bb-droid | Android 16, x86_64 | Creation, received phone edits, deletion, offline queue, force-stop/restart/reconnect |
| `emulator-5556` — readme-shots | Android 16, x86_64 | Received edits, recurrence exceptions/completions, calendar deletion, received offline upload |
| `3300f68a2618a277` — Samsung SM-G903W | Android 12 / API 32 | Pulled emulator creation, edited/completed recurring task, verified cascade and offline upload |

The repeatable three-device script completed **13 instrumentation phases successfully**. These use the real app container, Room database, SQLite journal/triggers, Android Keystore, and HTTP server through ADB reverse.

Two additional race phases on the emulator and phone verified that a newer local edit survives an acknowledgement, that a conflicting response does not rebase it, and that a remote calendar deletion recovers the unsent event into a queued **Recovered edits** calendar.

The offline phase removed `tcp:3000` reverse forwarding, saved an event, verified it stayed queued when sync failed, force-stopped the app, restored forwarding, and verified that event appeared exactly once on all three devices.

A separate browser test created a one-day all-day event using the actual editor and verified its floating start/end in the Android app on all three devices (**3 additional instrumentation phases**).

## UI checks

- Android emulator: navigated Menu → Settings, filled the actual server/email/password fields, signed in, and observed “Up to date” plus the last sync time.
- Browser: account creation/sign-in, full-screen mobile editor, all-day create/edit, task completion, event details/editor interactions, calendar creation/deletion, search, and all five views.
- Final standalone browser smoke test passed against the finished UI: fresh account, calendar creation, each view, a floating one-day all-day save, search, mobile/light and desktop/dark. No page errors or horizontal overflow. T3 preview became intermittent, so the final smoke test used a local headless Chromium session.
- Browser desktop and mobile layouts, light/dark appearances, seasonal banner, solid event chips, drawer, typography and calendar controls. No horizontal overflow; bundled Google Sans Flex loaded successfully.
- Recurring events are ordered by local calendar day, with all-day items first. Inclusive all-day editor dates become exclusive floating UTC end dates on the wire.
- The physical phone's visual lock screen prevented a visual UI walkthrough; its functional checks ran successfully through Android instrumentation.

Public hosting, public DNS/certificate issuance, and a signed distribution release were not performed. The server was run locally, including in Docker, and the deployment configuration is provided in [README.md](README.md).

## Event links — October 11, 2026

- **14 Node tests passed**, including anonymous reads of explicitly shared events, account isolation, stable links on retries, field privacy, live edits, revocation/re-sharing, permanent revocation after event/calendar deletion and restoration, and share persistence after server restart.
- The browser’s actual sharing dialog created and copied a link. A fresh unauthenticated browser context opened the all-day event page at 390px/light and 1280px/dark, then displayed the unavailable state after revocation and refresh. No page errors or horizontal overflow.
- Both Android 16 emulators passed `tools/cloud/event-share-test.sh`: first device created a recurring all-day link, second device received it, updated the live notes and revoked it, first device created a fresh link and verified deletion invalidated both links.
- Native UI checks opened the Android sharing dialog, created a link, copied it, opened the system share sheet with the event title and URL, and disabled the link with **Stop sharing**. Device test builds now use a separate `.devicetest` app ID and check the signed-in account explicitly.
- Node 24 Docker tests also passed all 14 cases. Standard Cloud/Offline debug builds, the minified unsigned Cloud release, and both lint checks passed; 55 unit tests passed with one opt-in benchmark skipped per edition.
- Physical-device testing for this feature was skipped at the user’s request.

The feature was tested against the local server; public hosting/DNS remains a deployment step described in README.md.

## Web UI handoff integration — October 11, 2026

- **19 Node tests passed** after integrating `6fdc02d`, including profile validation/account isolation, the ported Android smart-parser cases, and every event-sharing regression.
- A new regression test sends all 369 web starter records (3 calendars and 366 holidays), then simulates a fresh phone sending identical records with new operation IDs and a different JSON property order. The server returns the existing versions with no conflicts, duplicate records or history entries. A later actual calendar edit remains protected from an older phone’s seed data.
- The local server restarted on port 3000 with `/api/v1/profile` available. The collaborative preview timed out repeatedly, so a fresh visual UI spot-check could not be completed in this handoff.

## Main app identity and merge preparation — October 11, 2026

- Both standard Android editions use the main app’s package, `dev.lmnaide.calendar`, and display **calander**. Version `0.0.4-alpha` / code `4` is newer than main’s code `3`. The browser and shared event pages use the same name.
- Cloud/Offline debug builds, the minified unsigned Cloud release, both lint checks and Android unit tests passed. APK metadata confirmed the package, display name and version. All 19 Node tests passed.
- An actual in-place upgrade on `emulator-5554` replaced the existing main app’s `0.0.2-alpha` build with the Cloud build. All 4 calendars, all 396 event records and every existing preference file were preserved exactly. The cloud journal was initialized for each existing record, and sync remained disconnected. The native drawer displayed **calander**.
- Device-test builds use the isolated `dev.lmnaide.calendar.devicetest` package; test scripts were updated accordingly. Creation, live edits, revocation, re-sharing and deletion passed in three instrumentation phases on the emulator with the new package. Standard APK outputs were rebuilt afterward.
- `origin/main` is an ancestor of this branch; merge readiness was checked without modifying main. The user will perform the merge.
