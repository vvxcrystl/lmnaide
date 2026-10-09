package dev.lmnaide.calendar.ui.event

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.domain.EditScope
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.common.ConfirmDialog
import dev.lmnaide.calendar.ui.common.Fmt
import dev.lmnaide.calendar.ui.common.OptionsDialog
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    onBack: () -> Unit,
    onEdit: (EventLink) -> Unit,
    onOpenEvent: (EventLink) -> Unit,
    viewModel: EventDetailViewModel = viewModel(factory = EventDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // The event was deleted (here or elsewhere).
    LaunchedEffect(state) { if (state is DetailState.Missing) onBack() }

    val loaded = state as? DetailState.Loaded
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Close") }
                },
                actions = {
                    if (loaded != null) {
                        IconButton(onClick = { onEdit(EventLink(loaded.occurrence.event.id, loaded.occurrence.instanceId)) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.duplicate(loaded.occurrence, onOpenEvent)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val occurrence = loaded.occurrence
        val event = occurrence.event
        val today = LocalDate.now()
        val context = LocalContext.current
        val (whenLine, secondLine) = Fmt.occurrenceWhen(occurrence, settings.use24Hour, today)

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(occurrence.color)),
                )
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Text(event.title.ifBlank { "(No title)" }, style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(whenLine, style = MaterialTheme.typography.bodyLarge)
                    secondLine?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (event.recurrence != Recurrence.NONE) {
                        val until = event.recurrenceUntil?.let { ", until ${Fmt.dayShort(LocalDate.ofEpochDay(it))}" }.orEmpty()
                        Text(
                            Fmt.recurrence(event.recurrence, event.localStart().toLocalDate()) + until,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (event.location.isNotBlank()) {
                DetailRow(
                    icon = Icons.Outlined.LocationOn,
                    onClick = {
                        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(event.location))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
                ) {
                    Text(event.location, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (event.reminders.isNotEmpty()) {
                DetailRow(icon = Icons.Outlined.Notifications) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        event.reminders.sorted().forEach {
                            Text(Fmt.reminder(it, event.allDay, settings.use24Hour), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            DetailRow(icon = Icons.Outlined.Event) {
                Text(loaded.calendar?.name ?: "Unknown calendar", style = MaterialTheme.typography.bodyLarge)
            }
            if (event.description.isNotBlank()) {
                DetailRow(icon = Icons.AutoMirrored.Outlined.Notes) {
                    SelectionContainer {
                        Text(event.description, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        if (confirmDelete) {
            if (event.recurrence == Recurrence.NONE) {
                ConfirmDialog(
                    title = "Delete event?",
                    text = "“${event.title.ifBlank { "(No title)" }}” will be removed from your calendar.",
                    confirmLabel = "Delete",
                    onConfirm = { viewModel.delete(occurrence, EditScope.ALL) },
                    onDismiss = { confirmDelete = false },
                )
            } else {
                OptionsDialog(
                    title = "Delete recurring event",
                    options = EditScope.entries,
                    selected = null,
                    label = { it.label },
                    onSelect = { viewModel.delete(occurrence, it) },
                    onDismiss = { confirmDelete = false },
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f).padding(top = 1.dp)) { content() }
    }
}
