package dev.lmnaide.calendar

import android.app.Application
import android.content.Context
import dev.lmnaide.calendar.data.AppDatabase
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CalendarApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        dev.lmnaide.calendar.widget.AgendaWidgetProvider.refresh(this)
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository = CalendarRepository(AppDatabase.build(context))
    val settings = SettingsRepository(context)
    val reminders = ReminderScheduler(context, repository, settings)

    fun start() {
        appScope.launch {
            kotlinx.coroutines.flow.combine(repository.events, repository.calendars, settings.settings) { _, _, _ -> Unit }
                .collectLatest { dev.lmnaide.calendar.widget.AgendaWidgetProvider.refresh(context) }
        }
        appScope.launch {
            repository.ensureDefaults()
            // Keep the next reminder alarm in sync with every change to the events.
            repository.events.collectLatest { reminders.reschedule() }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as CalendarApp).container
