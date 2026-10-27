package dev.lmnaide.calendar.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ApiOriginTag(tint: Color = MaterialTheme.colorScheme.primary) {
    var showExplanation by remember { mutableStateOf(false) }
    Text(
        "API",
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClickLabel = "Show event origin") { showExplanation = true }
            .semantics { contentDescription = "Added programmatically via the calendar API" }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            title = { Text("Added programmatically") },
            text = { Text("This event was added programmatically through the calendar API.") },
            confirmButton = {
                TextButton(onClick = { showExplanation = false }) { Text("Got it") }
            },
        )
    }
}
