# Calendar Cloud

A separate Node.js server, responsive browser calendar, and Android Cloud edition on the `cloud` branch. The Android edition installs as **Calendar Cloud** (`dev.lmnaide.calendar.cloud`), alongside the original offline app. Each has its own database. Existing offline-app data is not automatically copied into the Cloud edition.

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

## Deploy with HTTPS

On a Docker host, point a DNS hostname at that host, allow incoming ports 80 and 443, then:

```sh
cd server
cp .env.example .env
# Edit CALENDAR_DOMAIN in .env to your real hostname.
docker compose -f compose.production.yaml up --build -d
```

Caddy obtains and renews the HTTPS certificate ([Caddy HTTPS documentation](https://caddyserver.com/docs/quick-starts/https)). Open `https://YOUR_HOSTNAME` and use that same address in Android Settings. The calendar service is accessible through Caddy, without exposing port 3000 publicly. This configuration has been validated locally; a public host and domain are needed to deploy it.

After creating the accounts you need, set `ALLOW_REGISTRATION=false` in `.env` and rerun the production command. Existing accounts can still sign in.

For a plain Node service, environment variables are:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `3000` | HTTP listen port |
| `HOST` | `0.0.0.0` | Listen interface; use `127.0.0.1` behind a host reverse proxy |
| `DB_PATH` | `./data/calendar.sqlite` | Persistent SQLite file, relative to the working directory |
| `ALLOW_REGISTRATION` | `true` | Set exactly `false` to disable new accounts |

For a simple consistent backup, stop the calendar service and copy the whole data directory or Docker data volume, including SQLite's WAL files, then restart. Restore with the service stopped. Keep the data volume when updating the server.

## What syncs

- Calendars: names, colors, visibility, keywords and default importance.
- Events and tasks: titles, times, floating all-day dates, notes, locations, colors, importance, reminders, repeat rules, exceptions and task completions.
- Deletions, including events removed with a calendar.

Contact names and device contact lookup URIs stay local. Appearance, display preferences, selected/default calendar and notification acknowledgement/snooze state also stay local. The server does not send reminders; Android schedules them using its synced local data. The browser includes Schedule, Day, 3 days, Week and Month views, a mini-month picker, event details/editor, search, calendar management and settings. Recurring items can be edited or deleted for one occurrence, this and following, or the entire series. Android retains its offline quick-add parser, contacts and home-screen widget.

Android keeps working without a connection. A SQLite trigger journal records writes atomically with the local edits, and retries use stable operation IDs. Changes sync after local edits, when opening the app, about every 10 seconds while it is foregrounded, and through network-constrained WorkManager background jobs with a 15-minute minimum interval. Android can delay background work for battery management. Sign out preserves the local calendar and stops sync. Its existing account binding prevents mixing two accounts' records in one local database; clear the Cloud app's storage to start with a different account.

The browser requires a connection for saving and refreshes every 10 seconds while visible. Its session lasts for the browser tab's session. A failed save keeps the editor open with the draft.

If two devices edit the same record from the same version, the first accepted version stays current. The later edit is saved in **Edit history** on the browser, where you can restore it. Older accepted versions are saved there too. If an unsent Android edit races with a remote calendar deletion, the app preserves it in a **Recovered edits** calendar and queues that copy for sync. The browser lists the latest 200 history entries. Tombstones prevent an old offline edit from silently resurrecting a deleted record. Restoring an event whose calendar was deleted requires restoring the calendar first.

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
