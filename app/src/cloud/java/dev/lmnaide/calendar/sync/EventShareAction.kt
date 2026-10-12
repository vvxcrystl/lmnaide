package dev.lmnaide.calendar.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun EventShareAction(eventId: Long, title: String, recurring: Boolean, colors: IconButtonColors) {
    val context = LocalContext.current
    val cloud = context.container.cloud
    val scope = rememberCoroutineScope()
    var open by remember(eventId) { mutableStateOf(false) }
    var busy by remember(eventId) { mutableStateOf(false) }
    var loaded by remember(eventId) { mutableStateOf(false) }
    var link by remember(eventId) { mutableStateOf<String?>(null) }
    var message by remember(eventId) { mutableStateOf("") }
    suspend fun update(action: String) {
        busy = true
        message = ""
        try {
            link = cloud.share(eventId, action)
            loaded = true
            if (action == "revoke") message = "Link disabled"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Unable to share this event. Try again."
        } finally {
            busy = false
        }
    }
    IconButton(onClick = { open = true }, colors = colors) {
        Icon(Icons.Outlined.Share, contentDescription = "Share event")
    }
    LaunchedEffect(open, eventId) {
        if (open) {
            loaded = false
            update("status")
        }
    }
    if (open)
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text("Share event") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Anyone with the link can read this event’s title, dates, location and notes. Edits update the shared page."
                    )
                    if (recurring) Text("This link shares the recurring series.")
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    if (message.isNotEmpty()) Text(message)
                    val url = link
                    if (loaded && url != null) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Event link") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                enabled = !busy,
                                onClick = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("Event link", url)
                                    )
                                    message = "Link copied"
                                },
                            ) {
                                Text("Copy link")
                            }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, title)
                                                    putExtra(Intent.EXTRA_TEXT, "$title\n$url")
                                                },
                                                "Share event",
                                            )
                                        )
                                    } catch (e: Exception) {
                                        message =
                                            "No sharing app is available. Copy the link instead."
                                    }
                                },
                            ) {
                                Text("Share")
                            }
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = { scope.launch { update("revoke") } },
                        ) {
                            Text("Stop sharing")
                        }
                    } else if (!busy) {
                        Button(
                            onClick = {
                                scope.launch { update(if (loaded) "create" else "status") }
                            }
                        ) {
                            Text(if (loaded) "Create link" else "Retry")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }, enabled = !busy) { Text("Close") }
            },
        )
}
