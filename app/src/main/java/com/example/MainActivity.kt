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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.BridgeServerScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DisguiseLabScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ProfileEditorScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.VeloBridgeTheme
import com.example.ui.viewmodel.VeloViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object DashboardRoute
@Serializable object ProfilesRoute
@Serializable object AnalyticsRoute
@Serializable object BridgeServerRoute
@Serializable object SettingsRoute
@Serializable object DisguiseLabRoute
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
        selectedIcon = Icons.AutoMirrored.Filled.ListAlt,
        unselectedIcon = Icons.AutoMirrored.Outlined.ListAlt,
        route = ProfilesRoute,
        tag = "nav_profiles"
    ),
    TopNavItem(
        labelRes = R.string.nav_analytics,
        selectedIcon = Icons.Filled.Analytics,
        unselectedIcon = Icons.Outlined.Analytics,
        route = AnalyticsRoute,
        tag = "nav_analytics"
    ),
    TopNavItem(
        labelRes = R.string.nav_bridge,
        selectedIcon = Icons.Filled.Router,
        unselectedIcon = Icons.Outlined.Router,
        route = BridgeServerRoute,
        tag = "nav_bridge"
    ),
    TopNavItem(
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        route = SettingsRoute,
        tag = "nav_settings"
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VeloViewModel = viewModel()
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()

            VeloBridgeTheme(appearanceMode = preferences.appearanceMode) {
                VeloBridgeRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun VeloBridgeRoot(viewModel: VeloViewModel) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val filteredProfiles by viewModel.filteredProfiles.collectAsStateWithLifecycle()
    val profileFilter by viewModel.profileFilter.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val securityReport by viewModel.securityReport.collectAsStateWithLifecycle()
    val isScanningLan by viewModel.isScanningLan.collectAsStateWithLifecycle()
    val lanServers by viewModel.lanServers.collectAsStateWithLifecycle()
    val clientPairingSession by viewModel.clientPairingSession.collectAsStateWithLifecycle()
    val isProbingDisguise by viewModel.isProbingDisguise.collectAsStateWithLifecycle()
    val disguiseProbeResult by viewModel.disguiseProbeResult.collectAsStateWithLifecycle()
    val testingAllLatencies by viewModel.testingAllLatencies.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var showOnboardingReplay by remember { mutableStateOf(false) }

    fun triggerHaptic(strong: Boolean = false) {
        if (preferences.hapticFeedback) {
            haptic.performHapticFeedback(
                if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
            )
        }
    }

    fun postSnackbar(msg: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    if (!preferences.onboardingCompleted || showOnboardingReplay) {
        OnboardingScreen(
            onFinish = {
                triggerHaptic(strong = true)
                showOnboardingReplay = false
                viewModel.completeOnboarding()
            }
        )
        return
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
        if (preferences.notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        triggerHaptic(strong = true)
        if (telemetry.state == VpnConnectionState.CONNECTED ||
            telemetry.state == VpnConnectionState.CONNECTING ||
            telemetry.state == VpnConnectionState.RECONNECTING
        ) {
            viewModel.stopVpnConnection(context)
            return
        }
        val profile = activeProfile
        if (profile == null) {
            viewModel.showBanner("Import a V2Ray config or pair a Phone Bridge first.")
            return
        }
        if (!profile.protocol.supportLevel.isNativeTunnelSupported) {
            viewModel.startVpnConnection(context)
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
        triggerHaptic(strong = true)
        runWithNotificationPermission {
            viewModel.toggleBridgeServer(context)
        }
    }

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
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
                                    triggerHaptic()
                                    navigateTopLevel(item.route)
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
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
                                    triggerHaptic()
                                    navigateTopLevel(item.route)
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
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
                            profiles = profiles,
                            activeProfile = activeProfile,
                            telemetry = telemetry,
                            serverIsRunning = serverState.isRunning,
                            highSpeedMode = telemetry.highSpeedMode,
                            onToggleConnection = { handleToggleVpn() },
                            onSelectProfile = { id ->
                                triggerHaptic()
                                viewModel.selectProfile(id)
                                viewModel.showBanner("Active profile updated")
                            },
                            onToggleHighSpeedMode = { enabled ->
                                triggerHaptic()
                                viewModel.setHighSpeedMode(context, enabled)
                            },
                            onSelectDisguise = { presetId, customDomain ->
                                triggerHaptic()
                                viewModel.applyQuickDisguise(presetId, customDomain)
                            },
                            onPingActive = { profile -> viewModel.testProfileLatency(profile) },
                            onNavigateToProfiles = { navigateTopLevel(ProfilesRoute) },
                            onNavigateToBridge = { navigateTopLevel(BridgeServerRoute) },
                            onNavigateToDisguiseLab = { navController.navigate(DisguiseLabRoute) }
                        )
                    }

                    composable<ProfilesRoute> {
                        ProfilesScreen(
                            profiles = filteredProfiles,
                            totalProfilesCount = profiles.size,
                            activeProfileId = activeProfile?.id,
                            filterState = profileFilter,
                            isScanningLan = isScanningLan,
                            lanServers = lanServers,
                            clientPairingSession = clientPairingSession,
                            isTestingAll = testingAllLatencies,
                            onSearchChange = { viewModel.setProfileSearchQuery(it) },
                            onProtocolFilterChange = { viewModel.setProfileProtocolFilter(it) },
                            onToggleFavoritesOnly = { viewModel.toggleFavoritesFilter() },
                            onSortChange = { viewModel.setProfileSortOption(it) },
                            onSelectProfile = { id ->
                                triggerHaptic()
                                viewModel.selectProfile(id)
                                viewModel.showBanner("Active profile updated")
                            },
                            onToggleFavorite = { profile ->
                                triggerHaptic()
                                viewModel.toggleFavorite(profile.id, profile.isFavorite)
                            },
                            onDuplicateProfile = { profile ->
                                triggerHaptic()
                                viewModel.duplicateProfile(profile)
                            },
                            onCreateProfile = {
                                navController.navigate(ProfileEditorRoute(profileId = null))
                            },
                            onEditProfile = { id ->
                                navController.navigate(ProfileEditorRoute(profileId = id))
                            },
                            onDeleteProfile = { id ->
                                triggerHaptic()
                                viewModel.deleteProfile(id)
                            },
                            onPingProfile = { profile -> viewModel.testProfileLatency(profile) },
                            onTestAll = { viewModel.testAllLatencies() },
                            onImportText = { raw, cb -> viewModel.importConfigText(raw, cb) },
                            onImportFileUri = { ctx, uri, cb -> viewModel.importConfigFromUri(ctx, uri, cb) },
                            onImportQrImageUri = { ctx, uri, cb -> viewModel.importQrFromImageUri(ctx, uri, cb) },
                            onScanLanServers = { viewModel.scanLanBridgeServers() },
                            onStartLanPairing = { srv, onSuccess -> viewModel.startLanPairing(srv, onSuccess) },
                            onCancelLanPairing = { viewModel.cancelLanPairing() },
                            onNotify = { msg -> postSnackbar(msg) }
                        )
                    }

                    composable<AnalyticsRoute> {
                        AnalyticsScreen(
                            telemetry = telemetry,
                            activeProfile = activeProfile,
                            dnsServer = preferences.dnsServer,
                            probeResult = disguiseProbeResult,
                            isProbing = isProbingDisguise,
                            onRunProbe = { domain -> viewModel.runDisguiseProbe(domain) },
                            onRefreshNetwork = { viewModel.refreshLocalIps() }
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
                            onApprovePairing = { requestId -> viewModel.approvePairingRequest(context, requestId) },
                            onDenyPairing = { requestId -> viewModel.denyPairingRequest(context, requestId) },
                            onRevokeDevice = { deviceId -> viewModel.revokePairedDevice(context, deviceId) },
                            onToggleHighSpeedMode = { enabled ->
                                triggerHaptic()
                                viewModel.setHighSpeedMode(context, enabled)
                            },
                            onNotify = { msg -> postSnackbar(msg) }
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            preferences = preferences,
                            highSpeedMode = telemetry.highSpeedMode,
                            securityReport = securityReport,
                            logs = logs,
                            onSetAppearanceMode = { mode -> viewModel.setAppearanceMode(mode) },
                            onSetHapticFeedback = { enabled -> viewModel.setHapticFeedback(enabled) },
                            onSetNotificationsEnabled = { enabled -> viewModel.setNotificationsEnabled(enabled) },
                            onSetRoutingMode = { mode -> viewModel.setRoutingMode(mode) },
                            onSetDns = { dns ->
                                viewModel.setDnsServer(dns)
                                viewModel.showBanner("DNS updated to $dns")
                            },
                            onSetBypassLan = { enabled -> viewModel.setBypassLan(enabled) },
                            onSetAutoReconnect = { enabled -> viewModel.setAutoReconnect(enabled) },
                            onSetHighSpeedMode = { enabled ->
                                triggerHaptic()
                                viewModel.setHighSpeedMode(context, enabled)
                            },
                            onSetSensitiveProtection = { enabled -> viewModel.setSensitiveConfigProtection(enabled) },
                            onOpenDisguiseLab = { navController.navigate(DisguiseLabRoute) },
                            onReplayOnboarding = { showOnboardingReplay = true },
                            onClearLogs = { viewModel.clearLogs() }
                        )
                    }

                    composable<DisguiseLabRoute> {
                        DisguiseLabScreen(
                            activeProfile = activeProfile,
                            isProbing = isProbingDisguise,
                            probeResult = disguiseProbeResult,
                            onApplyDisguise = { presetId, custom ->
                                triggerHaptic()
                                viewModel.applyQuickDisguise(presetId, custom)
                            },
                            onRunProbe = { domain -> viewModel.runDisguiseProbe(domain) },
                            onBack = { navController.popBackStack() }
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
