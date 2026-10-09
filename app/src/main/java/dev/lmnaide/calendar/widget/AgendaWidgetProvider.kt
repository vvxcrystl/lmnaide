package dev.lmnaide.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.lmnaide.calendar.MainActivity
import dev.lmnaide.calendar.R
import dev.lmnaide.calendar.container
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.data.ThemeMode
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.domain.RecurrenceExpander
import dev.lmnaide.calendar.ui.common.Fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AgendaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refreshBroadcast(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { refreshBroadcast(context) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in listOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) refreshBroadcast(context)
    }
    private fun refreshBroadcast(context: Context) {
        val pending = goAsync()
        refresh(context) { pending.finish() }
    }
    companion object {
        fun refresh(context: Context, onFinished: () -> Unit = {}) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AgendaWidgetProvider::class.java))
            if (ids.isEmpty()) { onFinished(); return }
            context.container.appScope.launch {
                try {
                val rows = loadRows(context)
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.widget_agenda)
                    val palette = widgetPalette(context)
                    views.setInt(android.R.id.background, "setBackgroundResource", palette.background)
                    views.setTextColor(R.id.widget_month, palette.foreground)
                    views.setTextColor(R.id.widget_empty, palette.secondary)
                    views.setInt(R.id.widget_add, "setBackgroundResource", palette.addBackground)
                    views.setTextColor(R.id.widget_add, palette.addForeground)
                    if (followsSystem(context)) {
                        views.setColorStateList(android.R.id.background, "setBackgroundTintList", ColorStateList.valueOf(Color.rgb(241, 243, 244)), ColorStateList.valueOf(Color.rgb(48, 48, 48)))
                        views.setColorInt(R.id.widget_month, "setTextColor", Color.rgb(31, 31, 31), Color.rgb(238, 238, 238))
                        views.setColorInt(R.id.widget_empty, "setTextColor", Color.rgb(68, 71, 70), Color.rgb(204, 204, 204))
                        views.setColorStateList(R.id.widget_add, "setBackgroundTintList", ColorStateList.valueOf(Color.rgb(211, 227, 253)), ColorStateList.valueOf(Color.WHITE))
                        views.setColorInt(R.id.widget_add, "setTextColor", Color.rgb(4, 30, 73), Color.rgb(32, 32, 32))
                    }
                    views.setTextViewText(R.id.widget_month, LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM")))
                    val open = Intent(context, MainActivity::class.java)
                    views.setOnClickPendingIntent(R.id.widget_month, PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    views.setOnClickPendingIntent(R.id.widget_add, PendingIntent.getActivity(context, 1, Intent(context, WidgetQuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    if (Build.VERSION.SDK_INT >= 31) {
                        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(false).setViewTypeCount(1)
                        rows.forEachIndexed { index, row -> items.addItem(index.toLong(), rowView(context, row)) }
                        views.setRemoteAdapter(R.id.widget_list, items.build())
                    } else {
                        views.setRemoteAdapter(R.id.widget_list, Intent(context, AgendaWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(android.net.Uri.parse("agenda://$id")))
                    }
                    views.setEmptyView(R.id.widget_list, R.id.widget_empty)
                    manager.updateAppWidget(id, views)
                    if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
                }
                } finally { onFinished() }
            }
        }
    }
}

private fun followsSystem(context: Context) = Build.VERSION.SDK_INT >= 31 && context.container.settings.settings.value.themeMode == ThemeMode.SYSTEM

/** Resolve the app preference rather than relying on the launcher's theme. */
private data class WidgetPalette(
    val background: Int,
    val foreground: Int,
    val secondary: Int,
    val addBackground: Int,
    val addForeground: Int,
    val todayBackground: Int,
    val todayForeground: Int,
    val tasksBackground: Int,
    val tasksColor: Int,
)

private fun widgetPalette(context: Context): WidgetPalette {
    val dark = when (context.container.settings.settings.value.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    return if (dark) WidgetPalette(
        R.drawable.widget_background, Color.rgb(238, 238, 238), Color.rgb(204, 204, 204),
        R.drawable.widget_today, Color.rgb(32, 32, 32), R.drawable.widget_today, Color.BLACK,
        R.drawable.widget_tasks, Color.rgb(41, 41, 41),
    ) else WidgetPalette(
        R.drawable.widget_background_light, Color.rgb(31, 31, 31), Color.rgb(68, 71, 70),
        R.drawable.widget_add_light, Color.rgb(4, 30, 73), R.drawable.widget_today_light, Color.WHITE,
        R.drawable.widget_tasks_light, Color.rgb(227, 231, 235),
    )
}

internal data class AgendaRow(val date: LocalDate, val showDate: Boolean, val occurrence: Occurrence? = null, val tasks: Int = 0, val importance: Importance? = occurrence?.event?.importance)

internal suspend fun loadRows(context: Context): List<AgendaRow> {
    val today = LocalDate.now()
    val calendars = context.container.repository.calendars.first().filter { it.visible }.associateBy { it.id }
    val settings = context.container.settings.settings.value
    val occurrences = context.container.repository.getAllEvents().flatMap { event ->
        val calendar = calendars[event.calendarId] ?: return@flatMap emptyList()
        RecurrenceExpander.expand(event, event.color ?: calendar.color, calendar.name, today, today.plusDays(30))
    }.filter { !it.completed && (!it.isTask || settings.showTasks) && (it.allDay || it.end >= LocalDateTime.now() || it.isTask) }.sortedBy { it.start }
    val rows = mutableListOf<AgendaRow>()
    var previous: LocalDate? = null
    val tasks = occurrences.count { it.isTask && it.startDate <= today }
    if (tasks > 0) { rows += AgendaRow(today, true, tasks = tasks, importance = occurrences.filter { it.isTask && it.startDate <= today }.mapNotNull { it.event.importance }.maxByOrNull { it.ordinal }); previous = today }
    occurrences.filterNot { it.isTask && it.startDate <= today }.take(100).forEach {
        val date = maxOf(today, it.startDate)
        rows += AgendaRow(date, date != previous, it)
        previous = date
    }
    return rows
}

internal fun rowView(context: Context, row: AgendaRow): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_agenda_row)
    val palette = widgetPalette(context)
    val today = row.date == LocalDate.now()
    views.setTextViewText(R.id.widget_date, if (!row.showDate) "" else row.date.format(DateTimeFormatter.ofPattern(if (today) "EEEEE\nd" else "EEE\nd")))
    views.setInt(R.id.widget_date, "setBackgroundResource", if (today && row.showDate) palette.todayBackground else 0)
    views.setTextColor(R.id.widget_date, if (today) palette.todayForeground else palette.foreground)
    val occurrence = row.occurrence
    val color = occurrence?.color ?: palette.tasksColor
    val importance = row.importance
    views.setViewVisibility(R.id.widget_importance, if (importance == null) View.GONE else View.VISIBLE)
    importance?.let {
        val drawable = when (it) {
            Importance.LOW -> R.drawable.widget_importance_low
            Importance.MEDIUM -> R.drawable.widget_importance_medium
            Importance.HIGH -> R.drawable.widget_importance_high
        }
        views.setImageViewResource(R.id.widget_importance, drawable)
        views.setContentDescription(R.id.widget_importance, "${it.label} importance")
    }
    // Tint a rounded drawable instead of tinting the rectangular layout background.
    views.setInt(R.id.widget_card, "setBackgroundResource", if (occurrence == null) palette.tasksBackground else R.drawable.widget_card)
    if (Build.VERSION.SDK_INT >= 31) views.setColorStateList(R.id.widget_card, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(color))
    else views.setInt(R.id.widget_card, "setBackgroundResource", if (occurrence == null) palette.tasksBackground else when (color) {
        0xFFD50000.toInt() -> R.drawable.widget_color_tomato
        0xFFE67C73.toInt() -> R.drawable.widget_color_flamingo
        0xFFF4511E.toInt() -> R.drawable.widget_color_tangerine
        0xFFF6BF26.toInt() -> R.drawable.widget_color_banana
        0xFF33B679.toInt() -> R.drawable.widget_color_sage
        0xFF0B8043.toInt() -> R.drawable.widget_color_basil
        0xFF039BE5.toInt() -> R.drawable.widget_color_peacock
        0xFF3F51B5.toInt() -> R.drawable.widget_color_blueberry
        0xFF7986CB.toInt() -> R.drawable.widget_color_lavender
        0xFF8E24AA.toInt() -> R.drawable.widget_color_grape
        0xFF616161.toInt() -> R.drawable.widget_color_graphite
        else -> R.drawable.widget_color_peacock
    })
    val luminance = Color.red(color)*0.299 + Color.green(color)*0.587 + Color.blue(color)*0.114
    val text = if (occurrence == null) palette.secondary else if (luminance > 160) Color.BLACK else Color.WHITE
    views.setTextViewText(R.id.widget_title, occurrence?.let { (if (it.isTask) "☑  " else "") + it.event.title } ?: "☑  ${row.tasks} pending ${if (row.tasks == 1) "task" else "tasks"}")
    views.setTextColor(R.id.widget_title, text)
    views.setTextColor(R.id.widget_time, text)
    if (followsSystem(context)) {
        views.setColorInt(R.id.widget_date, "setTextColor", if (today) Color.WHITE else Color.rgb(31, 31, 31), if (today) Color.BLACK else Color.rgb(238, 238, 238))
        if (today && row.showDate) {
            views.setColorStateList(R.id.widget_date, "setBackgroundTintList", ColorStateList.valueOf(Color.rgb(11, 87, 208)), ColorStateList.valueOf(Color.WHITE))
        }
        if (occurrence == null) {
            views.setColorStateList(R.id.widget_card, "setBackgroundTintList", ColorStateList.valueOf(Color.rgb(227, 231, 235)), ColorStateList.valueOf(Color.rgb(41, 41, 41)))
            views.setColorInt(R.id.widget_title, "setTextColor", Color.rgb(68, 71, 70), Color.rgb(204, 204, 204))
        }
    }
    views.setViewVisibility(R.id.widget_time, if (occurrence == null) View.GONE else View.VISIBLE)
    occurrence?.let {
        views.setTextViewText(R.id.widget_time, if (it.isTask) { if (it.allDay) "All day" else Fmt.time(it.start.toLocalTime(), context.container.settings.settings.value.use24Hour) } else Fmt.occurrenceTime(it, context.container.settings.settings.value.use24Hour))
    }
    views.setOnClickFillInIntent(R.id.widget_row, occurrence?.let { MainActivity.eventIntent(context, it.event.id, it.instanceId) } ?: Intent(context, MainActivity::class.java))
    return views
}

class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var rows = emptyList<AgendaRow>()
        override fun onCreate() {}
        override fun onDataSetChanged() { rows = runBlocking { loadRows(applicationContext) } }
        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int) = rows.getOrNull(position)?.let { rowView(applicationContext, it) }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
