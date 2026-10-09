package dev.lmnaide.calendar.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.lmnaide.calendar.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.CalendarView

val CalendarView.label: String
    get() = when (this) {
        CalendarView.SCHEDULE -> "Schedule"
        CalendarView.DAY -> "Day"
        CalendarView.THREE_DAY -> "3 days"
        CalendarView.WEEK -> "Week"
        CalendarView.MONTH -> "Month"
    }

private val CalendarView.icon: ImageVector
    get() = when (this) {
        CalendarView.SCHEDULE -> Icons.Outlined.ViewAgenda
        CalendarView.DAY -> Icons.Outlined.ViewDay
        CalendarView.THREE_DAY -> Icons.Outlined.ViewColumn
        CalendarView.WEEK -> Icons.Outlined.CalendarViewWeek
        CalendarView.MONTH -> Icons.Outlined.CalendarViewMonth
    }

private val ItemShape = CircleShape

@Composable
fun AppDrawer(
    currentView: CalendarView,
    calendars: List<CalendarEntity>,
    showTasks: Boolean,
    onSelectView: (CalendarView) -> Unit,
    onToggleCalendar: (CalendarEntity) -> Unit,
    onToggleTasks: () -> Unit,
    onManageCalendars: () -> Unit,
    onSettings: () -> Unit,
) {
    val itemColors = NavigationDrawerItemDefaults.colors(
        unselectedContainerColor = Color.Transparent,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 20.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_lemon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text("lmnaide", style = MaterialTheme.typography.titleLarge)
            }

            CalendarView.entries.forEach { view ->
                NavigationDrawerItem(
                    label = { Text(view.label, style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(view.icon, contentDescription = null) },
                    selected = view == currentView,
                    onClick = { onSelectView(view) },
                    shape = ItemShape,
                    colors = itemColors,
                )
            }

            SectionDivider()
            SectionLabel("Calendars")
            calendars.forEach { calendar ->
                VisibilityRow(calendar.name, Color(calendar.color), calendar.visible) { onToggleCalendar(calendar) }
            }
            VisibilityRow("Tasks", MaterialTheme.colorScheme.primary, showTasks, onToggleTasks)
            NavigationDrawerItem(
                label = { Text("Manage calendars", style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(Icons.Outlined.EditCalendar, contentDescription = null) },
                selected = false,
                onClick = onManageCalendars,
                shape = ItemShape,
                colors = itemColors,
            )

            SectionDivider()
            NavigationDrawerItem(
                label = { Text("Settings", style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                selected = false,
                onClick = onSettings,
                shape = ItemShape,
                colors = itemColors,
            )
        }
    }
}

@Composable
private fun VisibilityRow(name: String, color: Color, visible: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(ItemShape)
            .toggleable(value = visible, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 4.dp),
    ) {
        Checkbox(
            checked = visible,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
            modifier = Modifier.padding(start = 8.dp, end = 16.dp),
        )
        Text(name, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}
