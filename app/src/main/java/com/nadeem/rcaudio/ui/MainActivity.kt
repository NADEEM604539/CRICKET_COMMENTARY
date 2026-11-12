package com.nadeem.rcaudio.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nadeem.rcaudio.audio.AudioPoolManager
import com.nadeem.rcaudio.events.GameEventStore
import com.nadeem.rcaudio.events.GameEventType
import com.nadeem.rcaudio.overlay.FloatingOverlayService
import com.nadeem.rcaudio.service.CommentaryService
import com.nadeem.rcaudio.service.KeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAlive.restore(this)
        setContent {
            RcTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompanionApp(
                        onStartPlayback = { startPlaybackService() },
                        onStopPlayback = {
                            ContextCompat.startForegroundService(
                                this,
                                Intent(this, CommentaryService::class.java)
                                    .setAction(CommentaryService.ACTION_STOP),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun startPlaybackService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, CommentaryService::class.java).setAction(CommentaryService.ACTION_START),
        )
        if (Settings.canDrawOverlays(this)) startOverlayService()
    }

    private fun startOverlayService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, FloatingOverlayService::class.java)
                .setAction(FloatingOverlayService.ACTION_SHOW),
        )
    }
}

private enum class AppTab(val label: String) {
    HOME("Home"),
    LIBRARY("Audio library"),
    EVENTS("Events"),
}

@Composable
private fun CompanionApp(
    onStartPlayback: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.label.take(1), style = MaterialTheme.typography.labelLarge) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        when (selectedTab) {
            AppTab.HOME -> HomeScreen(
                modifier = Modifier.padding(contentPadding),
                onStartPlayback = onStartPlayback,
                onStopPlayback = onStopPlayback,
            )
            AppTab.LIBRARY -> AudioLibraryScreen(Modifier.padding(contentPadding))
            AppTab.EVENTS -> EventsScreen(Modifier.padding(contentPadding))
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    onStartPlayback: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Real Cricket Audio", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your match controls, voice packs, and audio clips in one place.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard(
            title = "Commentary service",
            subtitle = "Keep playback ready while you play.",
        ) {
            Text(
                "The service runs in the background. Stop turns off playback and hides the floating buttons.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    onStartPlayback()
                }) { Text("Start") }
                OutlinedButton(onClick = onStopPlayback) { Text("Stop") }
            }
        }
        OverlayControlCard()
        KeepAliveCard()
        SectionCard(
            title = "Automatic event source",
            subtitle = "Not connected",
        ) {
            Text(
                "The app cannot read another app's private gameplay state. Use the floating buttons to trigger commentary manually.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AudioLibraryScreen(modifier: Modifier) {
    val context = LocalContext.current
    val registry = remember { AudioPoolManager(context) }
    var commentators by remember { mutableStateOf(registry.commentators()) }
    var selectedCommentator by remember { mutableStateOf(registry.selectedCommentator()) }
    var newCommentator by remember { mutableStateOf("") }
    var commentatorMenuExpanded by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(GameEventType.FOUR) }
    var search by remember { mutableStateOf("") }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var poolStatus by remember { mutableStateOf<String?>(null) }
    val clips = remember(selectedCommentator, selectedType, libraryRevision) {
        registry.clipsFor(selectedType)
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        var rejectedByLimit = 0
        var permissionFailures = 0
        uris.forEach { uri ->
            if (!registry.canAddClip(selectedType, uri)) {
                rejectedByLimit++
                return@forEach
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                if (!registry.addClip(selectedType, uri)) rejectedByLimit++
            } catch (exception: SecurityException) {
                permissionFailures++
                Log.e("RCCompanion", "Could not retain audio permission for $uri", exception)
            }
        }
        libraryRevision++
        poolStatus = when {
            rejectedByLimit > 0 ->
                "$rejectedByLimit clip(s) not added. Each category holds up to ${AudioPoolManager.MAX_CLIPS_PER_EVENT} clips."
            permissionFailures > 0 -> "$permissionFailures clip(s) could not be accessed."
            else -> "Audio library updated."
        }
    }

    val matchingTypes = remember(search) {
        GameEventType.entries.filter {
            search.isBlank() || it.title().contains(search.trim(), ignoreCase = true)
        }.toSet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Audio library", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Choose a voice pack and category to organize, preview, or remove clips.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(title = "Voice pack", subtitle = "Each pack has its own clip collection.") {
            OutlinedButton(onClick = { commentatorMenuExpanded = true }) {
                Text(selectedCommentator)
            }
            DropdownMenu(
                expanded = commentatorMenuExpanded,
                onDismissRequest = { commentatorMenuExpanded = false },
            ) {
                commentators.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            registry.selectCommentator(name)
                            selectedCommentator = name
                            commentatorMenuExpanded = false
                            libraryRevision++
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newCommentator,
                    onValueChange = { newCommentator = it },
                    label = { Text("New voice pack") },
                    supportingText = { Text("Up to 40 characters") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(onClick = {
                    if (registry.createCommentator(newCommentator)) {
                        newCommentator = ""
                        commentators = registry.commentators()
                        selectedCommentator = registry.selectedCommentator()
                        libraryRevision++
                        poolStatus = "Voice pack created."
                    } else {
                        poolStatus = "Enter a name between 1 and 40 characters."
                    }
                }) { Text("Create") }
            }
        }

        SectionCard(title = "Find a category", subtitle = "Search or browse by match event.") {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search categories") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            primaryGroups.forEach { group ->
                val visibleTypes = group.types.filter { it in matchingTypes }
                if (visibleTypes.isNotEmpty()) {
                    Text(group.title, style = MaterialTheme.typography.titleSmall)
                    visibleTypes.chunked(2).forEach { rowTypes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowTypes.forEach { type ->
                                FilterChip(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    label = { Text(type.title()) },
                                )
                            }
                        }
                    }
                }
            }
            val remaining = otherTypes.filter { it in matchingTypes }
            if (remaining.isNotEmpty()) {
                Text("Other categories", style = MaterialTheme.typography.titleSmall)
                remaining.chunked(2).forEach { rowTypes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowTypes.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(type.title()) },
                            )
                        }
                    }
                }
            }
            if (matchingTypes.isEmpty()) {
                Text("No categories match that search.")
            }
        }

        SectionCard(
            title = selectedType.title(),
            subtitle = selectedType.hint(),
        ) {
            Text(
                "${clips.size} of ${AudioPoolManager.MAX_CLIPS_PER_EVENT} clips",
                style = MaterialTheme.typography.titleSmall,
            )
            Button(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                enabled = clips.size < AudioPoolManager.MAX_CLIPS_PER_EVENT,
            ) {
                Text(if (clips.size >= AudioPoolManager.MAX_CLIPS_PER_EVENT) "Category full" else "Add audio clips")
            }
            poolStatus?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (clips.isEmpty()) {
                Text("No clips yet. Add audio to make this category ready.")
            } else {
                clips.forEachIndexed { index, uri ->
                    SectionCard(
                        title = "Clip ${index + 1}",
                        subtitle = uri.lastPathSegment?.takeLast(48) ?: "Audio file",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                ContextCompat.startForegroundService(
                                    context,
                                    Intent(context, CommentaryService::class.java)
                                        .setAction(CommentaryService.ACTION_PREVIEW)
                                        .setData(uri),
                                )
                            }) { Text("Preview") }
                            OutlinedButton(onClick = {
                                registry.removeClip(selectedType, uri)
                                libraryRevision++
                                poolStatus = "Clip removed."
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var eventLines by remember { mutableStateOf(emptyList<String>()) }
    var eventLoadError by remember { mutableStateOf<String?>(null) }
    var refreshCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshCount) {
        try {
            eventLines = withContext(Dispatchers.IO) {
                GameEventStore(context).pendingEvents()
                    .asReversed()
                    .map { "${it.type.title()} · ${it.eventId} · ${it.source.name}" }
            }
            eventLoadError = null
        } catch (exception: JSONException) {
            eventLoadError = exception.message ?: "Stored event inbox is invalid"
            Log.e("RCCompanion", "Stored event inbox is invalid", exception)
        } catch (exception: IllegalStateException) {
            eventLoadError = exception.message ?: "Could not load event inbox"
            Log.e("RCCompanion", "Could not load event inbox", exception)
        } catch (exception: ClassCastException) {
            eventLoadError = exception.message ?: "Could not load event inbox"
            Log.e("RCCompanion", "Could not load event inbox", exception)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Event inbox", style = MaterialTheme.typography.headlineMedium)
        SectionCard(
            title = "Pending events",
            subtitle = "Check events received by this device.",
        ) {
            OutlinedButton(onClick = { refreshCount++ }) { Text("Refresh") }
            eventLoadError?.let {
                Text("Could not load events: $it", color = MaterialTheme.colorScheme.error)
            }
            if (eventLines.isEmpty() && eventLoadError == null) {
                Text("No pending events.")
            } else {
                eventLines.forEachIndexed { index, event ->
                    Text("${index + 1}. $event")
                }
            }
        }
        SectionCard(title = "About event access") {
            Text(
                "The companion only displays events it receives. It cannot inspect private game data or connect to an automatic event source on its own.",
            )
        }
    }
}
