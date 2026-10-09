<img src="assets/icon.svg" width="64" height="64" alt="">

# lmnaide Calendar

A clean, offline Android calendar built with Jetpack Compose and Material 3. It follows Google Calendar's features and design: Material You dynamic color (with Google's GM3 blue baseline as the fallback), Google Sans Flex type, solid color event chips, pill-shaped navigation, and illustrated seasonal month banners in the schedule.

Created in [T3 Code](https://t3.codes).

## Features

- **Five views:** Schedule, Day, 3 days, Week and Month. Swipe between periods, tap the month title for a quick date picker, and use the "today" button to jump back.
- **Events:** title, all-day or timed, multi-day, location (opens in maps), description, per-event color, and calendar.
- **Tasks:** create from the + menu or switch any item between Event and Task. Tasks have a due date with an optional time, can repeat, and are checked off per occurrence from the list, the task page or the notification. A drawer toggle shows or hides them.
- **Importance:** mark any event or task Low (green), Medium (yellow) or High (red). Each level reminds differently:
  - **Low:** silent notifications that wait in the shade.
  - **Medium:** standard alert with sound and a Snooze button.
  - **High:** urgent alert with strong vibration, an extra alert when it starts or is due, then repeats every 5 minutes (up to 3 times) until you tap Got it, snooze, open it or mark the task done.
- **Repeating events:** daily, every weekday, weekly, monthly and yearly, with an optional end date. Edit or delete *this event*, *this and following*, or *all events*.
- **Reminders:** multiple notifications per event, delivered with exact alarms. They are rescheduled after reboots and time zone changes.
- **Calendars:** create, rename, recolor and delete calendars, and show or hide each one from the drawer.
- **Search** across titles, locations and descriptions.
- **Settings:** light, dark or system theme; dynamic (wallpaper) color, on by default; week start; 24-hour time; default calendar, duration and notification.
- Overlapping events sit side by side, and multi-day events span across days. Past events are dimmed, and a line marks the current time.

## Building

Requirements: JDK 17 (Gradle provisions it from `gradle/gradle-daemon-jvm.properties`) and the Android SDK with platform 37.

```sh
./gradlew assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                   # install on a connected device or emulator
```

The minimum Android version is 8.0 (API 26).

## Structure

```
app/src/main/java/dev/lmnaide/calendar/
├── data/        Room entities, DAOs, repository, settings (SharedPreferences)
├── domain/      Occurrence model, recurrence expansion, event index
├── reminders/   Exact-alarm scheduler and broadcast receiver
└── ui/
    ├── main/        Drawer, top bar, month / time grid / schedule views, mini month
    ├── event/       Event details and editor
    ├── search/      Search
    ├── settings/    Settings
    ├── calendars/   Calendar management
    ├── common/      Shared components, dialogs, formatting
    └── theme/       Material 3 color scheme and typography
```

Timed events store UTC instants. All-day events store "floating" dates, so they stay on the same day when you change time zones. Recurring events are stored once and expanded into occurrences for whatever range is on screen.

## Third-party assets

Google Sans Flex is bundled under the SIL Open Font License; see `third_party/google-sans-flex/OFL.txt`.
