package com.example.onetapdnd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun LocationMapPicker(
    initialLatitude: Double,
    initialLongitude: Double,
    initialZoom: Double,
    onSearch: suspend (String) -> Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onLocationChosen: (Double, Double) -> Unit
) {
    val camera = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = initialLongitude, latitude = initialLatitude),
            zoom = initialZoom
        )
    )
    val selected = camera.position.target
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
                    cameraState = camera,
                    onMapClick = { position, _ ->
                        camera.position = camera.position.copy(target = position)
                        ClickResult.Consume
                    }
                )
                MapPin(Modifier.align(Alignment.Center))
                Surface(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Choose exact location", style = MaterialTheme.typography.titleLarge)
                        Text("Move the map until the pin is on the building. Tap the map to center the pin there.")
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it; searchError = null },
                                label = { Text("Search address or place") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                enabled = searchText.isNotBlank() && !searching,
                                onClick = {
                                    searching = true
                                    searchError = null
                                    scope.launch {
                                        try {
                                            val result = onSearch(searchText)
                                            if (result == null) {
                                                searchError = "No matching place found."
                                            } else {
                                                camera.position = camera.position.copy(
                                                    target = Position(
                                                        longitude = result.second,
                                                        latitude = result.first
                                                    ),
                                                    zoom = 18.0
                                                )
                                            }
                                        } catch (_: Exception) {
                                            searchError = "Search failed. Check your connection and try again."
                                        } finally {
                                            searching = false
                                        }
                                    }
                                }
                            ) { Text("Search") }
                        }
                        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                        searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            String.format(Locale.US, "%.7f, %.7f", selected.latitude, selected.longitude),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Button(onClick = { onLocationChosen(selected.latitude, selected.longitude) }) {
                                Text("Use this location")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPin(modifier: Modifier = Modifier) {
    Canvas(modifier.size(42.dp)) {
        val center = this.center
        drawCircle(Color.White, radius = 12.dp.toPx(), center = center)
        drawCircle(Color(0xFFD32F2F), radius = 9.dp.toPx(), center = center)
        drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 12.dp.toPx(), center = center,
            style = Stroke(width = 1.dp.toPx()))
    }
}
