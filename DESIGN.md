# Design

How lmnaide Calendar looks, behaves and speaks, and why. Read this before changing UI. When code and this document disagree, fix one of them.

## Direction

**Google Calendar's Material 3, made functional rather than flashy.** The app should feel at home next to Google's own apps on a Pixel. Use Google's colors, type, shapes and patterns, and don't invent a visual language. Only one element is allowed to be expressive: the seasonal month banners in Schedule view. Everything else stays quiet so that the events supply the color.

The project started out styled after T3 Chat (a magenta accent, plum-tinted neutrals, a sidebar with a "New event" button). That looked too bland next to the system, so it moved to Google's style. Don't bring the T3-era styling back.

## Principles

1. **Events are the color.** The interface is neutral white, grey and blue. Saturated color belongs only to events, calendars and importance, so color always means something.
2. **Spend boldness in one place.** The month banners are the one decorative element. Don't add gradients, shadows, illustrations or motion anywhere else just for decoration.
3. **Follow the platform.** Use stock Material 3 components and default behaviors before building custom ones. Dynamic color is on by default.
4. **Every mark carries information.** Dots, pills, check circles, fading and strikethrough each encode a state (importance, task, completed, past). Don't use them as decoration.
5. **Act in place.** Common actions happen where you are: tick a task in the list, tap an empty hour to create, snooze from the notification. Full screens are for full editing.
6. **Plain words.** Sentence case, active verbs, no jargon. See [Writing](#writing).

## Color

### Scheme

Material You dynamic color is on by default on Android 12 and later. When it's off or unavailable, the app uses Google's GM3 baseline (`ui/theme/Theme.kt`).

| Role | Light | Dark |
|---|---|---|
| primary | `#0B57D0` | `#A8C7FA` |
| onPrimary | `#FFFFFF` | `#062E6F` |
| primaryContainer | `#D3E3FD` | `#0842A0` |
| secondaryContainer (selected nav pill) | `#C2E7FF` | `#004A77` |
| surface / background | `#FFFFFF` | `#131314` |
| surfaceContainerLow (drawer) | `#F8FAFD` | `#1B1B1B` |
| surfaceContainer | `#F0F4F9` | `#1E1F20` |
| surfaceContainerHigh (FAB) | `#E9EEF6` | `#282A2C` |
| onSurface (ink) | `#1F1F1F` | `#E3E3E3` |
| onSurfaceVariant | `#444746` | `#C4C7C5` |
| outlineVariant (grid lines, dividers) | `#C4C7C5` | `#444746` |
| error | `#B3261E` | `#F2B8B5` |

Always use theme roles in Compose. Never hard-code chrome colors, because dynamic color has to be able to replace them.

### Event palette

Google Calendar's eleven event colors (`ui/common/EventColors.kt`): Tomato `#D50000`, Flamingo `#E67C73`, Tangerine `#F4511E`, Banana `#F6BF26`, Sage `#33B679`, Basil `#0B8043`, Peacock `#039BE5`, Blueberry `#3F51B5`, Lavender `#7986CB`, Grape `#8E24AA`, Graphite `#616161`.

- **Text on a chip:** dark ink `#1F1F1F` when the background's luminance is above 0.55, otherwise white.
- **Past and completed items** blend 55% toward the surface color. Their text becomes ink at 75% opacity when the faded background's luminance is above 0.3, otherwise white at 75%.
- **Open tasks never fade,** even when overdue. Something you still owe shouldn't recede.

### Importance

Importance is the one place where color carries an urgency meaning, so these three colors are reserved for it.

| Level | Color | Meaning |
|---|---|---|
| Low | Google green `#34A853` | Nice to know |
| Medium | Google yellow `#FBBC04` | Normal |
| High | Google red `#EA4335` | Don't miss this |

No importance is a valid state: an item with none shows no mark. Never use these three colors for anything else (for example as an error color or a decoration), and never show importance by color alone. It always comes with a label or a dedicated shape.

## Typography

**Google Sans Flex** (bundled, SIL OFL) is the only typeface. The weight and optical-size axes do all the work, so there is no second family and no monospace.

| Style | Size / line | Weight | Used for |
|---|---|---|---|
| headlineSmall | 24 / 32 | Regular | Event page title, editor title field, quick-add input, banner month (Medium) |
| titleLarge | 22 / 28 | Regular | Top app bar, drawer wordmark |
| titleMedium | 16 / 24 | Medium | Section emphasis |
| titleSmall | 14 / 20 | Medium | Card titles, drawer section labels |
| bodyLarge | 16 / 24 | Regular | Editor rows, detail rows, settings |
| bodyMedium | 14 / 20 | Regular | Secondary lines |
| bodySmall | 12 / 16 | Regular | Card time and location |
| labelLarge | 14 / 20 | Medium | Buttons, drawer items |
| labelSmall | 11 / 16 | Medium | Weekday headers, hour labels |

Chips in dense grids use fixed sizes: 13sp for grid blocks (11sp when compact), 12sp for all-day bars, and 10–11sp in the month grid.

Rules:
- Sentence case everywhere: "Thu", not "THU". No all-caps labels and no tracked-out eyebrows.
- Don't accent a single word with weight or color.
- Titles are Regular weight. Medium is for labels and emphasis.

## Shape

| Token | Radius | Used for |
|---|---|---|
| extraSmall | 4dp | All-day bars, month chips |
| small | 8dp | Hour-grid event blocks (6dp when compact) |
| medium | 12dp | Schedule and search cards |
| large | 16dp | FAB, settings groups |
| extraLarge | 28dp | Dialogs, sheets |
| — | 24dp | Month banners |
| full (pill) | 50% | Drawer selection, chips, importance pills, buttons, create menu options |

Radius follows hierarchy: the denser and smaller the element, the tighter the corner. Don't apply one radius to everything.

## Layout and spacing

- **Grid:** 4dp base. Common gaps are 4, 8, 12, 16, 20 and 24dp.
- **Content indent:** detail and editor rows use a 20dp margin, a 42dp icon column, then content at 62dp from the edge. Titles and fields without icons align to that 62dp line.
- **Minimum touch target:** 48dp. Rows are at least 48–52dp tall.
- **Left-aligned** text throughout, except for numbers and labels inside grid cells.

### Calendar views

| View | Key measurements |
|---|---|
| Hour grid (Day, 3 days, Week) | 56dp per hour, 52dp hour gutter, 22dp all-day lanes. Lanes collapse to 2 plus "+N" when there are more than 3 |
| Month | 26dp date header, 17dp chips with 1dp gaps. When a cell overflows, its last row becomes "+N" |
| Schedule | 52dp date column, 112dp month banners, 6dp gaps between cards |
| Mini month | 40dp rows, 32dp date circles, 4dp event dots |

- **Overlapping events** sit side by side in columns, and widen into free columns to their right.
- **Multi-day and all-day items** span across days as bars.
- **Today** gets a filled primary circle behind the date. A selected date uses primaryContainer.
- **Now line:** 2dp primary line with a 10dp dot, on today only.
- **Compact mode:** grid columns narrower than 64dp switch to smaller text and show only the start time.

## Components

### Event chips (`ui/common/EventBlocks.kt`)

Chips are solid fills in the event's color with contrasting text, like Google's.

| Component | Where | Contents |
|---|---|---|
| `GridEventBlock` | Hour grid | Title (up to 4 lines when there's room), time when the block is at least 40dp tall (56dp compact) |
| `EventBar` | All-day lanes, month grid | One clipped line |
| `EventCard` | Schedule, search | Title, time, location on its own line, importance pill on the right |

Tasks add a **check circle** in front of the title: an outline when open, filled when done. Done tasks are struck through and faded. Importance shows as a **9dp dot ringed in the chip's text color** on blocks and bars (so it stays visible on a same-colored chip), and as a **labeled pill** on cards and the event page.

### Month banners (`ui/main/MonthBanner.kt`)

The signature element. Each month header in Schedule view is a 112dp, 24dp-radius card with a flat seasonal landscape drawn in code, seeded by month so it's stable but varies:

| Season | Months | Scene |
|---|---|---|
| Winter | Dec–Feb | Pale sky, snow hills, pines, snowflakes |
| Spring | Mar–May | Mint sky, green hills, sun, flowers |
| Summer | Jun–Aug | Warm sky, haloed sun, sea with waves, sand |
| Autumn | Sep–Nov | Peach sky, rust hills, a red tree, falling leaves |

The month name sits top-left in headlineSmall Medium ink. Decoration never overlaps it: leaves and similar elements stay to its right. The art is flat shapes only, with no gradients, and is identical in light and dark themes. Don't put banners anywhere else.

### Create button

A 56dp FAB with a 16dp radius on surfaceContainerHigh, carrying Google's four-color plus (red `#EA4335`, blue `#4285F4`, green `#34A853`, yellow `#FBBC04`). Tapping it opens **Event** and **Task** as pill-shaped extended FABs in primaryContainer above it, with a 32% scrim behind them. While open, the FAB turns primary and shows ×.

### Quick add (`ui/event/QuickAddSheet.kt`)

A modal bottom sheet with one large headlineSmall input ("What, and when?" / "What needs doing, and by when?"):
- **Event / Task chips** at the top. The wording picks one ("by Friday" means a task) until you tap a chip.
- **A live preview** below the input: the title, when, repeat, and which calendar it will go into and why. With no input, scrollable example chips appear instead. If the input can't be understood, a muted row says what's missing.
- **Actions:** "More options" (text button) on the left, "Save" (filled) on the right. The keyboard's Done key also saves.

### Navigation drawer

surfaceContainerLow background. A primary calendar glyph with a "Calendar" wordmark, then:
1. Views, with a pill-shaped secondaryContainer indicator on the selected one.
2. A divider, then "Calendars": checkboxes tinted with each calendar's color, plus "Tasks".
3. "Manage calendars".
4. A divider, then "Settings".

There's no create button in the drawer; creating starts from the FAB.

### Top app bar

Menu button, then the period title ("October", "Sep – Oct", "Oct 2027") with a drop-down arrow that opens the mini month. Search and the today button (a rounded square showing today's date) sit on the right.

### Editor

Full screen, with × on the left and a filled pill "Save" on the right. The title field is borderless headlineSmall ("Add title"). Rows are icon plus content, grouped by dividers:

1. Event / Task chips
2. All-day toggle, start, end (hidden for tasks), repeat
3. Importance chips: None, Low, Medium, High. Each has its color dot and fills with its color when selected, with a one-line hint about how it will notify
4. Location (events only)
5. Notifications
6. Calendar and color
7. Description

Pickers use M3 dialogs. Choices that close as soon as you pick are radio dialogs.

### Event page

A colored square, the title in headlineSmall, then when it happens, the repeat rule, importance (a flag icon in its color, the level, and what it does), location (taps open maps), notifications, calendar and description. Tasks show "Task" or "Task, completed" and a tonal "Mark completed" / "Mark uncompleted" button.

### Settings

Grouped surfaces with a 16dp radius on surfaceContainerLow, with primary titleSmall section labels. Theme uses an M3 segmented button.

### Home screen widget (`widget/`)

A RemoteViews agenda that matches the app without Compose:
- 24dp-radius panel: `#F1F3F4` in light, `#303030` in dark. It follows the app's theme setting, not the launcher's.
- A month title and a "+" shortcut in the header.
- 42dp date badges, filled primary for today.
- 12dp-radius cards tinted with the calendar color, with contrasting text and an 18dp importance icon. Pending tasks appear on a neutral card.

## Motion

Motion only answers a person's action:
- Drawer and sheet slides, and the mini month expanding under the title (with the arrow rotating 180°).
- The create menu fading and expanding from the FAB.
- Pager swipes between periods, animated only for adjacent periods. Long jumps cut straight to the target.
- Quick-add preview states crossfade.

There are no entrance animations, no scroll-triggered effects and no looping motion.

## Notifications

Importance controls how reminders behave, not just how they look. Each level has its own Android notification channel so you can adjust them in system settings.

| Level | Channel | Behavior |
|---|---|---|
| Low | `importance_low` (IMPORTANCE_LOW) | Silent and no pop-up. Waits in the shade |
| Medium, or none | `importance_medium` (IMPORTANCE_HIGH) | Sound and pop-up. "Snooze 10 min" button |
| High | `importance_high` (IMPORTANCE_HIGH) | Strong vibration (600ms ×3), red accent, title prefixed "High importance:". An extra alert at the start or due time, then repeats every 5 minutes up to 3 times |

High-importance repeats stop on "Got it", Snooze, opening the notification, or "Mark done". Swiping the notification away doesn't stop them, deliberately. Task notifications always offer "Mark done".

Copy follows the same voice: "Today, 9 PM – 10 PM", "Due now", "Starting now", "Still not done, was due at 8:50 PM".

## Writing

- **Name things by what people do:** "New event", "Mark completed", "Manage calendars". Avoid system nouns.
- **A button says exactly what happens,** and the same action keeps the same name across the flow: "Delete" leads to "Delete event?".
- **Empty states point to an action or explain:** "Nothing planned today", "Search by title, location, or description", "No events match “dentist”".
- **Errors say what's wrong in the interface's voice,** without apologizing: "The event ends before it starts".
- **Separate metadata with commas or line breaks.** Don't join it with middle dots. Time and location go on separate lines.
- **Use "(No title)"** for untitled items, never an empty string.
- **Use real ranges with an en dash:** "9:30 AM – 9:45 AM". In 12-hour time, drop ":00" ("3 PM").

## Accessibility

- Text on chips is picked for contrast against the actual (possibly faded) background.
- Importance is never shown by color alone. It always has a label, a dot shape, or a named icon.
- Icons that do something have content descriptions ("Open menu", "New event", "Remove notification"). Decorative icons have none.
- Touch targets are at least 48dp. Tiny month chips aren't targets themselves: tapping a month cell opens that day.
- Dark theme is a first-class design, not an inversion: it uses GM3 dark roles and a `#131314` surface.

## Don't

- Don't add gradients, glows, drop shadows on cards, or decorative illustrations outside the month banners.
- Don't use all-caps labels, tracked-out eyebrows, or monospace data labels.
- Don't join metadata with "·", or append "→" to buttons and links.
- Don't use the importance colors for anything but importance.
- Don't use tinted or translucent event blocks with accent stripes (the T3-era style). Chips are solid.
- Don't put a second create button anywhere other than the FAB, the empty-slot "+ New event" in the hour grid, and the widget's "+".

## Known deviations

- The quick-add preview and its confirmation snackbar still join parts with "·" ("Tomorrow · 3 – 5 PM", "Event added · …"), in `QuickAddSheet.kt` `whenLabel` and `save`. These should become commas to match [Writing](#writing).
- The widget hard-codes its RGB colors because RemoteViews can't read the Compose theme. Keep them in sync with the GM3 table above by hand.
