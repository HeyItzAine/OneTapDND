package com.example.onetapdnd

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
fun QuietPlaces() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember { PlaceStore(context) }
    val coordinator = remember { MonitoringCoordinator(context) }
    var revision by remember { mutableIntStateOf(0) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var infoExpanded by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
            }
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
        }
    }

    val rules = remember(revision) { store.rules() }
    val state = remember(revision) { store.state() }
    val precise = remember(revision) { PlaceMonitoring.hasPreciseLocation(context) }
    val background = remember(revision) { PlaceMonitoring.hasBackgroundLocation(context) }
    val hasDnd = remember(revision) { DndController(context).hasAccess }
    val pausedUntil = remember(revision) { store.pauseUntilEpochMs() }
    val paused = pausedUntil > System.currentTimeMillis()
    var customPauseMinutes by remember(revision) { mutableIntStateOf(store.customPauseMinutes()) }

    LaunchedEffect(precise, lifecycle) {
        if (!precise) {
            currentLocation = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val latest = try {
                    currentDeviceLocation(context)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                currentLocation = latest ?: currentLocation
                delay(30_000)
            }
        }
    }

    val foregroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        revision++
        coordinator.onPlacesChanged()
    }
    val backgroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        revision++
        coordinator.onPlacesChanged()
    }

    fun appSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun changed(updated: List<PlaceRule>) {
        feedback = null
        runCatching {
            store.save(updated)
            coordinator.onPlacesChanged()
        }.onFailure {
            feedback = "Could not update quiet places. Check DND access and try again."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quiet places", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            paused -> "Paused until ${formatTime(pausedUntil)}"
                            rules.none { it.enabled } -> "No enabled places"
                            state.active(rules).isNotEmpty() -> "${state.active(rules).size} active"
                            else -> "Monitoring ${rules.count { it.enabled }} places"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(
                    onClick = { infoExpanded = !infoExpanded },
                    modifier = Modifier.semantics {
                        contentDescription = if (infoExpanded) {
                            "Hide quiet place information"
                        } else {
                            "Show quiet place information"
                        }
                    }
                ) {
                    Text(if (infoExpanded) "Hide" else "ⓘ")
                }
            }

            if (!hasDnd) {
                Text("DND access is required before a place can change sound settings.", color = MaterialTheme.colorScheme.error)
            }
            if (!precise) {
                Button(onClick = {
                    foregroundRequest.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) { Text("Allow precise location") }
            } else if (!background) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT == 29) {
                        backgroundRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        appSettings()
                    }
                }) { Text("Allow background location") }
            }
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (paused) {
                Button(onClick = { coordinator.resumeNow(); revision++ }) { Text("Resume now") }
            }

            rules.forEach { rule ->
                HorizontalDivider()
                PlaceRow(
                    rule = rule,
                    inside = rule.id in state.inside,
                    paused = paused,
                    distance = currentLocation?.let { distanceToRule(it, rule) },
                    onEnabledChange = { enabled ->
                        changed(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                    },
                    onEdit = { editingId = rule.id; editorOpen = true },
                    onMap = { openMap(context, rule.latitude, rule.longitude, rule.name) },
                    onDelete = { deletingId = rule.id }
                )
            }

            Button(
                onClick = { editingId = null; editorOpen = true },
                enabled = rules.size < 20
            ) { Text("Add place") }

            if (infoExpanded) {
                HorizontalDivider()
                Text(
                    "One Tap DND checks location in the background. Place names and coordinates stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(store.status(), style = MaterialTheme.typography.bodySmall)
                store.adaptiveCheckStatus().takeIf { it.isNotBlank() }?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                if (rules.isNotEmpty()) {
                    TextButton(onClick = { coordinator.onPlacesChanged() }) { Text("Retry monitoring") }
                }
                PauseSettings(
                    minutes = customPauseMinutes,
                    paused = paused,
                    onMinutesChanged = { minutes ->
                        customPauseMinutes = minutes
                        store.saveCustomPauseMinutes(minutes)
                    },
                    onPause = { coordinator.pauseFor(customPauseMinutes); revision++ }
                )
            }
        }
    }

    if (editorOpen) {
        PlaceEditor(
            existing = rules.firstOrNull { it.id == editingId },
            preciseLocation = precise,
            onDismiss = { editorOpen = false },
            onSave = { rule ->
                changed(rules.filterNot { it.id == editingId } + rule)
                editorOpen = false
            }
        )
    }
    deletingId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete place?") },
            text = { Text("Active sound settings for this place will end.") },
            confirmButton = {
                TextButton(onClick = {
                    changed(rules.filterNot { it.id == id })
                    deletingId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PlaceRow(
    rule: PlaceRule,
    inside: Boolean,
    paused: Boolean,
    distance: Float?,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onMap: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.titleSmall)
            Text(
                buildList {
                    add(audioModeLabel(rule.audioMode))
                    add("${rule.radiusMeters.toInt()} m")
                    distance?.let { add(formatDistance(it)) }
                }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                when {
                    !rule.enabled -> "Disabled"
                    paused -> "Monitoring paused"
                    inside -> "Inside area"
                    else -> "Waiting for arrival"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onEnabledChange)
        Box {
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) { Text("⋮") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text("Map") }, onClick = { menuOpen = false; onMap() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun PauseSettings(
    minutes: Int,
    paused: Boolean,
    onMinutesChanged: (Int) -> Unit,
    onPause: () -> Unit
) {
    Text("Pause duration", style = MaterialTheme.typography.titleSmall)
    Text(formatDuration(minutes), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = minutes.toFloat(),
        onValueChange = {
            val stepped = (it / PlaceStore.PAUSE_STEP_MINUTES).roundToInt() * PlaceStore.PAUSE_STEP_MINUTES
            onMinutesChanged(stepped.coerceIn(PlaceStore.MIN_PAUSE_MINUTES, PlaceStore.MAX_PAUSE_MINUTES))
        },
        valueRange = PlaceStore.MIN_PAUSE_MINUTES.toFloat()..PlaceStore.MAX_PAUSE_MINUTES.toFloat(),
        steps = 94
    )
    if (!paused) {
        OutlinedButton(onClick = onPause) { Text("Pause for ${formatDuration(minutes)}") }
    }
}

private data class SearchPlace(val label: String, val latitude: Double, val longitude: Double)

@SuppressLint("MissingPermission")
@Composable
private fun PlaceEditor(
    existing: PlaceRule?,
    preciseLocation: Boolean,
    onDismiss: () -> Unit,
    onSave: (PlaceRule) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf(existing?.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(existing?.longitude?.toString().orEmpty()) }
    var radius by rememberSaveable { mutableStateOf(existing?.radiusMeters?.toInt()?.toString() ?: "200") }
    var modeName by rememberSaveable { mutableStateOf(existing?.audioMode?.name ?: PlaceAudioMode.DND_ONLY.name) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(emptyList<SearchPlace>()) }
    var lastResolvedQuery by rememberSaveable { mutableStateOf("") }
    var mapPickerCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapPickerZoom by remember { mutableDoubleStateOf(18.0) }
    val audioMode = PlaceAudioMode.valueOf(modeName)

    fun select(place: SearchPlace) {
        latitude = place.latitude.toString()
        longitude = place.longitude.toString()
        lastResolvedQuery = AddressOcrParser.normalizeSearchText(query)
        results = emptyList()
    }

    fun searchAddress(searchText: String) {
        val normalized = AddressOcrParser.normalizeSearchText(searchText)
        if (normalized.isBlank() || busy) return
        query = normalized
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val found = geocode(context, normalized)
                if (found.isEmpty()) {
                    results = emptyList()
                    error = "No places found. Check the address or plus code."
                } else {
                    select(found.first())
                    results = found
                    notice = "Pin set from the first result."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Address search failed. Check your connection or enter coordinates."
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        val normalizedQuery = AddressOcrParser.normalizeSearchText(query)
        val radiusValue = radius.toFloatOrNull()
        val latitudeValue = latitude.toDoubleOrNull()
        val longitudeValue = longitude.toDoubleOrNull()
        if (name.isBlank()) {
            error = "Enter a label such as Home or Work."
            return
        }
        if (radiusValue == null || !radiusValue.isFinite() || radiusValue !in 100f..10_000f) {
            error = "Radius must be from 100 to 10,000 meters."
            return
        }
        val coordinatesValid = latitudeValue != null && latitudeValue in -90.0..90.0 &&
            longitudeValue != null && longitudeValue in -180.0..180.0
        if (!coordinatesValid && normalizedQuery.length < 5) {
            error = "Choose a map pin or enter an address."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val coordinates = if (coordinatesValid) {
                    latitudeValue!! to longitudeValue!!
                } else {
                    geocode(context, normalizedQuery).firstOrNull()?.let { it.latitude to it.longitude }
                }
                if (coordinates == null) {
                    error = "That address was not found. Check it or choose a map pin."
                    return@launch
                }
                val rule = PlaceRule(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    latitude = coordinates.first,
                    longitude = coordinates.second,
                    radiusMeters = radiusValue,
                    audioMode = audioMode,
                    enabled = existing?.enabled ?: true
                )
                if (rule.isValid()) onSave(rule) else error = "Check the place details and try again."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not save this place. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(query) {
        val normalized = AddressOcrParser.normalizeSearchText(query)
        if (normalized.length < 5 || normalized == lastResolvedQuery) return@LaunchedEffect
        delay(900)
        if (busy || normalized != AddressOcrParser.normalizeSearchText(query)) return@LaunchedEffect
        busy = true
        try {
            val found = geocode(context, normalized)
            if (found.isNotEmpty() && normalized == AddressOcrParser.normalizeSearchText(query)) {
                select(found.first())
                results = found
                notice = "Pin filled from the first result."
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        } finally {
            busy = false
        }
    }

    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            busy = true
            error = null
            notice = null
            results = emptyList()
            scope.launch {
                try {
                    val guess = AddressOcrParser.parse(recognizeScreenshot(context, uri))
                    if (!guess.address.isNullOrBlank()) query = guess.address
                    if (guess.latitude != null && guess.longitude != null) {
                        latitude = guess.latitude.toString()
                        longitude = guess.longitude.toString()
                        notice = "Coordinates read from the screenshot."
                    } else if (!guess.address.isNullOrBlank()) {
                        val found = geocode(context, guess.address)
                        if (found.isEmpty()) {
                            error = "The address was read, but no map result matched it."
                        } else {
                            select(found.first())
                            results = found
                            notice = "Address read and pin set."
                        }
                    } else {
                        error = "No address or coordinates were recognized."
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "The screenshot could not be read. Paste the address or choose a map pin."
                } finally {
                    busy = false
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        if (existing == null) "Add quiet place" else "Edit quiet place",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(onClick = { save() }, enabled = !busy) { Text("Save") }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    EditorSection("Label") {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; error = null },
                            label = { Text("Name") },
                            placeholder = { Text("Home, Work, Library") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("This label is only for you.", style = MaterialTheme.typography.bodySmall)
                    }

                    EditorSection("Location") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = AddressOcrParser.normalizeSearchText(it)
                                if (query != lastResolvedQuery) {
                                    error = null
                                    notice = null
                                }
                            },
                            label = { Text("Address, place, or plus code") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = query.isNotBlank() && !busy,
                            onClick = { searchAddress(query) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Search") }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(enabled = !busy, onClick = {
                                openMapPicker(
                                    query = query,
                                    latitude = latitude,
                                    longitude = longitude,
                                    context = context,
                                    scope = scope,
                                    setBusy = { busy = it },
                                    setError = { error = it },
                                    setCenter = { center, zoom -> mapPickerCenter = center; mapPickerZoom = zoom }
                                )
                            }, modifier = Modifier.weight(1f)) { Text("Map") }
                            OutlinedButton(enabled = preciseLocation && !busy, onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val location = currentDeviceLocation(context)
                                        if (location == null) {
                                            error = "No location fix. Turn on Location and try again."
                                        } else {
                                            select(SearchPlace("Current location", location.latitude, location.longitude))
                                            notice = "Pin set to your current location."
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = "Could not read your location."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }, modifier = Modifier.weight(1f)) { Text("Current") }
                        }
                        results.forEach { place ->
                            TextButton(onClick = { select(place) }) { Text(place.label) }
                        }
                        val lat = latitude.toDoubleOrNull()
                        val lng = longitude.toDoubleOrNull()
                        if (lat != null && lng != null) {
                            Text(
                                String.format(Locale.US, "Pin: %.6f, %.6f", lat, lng),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    EditorSection("Radius") {
                        OutlinedTextField(
                            value = radius,
                            onValueChange = { radius = it.filter(Char::isDigit); error = null },
                            label = { Text("Meters") },
                            supportingText = { Text("100 to 10,000") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    EditorSection("On arrival") {
                        PlaceAudioMode.entries.forEach { mode ->
                            AudioModeRow(
                                mode = mode,
                                selected = mode == audioMode,
                                onClick = { modeName = mode.name }
                            )
                        }
                    }

                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Advanced location", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text(if (advanced) "⌃" else "⌄")
                        }
                        if (advanced) {
                            HorizontalDivider()
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(enabled = !busy, onClick = {
                                    screenshotPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }) { Text("Read screenshot") }
                                Text(
                                    "Text recognition runs on this device. One Tap DND does not save the image.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedTextField(
                                    latitude,
                                    { latitude = it },
                                    label = { Text("Latitude") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    longitude,
                                    { longitude = it },
                                    label = { Text("Longitude") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                val lat = latitude.toDoubleOrNull()
                                val lng = longitude.toDoubleOrNull()
                                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                                    TextButton(onClick = { openMap(context, lat, lng, name) }) {
                                        Text("Preview in Maps")
                                    }
                                }
                            }
                        }
                    }

                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    mapPickerCenter?.let { center ->
        LocationMapPicker(
            initialLatitude = center.first,
            initialLongitude = center.second,
            initialZoom = mapPickerZoom,
            onSearch = { searchText ->
                geocode(context, searchText).firstOrNull()?.let { it.latitude to it.longitude }
            },
            onDismiss = { mapPickerCenter = null },
            onLocationChosen = { chosenLatitude, chosenLongitude ->
                latitude = chosenLatitude.toString()
                longitude = chosenLongitude.toString()
                lastResolvedQuery = AddressOcrParser.normalizeSearchText(query)
                notice = "Coordinates set from the map pin."
                error = null
                mapPickerCenter = null
            }
        )
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun AudioModeRow(mode: PlaceAudioMode, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(audioModeLabel(mode), fontWeight = FontWeight.Medium)
                if (selected) {
                    Text(audioModeDescription(mode), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun openMapPicker(
    query: String,
    latitude: String,
    longitude: String,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    setCenter: (Pair<Double, Double>, Double) -> Unit
) {
    val lat = latitude.toDoubleOrNull()
    val lng = longitude.toDoubleOrNull()
    if (lat != null && lat in -90.0..90.0 && lng != null && lng in -180.0..180.0) {
        setCenter(lat to lng, 18.0)
        return
    }
    setBusy(true)
    setError(null)
    scope.launch {
        try {
            val found = if (query.isNotBlank()) geocode(context, query) else emptyList()
            val first = found.firstOrNull()
            setCenter(
                if (first == null) 0.0 to 0.0 else first.latitude to first.longitude,
                if (first == null) 2.0 else 18.0
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            setCenter(0.0 to 0.0, 2.0)
        } finally {
            setBusy(false)
        }
    }
}

private fun audioModeLabel(mode: PlaceAudioMode): String = when (mode) {
    PlaceAudioMode.DND_ONLY -> "DND only"
    PlaceAudioMode.DND_AND_SILENT -> "DND + Silent"
    PlaceAudioMode.DND_SILENT_MEDIA_ZERO -> "DND + Silent + Media 0"
}

private fun audioModeDescription(mode: PlaceAudioMode): String = when (mode) {
    PlaceAudioMode.DND_ONLY -> "Uses priority DND. Ringer and media volume stay unchanged."
    PlaceAudioMode.DND_AND_SILENT -> "Adds silent ringer. Media and alarm volume stay unchanged."
    PlaceAudioMode.DND_SILENT_MEDIA_ZERO -> "Adds silent ringer and holds media volume at zero."
}

private fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}

private fun formatTime(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

@SuppressLint("MissingPermission")
private suspend fun currentDeviceLocation(context: android.content.Context): Location? =
    suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellation.cancel() }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(5_000)
            .setDurationMillis(15_000)
            .build()
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
    }

private fun distanceToRule(location: Location, rule: PlaceRule): Float {
    val result = FloatArray(1)
    Location.distanceBetween(
        location.latitude,
        location.longitude,
        rule.latitude,
        rule.longitude,
        result
    )
    return result[0]
}

private fun formatDistance(meters: Float): String = when {
    meters < 1_000f -> "${meters.toInt()} m away"
    meters < 10_000f -> String.format(Locale.getDefault(), "%.1f km away", meters / 1_000f)
    else -> "${(meters / 1_000f).toInt()} km away"
}

private suspend fun geocode(context: android.content.Context, searchText: String): List<SearchPlace> {
    if (!Geocoder.isPresent()) throw IllegalStateException("Geocoder unavailable")
    val normalized = AddressOcrParser.normalizeSearchText(searchText)
    if (normalized.isBlank()) return emptyList()
    return withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        Geocoder(context).getFromLocationName(normalized, 5).orEmpty().map {
            SearchPlace(
                label = it.getAddressLine(0) ?: it.featureName ?: normalized,
                latitude = it.latitude,
                longitude = it.longitude
            )
        }
    }
}

private suspend fun recognizeScreenshot(context: android.content.Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(context, uri)
            Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS).text
        } finally {
            recognizer.close()
        }
    }

private fun openMap(context: android.content.Context, latitude: Double, longitude: Double, name: String) {
    val pin = Uri.encode("$latitude,$longitude ($name)")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$pin")))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
                )
            )
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                "Install a map app or browser to preview this place.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
