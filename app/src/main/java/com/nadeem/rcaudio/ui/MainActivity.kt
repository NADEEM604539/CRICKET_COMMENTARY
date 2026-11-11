package com.nadeem.rcaudio.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        // Bring back whatever the user left switched on (playback service + floating buttons).
        KeepAlive.restore(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompanionDashboard(
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
        // Start = everything on: the floating buttons come back too (when overlay access is granted).
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

@Composable
private fun CompanionDashboard(
    onStartPlayback: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val registry = remember { AudioPoolManager(context) }
    var selectedType by remember { mutableStateOf(GameEventType.FOUR) }
    var commentators by remember { mutableStateOf(registry.commentators()) }
    var selectedCommentator by remember { mutableStateOf(registry.selectedCommentator()) }
    var newCommentator by remember { mutableStateOf("") }
    var commentatorMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var clips by remember(selectedType) { mutableStateOf(registry.clipsFor(selectedType)) }
    var eventLines by remember { mutableStateOf(emptyList<String>()) }
    var eventLoadError by remember { mutableStateOf<String?>(null) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var poolStatus by remember { mutableStateOf<String?>(null) }

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
                android.util.Log.e("RCCompanion", "Could not retain audio permission for $uri", exception)
            }
        }
        clips = registry.clipsFor(selectedType)
        poolStatus = when {
            rejectedByLimit > 0 ->
                "$rejectedByLimit clip(s) not added: a category can contain at most ${AudioPoolManager.MAX_CLIPS_PER_EVENT} clips."
            permissionFailures > 0 -> "$permissionFailures clip(s) could not be accessed."
            else -> "Audio pool updated."
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    androidx.compose.runtime.LaunchedEffect(refreshCount) {
        try {
            eventLines = withContext(Dispatchers.IO) {
                GameEventStore(context).pendingEvents()
                    .asReversed()
                    .map { "${it.type.name} · ${it.eventId} · ${it.source.name}" }
            }
            eventLoadError = null
        } catch (exception: JSONException) {
            eventLoadError = exception.message ?: "Stored event inbox is invalid"
            android.util.Log.e("RCCompanion", "Stored event inbox is invalid", exception)
        } catch (exception: IllegalStateException) {
            eventLoadError = exception.message ?: "Could not load event inbox"
            android.util.Log.e("RCCompanion", "Could not load event inbox", exception)
        } catch (exception: ClassCastException) {
            eventLoadError = exception.message ?: "Could not load event inbox"
            android.util.Log.e("RCCompanion", "Could not load event inbox", exception)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Real Cricket Audio Companion", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Automatic game-event source: not connected. The app cannot read another app's private gameplay state.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Commentary service", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stays running after you close the app or swipe it out of Recents, like a VPN. " +
                        "It only turns off when you open this app and press Stop (this also hides the floating buttons).",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        onStartPlayback()
                    }) { Text("Start") }
                    OutlinedButton(onClick = onStopPlayback) { Text("Stop") }
                }
            }
        }

        OverlayControlCard()
        KeepAliveCard()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Local audio mapping", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { commentatorMenuExpanded = true }) {
                    Text("Voice pack: $selectedCommentator")
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
                                clips = registry.clipsFor(selectedType)
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCommentator,
                        onValueChange = { newCommentator = it },
                        label = { Text("New voice pack") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(onClick = {
                        if (registry.createCommentator(newCommentator)) {
                            newCommentator = ""
                            commentators = registry.commentators()
                            selectedCommentator = registry.selectedCommentator()
                            clips = registry.clipsFor(selectedType)
                        }
                    }) { Text("Create") }
                }
                OutlinedButton(onClick = { menuExpanded = true }) { Text("Event: ${selectedType.audioPoolLabel()}") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    GameEventType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.audioPoolLabel()) },
                            onClick = {
                                selectedType = type
                                menuExpanded = false
                            },
                        )
                    }
                }
                Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                    Text("Import audio clips")
                }
                Text("${clips.size} / ${AudioPoolManager.MAX_CLIPS_PER_EVENT} clips for ${selectedType.audioPoolLabel()}")
                poolStatus?.let { Text(it) }
                clips.forEach { uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(uri.lastPathSegment?.takeLast(28) ?: uri.toString())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                clips = registry.clipsFor(selectedType)
                            }) { Text("Remove") }
                        }
                    }
                }
                Text(
                    "Each category avoids the last three played clips when alternatives exist. " +
                        "Toss · Bat, Toss · Bowl, and Refreshment are separate manual-only pools. " +
                        "Boundary Explanation clips play by themselves, one at random, right after a 4 or 6 clip finishes.",
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Pending event inbox", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { refreshCount++ }) { Text("Refresh") }
                }
                if (eventLines.isEmpty()) Text("No pending events")
                eventLoadError?.let { Text("Event inbox error: $it", color = MaterialTheme.colorScheme.error) }
                eventLines.forEach { Text(it) }
            }
        }

        Text(
            "Audio focus can request transient ducking, but Real Cricket controls whether it honors focus. The companion cannot directly set another app's volume.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun GameEventType.audioPoolLabel(): String = when (this) {
    GameEventType.EXP_BOUND -> "Boundary Explanation (auto after 4 / 6)"
    GameEventType.TOSS_BAT -> "Toss · Bat"
    GameEventType.TOSS_BOWL -> "Toss · Bowl"
    GameEventType.REFRESHMENT -> "Refreshment"
    GameEventType.BOWLED_OUT -> "Wicket · Bowled Out"
    GameEventType.CATCH_OUT -> "Wicket · Catch Out"
    GameEventType.LBW_OUT -> "Wicket · LBW"
    GameEventType.RUN_OUT -> "Wicket · Run Out"
    GameEventType.PITCHED_UP_DOT -> "Dot · Pitched Up"
    GameEventType.BOUNCER_DOT -> "Dot · Bouncer"
    else -> name
}
