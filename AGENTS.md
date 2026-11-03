# Instructions for agents

lmnaide Calendar has two parts:

- `app/` is the Android app (Kotlin, Jetpack Compose). It has two flavors: Cloud syncs with the server, Offline has no accounts or network access.
- `server/` is the Node.js sync server, which also serves the browser calendar from `server/public/`. It has no npm dependencies and needs Node.js 24 or later.

Read [DESIGN.md](DESIGN.md) before changing any UI or UI text. Its Writing section applies to buttons, messages and docs.

## Check your work

Server tests:

```sh
cd server
npm test
```

Android unit tests and builds, from the repository root. Create `local.properties` with `sdk.dir=<path to the Android SDK>` first if it's missing.

```sh
./gradlew testCloudDebugUnitTest testOfflineDebugUnitTest assembleCloudDebug assembleOfflineDebug
```

Put Cloud-only unit tests in `app/src/testCloud`, the folder Gradle runs for the Cloud flavor.

## Run the server locally

```sh
cd server
ACCOUNTS=local npm start
```

Open http://localhost:3000. `ACCOUNTS=local` uses lmnaide's own email and password accounts. Without it the server expects CrystlCloud settings and stops at startup.

## Set up a server

Ask the operator two things before starting:

1. The calendar's public hostname, such as `calendar.domain.com`. Its DNS must point at the host, with ports 80 and 443 open.
2. Which accounts to use:
   - **CrystlCloud Accounts**, the default. People sign in with their CrystlCloud account.
   - **Local accounts.** People create an email and password account on this server.

Then follow the steps for that choice from the `server/` directory. Use the operator's real values wherever this file shows a placeholder.

### CrystlCloud Accounts

1. Register lmnaide with CrystlCloud Accounts. This runs in the crystl-cloud-accounts checkout on the accounts server, so ask the operator to run it if you can't reach that machine:

   ```sh
   npm run client -- add "lmnaide" https://calendar.domain.com/api/v1/oidc/callback --owner you@example.com --uri https://calendar.domain.com
   ```

   It prints a client ID and a client secret. The secret is shown only once. Don't write it anywhere except `server/.env`, and don't repeat it in chat or logs.

2. Create `server/.env` from `.env.example` and set:

   ```sh
   CALENDAR_DOMAIN=calendar.domain.com
   OIDC_CLIENT_ID=<client ID>
   OIDC_CLIENT_SECRET=<client secret>
   ```

   For another OpenID Connect issuer, also set `OIDC_ISSUER`, `OIDC_NAME` and `OIDC_ACCOUNT_URL`.

### Local accounts

Create `server/.env` from `.env.example` and set:

```sh
CALENDAR_DOMAIN=calendar.domain.com
ACCOUNTS=local
```

After the operator has created the accounts they need, set `ALLOW_REGISTRATION=false` and restart.

### Start and verify

```sh
docker compose -f compose.production.yaml up --build -d
docker compose -f compose.production.yaml logs calendar
curl https://calendar.domain.com/health
curl https://calendar.domain.com/api/v1/config
```

- `/health` returns `{"ok":true,...}`.
- `/api/v1/config` returns `"oidc":{"name":"CrystlCloud",...}` for CrystlCloud and `"oidc":null` for local accounts.
- If the calendar service exits, its log names the missing setting and how to fix it.

For hosts that already run a reverse proxy, and for every environment variable, see [server/README.md](server/README.md).

## Test sign-in with CrystlCloud locally

The crystl-cloud-accounts repository has a Docker test stack. Its `dev/up.sh` starts CrystlCloud Accounts on :3300 and lmnaide on :3301, and registers lmnaide for you. It builds lmnaide from the path in `LMNAIDE_DIR` in its `dev/.env`.

After changing lmnaide, rebuild only lmnaide so the other containers keep running:

```sh
cd <crystl-cloud-accounts>/dev
docker compose --env-file .env up -d --build --no-deps lmnaide
```

## Rules for account code

- Never match or merge accounts by email. An issuer account links to an lmnaide user only through the `identities` table, and an existing user connects it while signed in.
- Never put a bearer token or client secret in a URL. OIDC sign-in hands clients a single-use code, which they swap with a verifier.
- Keep cloud code out of the Offline flavor. It belongs in `app/src/cloud`, with a no-op stub in `app/src/offline` when shared code calls it.
- Tests that start the server without OIDC settings must pass `accounts: "local"` to `createApp`.
