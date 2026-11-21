package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.BridgeCrypto
import com.example.core.parser.ParseImportResult
import com.example.core.parser.QrCodeGenerator
import com.example.core.parser.V2RayConfigParser
import com.example.core.security.SecureVault
import com.example.data.local.AppDatabase
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.ProtocolType
import com.example.domain.model.V2RayProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoreSecurityAndParserTest {

    @Before
    fun setUp() {
        BridgeCrypto.clearReplayCacheForTesting()
    }

    @Test
    fun `bridge crypto aead roundtrip and replay guard work`() {
        val secret = "550e8400-e29b-41d4-a716-446655440000"
        val key = BridgeCrypto.deriveKey(secret)
        val payload = "GET /youtubei/v1/player HTTP/1.1".toByteArray(Charsets.UTF_8)

        val aesCiphertext = BridgeCrypto.encryptFrame(payload, key, EncryptionCipher.AES_256_GCM)
        assertArrayEquals(payload, BridgeCrypto.decryptFrame(aesCiphertext, key, EncryptionCipher.AES_256_GCM))

        val chachaCiphertext = BridgeCrypto.encryptFrame(payload, key, EncryptionCipher.CHACHA20_POLY1305)
        assertArrayEquals(payload, BridgeCrypto.decryptFrame(chachaCiphertext, key, EncryptionCipher.CHACHA20_POLY1305))

        val nowSec = System.currentTimeMillis() / 1000L
        val window = nowSec / 60L
        val nonce = BridgeCrypto.generateAuthNonce()
        val token = BridgeCrypto.computeAuthToken(secret, "www.youtube.com", window, nonce)

        // First use succeeds
        assertTrue(
            BridgeCrypto.verifyAuthTokenWithReplayCheck(
                token = token,
                uuidOrSecret = secret,
                disguiseDomain = "www.youtube.com",
                nonce = nonce,
                nowSeconds = nowSec
            )
        )
        // Replayed nonce must be rejected
        assertFalse(
            BridgeCrypto.verifyAuthTokenWithReplayCheck(
                token = token,
                uuidOrSecret = secret,
                disguiseDomain = "www.youtube.com",
                nonce = nonce,
                nowSeconds = nowSec
            )
        )
        // Wrong domain must be rejected
        assertFalse(
            BridgeCrypto.verifyAuthTokenWithReplayCheck(
                token = token,
                uuidOrSecret = secret,
                disguiseDomain = "evil.example",
                nonce = nonce,
                nowSeconds = nowSec
            )
        )
    }

    @Test
    fun `bridge crypto maskSecret hides sensitive middle characters`() {
        val masked = BridgeCrypto.maskSecret("550e8400-e29b-41d4-a716-446655440000")
        assertTrue(masked.startsWith("550e"))
        assertTrue(masked.endsWith("0000"))
        assertTrue(masked.contains("•"))
        assertFalse(masked.contains("44665544"))
    }

    @Test
    fun `secure vault encrypts and restores secrets with legacy plaintext compatibility`() {
        val secret = "ab12cd34-5678-90ef-1234-567890abcdef"
        val encrypted = SecureVault.protectSecret(secret)
        assertTrue(SecureVault.isEncryptedValue(encrypted))
        assertNotEquals(secret, encrypted)
        assertEquals(secret, SecureVault.unprotectSecret(encrypted))

        // Legacy plaintext stored before Vault encryption must still decode cleanly
        assertEquals(secret, SecureVault.unprotectSecret(secret))
        assertEquals("", SecureVault.protectSecret(""))
    }

    @Test
    fun `v2ray parser supports all formats and rejects malformed ports or credentials`() {
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

        // Invalid explicit ports must fail strictly instead of defaulting silently
        val badPort = V2RayConfigParser.parseInput("vless://11111111-2222-3333-4444-555555555555@example.com:99999#BadPort")
        assertTrue(badPort is ParseImportResult.Error)

        val zeroPort = V2RayConfigParser.parseInput("trojan://secret@example.com:0#ZeroPort")
        assertTrue(zeroPort is ParseImportResult.Error)

        val emptyUuid = V2RayConfigParser.parseInput("vless://@example.com:443#NoUuid")
        assertTrue(emptyUuid is ParseImportResult.Error)
    }

    @Test
    fun `bulk import reports invalid lines and duplicate detector catches duplicates`() {
        val mixed = """
            vless://11111111-2222-3333-4444-555555555555@10.0.0.2:443?sni=github.com#NodeOne
            http://not-supported.example.com:80
            trojan://pass123@10.0.0.3:8443?sni=zoom.us#NodeTwo
        """.trimIndent()

        val bulk = V2RayConfigParser.parseBulkInput(mixed)
        assertEquals(2, bulk.profiles.size)
        assertEquals(1, bulk.failedEntries.size)

        val existing = listOf(bulk.profiles.first())
        val dupResult = V2RayConfigParser.findDuplicates(bulk.profiles, existing)
        assertEquals(1, dupResult.duplicates.size)
        assertEquals(1, dupResult.unique.size)
        assertEquals("NodeTwo", dupResult.unique.first().name)
    }

    @Test
    fun `qr code generator encodes and decodes pairing uri roundtrip`() {
        val uri = "velobridge://11111111-2222-3333-4444-555555555555@192.168.1.10:10808?disguise=youtube&sni=www.youtube.com"
        val bitmap = QrCodeGenerator.generateBitmap(uri, 320)
        assertNotNull(bitmap)
        assertEquals(320, bitmap?.width)

        val decoded = QrCodeGenerator.decodeBitmap(bitmap!!)
        assertEquals(uri, decoded)
    }

    @Test
    fun `room migration 1 to 2 adds favorite and lastConnectedAt columns cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v1_v2.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS v2ray_profiles (
                                id TEXT NOT NULL PRIMARY KEY,
                                name TEXT NOT NULL,
                                protocol TEXT NOT NULL,
                                address TEXT NOT NULL,
                                port INTEGER NOT NULL,
                                uuidOrPassword TEXT NOT NULL,
                                encryption TEXT NOT NULL,
                                transport TEXT NOT NULL,
                                disguisePresetId TEXT NOT NULL,
                                customDisguiseDomain TEXT NOT NULL,
                                sni TEXT NOT NULL,
                                hostHeader TEXT NOT NULL,
                                wsPath TEXT NOT NULL,
                                tlsEnabled INTEGER NOT NULL,
                                allowInsecure INTEGER NOT NULL,
                                fingerprint TEXT NOT NULL,
                                isPhoneBridge INTEGER NOT NULL,
                                latencyMs INTEGER,
                                lastTestedAt INTEGER,
                                createdAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO v2ray_profiles VALUES (
                                'p1', 'Legacy Server', 'VELO_BRIDGE', '192.168.1.5', 10808,
                                'uuid-1', 'AES_256_GCM', 'WEBSOCKET', 'youtube', '',
                                'www.youtube.com', 'www.youtube.com', '/ws', 1, 0, 'CHROME',
                                1, 15, 1000, 1000
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val db = helper.writableDatabase
        AppDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT id, isFavorite, lastConnectedAt FROM v2ray_profiles WHERE id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("p1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
        }
        db.close()
        context.deleteDatabase(dbName)
    }
}
