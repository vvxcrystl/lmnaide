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
