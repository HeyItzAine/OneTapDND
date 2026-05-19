package com.example.onetapdnd

import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.onetapdnd.ui.theme.OneTapDNDTheme

class MainActivity : ComponentActivity() {

    private var permissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OneTapDNDTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(
                        permissionGranted = permissionGranted,
                        onGrantPermission = { openDndPermissionSettings() },
                        onAddTile = { requestTileAddition() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val nm = getSystemService(NotificationManager::class.java)
        permissionGranted = nm.isNotificationPolicyAccessGranted
    }

    private fun openDndPermissionSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun requestTileAddition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                ComponentName(this, DndTileService::class.java),
                getString(R.string.tile_label),
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_dnd),
                mainExecutor
            ) { /* result ignored - system handles UI feedback */ }
        }
    }
}

@Composable
fun SetupScreen(
    permissionGranted: Boolean,
    onGrantPermission: () -> Unit,
    onAddTile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        androidx.compose.material3.Icon(
            painter = painterResource(id = R.drawable.ic_dnd),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.setup_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Step 1: Permission
        SetupCard(
            title = stringResource(R.string.step1_title),
            description = stringResource(R.string.step1_description)
        ) {
            if (permissionGranted) {
                Text(
                    text = stringResource(R.string.permission_granted),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Button(onClick = onGrantPermission) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Step 2: Add tile
        SetupCard(
            title = stringResource(R.string.step2_title),
            description = stringResource(R.string.step2_description)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = onAddTile,
                    enabled = permissionGranted
                ) {
                    Text(stringResource(R.string.add_tile))
                }
            }
        }

        if (permissionGranted) {
            Spacer(modifier = Modifier.height(16.dp))

            SetupCard(
                title = stringResource(R.string.done_title),
                description = stringResource(R.string.done_description)
            )
        }
    }
}

@Composable
fun SetupCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (action != null) {
                Spacer(modifier = Modifier.height(12.dp))
                action()
            }
        }
    }
}
