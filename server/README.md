# calander server

A separate Node.js sync server, responsive browser calendar, and optional sync for Android **calander**. The Android Cloud build uses `dev.lmnaide.calendar`, matching `main`, and updates the existing app in place with the same signing key. Existing calendars, events and settings stay in its database. Sync starts only after you connect an account in Settings.

Cloud and Offline builds are alternative editions of the same installed app. Earlier experimental `dev.lmnaide.calendar.cloud` installations have a separate database; this package change does not automatically move their local data. Their server data remains available by signing in to the same account.

## Start locally

Use Node.js 24 or later. There are no npm runtime dependencies or external database services.

```sh
cd server
npm start
```

Open **http://localhost:3000**, create an account, then sign in to that account on your other devices. Data is stored in `server/data/calendar.sqlite` by default.

Or use Docker:

```sh
cd server
docker compose up --build -d
```

The Docker data volume survives container replacement. `docker compose down` keeps it; `docker compose down -v` removes it.

## Android

From the repository root:

```sh
./gradlew assembleCloudDebug
# app/build/outputs/apk/cloud/debug/app-cloud-debug.apk

./gradlew assembleOfflineDebug
# app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

Install the Cloud APK, open **Menu → Settings → Cloud sync**, enter the server address, and sign in or create an account. The calendar UI and existing offline features are retained. Settings shows the connection, last successful sync, errors, Sync now, and Sign out.

For a USB-connected phone or emulator:

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/cloud/debug/app-cloud-debug.apk
adb -s DEVICE_SERIAL reverse tcp:3000 tcp:3000
```

Enter **http://127.0.0.1:3000** in the Android server field. The debug Cloud edition permits HTTP for development; release builds require HTTPS. Use a reachable HTTPS hostname when away from USB.

The release build is produced with `./gradlew assembleCloudRelease`. Configure your own release signing before distributing it; the repository does not contain a private signing key.

## Custom domains and HTTPS

You can host the cloud server at a subdomain such as **calander.domain.com**. At your DNS provider, create an `A` record named `calander` pointing to the Docker host's public IPv4 address. If you also publish an `AAAA` record, it must point to the host's reachable IPv6 address. Allow incoming TCP ports 80 and 443, then:

```sh
cd server
cp .env.example .env
# Edit .env: CALENDAR_DOMAIN=calander.domain.com
# Use your actual hostname, without https://, a port or a path.
docker compose -f compose.production.yaml up --build -d
```

Caddy obtains and renews the HTTPS certificate and redirects HTTP to HTTPS ([Caddy HTTPS documentation](https://caddyserver.com/docs/automatic-https)). Open `https://calander.domain.com` and enter that same address in Android Settings → Cloud sync. Browser API calls use the same origin, and event share links use the configured HTTPS domain. The calendar service is accessible through Caddy, without exposing port 3000 publicly. A public host and domain are needed to deploy it.

To change the hostname later, update its DNS record and `CALENDAR_DOMAIN` in `.env`, then rerun the production command. Keep the existing volumes to preserve accounts and events, and update the Android server address. Previously copied share links still contain the old hostname; keep that hostname working through your proxy if those links must remain reachable.

Check startup with `docker compose -f compose.production.yaml logs https calendar`, then open `https://calander.domain.com/health`. If certificate issuance fails, check DNS (including any `AAAA` record) and whether ports 80/443 reach this host.

### Behind an existing reverse proxy

If the host already runs Caddy, nginx or another HTTPS proxy, route your custom hostname to a plain Node instance instead of starting the bundled Caddy on the same ports:

```sh
cd server
HOST=127.0.0.1 CALENDAR_DOMAIN=calander.domain.com npm start
```

For example, add this site to a Caddy installation on that same host:

```caddyfile
calander.domain.com {
    reverse_proxy 127.0.0.1:3000
}
```

The proxy supplies HTTPS; Node listens on HTTP internally. `CALENDAR_DOMAIN` configures public links and does not obtain certificates itself. Plain `npm start` does not load `.env`; export the variables through your service manager or use `node --env-file=.env src.js`. Set `PUBLIC_URL` instead when the external origin needs an explicit port, for example `https://calander.domain.com:8443`.

After creating the accounts you need, set `ALLOW_REGISTRATION=false` in `.env` and rerun the production command. Existing accounts can still sign in.

For a plain Node service, environment variables are:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `3000` | HTTP listen port |
| `HOST` | `0.0.0.0` | Listen interface; use `127.0.0.1` behind a host reverse proxy |
| `DB_PATH` | `./data/calendar.sqlite` | Persistent SQLite file, relative to the working directory |
| `ALLOW_REGISTRATION` | `true` | Set exactly `false` to disable new accounts |
| `CALENDAR_DOMAIN` | empty | Custom DNS hostname, e.g. `calander.domain.com`; supplies the HTTPS origin for share links and configures Caddy in production Compose |
| `PUBLIC_URL` | empty | Explicit HTTP(S) origin for share links; overrides `CALENDAR_DOMAIN` for plain Node hosting |
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | Comma-separated proxy IPs or CIDRs allowed to supply `X-Forwarded-For`; only use addresses that cannot be reached by untrusted clients. Production Compose trusts its private Caddy network (`172.30.0.0/24`). IPv6 sources share their `/64` prefix in per-IP limits. |
| `STORAGE_QUOTA_BYTES` | `268435456` (256 MiB) | Per-account ceiling over records and the sync journal; edit history is separately capped per account. Deleting records frees space; set to 0 to lift the quota. |

For a simple consistent backup, stop the calendar service and copy the whole data directory or Docker data volume, including SQLite's WAL files, then restart. Restore with the service stopped. Keep the data volume when updating the server.

## What syncs

- Calendars: names, colors, visibility, keywords and default importance.
- Events and tasks: titles, times, floating all-day dates, notes, locations, colors, importance, reminders, repeat rules, exceptions and task completions.
- Deletions, including events removed with a calendar.

Contact names and device contact lookup URIs stay local. Appearance, display preferences, selected/default calendar and notification acknowledgement/snooze state also stay local. The server does not send reminders; Android schedules them using its synced local data. The browser includes Schedule, Day, 3 days, Week and Month views, a mini-month picker, event details/editor, search, calendar management and settings. Recurring items can be edited or deleted for one occurrence, this and following, or the entire series. Android retains its offline quick-add parser, contacts and home-screen widget.

## Sync protocol

Client authors use `POST /api/v1/sync` with a cursor and up to 1,000 changes. A change may include a `group` string of 1–250 characters. Changes with the same group in one request are atomic: if any has a stale base version that is not already identical, or an event targets a missing/deleted calendar, none of that group's changes in the request are applied. Each is saved to history as a conflict and its record ID is returned in `conflicts`. Changes without a group keep per-change conflict handling. Operation IDs remain idempotent per change; replaying an applied group is a no-op, the group is part of each operation fingerprint, and clients must keep a group in one request.

Responses include `more`. The server returns up to 500 records and at most 1 MiB of JSON, ordered by version. When `more` is true, sync again with the returned cursor and no changes if needed; continue until it is false. A page cursor advances to the highest version it contains. If no page records are returned, the cursor keeps the server's current high-water mark. Submitted records may also be echoed when space permits so clients can resolve writes and conflicts.

Sync requests are limited to 90 per signed-in account per minute (background clients poll well below that); a 429 response means to back off and retry. Each account keeps up to `STORAGE_QUOTA_BYTES` of records and sync journal; a 413 response means the cap is reached. Deleting live records (at their current version, not grouped with writes, and not of a record the same request also writes) is always accepted, so a full account can free space and sync again; a deletion grouped with writes charges the whole group. Stale, duplicate or unknown-id tombstones count against the quota like writes, as do writes overall, and retries of already-synced operations are free. Edit history and the sync journal are separately capped at the latest 10,000 entries per account.

Validation limits are shared across clients: event start/end are epoch milliseconds within 1900–2200 UTC; `recurrenceUntil`, `exceptions` and `completions` are epoch days in the same year range; reminders are 0–525,600 minutes with at most 100 entries; colors are signed 32-bit integers. Titles may be empty and are limited to 1,000 characters; descriptions to 20,000, locations to 2,000, calendar names to 1–200 non-blank characters, and keywords to 2,000. A 400 response for an invalid change includes that change's `operationId`; malformed batch-level fields such as an invalid cursor or more than 1,000 changes return only `error`.

Android keeps working without a connection. A SQLite trigger journal records writes atomically with the local edits, and retries use stable operation IDs. Changes sync after local edits, when opening the app, about every 10 seconds while it is foregrounded, and through network-constrained WorkManager background jobs with a 15-minute minimum interval. Android can delay background work for battery management. Sign out preserves the local calendar and stops sync. Its existing account binding prevents mixing two accounts' records in one local database; clear the Cloud app's storage to start with a different account.

The browser requires a connection for saving and refreshes every 10 seconds while visible. Its session lasts for the browser tab's session. A failed save keeps the editor open with the draft.

If two devices edit the same record from the same version, the first accepted version stays current. The later edit is saved in **Edit history** on the browser, where you can restore it. Older accepted versions are saved there too. Edit history and the sync operation journal are pruned at sign-in once older than 90 days, and the server keeps at most the latest 10,000 entries of each per account. A retried operation the server no longer has journaled (90-day prune or the 10,000-row cap) re-applies as a fresh operation: unchanged records hit the identical no-op path and changed records go to history as conflicts, so nothing is lost. If an unsent Android edit races with a remote calendar deletion, the app preserves it in a **Recovered edits** calendar and queues that copy for sync. The browser lists the latest 200 history entries. Tombstones prevent an old offline edit from silently resurrecting a deleted record. Restoring an event whose calendar was deleted requires restoring the calendar first.

Passwords are salted and hashed with scrypt. Sessions last 90 days, are stored as hashes on the server, and are revoked on online sign-out. Android encrypts the session token with Android Keystore and excludes the Cloud app's database/preferences from device backup/transfer, so cloned devices do not share sync identities. The server stores readable calendar data in its own database; this is not end-to-end encryption.

## Tests

```sh
cd server
npm test
```

The tests cover authentication/isolation, retries, conflicts, deletion cascades, database restarts, contact stripping, and browser recurrence parity (31sts, leap days, exceptions, exclusive end dates and daylight saving).

From the repository root:

```sh
./gradlew testCloudDebugUnitTest testOfflineDebugUnitTest lintCloudDebug lintOfflineDebug
```

For the real device suite, run the server on port 3000 and create a **dedicated test account** first. The test installs only the Cloud app/test package and creates test calendar records in that account:

```sh
export CLOUD_TEST_EMAIL='your-test-account@example.com'
export CLOUD_TEST_PASSWORD='your-test-password'
export CLOUD_TEST_FIRST='emulator-5554'
export CLOUD_TEST_SECOND='emulator-5556'
export CLOUD_TEST_PHONE='your-real-device-serial'
tools/cloud/device-sync-test.sh
```

This suite creates a recurring task on emulator one, edits/completes it on the phone, verifies it on emulator two and one, deletes its calendar, verifies the cascade on all devices, then removes ADB reverse, saves offline, force-stops the app, reconnects and verifies the queued event on all devices. It also checks edits made during a request and recovery from a remote deletion cascade on the emulator and phone. It leaves offline/recovery test events for inspection in the test account. The original offline package/data is not touched.

See [TESTING.md](TESTING.md) for the device results from this implementation.

## Public calendar

Open **Settings → Public** in the browser or Android Cloud app to create one read-only address for all your synced calendars. Anyone with the link can view it without signing in. Public uses the app's Month, Week, 3 days, Day and Schedule views, with the same date navigation, search and event details. Visitors can filter calendars locally. **Stop sharing** disables the address permanently. Creating a link again gives you a new address.

Each calendar has a **Share details publicly** toggle in Settings and its calendar editor. It starts off. Off shows event times as **[username] is busy**, or **User is busy** if you haven't set a username. On includes the calendar name, event titles, notes, locations and event/task type. Contacts, reminders, importance and task completion stay private. Hiding a calendar from your own views does not remove it from Public. Android changes appear after syncing.

Public sharing is separate from individual event links. Changing a calendar's toggle does not revoke event links you previously created.

## Share event links

In the browser, open an event → **More options → Share event → Create link**. In calander for Android, open an event and tap the **Share event** icon, then **Create link**. Copy the link or use the device’s share sheet. Anyone with the link can open a read-only page without an account.

The page includes title, dates, location and notes. Recurring events share the series, with its start dates and repeat rule clearly labelled. Timed dates display in the viewer’s time zone; all-day dates stay fixed. Calendar details, contacts, reminders, importance and task completion remain private. Edits update the shared page when it is opened or refreshed.

Recipients can use **Add to calendar** to download a calendar file, or open the arrow menu for **Google Calendar**, **lmnaide**, and **System default**. On Android, the lmnaide option opens a draft in the installed app, including the Offline edition. If the app is unavailable, it opens the browser calendar. The browser keeps the event through sign-in or account creation, then opens the editor so the recipient can choose a calendar and save. System default downloads an iCalendar file that other calendar apps can open or import.

When the current browser tab has a valid session on this server, the main button shows **Add to lmnaide** with the app logo and opens the browser editor for that account. The page verifies the session before choosing this default. Expired sessions or an unavailable session check keep the calendar-file default. Web sign-in remains scoped to the browser tab.

These actions save independent copies. Later edits or revocation do not change copies already saved. Copies include the repeat pattern and its end date, without skipped occurrences. Google Calendar and iCalendar files represent tasks as events. The download endpoint, `GET /api/v1/shared/{token}/event.ics`, checks the live share link on every request and returns 404 after revocation or deletion.

**Stop sharing** works from either client on the same account. Re-sharing creates a fresh link; revoked links never become valid again. Deleting the event or its calendar also revokes the link permanently, including if you later restore the event. Sharing is opt-in and does not publish other calendar events.

The server must be reachable by recipients. Production Compose sets the public HTTPS origin automatically. For plain Node hosting, set `PUBLIC_URL` to your public origin; otherwise the browser uses its origin and Android uses its configured server address. A localhost/ADB development link only reaches the local test server.

Two-emulator sharing checks: run `tools/cloud/event-share-test.sh` with `CLOUD_TEST_EMAIL` and `CLOUD_TEST_PASSWORD` set to a dedicated account. The script builds with `-PcloudDeviceTest=true`, installing `dev.lmnaide.calendar.devicetest` separately from calendar. The three-device sync script uses that isolated package too. Both standard editions use `dev.lmnaide.calendar`.

For the calendar-button browser check, install Playwright and its Chromium browser, then run `node tools/cloud/event-calendar-browser-test.mjs` from the repository root. It starts a temporary server with an in-memory database and checks downloads, sign-in, saving copies, all-day dates, and revocation. Set `PLAYWRIGHT_MODULE` to use an existing Playwright installation.

## Web appearance and quick add

The browser has colored themes, a desktop calendar layout, Material date/time pickers and smart quick add using the Android parser’s rules. New accounts start with Personal, Work and Holidays calendars; their stable IDs match Android’s defaults so devices share the same records.

Use the top-bar account button to add, change or remove a profile photo. Photos are stored privately per account via authenticated `/api/v1/profile`, accept JPEG/PNG/WebP data URLs up to 200,000 characters, and do not appear on shared event pages.

Accounts can have an optional unique username (3–30 letters, numbers or underscores, case-insensitive uniqueness). Set it when creating an account, or add, change or remove it from the browser account card or Android Cloud sync settings. Email remains the sign-in address. Existing accounts and sessions are preserved when the server adds username storage.
