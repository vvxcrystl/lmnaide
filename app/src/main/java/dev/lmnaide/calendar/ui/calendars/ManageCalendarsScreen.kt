package dev.lmnaide.calendar.ui.calendars

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.ui.common.ColorDot
import dev.lmnaide.calendar.ui.common.ConfirmDialog
import dev.lmnaide.calendar.ui.common.EventColors
import dev.lmnaide.calendar.ui.common.appViewModelFactory
import dev.lmnaide.calendar.ui.common.contentColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManageCalendarsViewModel(private val repository: CalendarRepository) : ViewModel() {
    val calendars: StateFlow<List<CalendarEntity>> = repository.calendars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(calendar: CalendarEntity) {
        viewModelScope.launch { repository.saveCalendar(calendar) }
    }

    fun delete(calendar: CalendarEntity) {
        viewModelScope.launch { repository.deleteCalendar(calendar) }
    }

    companion object {
        val Factory = appViewModelFactory { container, _ -> ManageCalendarsViewModel(container.repository) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCalendarsScreen(
    onBack: () -> Unit,
    viewModel: ManageCalendarsViewModel = viewModel(factory = ManageCalendarsViewModel.Factory),
) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CalendarEntity?>(null) }
    var deleting by remember { mutableStateOf<CalendarEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendars") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val unused = EventColors.entries.firstOrNull { c -> calendars.none { it.color == c.argb } }
                            editing = CalendarEntity(name = "", color = (unused ?: EventColors.Peacock).argb)
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add calendar")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        calendars.forEach { calendar ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable { editing = calendar }
                                    .padding(start = 20.dp, end = 4.dp),
                            ) {
                                ColorDot(Color(calendar.color), size = 16.dp)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                                    if (calendar.keywordList.isNotEmpty()) {
                                        Text(
                                            calendar.keywordList.joinToString(", "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = { deleting = calendar }, enabled = calendars.size > 1) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete ${calendar.name}")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Hidden calendars stay here; toggle visibility from the menu on the main screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }

    editing?.let { calendar ->
        CalendarDialog(
            initial = calendar,
            onSave = { viewModel.save(it); editing = null },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { calendar ->
        ConfirmDialog(
            title = "Delete “${calendar.name}”?",
            text = "All events in this calendar will be deleted. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(calendar) },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarDialog(initial: CalendarEntity, onSave: (CalendarEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableStateOf(initial.color) }
    var keywords by remember { mutableStateOf(initial.keywords) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New calendar" else "Edit calendar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Keywords") },
                    placeholder = { Text("produce, shift") },
                    supportingText = { Text("Quick add sends matching items here") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EventColors.entries.forEach { option ->
                        val selected = option.argb == color
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .then(
                                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                                )
                                .clickable { color = option.argb }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ColorDot(option.color, size = 28.dp)
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = option.label,
                                    tint = option.color.contentColor(),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = name.trim(), color = color, keywords = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "))) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
