package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.BridgeCrypto
import com.example.core.parser.ParseImportResult
import com.example.core.parser.QrCodeGenerator
import com.example.core.parser.V2RayConfigParser
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.ProtocolType
import com.example.domain.model.V2RayProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `app name matches VeloBridge`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("VeloBridge", context.getString(R.string.app_name))
    }

    @Test
    fun `crypto encrypt and decrypt roundtrip works with AES-256-GCM`() {
        val key = BridgeCrypto.deriveKey("550e8400-e29b-41d4-a716-446655440000")
        val payload = "GET /youtubei/v1/player HTTP/1.1".toByteArray(Charsets.UTF_8)
        val encrypted = BridgeCrypto.encryptFrame(payload, key, EncryptionCipher.AES_256_GCM)
        val decrypted = BridgeCrypto.decryptFrame(encrypted, key, EncryptionCipher.AES_256_GCM)
        assertArrayEquals(payload, decrypted)

        val window = System.currentTimeMillis() / 1000L / 60L
        val token = BridgeCrypto.computeAuthToken(
            "550e8400-e29b-41d4-a716-446655440000",
            "www.youtube.com",
            window
        )
        assertTrue(
            BridgeCrypto.verifyAuthToken(
                token,
                "550e8400-e29b-41d4-a716-446655440000",
                "www.youtube.com"
            )
        )
    }

    @Test
    fun `v2ray parser parses vless and velobridge and xray json`() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@192.168.1.44:10808?security=tls&type=ws&sni=zoom.us&host=zoom.us&path=%2Fwc%2Fstream#OfficeZoom"
        val res1 = V2RayConfigParser.parseInput(vless)
        assertTrue(res1 is ParseImportResult.Success)
        val profile = (res1 as ParseImportResult.Success).profiles.first()
        assertEquals(ProtocolType.VLESS, profile.protocol)
        assertEquals("192.168.1.44", profile.address)
        assertEquals(10808, profile.port)
        assertEquals("zoom", profile.disguisePresetId)
        assertEquals("zoom.us", profile.effectiveDisguiseDomain)

        val json = V2RayConfigParser.toXrayClientJson(profile)
        val res2 = V2RayConfigParser.parseInput(json)
        assertTrue(res2 is ParseImportResult.Success)

        val bridgeUri = V2RayConfigParser.toVeloBridgeUri(
            ip = "192.168.43.1",
            port = 10808,
            uuid = "11111111-2222-3333-4444-555555555555",
            disguiseId = "github",
            disguiseDomain = "github.com",
            wsPath = "/_ws/live",
            cipher = EncryptionCipher.AES_256_GCM
        )
        val res3 = V2RayConfigParser.parseInput(bridgeUri)
        assertTrue(res3 is ParseImportResult.Success)
        assertEquals(ProtocolType.VELO_BRIDGE, (res3 as ParseImportResult.Success).profiles.first().protocol)
    }

    @Test
    fun `qr code generator renders pairing bitmap`() {
        val bitmap = QrCodeGenerator.generateBitmap("velobridge://uuid@192.168.1.10:10808", 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap?.width)
    }

    @Test
    fun `main navigation tabs render and switch cleanly`() {
        composeRule.onNodeWithTag("vpn_power_button").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_profiles").performClick()
        composeRule.onNodeWithTag("find_phone_server_button").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_bridge").performClick()
        composeRule.onNodeWithTag("server_power_button").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_disguise").performClick()
        composeRule.onNodeWithTag("disguise_lazy_column")
            .performScrollToNode(hasTestTag("probe_card"))
        composeRule.onNodeWithTag("run_probe_button").assertIsDisplayed()
    }
}
