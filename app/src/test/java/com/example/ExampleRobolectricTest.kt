package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.PerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun dismissOnboardingIfVisible() {
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("nav_profiles").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("onboarding_skip_button").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("onboarding_skip_button").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboarding_skip_button").performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodesWithTag("nav_profiles").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun `app name matches VeloBridge`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("VeloBridge", context.getString(R.string.app_name))
    }

    @Test
    fun `onboarding flow completes and can be replayed from settings`() {
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty()
        }
        val startedFromSettings =
            composeRule.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().isEmpty()
        if (startedFromSettings) {
            composeRule.onNodeWithTag("nav_settings").performClick()
            composeRule.onNodeWithTag("settings_lazy_column")
                .performScrollToNode(hasTestTag("settings_replay_onboarding"))
            composeRule.onNodeWithTag("settings_replay_onboarding").performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.onNodeWithTag("onboarding_finish_button").performClick()
        if (startedFromSettings) {
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodesWithTag("nav_connect").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("nav_connect").performClick()
        }
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("vpn_power_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("vpn_power_button").assertIsDisplayed()

        // Navigate to Settings and verify replay guide works
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(hasTestTag("settings_replay_onboarding"))
        composeRule.onNodeWithTag("settings_replay_onboarding").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_skip_button").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("settings_lazy_column").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `main 5-tab navigation and disguise lab subscreen render cleanly`() {
        dismissOnboardingIfVisible()

        composeRule.onNodeWithTag("vpn_power_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_profiles").performClick()
        composeRule.onNodeWithTag("find_phone_server_button").assertIsDisplayed()
        composeRule.onNodeWithTag("import_qr_image_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_analytics").performClick()
        composeRule.onNodeWithTag("analytics_quality_card").assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_lazy_column")
            .performScrollToNode(hasTestTag("analytics_diagnostics_card"))
        composeRule.onNodeWithTag("analytics_diagnostics_card").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_bridge").performClick()
        composeRule.onNodeWithTag("server_power_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(hasTestTag("settings_open_disguise_lab"))
        composeRule.onNodeWithTag("settings_open_disguise_lab").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("disguise_lazy_column")
            .performScrollToNode(hasTestTag("probe_card"))
        composeRule.onNodeWithTag("run_probe_button").performScrollTo()
        composeRule.onNodeWithTag("run_probe_button").assertIsDisplayed()
        composeRule.onNodeWithTag("disguise_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(hasTestTag("settings_general_card"))
        composeRule.onNodeWithTag("settings_general_card").assertIsDisplayed()
    }

    @Test
    fun `high speed data mode specs and cross-screen toggle sync work`() {
        assertEquals(1500, PerformanceMode.BALANCED.mtu)
        assertEquals(9000, PerformanceMode.HIGH_SPEED.mtu)
        assertEquals(262144, PerformanceMode.HIGH_SPEED.socketBufferBytes)
        assertEquals(49152, PerformanceMode.HIGH_SPEED.relayChunkBytes)
        assertEquals(8800, PerformanceMode.HIGH_SPEED.tunSegmentBytes)

        dismissOnboardingIfVisible()

        composeRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("high_speed_mode_card"))
        val switchNode = composeRule.onNodeWithTag("high_speed_mode_switch")
        switchNode.assertIsDisplayed()
        switchNode.assertIsOff()
        switchNode.performClick()
        composeRule.waitForIdle()
        switchNode.assertIsOn()
        composeRule.mainClock.advanceTimeBy(5000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_bridge").performClick()
        composeRule.onNodeWithTag("bridge_lazy_column")
            .performScrollToNode(hasTestTag("server_settings_card"))
        composeRule.onNodeWithTag("server_high_speed_switch").assertIsOn()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(hasTestTag("settings_high_speed_switch"))
        composeRule.onNodeWithTag("settings_high_speed_switch").assertIsOn()

        composeRule.onNodeWithTag("nav_connect").performClick()
        composeRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("high_speed_mode_card"))
        switchNode.performClick()
        composeRule.waitForIdle()
        switchNode.assertIsOff()

        composeRule.onNodeWithTag("nav_bridge").performClick()
        composeRule.onNodeWithTag("bridge_lazy_column")
            .performScrollToNode(hasTestTag("server_settings_card"))
        composeRule.onNodeWithTag("server_high_speed_switch").assertIsOff()
    }

    @Test
    fun `profiles import and search filter work via UI`() {
        dismissOnboardingIfVisible()

        composeRule.onNodeWithTag("nav_profiles").performClick()
        composeRule.onNodeWithTag("import_paste_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("import_config_textfield").performTextInput(
            "velobridge://11111111-2222-3333-4444-555555555555@192.168.43.1:10808?disguise=github&sni=github.com#HomeBridge\n" +
                "vless://22222222-2222-3333-4444-555555555555@198.51.100.12:443?sni=zoom.us#RemoteZoom"
        )
        composeRule.onNodeWithTag("confirm_import_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("profiles_lazy_column")
            .performScrollToNode(hasText("HomeBridge"))
        composeRule.onNodeWithText("HomeBridge").assertIsDisplayed()

        composeRule.onNodeWithTag("profiles_lazy_column")
            .performScrollToNode(hasText("RemoteZoom"))
        composeRule.onNodeWithText("RemoteZoom").assertIsDisplayed()

        // Scroll back to filter chips and filter by VLESS -> RemoteZoom remains visible
        composeRule.onNodeWithTag("profiles_lazy_column")
            .performScrollToNode(hasTestTag("filter_protocol_vless"))
        composeRule.onNodeWithTag("filter_protocol_vless").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("profiles_lazy_column")
            .performScrollToNode(hasText("RemoteZoom"))
        composeRule.onNodeWithText("RemoteZoom").assertIsDisplayed()
    }
}
