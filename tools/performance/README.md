# Performance checks

All changes preserve the existing animation specifications, colors, effects, and calendar layout rules.

## Run the checks

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug

# Optional host-JVM comparison. Results are in the JUnit XML system-out section.
LMNAIDE_PERF_BENCHMARK=1 ./gradlew testDebugUnitTest \
  --tests '*PerformanceBenchmarkTest' --rerun-tasks

# Run domain microbenchmarks from a debug APK on a connected Android device.
ANDROID_HOME=/path/to/Android/Sdk tools/performance/device-benchmark.sh \
  DEVICE_SERIAL app/build/outputs/apk/debug/app-debug.apk
```

The device script needs JDK, ADB, and SDK platform/build tools. It compiles a small runner, loads the actual APK classes under Android's ART runtime, and removes its temporary files on exit. It does not install the APK, access contacts, or read/write app data. An unobfuscated debug APK is required because the runner locates classes by name. Run the script separately against the baseline APK and the optimized APK on the same idle device.

Both benchmarks check output correctness before measurement, warm up five times, and report the median of eleven samples. Both use 250 daily recurring series. The host fixture uses 5,000 synthetic contacts; the device fixture uses 500 to keep the original regex-heavy implementation tractable on older phones. Pass `search` or `contacts` as the device script’s optional third argument to run one scenario. Contact measurements exclude initial index construction, which is now done once on the contact-loading worker. Host allocation counts use ThreadMXBean; device allocation counts use ART's process-wide allocation counter. These are domain microbenchmarks, not startup, frame-rate, or end-to-end input-latency measurements. ART execution in a shell process also has different compilation/warmup behavior from the installed app.

## Implemented changes

- **Recurring search:** `EventIndex.representative` used to expand five years of each matching series before discarding everything except the first result. `RecurrenceExpander.first` now short-circuits the shared lazy expansion pipeline. Recurrence exceptions, inclusive end dates, multi-day overlaps, calendar colors, and past-result fallback are retained.
- **Calendar invalidation:** `MainViewModel.index` now observes distinct `showTasks` values instead of every settings change. Theme changes, switching views, and formatting preferences no longer invalidate all expanded calendar data.
- **Schedule recomputation:** occurrence grouping is remembered separately from row labels and selection. An in-range date jump, week-start change, or midnight label update reuses the same grouped occurrences. Lazy rows declare their content type so banners, week labels, and event days reuse compatible compositions.
- **Hourly-grid recomputation:** each day's timed-event list is remembered instead of filtering again when the minute tick or draft-slot state changes.
- **Overlapping-event layout:** rightward widening uses binary search within ordered, non-overlapping columns instead of repeatedly scanning the full overlap cluster. A frozen reference implementation and 150 seeded randomized dense-day fixtures verify identical columns, spans, minimum heights, and clipping.
- **Contact matching:** regular expressions are compiled once; normalized exact names and per-word candidates are indexed once on the loading worker. A query checks the rarest word's candidates, preserving exact-match precedence, original order, punctuation handling, and ambiguous matches. Title matches are shared between automatic selection and suggestion chips.
- **Contact picker:** a keyed `LazyColumn` replaces eager composition of the entire address book. Off-screen rows no longer all start avatar-loading coroutines when the picker opens. The row styling and selection behavior are unchanged.

## Remaining opportunities identified in the audit

These are code-based findings, not measured speedups. They need separate workload profiling and behavior tests before changing their architecture.

1. **Calendar expansion still runs during composition.** Month pages, time-grid pages, and initial/extended schedule ranges call the index from `remember`. Each fresh index/range scans all visible events; the schedule starts with roughly eight months and can grow to five years. Move expansion and lane assignment to cancellable worker computations with bounded page caching/prefetch. Include timezone and visibility in invalidation, and preserve the existing date-jump/scroll behavior while new data arrives.
2. **Very dense schedule days remain eager.** The lazy list's item is a whole day; `DayRow` composes every event in that day. A day with hundreds of events still builds hundreds of cards. Flattening events into lazy rows would need to preserve the pinned date badge, now marker, stable keys, and jump targeting.
3. **Widget refreshes can overlap.** `AppContainer` listens to all settings and calls `AgendaWidgetProvider.refresh`, which launches a detached job. `collectLatest` therefore does not cancel the actual older refresh. Coalesce refresh requests and serialize publication so stale data cannot overtake newer data. `rowView` also recreates the theme palette for every row; share a single render snapshot per refresh.
4. **Reminder refresh re-queries events.** The app receives the full events snapshot, then `ReminderScheduler` calls `getAllEvents` again. Pass the received snapshot through the scheduling path while retaining fresh reads for alarm/boot/snooze entry points. Keep the scheduler mutex and cancellation semantics intact.
5. **Quick-add calendar matching scans history per edit.** `CalendarMatcher.match` filters and sorts history, and recompiles keyword patterns. An index by normalized title and precompiled calendar keywords could remove repeated work. Preserve most-specific-keyword precedence and the newest valid matching calendar.
6. **Theme-reveal GPU cost needs a trace.** The existing reveal can stack six full-screen snapshots and composite several feathered masks per frame. Its animation values already run in drawing rather than composition. Profile GPU/render-thread cost before changing rendering; preserve every circle, feather, color, timing, and rapid-change layering rule.
7. **Seasonal banner geometry is regenerated on redraw.** The month is remembered, but the drawing lambda creates seeded random values and paths each time it draws. A recorded graphics layer or cached geometry could retain identical artwork while avoiding this work. Measure first: Compose may already reuse the display list during ordinary scrolling.
