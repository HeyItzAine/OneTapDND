package com.example.onetapdnd

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.location.Geocoder
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
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
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
                        Text("${rule.radiusMeters.toInt()} m • ${if (rule.totalSilence) "Total silence" else "DND"}",
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

private data class SearchPlace(val label: String, val latitude: Double, val longitude: Double, val name: String?)

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
    val candidate = PlaceRule("candidate", name.trim(), latitude.toDoubleOrNull() ?: Double.NaN,
        longitude.toDoubleOrNull() ?: Double.NaN, radius.toFloatOrNull() ?: Float.NaN,
        silence, existing?.enabled ?: true)
    fun select(place: SearchPlace, suggestedName: String? = null) {
        latitude = place.latitude.toString(); longitude = place.longitude.toString()
        if (name.isBlank()) name = suggestedName ?: place.name ?: place.label
        results = emptyList()
    }
    fun searchAddress(searchText: String) {
        if (searchText.isBlank() || busy) return
        busy = true; error = null; notice = null
        scope.launch {
            try {
                val found = geocode(context, searchText)
                results = found
                if (found.isEmpty()) error = "No places found. Try a fuller address or enter coordinates."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Address search failed. Check your connection or enter coordinates." }
            finally { busy = false }
        }
    }
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true; error = null; notice = null; results = emptyList()
            scope.launch {
                try {
                    val guess = AddressOcrParser.parse(recognizeScreenshot(context, uri))
                    if (name.isBlank() && !guess.placeName.isNullOrBlank()) name = guess.placeName
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
                                select(found.first(), guess.placeName)
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
                        error = "No address or coordinates were found. Crop the screenshot around the address and try again."
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = "The screenshot could not be read. Try a clearer or more tightly cropped image." }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add quiet place" else "Edit quiet place") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Place name") }, singleLine = true)
                OutlinedTextField(query, { query = it }, label = { Text("Search address or place") }, singleLine = true)
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
                            val location = suspendCancellableCoroutine { continuation ->
                                val cancellation = CancellationTokenSource()
                                continuation.invokeOnCancellation { cancellation.cancel() }
                                val request = CurrentLocationRequest.Builder()
                                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                                    .setMaxUpdateAgeMillis(5_000).setDurationMillis(15_000).build()
                                LocationServices.getFusedLocationProviderClient(context)
                                    .getCurrentLocation(request, cancellation.token)
                                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                                    .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
                            }
                            if (location == null) error = "No location fix. Turn on Location and try again outdoors."
                            else select(SearchPlace("Current location", location.latitude, location.longitude, "Current location"))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Could not read your location. Check precise location access." }
                        finally { busy = false }
                    }
                }) { Text("Use current location") }
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
        confirmButton = { TextButton(enabled = candidate.isValid() && !busy, onClick = {
            // A new ID prevents delayed transitions for the old coordinates from applying after an edit.
            onSave(candidate.copy(id = UUID.randomUUID().toString()))
        }) { Text("Save place") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private suspend fun geocode(context: android.content.Context, searchText: String): List<SearchPlace> {
    if (!Geocoder.isPresent()) throw IllegalStateException("Geocoder unavailable")
    return withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        Geocoder(context).getFromLocationName(searchText, 5).orEmpty().map {
            SearchPlace(
                label = it.getAddressLine(0) ?: it.featureName ?: searchText,
                latitude = it.latitude,
                longitude = it.longitude,
                name = it.featureName?.takeUnless { feature -> feature == it.getAddressLine(0) }
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
