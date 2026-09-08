package com.example.onetapdnd

import android.Manifest
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.onetapdnd.ui.theme.OneTapDNDTheme

enum class IconStyle { BLACK, WHITE }

class MainActivity : ComponentActivity() {

    private var dndGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var selectedIcon by mutableStateOf(IconStyle.BLACK)
    private var pendingIconStyle: IconStyle? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
        runCatching { MonitoringCoordinator(this).reconcile() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedIcon = requestedIconStyle()
        reconcileIconAliases(selectedIcon)
        enableEdgeToEdge()
        setContent {
            OneTapDNDTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(
                        dndGranted = dndGranted,
                        notificationsGranted = notificationsGranted,
                        selectedIcon = selectedIcon,
                        onGrantDnd = { openDndPermissionSettings() },
                        onGrantNotifications = { requestNotificationPermission() },
                        onAddTile = { requestTileAddition() },
                        onIconStyleSelected = { requestIconStyle(it) },
                        placesContent = { QuietPlaces() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dndGranted = getSystemService(NotificationManager::class.java)
            .isNotificationPolicyAccessGranted
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        runCatching {
            MonitoringCoordinator(this).reconcile()
            if (PlaceStore(this).rules().isNotEmpty()) PlaceMonitoring.schedule(this)
        }
    }

    override fun onStop() {
        pendingIconStyle?.let {
            pendingIconStyle = null
            applyIconStyle(it)
        }
        super.onStop()
    }

    private fun openDndPermissionSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestTileAddition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, DndTileService::class.java),
                getString(R.string.tile_label),
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_dnd),
                mainExecutor
            ) { }
        }
    }

    private fun requestIconStyle(style: IconStyle) {
        selectedIcon = style
        getSharedPreferences(ICON_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(ICON_STYLE_KEY, style.name)
            .apply()
        pendingIconStyle = style
        Toast.makeText(this, R.string.icon_change_pending, Toast.LENGTH_SHORT).show()
    }

    private fun requestedIconStyle(): IconStyle {
        val saved = getSharedPreferences(ICON_PREFERENCES, MODE_PRIVATE)
            .getString(ICON_STYLE_KEY, null)
        return runCatching { IconStyle.valueOf(saved.orEmpty()) }.getOrNull()
            ?: runCatching { currentIconStyle() }.getOrDefault(IconStyle.BLACK)
    }

    private fun currentIconStyle(): IconStyle {
        val whiteEnabled = isComponentEnabled(launcherAlias(WHITE_ALIAS))
        return if (whiteEnabled) IconStyle.WHITE else IconStyle.BLACK
    }

    private fun launcherAlias(simpleName: String): ComponentName {
        val basePackage = MainActivity::class.java.name.substringBeforeLast('.')
        return ComponentName(this, "$basePackage.$simpleName")
    }

    private fun reconcileIconAliases(style: IconStyle) {
        val blackAlias = launcherAlias(BLACK_ALIAS)
        val whiteAlias = launcherAlias(WHITE_ALIAS)
        val desired = if (style == IconStyle.BLACK) blackAlias else whiteAlias
        val other = if (style == IconStyle.BLACK) whiteAlias else blackAlias
        val aliasesCorrect = runCatching {
            isComponentEnabled(desired) && !isComponentEnabled(other)
        }.getOrDefault(false)
        if (!aliasesCorrect) {
            pendingIconStyle = style
        }
    }

    private fun isComponentEnabled(component: ComponentName): Boolean {
        return when (packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> packageManager.getActivityInfo(component, 0).isEnabled
        }
    }

    private fun applyIconStyle(style: IconStyle, showFailure: Boolean = true) {
        val blackAlias = launcherAlias(BLACK_ALIAS)
        val whiteAlias = launcherAlias(WHITE_ALIAS)
        val (enableAlias, disableAlias) = when (style) {
            IconStyle.BLACK -> blackAlias to whiteAlias
            IconStyle.WHITE -> whiteAlias to blackAlias
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    listOf(
                        PackageManager.ComponentEnabledSetting(
                            enableAlias,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        ),
                        PackageManager.ComponentEnabledSetting(
                            disableAlias,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    )
                )
            } else {
                packageManager.setComponentEnabledSetting(
                    enableAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                packageManager.setComponentEnabledSetting(
                    disableAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }.onFailure {
            if (showFailure) {
                Toast.makeText(this, R.string.icon_change_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val ICON_PREFERENCES = "launcher_icon"
        const val ICON_STYLE_KEY = "style"
        const val BLACK_ALIAS = "MainActivityBlackIcon"
        const val WHITE_ALIAS = "MainActivityWhiteIcon"
    }
}

@Composable
fun SetupScreen(
    dndGranted: Boolean,
    notificationsGranted: Boolean,
    selectedIcon: IconStyle,
    onGrantDnd: () -> Unit,
    onGrantNotifications: () -> Unit,
    onAddTile: () -> Unit,
    onIconStyleSelected: (IconStyle) -> Unit,
    modifier: Modifier = Modifier,
    placesContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_dnd),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        StatusCard(
            title = stringResource(R.string.dnd_access_title),
            status = if (dndGranted) stringResource(R.string.allowed) else stringResource(R.string.action_required),
            detail = stringResource(R.string.step1_description),
            actionLabel = if (dndGranted) null else stringResource(R.string.grant_permission),
            onAction = onGrantDnd
        )
        StatusCard(
            title = stringResource(R.string.notifications_title),
            status = if (notificationsGranted) stringResource(R.string.allowed) else stringResource(R.string.action_required),
            detail = stringResource(R.string.notifications_description),
            actionLabel = if (notificationsGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null
                else stringResource(R.string.allow_notifications),
            onAction = onGrantNotifications
        )
        StatusCard(
            title = stringResource(R.string.quick_tile_title),
            status = stringResource(R.string.quick_tile_status),
            detail = stringResource(R.string.step2_description),
            actionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                stringResource(R.string.add_tile) else null,
            onAction = onAddTile
        )

        placesContent()

        CollapsibleCard(
            title = stringResource(R.string.icon_style_title),
            summary = stringResource(R.string.icon_style_summary),
            detail = stringResource(R.string.icon_style_description)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconStyleRow(
                    label = stringResource(R.string.icon_black),
                    style = IconStyle.BLACK,
                    selected = selectedIcon == IconStyle.BLACK,
                    onClick = { onIconStyleSelected(IconStyle.BLACK) }
                )
                IconStyleRow(
                    label = stringResource(R.string.icon_white),
                    style = IconStyle.WHITE,
                    selected = selectedIcon == IconStyle.WHITE,
                    onClick = { onIconStyleSelected(IconStyle.WHITE) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(
    title: String,
    status: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    CollapsibleCard(
        title = title,
        summary = status,
        detail = detail,
        alwaysContent = {
        if (actionLabel != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
        }
    )
}

@Composable
fun CollapsibleCard(
    title: String,
    summary: String,
    detail: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    alwaysContent: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        contentDescription = "$title information"
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (expanded) "⌃" else "ⓘ", style = MaterialTheme.typography.titleLarge)
            }
            alwaysContent?.invoke()
            if (expanded) {
                HorizontalDivider()
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content?.invoke()
            }
        }
    }
}

@Composable
private fun IconStyleRow(
    label: String,
    style: IconStyle,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onClick),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            IconPreview(style)
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(if (selected) "✓" else "", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun IconPreview(style: IconStyle) {
    val background = if (style == IconStyle.BLACK) Color.White else Color.Black
    val foreground = if (style == IconStyle.BLACK) Color.Black else Color.White
    Surface(modifier = Modifier.size(44.dp), shape = CircleShape, color = background) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_dnd_black),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = foreground
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}
