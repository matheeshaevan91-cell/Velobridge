package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.domain.model.VpnConnectionState
import com.example.ui.screens.BridgeServerScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DisguiseAndSettingsScreen
import com.example.ui.screens.ProfileEditorScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.theme.VeloBridgeTheme
import com.example.ui.viewmodel.VeloViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object DashboardRoute
@Serializable object ProfilesRoute
@Serializable object BridgeServerRoute
@Serializable object DisguiseRoute
@Serializable data class ProfileEditorRoute(val profileId: String? = null)

private data class TopNavItem(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: Any,
    val tag: String
)

private val TopNavItems = listOf(
    TopNavItem(
        labelRes = R.string.nav_connect,
        selectedIcon = Icons.Filled.PowerSettingsNew,
        unselectedIcon = Icons.Outlined.PowerSettingsNew,
        route = DashboardRoute,
        tag = "nav_connect"
    ),
    TopNavItem(
        labelRes = R.string.nav_profiles,
        selectedIcon = Icons.Filled.ListAlt,
        unselectedIcon = Icons.Outlined.ListAlt,
        route = ProfilesRoute,
        tag = "nav_profiles"
    ),
    TopNavItem(
        labelRes = R.string.nav_bridge,
        selectedIcon = Icons.Filled.Router,
        unselectedIcon = Icons.Outlined.Router,
        route = BridgeServerRoute,
        tag = "nav_bridge"
    ),
    TopNavItem(
        labelRes = R.string.nav_disguise,
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        route = DisguiseRoute,
        tag = "nav_disguise"
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VeloViewModel = viewModel()
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()

            VeloBridgeTheme(darkTheme = preferences.darkTheme) {
                VeloBridgeRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun VeloBridgeRoot(viewModel: VeloViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val isScanningLan by viewModel.isScanningLan.collectAsStateWithLifecycle()
    val lanServers by viewModel.lanServers.collectAsStateWithLifecycle()
    val isProbingDisguise by viewModel.isProbingDisguise.collectAsStateWithLifecycle()
    val disguiseProbeResult by viewModel.disguiseProbeResult.collectAsStateWithLifecycle()
    val testingAllLatencies by viewModel.testingAllLatencies.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    var pendingStartAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnConnection(context)
        } else {
            viewModel.showBanner("VPN permission is required to route device traffic.")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingStartAction?.invoke()
        pendingStartAction = null
    }

    fun runWithNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                action()
            } else {
                pendingStartAction = action
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            action()
        }
    }

    fun handleToggleVpn() {
        if (telemetry.state == VpnConnectionState.CONNECTED ||
            telemetry.state == VpnConnectionState.CONNECTING
        ) {
            viewModel.stopVpnConnection(context)
            return
        }
        if (activeProfile == null) {
            viewModel.showBanner("Import a V2Ray config or pair a Phone Bridge first.")
            return
        }
        runWithNotificationPermission {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.startVpnConnection(context)
            }
        }
    }

    fun handleToggleServer() {
        runWithNotificationPermission {
            viewModel.toggleBridgeServer(context)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isTopLevelRoute = TopNavItems.any { item ->
        currentDestination?.hasRoute(item.route::class) == true
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (isTopLevelRoute && !useNavigationRail) {
                    NavigationBar {
                        TopNavItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isTopLevelRoute && useNavigationRail) {
                    NavigationRail {
                        TopNavItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.tag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = DashboardRoute,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (isTopLevelRoute) Modifier.statusBarsPadding() else Modifier)
                        .then(if (useNavigationRail && isTopLevelRoute) Modifier.navigationBarsPadding() else Modifier)
                ) {
                    composable<DashboardRoute> {
                        DashboardScreen(
                            activeProfile = activeProfile,
                            telemetry = telemetry,
                            serverIsRunning = serverState.isRunning,
                            onToggleConnection = { handleToggleVpn() },
                            onSelectDisguise = { presetId, customDomain ->
                                viewModel.applyQuickDisguise(presetId, customDomain)
                            },
                            onPingActive = { profile -> viewModel.testProfileLatency(profile) },
                            onNavigateToProfiles = {
                                navController.navigate(ProfilesRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToBridge = {
                                navController.navigate(BridgeServerRoute) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }

                    composable<ProfilesRoute> {
                        ProfilesScreen(
                            profiles = profiles,
                            activeProfileId = activeProfile?.id,
                            isScanningLan = isScanningLan,
                            lanServers = lanServers,
                            isTestingAll = testingAllLatencies,
                            onSelectProfile = { id ->
                                viewModel.selectProfile(id)
                                viewModel.showBanner("Active profile updated")
                            },
                            onCreateProfile = {
                                navController.navigate(ProfileEditorRoute(profileId = null))
                            },
                            onEditProfile = { id ->
                                navController.navigate(ProfileEditorRoute(profileId = id))
                            },
                            onDeleteProfile = { id -> viewModel.deleteProfile(id) },
                            onPingProfile = { profile -> viewModel.testProfileLatency(profile) },
                            onTestAll = { viewModel.testAllLatencies() },
                            onImportText = { raw, cb -> viewModel.importConfigText(raw, cb) },
                            onImportFileUri = { ctx, uri, cb -> viewModel.importConfigFromUri(ctx, uri, cb) },
                            onScanLanServers = { viewModel.scanLanBridgeServers() },
                            onAdoptLanServer = { srv -> viewModel.saveDiscoveredServerAsProfile(srv) },
                            onNotify = { msg ->
                                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        )
                    }

                    composable<BridgeServerRoute> {
                        BridgeServerScreen(
                            serverState = serverState,
                            logs = logs,
                            onToggleServer = { handleToggleServer() },
                            onUpdateDraft = { port, disguiseId, custom, cipher, wsPath, lan ->
                                viewModel.updateServerConfigDraft(port, disguiseId, custom, cipher, wsPath, lan)
                            },
                            onRegenerateUuid = { viewModel.regenerateServerUuid() },
                            onRefreshIps = { viewModel.refreshLocalIps() },
                            onNotify = { msg -> viewModel.showBanner(msg) }
                        )
                    }

                    composable<DisguiseRoute> {
                        DisguiseAndSettingsScreen(
                            activeProfile = activeProfile,
                            preferences = preferences,
                            isProbing = isProbingDisguise,
                            probeResult = disguiseProbeResult,
                            logs = logs,
                            onApplyDisguise = { presetId, custom ->
                                viewModel.applyQuickDisguise(presetId, custom)
                            },
                            onRunProbe = { domain -> viewModel.runDisguiseProbe(domain) },
                            onSetRoutingMode = { mode -> viewModel.setRoutingMode(mode) },
                            onSetDns = { dns ->
                                viewModel.setDnsServer(dns)
                                viewModel.showBanner("DNS updated to $dns")
                            },
                            onSetBypassLan = { enabled -> viewModel.setBypassLan(enabled) },
                            onSetDarkTheme = { dark -> viewModel.setDarkTheme(dark) },
                            onClearLogs = { viewModel.clearLogs() }
                        )
                    }

                    composable<ProfileEditorRoute> { entry ->
                        val route = entry.toRoute<ProfileEditorRoute>()
                        val existing = profiles.find { it.id == route.profileId }
                        ProfileEditorScreen(
                            existingProfile = existing,
                            onSave = { saved -> viewModel.saveProfile(saved) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
