package com.example.onetapdnd

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun DailyQuietTimes(
    times: List<DailyQuietTime>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTimesChange: (List<DailyQuietTime>) -> Unit
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quiet times", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (enabled) "Every day" else "Schedule paused", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, onEnabledChange, modifier = Modifier.semantics { contentDescription = "Enable daily quiet times" })
        }
        if (times.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietClock(22 * 60, Modifier.size(56.dp), active = false)
                Text("Make time for quiet", style = MaterialTheme.typography.titleMedium)
                Text("Add a start and end time. Repeat daily.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        times.sortedBy { it.window.startMinute }.forEach { time ->
            key(time.id) {
                val start = clockLabel(time.window.startMinute)
                val end = clockLabel((time.window.startMinute + time.window.durationMinutes) % 1440)
                val active = enabled && time.enabled
                val ink = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(onClickLabel = "Edit quiet time") {
                            editingId = time.id; editorOpen = true
                        }.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                QuietClock(time.window.startMinute, Modifier.size(32.dp), active)
                                Text(start, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = ink)
                            }
                            Text("Until $end${if (time.window.startMinute + time.window.durationMinutes >= 1440) " · next day" else ""}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(durationLabel(time.window.durationMinutes), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(time.enabled, { checked ->
                            onTimesChange(times.map { if (it.id == time.id) it.copy(enabled = checked) else it })
                        }, modifier = Modifier.semantics { contentDescription = "Quiet time $start to $end" })
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menu = true }, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(40.dp).semantics { contentDescription = "Options for quiet time $start" }) {
                                Text("⋮", style = MaterialTheme.typography.headlineSmall)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editingId = time.id; editorOpen = true })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onTimesChange(times.filterNot { it.id == time.id }) })
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { editingId = null; editorOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("+  Add quiet time", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (editorOpen) {
        key(editingId) {
            QuietTimeEditor(times.firstOrNull { it.id == editingId }, onDismiss = { editorOpen = false }) { saved ->
                onTimesChange(times.filterNot { it.id == saved.id } + saved)
                editorOpen = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuietTimeEditor(existing: DailyQuietTime?, onDismiss: () -> Unit, onSave: (DailyQuietTime) -> Unit) {
    val initial = existing?.window ?: DailyWindow(22 * 60, 30)
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val start = rememberTimePickerState(initial.startMinute / 60, initial.startMinute % 60, is24Hour)
    val endMinute = (initial.startMinute + initial.durationMinutes) % 1440
    val end = rememberTimePickerState(endMinute / 60, endMinute % 60, is24Hour)
    var editingEnd by rememberSaveable { mutableStateOf(false) }
    var keyboard by rememberSaveable { mutableStateOf(false) }
    val from = start.hour * 60 + start.minute
    val until = end.hour * 60 + end.minute
    val duration = (until - from + 1440) % 1440
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 400.dp).heightIn(max = maxHeight).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (existing == null) "New quiet time" else "Edit quiet time", modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeEndpoint("Start", clockLabel(from), !editingEnd, Modifier.weight(1f)) { editingEnd = false }
                    TimeEndpoint("End", clockLabel(until), editingEnd, Modifier.weight(1f)) { editingEnd = true }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (keyboard) TimeInput(if (editingEnd) end else start)
                    else TimePicker(if (editingEnd) end else start, layoutType = TimePickerLayoutType.Vertical)
                    Text(if (duration == 0) "Choose a different end time" else
                        "${durationLabel(duration)} · every day${if (until < from) " · ends next day" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (duration == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(20, 30, 60).forEach { minutes ->
                            TextButton(onClick = {
                                val target = (from + minutes) % 1440
                                end.hour = target / 60; end.minute = target % 60
                            }) { Text("${minutes}m") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { keyboard = !keyboard }) { Text(if (keyboard) "Clock" else "Keyboard") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = duration > 0, onClick = {
                        onSave(DailyQuietTime(existing?.id ?: java.util.UUID.randomUUID().toString(), DailyWindow(from, duration), existing?.enabled ?: true))
                    }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun TimeEndpoint(label: String, time: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(time, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun QuietClock(minute: Int, modifier: Modifier, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val radius = size.minDimension / 2
        val stroke = 1.5.dp.toPx()
        drawCircle(color.copy(alpha = 0.35f), radius - stroke, style = Stroke(stroke))
        fun hand(angle: Double, length: Float) {
            drawLine(color, center, Offset(center.x + cos(angle).toFloat() * radius * length,
                center.y + sin(angle).toFloat() * radius * length), stroke, StrokeCap.Round)
        }
        hand((minute % 720) / 720.0 * 2 * Math.PI - Math.PI / 2, 0.42f)
        hand((minute % 60) / 60.0 * 2 * Math.PI - Math.PI / 2, 0.65f)
    }
}

@Composable
internal fun clockLabel(minute: Int): String {
    val time = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60) }
    return DateFormat.getTimeFormat(LocalContext.current).format(time.time)
}

private fun durationLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}
