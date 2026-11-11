package com.nadeem.rcaudio.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nadeem.rcaudio.overlay.FloatingOverlayService

/** Dashboard card: grant "display over other apps" and show/hide the floating event buttons. */
@Composable
fun OverlayControlCard() {
    val context = LocalContext.current
    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showing by remember { mutableStateOf(FloatingOverlayService.running) }

    // Re-check when the user comes back from the system permission screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDraw = Settings.canDrawOverlays(context)
                showing = FloatingOverlayService.running
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Floating event buttons", style = MaterialTheme.typography.titleMedium)
            Text(
                "Compact mode keeps only the handle, fold toggle, and P stop control. Expand for two rows: " +
                    "P / T / R above and 0 1 2 4 6 W E below. 0 opens Pitched Up / Bouncer dot-ball choices; " +
                    "W opens Run / Bowl / Catch / LBW choices; E opens extras.",
            )
            if (!canDraw) {
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) { Text("Allow display over other apps") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, FloatingOverlayService::class.java)
                                .setAction(FloatingOverlayService.ACTION_SHOW),
                        )
                        showing = true
                    }) { Text(if (showing) "Buttons shown" else "Show buttons") }
                    OutlinedButton(onClick = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, FloatingOverlayService::class.java)
                                .setAction(FloatingOverlayService.ACTION_HIDE),
                        )
                        showing = false
                    }) { Text("Hide") }
                }
            }
            Text(
                "Drag the \u2261 handle to move the bar. Expanded/collapsed mode and position are remembered. " +
                    "The bar is restored when you reopen the app. Import up to 20 clips for each category below first. " +
                    "A selection stops the old clip even when its pool is empty.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
