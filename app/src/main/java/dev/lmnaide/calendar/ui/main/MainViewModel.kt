package dev.lmnaide.calendar.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.CalendarView
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.ui.common.appViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainViewModel(
    private val repository: CalendarRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepository.settings

    val calendars: StateFlow<List<CalendarEntity>> = repository.calendars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val index: StateFlow<EventIndex> =
        combine(repository.events, repository.calendars, settingsRepository.settings) { events, calendars, settings ->
            EventIndex(events, calendars, settings.showTasks)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventIndex.Empty)

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun setView(view: CalendarView) = settingsRepository.update { it.copy(view = view) }

    fun toggleCalendar(calendar: CalendarEntity) {
        viewModelScope.launch { repository.saveCalendar(calendar.copy(visible = !calendar.visible)) }
    }

    fun toggleTasks() = settingsRepository.update { it.copy(showTasks = !it.showTasks) }

    fun setTaskCompleted(occurrence: Occurrence, done: Boolean) {
        viewModelScope.launch { repository.setCompleted(occurrence.event.id, occurrence.startDate, done) }
    }

    companion object {
        val Factory = appViewModelFactory { container, _ ->
            MainViewModel(container.repository, container.settings)
        }
    }
}
