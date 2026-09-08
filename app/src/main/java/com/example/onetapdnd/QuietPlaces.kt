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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

@Composable
fun QuietPlaces() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember { PlaceStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
                if (store.rules().isNotEmpty()) PlaceMonitoring.schedule(context)
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
    val foregroundRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        PlaceMonitoring.schedule(context)
    }
    val backgroundRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        revision++
        PlaceMonitoring.schedule(context)
    }
    fun appSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")))
    }
    fun changed(updated: List<PlaceRule>) {
        store.save(updated)
        runCatching { DndController(context).sync() }.onFailure {
            feedback = "Could not update DND. Check DND access and retry."
        }
        store.status("Updating place monitoring...")
        PlaceMonitoring.schedule(context)
    }

    SetupCard("Quiet places", "Turn on DND when you enter a saved area. Leave the area to end its mode.") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Uses your location in the background, even with the app closed. Saved places stay on this device. Google Play services handles location detection.",
                style = MaterialTheme.typography.bodySmall)
            if (!precise) {
                Button(onClick = {
                    foregroundRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Allow precise location") }
                TextButton(onClick = { appSettings() }) { Text("Location permission settings") }
            } else if (!background) {
                Text("For automatic arrivals, choose Permissions > Location > Allow all the time. Keep precise location on.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (Build.VERSION.SDK_INT == 29) backgroundRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    else appSettings()
                }) { Text("Allow background location") }
            }
            Text(remember(revision) { store.status() }, style = MaterialTheme.typography.bodyMedium)
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            rules.forEach { rule ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.titleSmall)
                        val distance = currentLocation?.let { distanceToRule(it, rule) }
                        val details = buildList {
                            add("${rule.radiusMeters.toInt()} m radius")
                            distance?.let { add(formatDistance(it)) }
                            add(if (rule.totalSilence) "Total silence" else "DND")
                        }.joinToString(" • ")
                        Text(details,
                            style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            !rule.enabled -> "Disabled"
                            rule.id in state.paused -> "Paused until you leave"
                            rule.id in state.inside -> "Inside area"
                            else -> "Waiting for arrival"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { enabled ->
                        changed(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                    })
                }
                Row {
                    TextButton(onClick = { editingId = rule.id; editorOpen = true }) { Text("Edit") }
                    TextButton(onClick = { openMap(context, rule.latitude, rule.longitude, rule.name) }) { Text("Map") }
                    TextButton(onClick = { deletingId = rule.id }) { Text("Delete") }
                }
            }
            Button(onClick = { editingId = null; editorOpen = true }, enabled = rules.size < 20) { Text("Add place") }
            if (rules.isNotEmpty()) TextButton(onClick = {
                feedback = null
                PlaceMonitoring.schedule(context)
            }) { Text("Retry monitoring") }
            if (!hasDnd) Text("Grant DND access above to activate saved places.", style = MaterialTheme.typography.bodySmall)
            Text("Radius: 100–10,000 meters. Arrivals can take a few minutes to detect. Keep device Location on. After force-stopping the app, open it again to restart monitoring.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editorOpen) {
        PlaceEditor(rules.firstOrNull { it.id == editingId }, precise,
            onDismiss = { editorOpen = false }, onSave = { rule ->
                changed(rules.filterNot { it.id == editingId } + rule)
                editorOpen = false
            })
    }
    deletingId?.let { id ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Delete place?") },
            text = { Text("This ends the place's mode if it is active.") },
            confirmButton = { TextButton(onClick = { changed(rules.filterNot { it.id == id }); deletingId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } })
    }
}

private data class SearchPlace(val label: String, val latitude: Double, val longitude: Double)

@SuppressLint("MissingPermission")
@Composable
private fun PlaceEditor(existing: PlaceRule?, preciseLocation: Boolean, onDismiss: () -> Unit, onSave: (PlaceRule) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf(existing?.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(existing?.longitude?.toString().orEmpty()) }
    var radius by rememberSaveable { mutableStateOf(existing?.radiusMeters?.toInt()?.toString() ?: "200") }
    var silence by rememberSaveable { mutableStateOf(existing?.totalSilence ?: false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(emptyList<SearchPlace>()) }
    var lastResolvedQuery by rememberSaveable { mutableStateOf("") }
    var mapPickerCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapPickerZoom by remember { mutableDoubleStateOf(18.0) }
    val candidate = PlaceRule("candidate", name.trim(), latitude.toDoubleOrNull() ?: Double.NaN,
        longitude.toDoubleOrNull() ?: Double.NaN, radius.toFloatOrNull() ?: Float.NaN,
        silence, existing?.enabled ?: true)
    fun select(place: SearchPlace) {
        latitude = place.latitude.toString(); longitude = place.longitude.toString()
        lastResolvedQuery = AddressOcrParser.normalizeSearchText(query)
        results = emptyList()
    }
    fun searchAddress(searchText: String) {
        val normalized = AddressOcrParser.normalizeSearchText(searchText)
        if (normalized.isBlank() || busy) return
        query = normalized
        busy = true; error = null; notice = null
        scope.launch {
            try {
                val found = geocode(context, normalized)
                if (found.isEmpty()) {
                    results = emptyList()
                    error = "No places found. Check the address or plus code and try again."
                } else {
                    select(found.first())
                    results = found
                    notice = "Coordinates filled from the first match. Choose another result if needed."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Address search failed. Check your connection or enter coordinates." }
            finally { busy = false }
        }
    }
    LaunchedEffect(query) {
        val normalized = AddressOcrParser.normalizeSearchText(query)
        if (normalized.length < 5 || normalized == lastResolvedQuery) return@LaunchedEffect
        delay(900)
        if (busy || normalized != AddressOcrParser.normalizeSearchText(query)) return@LaunchedEffect
        busy = true
        error = null
        try {
            val found = geocode(context, normalized)
            if (found.isNotEmpty() && normalized == AddressOcrParser.normalizeSearchText(query)) {
                select(found.first())
                results = found
                notice = "Coordinates filled automatically. Choose another match if the pin is wrong."
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Automatic lookup stays quiet; the Search button provides an explicit retry and error.
        } finally {
            busy = false
        }
    }
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true; error = null; notice = null; results = emptyList()
            scope.launch {
                try {
                    val guess = AddressOcrParser.parse(recognizeScreenshot(context, uri))
                    if (!guess.address.isNullOrBlank()) query = guess.address
                    if (guess.latitude != null && guess.longitude != null) {
                        latitude = guess.latitude.toString()
                        longitude = guess.longitude.toString()
                        if (query.isBlank()) query = "${guess.latitude}, ${guess.longitude}"
                        notice = "Coordinates read from screenshot. Preview the pin before saving."
                    } else if (!guess.address.isNullOrBlank()) {
                        try {
                            val found = geocode(context, guess.address)
                            results = found
                            if (found.isNotEmpty()) {
                                select(found.first())
                                results = found
                                notice = "Address read from screenshot. Check the pin or choose another match."
                            } else {
                                error = "The address was read, but no map match was found. Edit the address and search again."
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "The address was read, but its pin could not be looked up. Check your connection or enter coordinates."
                        }
                    } else {
                        error = "No address or coordinates were recognized. Try another screenshot or paste the address below."
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = "The screenshot could not be read. Try another image or paste the address below." }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add quiet place" else "Edit quiet place") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Place name") }, singleLine = true)
                OutlinedTextField(query, {
                    query = AddressOcrParser.normalizeSearchText(it)
                    if (query != lastResolvedQuery) {
                        error = null
                        notice = null
                    }
                }, label = { Text("Address, place, or plus code") }, singleLine = true,
                    supportingText = { Text("Coordinates fill automatically after you paste or type an address.") })
                Button(enabled = query.isNotBlank() && !busy, onClick = {
                    searchAddress(query)
                }) { Text("Search") }
                results.forEach { place -> TextButton(onClick = { select(place) }) { Text(place.label) } }
                OutlinedButton(enabled = !busy, onClick = {
                    screenshotPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Read address from screenshot") }
                Text("Select a screenshot from Maps or another app. Text recognition runs on this device, and the image is not saved by One Tap DND.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = preciseLocation && !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val location = currentDeviceLocation(context)
                            if (location == null) error = "No location fix. Turn on Location and try again outdoors."
                            else select(SearchPlace("Current location", location.latitude, location.longitude))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Could not read your location. Check precise location access." }
                        finally { busy = false }
                    }
                }) { Text("Use current location") }
                OutlinedButton(enabled = !busy, onClick = {
                    val currentLatitude = latitude.toDoubleOrNull()
                    val currentLongitude = longitude.toDoubleOrNull()
                    if (currentLatitude != null && currentLatitude in -90.0..90.0 &&
                        currentLongitude != null && currentLongitude in -180.0..180.0) {
                        mapPickerCenter = currentLatitude to currentLongitude
                        mapPickerZoom = 18.0
                    } else {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val found = if (query.isNotBlank()) geocode(context, query) else emptyList()
                                if (found.isNotEmpty()) {
                                    mapPickerCenter = found.first().latitude to found.first().longitude
                                    mapPickerZoom = 18.0
                                } else {
                                    mapPickerCenter = 0.0 to 0.0
                                    mapPickerZoom = 2.0
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                mapPickerCenter = 0.0 to 0.0
                                mapPickerZoom = 2.0
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text("Choose on map") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("You can also copy coordinates from a dropped pin in Google Maps.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude (−90 to 90)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude (−180 to 180)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                if (candidate.latitude.isFinite() && candidate.latitude in -90.0..90.0 &&
                    candidate.longitude.isFinite() && candidate.longitude in -180.0..180.0) {
                    TextButton(onClick = { openMap(context, candidate.latitude, candidate.longitude, name) }) { Text("Preview in Maps") }
                }
                OutlinedTextField(radius, { radius = it }, label = { Text("Radius in meters") },
                    supportingText = { Text("100 to 10,000. Start with 200 m.") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("On arrival: Do Not Disturb", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use total silence", Modifier.weight(1f))
                    Switch(checked = silence, onCheckedChange = { silence = it })
                }
                Text(if (silence) "Total silence also mutes media and alarms. In-call audio is unaffected."
                    else "DND uses Android's priority interruptions policy.", style = MaterialTheme.typography.bodySmall)
                Text("On leaving: end this place's mode. Other active places and modes stay in effect.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            val normalizedQuery = AddressOcrParser.normalizeSearchText(query)
            val radiusValue = radius.toFloatOrNull()
            val radiusIsValid = radiusValue != null && radiusValue.isFinite() && radiusValue in 100f..10_000f
            val coordinatesAreValid = candidate.latitude.isFinite() && candidate.latitude in -90.0..90.0 &&
                candidate.longitude.isFinite() && candidate.longitude in -180.0..180.0
            val canResolveAddress = normalizedQuery.length >= 5
            TextButton(enabled = !busy && name.isNotBlank() && radiusIsValid &&
                (coordinatesAreValid || canResolveAddress), onClick = {
                val direct = candidate.copy(name = name.trim())
                if (direct.isValid()) {
                    // A new ID prevents delayed transitions for old coordinates from applying after an edit.
                    onSave(direct.copy(id = UUID.randomUUID().toString()))
                } else {
                    busy = true; error = null; notice = null
                    scope.launch {
                        try {
                            val found = geocode(context, normalizedQuery)
                            if (found.isEmpty()) {
                                error = "That address could not be located. Check it or choose a search result."
                            } else {
                                val place = found.first()
                                val resolved = PlaceRule(
                                    id = UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    latitude = place.latitude,
                                    longitude = place.longitude,
                                    radiusMeters = radiusValue!!,
                                    totalSilence = silence,
                                    enabled = existing?.enabled ?: true
                                )
                                latitude = place.latitude.toString()
                                longitude = place.longitude.toString()
                                results = found
                                lastResolvedQuery = normalizedQuery
                                if (resolved.isValid()) onSave(resolved)
                                else error = "The map result was invalid. Choose another result or enter coordinates."
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Address lookup failed. Check your connection and try Save place again."
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text("Save place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })

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
    meters < 10_000f -> String.format(java.util.Locale.getDefault(), "%.1f km away", meters / 1_000f)
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

private suspend fun recognizeScreenshot(context: android.content.Context, uri: Uri): String = withContext(Dispatchers.IO) {
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
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")))
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "Install a map app or browser to preview this place.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
