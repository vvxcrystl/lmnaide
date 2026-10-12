package dev.lmnaide.calendar.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lmnaide.calendar.BuildConfig
import dev.lmnaide.calendar.container
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun CloudSettings() {
    val cloud = LocalContext.current.container.cloud
    val state by cloud.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf(state.server) }
    var email by rememberSaveable { mutableStateOf(state.email) }
    // Passwords are kept only in memory, never in saved state or preferences.
    var password by remember { mutableStateOf("") }
    var signingOut by remember { mutableStateOf(false) }
    Text(
        "Cloud sync",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (state.connected) Icons.Default.CloudDone else Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.connected) "Your calendar, connected"
                        else "Your days, on every device",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            if (state.connected) {
                Text(state.email, style = MaterialTheme.typography.bodyMedium)
                Text(
                    state.server,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.lastSync > 0)
                    Text(
                        "Last synced ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(state.lastSync))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                Text(
                    "Changes sync automatically. Your calendar stays available offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = cloud::requestSync, enabled = !state.busy) { Text("Sync now") }
                    TextButton(onClick = { signingOut = true }, enabled = !state.busy) {
                        Text("Sign out")
                    }
                }
            } else {
                Text(
                    "Connect to your Calendar Cloud server and use the same account on each device. Events, tasks and calendars sync; device contacts and appearance stay local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server address") },
                    placeholder = { Text("https://calendar.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password · at least 10 characters") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ready =
                        server.isNotBlank() &&
                            email.isNotBlank() &&
                            password.length >= 10 &&
                            !state.busy
                    Button(
                        onClick = {
                            scope.launch {
                                cloud.connect(server, email, password, false)
                                if (cloud.state.value.connected) password = ""
                            }
                        },
                        enabled = ready,
                    ) {
                        Text("Sign in")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                cloud.connect(server, email, password, true)
                                if (cloud.state.value.connected) password = ""
                            }
                        },
                        enabled = ready,
                    ) {
                        Text("Create account")
                    }
                }
                if (BuildConfig.DEBUG)
                    Text(
                        "For USB testing, use http://127.0.0.1:3000 with ADB reverse.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
    if (signingOut)
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text("Sign out of cloud sync?") },
            text = {
                Text(
                    "Your local events stay on this device. Sign in to the same account to resume syncing."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        signingOut = false
                        scope.launch { cloud.disconnect() }
                    }
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text("Cancel") } },
        )
}
