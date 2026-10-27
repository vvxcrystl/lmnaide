# Calendar API reference

The calendar API operates on the signed-in account's calendars, events, and tasks. Its writes use the same validation, version counter, history, storage quota, and deletion handling as device sync. Changes appear on connected clients at their next sync.

## Authentication

`POST /api/v1/login` accepts `{"email":"you@example.com","password":"your password"}` and returns a `token`. Every calendar API request requires `Authorization: Bearer TOKEN`. A missing, expired, or revoked token returns `401`.

Tokens last 90 days. A script can sign in separately to obtain its own session. `POST /api/v1/logout` with that token revokes that session. Production requests use the server's HTTPS address.

## Resources

Both resource types support these routes:

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/v1/calendars` or `/api/v1/events` | A page of live records |
| `GET` | `/api/v1/calendars/{id}` or `/api/v1/events/{id}` | One live record, or `404` |
| `POST` | `/api/v1/calendars` or `/api/v1/events` | A new record, with status `201` |
| `PATCH` | `/api/v1/calendars/{id}` or `/api/v1/events/{id}` | A partial update, with status `200` |
| `DELETE` | `/api/v1/calendars/{id}` or `/api/v1/events/{id}` | A tombstone, with status `200` |

Request bodies are JSON objects. Record IDs in paths are URL-encoded. Unsupported methods return `405`.

Single-record responses have the same shape as sync records:

```json
{
  "kind": "event",
  "id": "meeting-2026-10-22",
  "version": 42,
  "deleted": false,
  "data": {
    "calendarId": "personal",
    "title": "Planning",
    "description": "",
    "location": "",
    "start": 1792677600000,
    "end": 1792681200000,
    "allDay": false,
    "color": null,
    "recurrence": "NONE",
    "recurrenceUntil": null,
    "exceptions": [],
    "reminders": [],
    "kind": "EVENT",
    "completions": [],
    "importance": null,
    "createdProgrammatically": true
  }
}
```

### List records

`GET /api/v1/events?limit=100&cursor=0&calendarId=personal` returns:

```json
{"records": [], "nextCursor": 0, "more": false}
```

`limit` defaults to 100 and accepts 1–500. Pages also have a 1 MiB response limit. `cursor` defaults to 0 and is a non-negative version number. Records are ordered by version. When `more` is true, the next page uses `nextCursor` as its `cursor`. `calendarId` optionally filters events by calendar. Calendar lists use the same pagination without the filter.

Lists contain live records. `POST /api/v1/sync` supplies incremental changes, including deletions, for clients that maintain a local replica.

### Create a calendar

```sh
curl "$CALENDAR_URL/api/v1/calendars" \
	-H "Authorization: Bearer $CALENDAR_TOKEN" \
	-H 'Content-Type: application/json' \
	-d '{"name":"Automation","id":"automation"}'
```

`name` is required. Optional fields are `id`, `color`, `visible`, `keywords`, and `defaultImportance`. Defaults are a blue color, visible, empty keywords, and no default importance. An omitted `id` receives a generated UUID. IDs accept 1–200 characters, excluding the path segments `.` and `..`. An existing ID, including a deleted record's ID, returns `409`.

### Create an event

```sh
curl "$CALENDAR_URL/api/v1/events" \
	-H "Authorization: Bearer $CALENDAR_TOKEN" \
	-H 'Content-Type: application/json' \
	-d '{"id":"meeting-2026-10-22","calendarId":"automation","title":"Planning","start":1792677600000,"end":1792681200000,"reminders":[10]}'
```

`calendarId`, `start`, and `end` are required. The calendar must exist in the same account. Dates are epoch milliseconds within 1900–2200 UTC, and `end` is exclusive. `end` can equal `start`. All-day events use UTC midnight for floating dates, with the following midnight as the exclusive end.

Optional fields and defaults match the response example. `id` follows the calendar creation rules. A caller-supplied stable ID prevents duplicate events if a create response is lost. Retrying that ID returns `409`; `GET /api/v1/events/{id}` retrieves the existing record.

`kind` accepts `EVENT` or `TASK`. `importance` accepts `LOW`, `MEDIUM`, `HIGH`, or `null`. `recurrence` accepts `NONE`, `DAILY`, `WEEKDAYS`, `WEEKLY`, `MONTHLY`, or `YEARLY`. `recurrenceUntil`, `exceptions`, and `completions` use epoch days. `reminders` contains up to 100 offsets of 0–525,600 minutes. Colors are signed 32-bit ARGB integers or `null` for an event's calendar color.

### Update or delete a record

`PATCH` accepts the fields to change and the record's current `version`:

```sh
curl -X PATCH "$CALENDAR_URL/api/v1/events/meeting-2026-10-22" \
	-H "Authorization: Bearer $CALENDAR_TOKEN" \
	-H 'Content-Type: application/json' \
	-d '{"version":42,"title":"Revised planning"}'
```

`DELETE` accepts `{"version":42}`. A stale version returns `409` without replacing the current record. A missing or invalid version returns `400`. A deleted or unknown record returns `404`. Deleting a calendar also deletes its events and revokes their share links.

### Event origin

Events created with `POST /api/v1/events` receive server-owned `data.createdProgrammatically: true`. The browser and Android show a small **API** tag. Pressing the tag displays "This event was added programmatically through the calendar API."

Edits from the API or device sync preserve the tag. API updates to events originally created in the app do not add it. Supplying or clearing the field in a request cannot change an event's recorded origin.

## Errors and limits

Errors return `{"error":"message"}`. Invalid fields or JSON return `400`; account storage limits return `413`. Each account has a limit of 90 calendar API requests per minute, including reads. Writes also count toward the existing sync limit of 90 requests per minute. A `429` response means the client must back off and retry later.

Titles accept up to 1,000 characters, descriptions 20,000, and locations 2,000. Calendar names accept 1–200 non-blank characters, and keywords accept up to 2,000. Contact names and device contact lookup URIs remain local to the device.
