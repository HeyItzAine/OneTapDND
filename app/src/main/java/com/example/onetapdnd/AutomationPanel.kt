package com.example.onetapdnd

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AutomationPanel() {
    val context = LocalContext.current
    val settings = remember { AutomationSettings(context) }
    var mode by remember { mutableStateOf(settings.mode) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var window by remember { mutableStateOf(settings.window) }
    var profiles by remember { mutableStateOf(settings.profilesEnabled) }
    var pauseRevision by remember { mutableIntStateOf(0) }
    fun changed() { MonitoringCoordinator(context).onPlacesChanged() }

    Text("When should DND turn on?", style = MaterialTheme.typography.titleLarge)
    AutomationModeSelector(mode) {
        mode = it
        settings.mode = it
        PlaceStore(context).clearPosition()
        changed()
    }
    Text(when (mode) {
        AutomationMode.TIME -> "Follow your daily quiet time."
        AutomationMode.LOCATION -> "Turn on at your saved places."
        AutomationMode.HYBRID -> "Turn on during quiet time or at a saved place. Turn off when both end."
    }, style = MaterialTheme.typography.bodyMedium)

    if (mode.usesTime) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily quiet time", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(enabled, { enabled = it; settings.enabled = it; changed() })
                }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        window = window.copy(startMinute = hour * 60 + minute)
                        settings.window = window
                        changed()
                    }, window.startMinute / 60, window.startMinute % 60, DateFormat.is24HourFormat(context)).show()
                }) { Text("Start at ${clockLabel(window.startMinute)}") }
                var endTime by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose an end time", Modifier.weight(1f))
                    Switch(endTime, { endTime = it })
                }
                if (endTime) {
                    val end = (window.startMinute + window.durationMinutes) % 1440
                    OutlinedButton(onClick = {
                        TimePickerDialog(context, { _, hour, minute ->
                            val duration = (hour * 60 + minute - window.startMinute + 1440) % 1440
                            window = window.copy(durationMinutes = duration.coerceAtLeast(1))
                            settings.window = window
                            changed()
                        }, end / 60, end % 60, DateFormat.is24HourFormat(context)).show()
                    }) { Text("End at ${clockLabel(end)}") }
                } else {
                    Text("Turn off after ${window.durationMinutes} minutes")
                    Slider(window.durationMinutes.toFloat().coerceIn(5f, 180f), {
                        window = window.copy(durationMinutes = (it / 5).roundToInt().coerceIn(1, 36) * 5)
                    }, valueRange = 5f..180f, steps = 34, onValueChangeFinished = {
                        settings.window = window; changed()
                    })
                }
                Text("Every day · ${clockLabel(window.startMinute)} – ${clockLabel((window.startMinute + window.durationMinutes) % 1440)}${if (window.startMinute + window.durationMinutes >= 1440) " (next day)" else ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (mode.usesLocation) QuietPlaces()
    if (!mode.usesLocation) {
        val store = remember { PlaceStore(context) }
        val paused = remember(pauseRevision) { store.isPaused() }
        if (paused) Button(onClick = { MonitoringCoordinator(context).resumeNow(); pauseRevision++ }) { Text("Resume automation") }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Audio by connection", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(profiles, { profiles = it; settings.profilesEnabled = it; changed() })
            }
            Text("Use separate settings for headphones and the phone speaker during automatic DND.", style = MaterialTheme.typography.bodySmall)
            if (profiles) {
                AudioProfileEditor("Phone speaker", false, settings, ::changed)
                HorizontalDivider()
                AudioProfileEditor("Wired, USB or Bluetooth headphones", true, settings, ::changed)
            }
        }
    }
}

@Composable
private fun clockLabel(minute: Int): String {
    val context = LocalContext.current
    val time = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
    }
    return DateFormat.getTimeFormat(context).format(time.time)
}

@Composable
private fun AudioProfileEditor(label: String, headphones: Boolean, settings: AutomationSettings, changed: () -> Unit) {
    var audio by remember { mutableStateOf(settings.profile(headphones)) }
    fun save(value: PlaceAudioMode) { audio = value; settings.saveProfile(headphones, value); changed() }
    Text(label, style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Change ringer", Modifier.weight(1f))
        Switch(audio.ringer != null, { save(PlaceAudioMode.fromSettings(if (it) RingerMode.SILENT else null, audio.mutesMedia)) })
    }
    audio.ringer?.let { ringer -> RingerModeSelector(ringer) { save(PlaceAudioMode.fromSettings(it, audio.mutesMedia)) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mute media", Modifier.weight(1f))
        Switch(audio.mutesMedia, { save(PlaceAudioMode.fromSettings(audio.ringer, it)) })
    }
}

@Composable
private fun AutomationModeSelector(selected: AutomationMode, onSelected: (AutomationMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        val segment = maxWidth / 3
        val offset by animateDpAsState(segment * selected.ordinal, label = "Automation mode")
        Box(Modifier.offset(x = offset).width(segment).fillMaxHeight().padding(4.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)))
        Row(Modifier.fillMaxSize().selectableGroup().pointerInput(Unit) {
            var position = 0f
            detectHorizontalDragGestures(onDragStart = { position = it.x }, onDragEnd = {
                onSelected(AutomationMode.entries[(position / (size.width / 3f)).toInt().coerceIn(0, 2)])
            }) { change, amount -> change.consume(); position += amount }
        }) {
            AutomationMode.entries.forEach { mode ->
                Box(Modifier.weight(1f).fillMaxHeight().selectable(selected == mode, role = Role.RadioButton,
                    onClick = { onSelected(mode) }), contentAlignment = Alignment.Center) {
                    Text(mode.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun TimingPermissionCard() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= 31) {
        CollapsibleCard("Precise timing", "Alarms & reminders", "Allow precise timing so quiet time starts and ends on schedule. Without access, Android may delay it.", alwaysContent = {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text("Timing settings") }
        })
    }
}

@Composable
fun LocationPermissionsCard() {
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MonitoringCoordinator(context).onPlacesChanged()
    }
    CollapsibleCard("Location access", "For location and hybrid modes", "Allow precise location, then set location access to Allow all the time in app settings.", alwaysContent = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Allow location") }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("App settings") }
        }
    })
}
