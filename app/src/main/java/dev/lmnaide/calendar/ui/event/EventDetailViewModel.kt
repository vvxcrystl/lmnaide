package dev.lmnaide.calendar.ui.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.domain.EditScope
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.common.appViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration

sealed interface DetailState {
    data object Loading : DetailState

    data object Missing : DetailState

    data class Loaded(val occurrence: Occurrence, val calendar: CalendarEntity?) : DetailState
}

class EventDetailViewModel(
    private val repository: CalendarRepository,
    settingsRepository: SettingsRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    private val eventId: Long = checkNotNull(handle["eventId"])
    private val instanceId: Long = checkNotNull(handle["instance"])

    val settings = settingsRepository.settings

    val state: StateFlow<DetailState> =
        combine(repository.observeEvent(eventId), repository.calendars) { event, calendars ->
            if (event == null) {
                DetailState.Missing
            } else {
                val calendar = calendars.firstOrNull { it.id == event.calendarId }
                DetailState.Loaded(occurrenceOf(event, calendar), calendar)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    /** The instance that was opened; for a series this is the tapped occurrence, not the first one. */
    private fun occurrenceOf(event: EventEntity, calendar: CalendarEntity?): Occurrence {
        val baseStart = event.localStart()
        val start = if (event.recurrence != Recurrence.NONE && instanceId != 0L) {
            Occurrence.instanceStart(instanceId)
        } else {
            baseStart
        }
        val length = Duration.between(baseStart, maxOf(event.localEnd(), baseStart))
        return Occurrence(event, start, start.plus(length), event.color ?: calendar?.color ?: 0, calendar?.name.orEmpty())
    }

    fun delete(occurrence: Occurrence, scope: EditScope) {
        viewModelScope.launch {
            val event = occurrence.event
            when (scope) {
                EditScope.THIS -> repository.deleteOccurrence(event, occurrence.startDate)
                EditScope.FOLLOWING -> repository.deleteFollowing(event, occurrence.startDate)
                EditScope.ALL -> repository.deleteEvent(event)
            }
        }
    }

    fun setCompleted(occurrence: Occurrence, done: Boolean) {
        viewModelScope.launch { repository.setCompleted(occurrence.event.id, occurrence.startDate, done) }
    }

    fun duplicate(occurrence: Occurrence, onCreated: (EventLink) -> Unit) {
        viewModelScope.launch {
            val id = repository.duplicate(occurrence.event, occurrence.start)
            onCreated(EventLink(id, occurrence.instanceId))
        }
    }

    companion object {
        val Factory = appViewModelFactory { container, handle ->
            EventDetailViewModel(container.repository, container.settings, handle)
        }
    }
}
