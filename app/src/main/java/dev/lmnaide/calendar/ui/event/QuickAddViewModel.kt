package dev.lmnaide.calendar.ui.event

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Settings
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.domain.SmartEventDraft
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.common.appViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneOffset

/** Saves events and tasks typed into the quick add sheet, using the same defaults as the editor. */
class QuickAddViewModel(
    private val repository: CalendarRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsRepository.settings

    val calendars: StateFlow<List<CalendarEntity>> = repository.calendars
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Past items, so a title goes back into the calendar it was put in before. */
    val history: StateFlow<List<EventEntity>> = repository.events
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var isSaving by mutableStateOf(false)
        private set

    fun save(draft: SmartEventDraft, task: Boolean, calendar: CalendarEntity, onSaved: (EventLink) -> Unit) {
        if (isSaving) return
        isSaving = true
        viewModelScope.launch {
            try {
                val id = repository.saveEvent(
                    EventEntity(
                        calendarId = calendar.id,
                        title = draft.title,
                        start = EventEntity.toStored(draft.start, draft.allDay),
                        end = EventEntity.toStored(draft.end, draft.allDay),
                        allDay = draft.allDay,
                        recurrence = draft.recurrence,
                        // Tasks remind when they're due; events use the default notification.
                        reminders = if (task) listOf(0) else listOfNotNull(settings.value.defaultReminderMinutes),
                        kind = if (task) EventKind.TASK else EventKind.EVENT,
                    ),
                )
                onSaved(EventLink(id, draft.start.toEpochSecond(ZoneOffset.UTC)))
            } finally { isSaving = false }
        }
    }

    companion object {
        val Factory = appViewModelFactory { container, _ ->
            QuickAddViewModel(container.repository, container.settings)
        }
    }
}
