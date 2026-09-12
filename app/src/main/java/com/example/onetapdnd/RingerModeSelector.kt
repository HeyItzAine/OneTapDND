package com.example.onetapdnd

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun RingerModeSelector(selected: RingerMode, onSelected: (RingerMode) -> Unit) {
    val track = Color(0xFF211F1E)
    val thumb = Color(0xFFD0CBC9)
    Column(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val trackHeight = maxWidth / 2.3f
            val diameter = trackHeight - 28.dp
            val thumbOffset by animateDpAsState(
                maxWidth / 3 * (selected.ordinal + 0.5f) - diameter / 2,
                animationSpec = tween(220), label = "Ringer position"
            )
            Box(Modifier.fillMaxWidth().height(trackHeight).clip(CircleShape).background(track)) {
                Box(
                    Modifier.offset(x = thumbOffset, y = 14.dp).size(diameter)
                        .background(thumb, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(when (selected) {
                            RingerMode.SILENT -> R.drawable.ic_ringer_silent
                            RingerMode.VIBRATE -> R.drawable.ic_ringer_vibrate
                            RingerMode.SOUND -> R.drawable.ic_ringer_sound
                        }),
                        contentDescription = null,
                        tint = track,
                        modifier = Modifier.size(diameter * 0.42f)
                    )
                }
                Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
                    RingerMode.entries.forEach { mode ->
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .selectable(
                                    selected = selected == mode,
                                    role = Role.RadioButton,
                                    onClick = { onSelected(mode) }
                                ).semantics { contentDescription = mode.label },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected != mode) {
                                Box(Modifier.size(11.dp).background(Color(0xFF969291), CircleShape))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            RingerMode.entries.forEach { mode ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(mode.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
