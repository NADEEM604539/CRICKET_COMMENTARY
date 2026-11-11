package com.nadeem.rcaudio.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nadeem.rcaudio.service.KeepAlive

/**
 * Phone makers (Samsung especially) put apps to sleep when they leave Recents. This card asks Android
 * to exempt the companion so the buttons and playback keep working while the game is in front.
 */
@Composable
fun KeepAliveCard() {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(KeepAlive.isIgnoringBatteryOptimizations(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = KeepAlive.isIgnoringBatteryOptimizations(context)
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
            Text("Keep running in background", style = MaterialTheme.typography.titleMedium)
            Text(
                if (unrestricted) {
                    "Battery restriction: off. Android will not put this app to sleep."
                } else {
                    "Battery restriction: ON. Android may stop the buttons after you close the app. " +
                        "Tap below and choose Allow."
                },
            )
            if (!unrestricted) {
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) { Text("Allow background use") }
            }
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }) { Text("Open app settings") }
            Text(
                "On Samsung: Settings > Battery > Background usage limits > Never sleeping apps > add " +
                    "RC Event Audio, and set this app's Battery to Unrestricted.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
