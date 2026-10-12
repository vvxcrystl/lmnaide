package dev.lmnaide.calendar.contacts

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Permission is requested only when the user chooses to connect contacts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeopleEditor(title: String, people: List<EventPerson>, autoMatch: Boolean = true, onChange: (List<EventPerson>) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<EventPerson>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var previousAutomatic by remember { mutableStateOf(setOf<String>()) }
    var excluded by remember { mutableStateOf(setOf<String>()) }
    val names = remember(title) { ContactMatcher.names(title) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        denied = !it
    }
    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val found = mutableListOf<EventPerson>()
                context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(2) ?: continue
                        val key = cursor.getString(1) ?: continue
                        found += EventPerson(name, ContactsContract.Contacts.getLookupUri(cursor.getLong(0), key).toString())
                    }
                } ?: error("Contacts unavailable")
                found.distinctBy { it.lookupUri }
            }
        }
        contacts = result.getOrDefault(emptyList())
        loadFailed = result.isFailure
        loaded = true
    }
    LaunchedEffect(names, contacts, loaded) {
        if (!loaded || !autoMatch) return@LaunchedEffect
        val automatic = names.flatMap { ContactMatcher.matches(it, contacts).singleOrNull()?.let(::listOf).orEmpty() }
            .filter { it.lookupUri !in excluded }
        val updated = (people.filterNot { it.lookupUri in previousAutomatic } + automatic).distinctBy { it.lookupUri }
        previousAutomatic = automatic.map { it.lookupUri }.toSet()
        if (updated != people) onChange(updated)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            people.forEach { person ->
                InputChip(
                    selected = false,
                    onClick = {
                        excluded = excluded + person.lookupUri
                        onChange(people.filterNot { it.lookupUri == person.lookupUri })
                    },
                    label = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    avatar = { PersonAvatar(person, Modifier.size(InputChipDefaults.AvatarSize)) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove ${person.name}", Modifier.size(InputChipDefaults.IconSize)) },
                    shape = CircleShape,
                )
            }
            if (allowed && loaded) {
                // Names from the title that still need a contact: none found, or more than one.
                names.filter { name -> ContactMatcher.matches(name, contacts).none { match -> people.any { it.lookupUri == match.lookupUri } } }
                    .forEach { name ->
                        AssistChip(
                            onClick = { search = name; picker = true },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.Outlined.PersonSearch, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
            }
            AssistChip(
                onClick = { if (allowed) { search = ""; picker = true } else permission.launch(Manifest.permission.READ_CONTACTS) },
                label = { Text(if (!allowed) "Connect contacts" else if (people.isEmpty()) "Add people" else "Add") },
                leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                shape = CircleShape,
                border = null,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
        val hint = when {
            denied -> "Contacts access wasn't granted. You can still save the event."
            loadFailed -> "Couldn't load contacts. Reopen this screen to try again."
            !allowed && names.isNotEmpty() -> "Connect contacts to match ${names.joinToString()}."
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (picker) AlertDialog(
        onDismissRequest = { picker = false },
        title = { Text("Add people") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    search, { search = it },
                    placeholder = { Text("Search contacts") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                val shown = contacts.filter { it.name.contains(search.trim(), ignoreCase = true) }
                if (shown.isEmpty()) {
                    Text("No contacts found", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    shown.forEach { person ->
                        val selected = people.any { it.lookupUri == person.lookupUri }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    excluded = if (selected) excluded + person.lookupUri else excluded - person.lookupUri
                                    onChange(if (selected) people.filterNot { it.lookupUri == person.lookupUri } else people + person)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            PersonAvatar(person, Modifier.size(36.dp))
                            Text(person.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (selected) Icon(Icons.Default.Check, contentDescription = "Added", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picker = false }) { Text("Done") } },
    )
}

/** Whether a compact surface (like quick add) has anything to show for people. */
fun hasPeople(title: String, people: List<EventPerson>) = people.isNotEmpty() || ContactMatcher.names(title).isNotEmpty()

private val AvatarColors = listOf(0xFFE8A0BF, 0xFF9AC5F4, 0xFFA8E6CF, 0xFFFFD3A5, 0xFFC3B1E1, 0xFFF7C5A8, 0xFF99DDCC, 0xFFFDE2A0)

/** Current device contact photo, with stable colored initials when unavailable. */
@Composable
fun PersonAvatar(person: EventPerson, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var photoVersion by remember { mutableIntStateOf(0) }
    // Refresh after returning from the device's contact editor or permission settings.
    LifecycleResumeEffect(person.lookupUri) {
        photoVersion++
        onPauseOrDispose { }
    }
    val photo by produceState<ImageBitmap?>(null, person.lookupUri, context, photoVersion) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, Uri.parse(person.lookupUri))
                    ?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    val name = person.name
    val initials = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier.clip(CircleShape).background(Color(AvatarColors[Math.floorMod(name.lowercase().hashCode(), AvatarColors.size)])),
        contentAlignment = Alignment.Center,
    ) {
        val image = photo
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else BoxWithConstraints(contentAlignment = Alignment.Center) {
            val size = with(LocalDensity.current) { (maxWidth * if (initials.length > 1) 0.38f else 0.46f).toSp() }
            Text(initials, color = Color(0xFF1F1A1C), fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
