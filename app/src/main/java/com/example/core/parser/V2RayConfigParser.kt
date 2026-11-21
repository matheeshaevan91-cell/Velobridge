package com.example.core.parser

import android.net.Uri
import android.util.Base64
import com.example.domain.model.DisguiseCatalog
import com.example.domain.model.EncryptionCipher
import com.example.domain.model.ProtocolType
import com.example.domain.model.ServerRuntimeState
import com.example.domain.model.TlsFingerprint
import com.example.domain.model.TransportType
import com.example.domain.model.V2RayProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

sealed class ParseImportResult {
    data class Success(val profiles: List<V2RayProfile>) : ParseImportResult()
    data class Error(val reason: String) : ParseImportResult()
}

data class BulkImportResult(
    val profiles: List<V2RayProfile>,
    val failedEntries: List<String>
)

data class DuplicateAnalysis(
    val unique: List<V2RayProfile>,
    val duplicates: List<V2RayProfile>
)

object V2RayConfigParser {

    fun parseInput(rawInput: String): ParseImportResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return ParseImportResult.Error(
                "Input is empty. Paste a vmess://, vless://, trojan://, ss://, velobridge:// link or Xray JSON config."
            )
        }

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseXrayJson(trimmed)
        }

        val bulk = parseBulkInput(trimmed)
        if (bulk.profiles.isNotEmpty() && bulk.failedEntries.isEmpty()) {
            return ParseImportResult.Success(bulk.profiles)
        }
        if (bulk.profiles.isNotEmpty() && bulk.failedEntries.isNotEmpty()) {
            // Strict mode: surface exact failure if caller uses parseInput expecting all lines valid,
            // while bulk UI uses parseBulkInput to report partial imports cleanly.
            return ParseImportResult.Success(bulk.profiles)
        }
        return ParseImportResult.Error(
            bulk.failedEntries.firstOrNull()
                ?: "Could not recognize configuration format. Supported: vless://, vmess://, trojan://, ss://, velobridge://, or Xray JSON."
        )
    }

    fun parseBulkInput(rawInput: String): BulkImportResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return BulkImportResult(emptyList(), listOf("Input is empty."))
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return when (val jsonRes = parseXrayJson(trimmed)) {
                is ParseImportResult.Success -> BulkImportResult(jsonRes.profiles, emptyList())
                is ParseImportResult.Error -> BulkImportResult(emptyList(), listOf(jsonRes.reason))
            }
        }

        val lines = trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val parsed = mutableListOf<V2RayProfile>()
        val failed = mutableListOf<String>()

        lines.forEachIndexed { idx, line ->
            val lineNum = idx + 1
            val outcome = when {
                line.startsWith("velobridge://", ignoreCase = true) -> parseVeloBridgeUriStrict(line)
                line.startsWith("vless://", ignoreCase = true) -> parseVlessUriStrict(line)
                line.startsWith("vmess://", ignoreCase = true) -> parseVmessUriStrict(line)
                line.startsWith("trojan://", ignoreCase = true) -> parseTrojanUriStrict(line)
                line.startsWith("ss://", ignoreCase = true) -> parseShadowsocksUriStrict(line)
                else -> Result.failure(IllegalArgumentException("Unsupported URI scheme"))
            }
            outcome.onSuccess { profile ->
                parsed.add(profile)
            }.onFailure { err ->
                failed.add("Line $lineNum: ${err.message ?: "Malformed configuration"}")
            }
        }

        return BulkImportResult(profiles = parsed, failedEntries = failed)
    }

    fun findDuplicates(
        incoming: List<V2RayProfile>,
        existing: List<V2RayProfile>
    ): DuplicateAnalysis {
        val existingFingerprints = existing.mapTo(mutableSetOf()) { it.endpointFingerprint }
        val seenBatch = mutableSetOf<String>()
        val unique = mutableListOf<V2RayProfile>()
        val duplicates = mutableListOf<V2RayProfile>()

        for (profile in incoming) {
            val fp = profile.endpointFingerprint
            if (fp in existingFingerprints || fp in seenBatch) {
                duplicates.add(profile)
            } else {
                seenBatch.add(fp)
                unique.add(profile)
            }
        }
        return DuplicateAnalysis(unique = unique, duplicates = duplicates)
    }

    private fun extractValidatedPort(uri: Uri, defaultPort: Int): Int {
        val authority = uri.encodedAuthority.orEmpty()
        val afterAt = authority.substringAfterLast('@', authority)
        val hasExplicitColon = if (afterAt.startsWith("[")) {
            afterAt.substringAfter(']', "").contains(':')
        } else {
            afterAt.contains(':')
        }
        val port = uri.port
        if (hasExplicitColon) {
            if (port !in 1..65535) {
                throw IllegalArgumentException("Invalid port in URI (must be 1–65535)")
            }
            return port
        }
        return if (port == -1) defaultPort else {
            require(port in 1..65535) { "Invalid port $port (must be 1–65535)" }
            port
        }
    }

    private fun parseVeloBridgeUriStrict(uriText: String): Result<V2RayProfile> = runCatching {
        val uri = Uri.parse(uriText)
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("velobridge:// missing bridge secret UUID")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("velobridge:// missing server IP or hostname")
        val port = extractValidatedPort(uri, 10808)
        val remarks = uri.fragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
            ?: "Phone Bridge ($host)"
        val disguiseId = uri.getQueryParameter("disguise") ?: "youtube"
        val domain = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("host")
            ?: DisguiseCatalog.findById(disguiseId).domain
        val wsPath = uri.getQueryParameter("path") ?: DisguiseCatalog.findById(disguiseId).wsPath
        val cipherRaw = uri.getQueryParameter("cipher") ?: "aes-256-gcm"
        val cipher = EncryptionCipher.entries.find { it.wireValue.equals(cipherRaw, true) }
            ?: EncryptionCipher.AES_256_GCM

        V2RayProfile(
            name = remarks,
            protocol = ProtocolType.VELO_BRIDGE,
            address = host,
            port = port,
            uuidOrPassword = uuid,
            encryption = cipher,
            transport = TransportType.WEBSOCKET,
            disguisePresetId = matchPresetId(domain, disguiseId),
            customDisguiseDomain = if (matchPresetId(domain, disguiseId) == "custom") domain else "",
            sni = domain,
            hostHeader = domain,
            wsPath = wsPath,
            tlsEnabled = true,
            isPhoneBridge = true
        )
    }

    private fun parseVlessUriStrict(uriText: String): Result<V2RayProfile> = runCatching {
        val uri = Uri.parse(uriText)
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("vless:// missing UUID")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("vless:// missing server host")
        val port = extractValidatedPort(uri, 443)
        val remarks = uri.fragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
            ?: "VLESS $host"

        val typeRaw = uri.getQueryParameter("type") ?: "tcp"
        val transport = when (typeRaw.lowercase()) {
            "ws", "websocket" -> TransportType.WEBSOCKET
            "grpc" -> TransportType.GRPC
            "http", "tcp-http" -> TransportType.TCP_HTTP
            else -> TransportType.TCP
        }
        val security = uri.getQueryParameter("security") ?: "tls"
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host
        val hostHdr = uri.getQueryParameter("host") ?: sni
        val path = uri.getQueryParameter("path")?.let { urlDecode(it) } ?: "/ray"
        val fpRaw = uri.getQueryParameter("fp") ?: "chrome"
        val fp = TlsFingerprint.entries.find { it.wireValue.equals(fpRaw, true) } ?: TlsFingerprint.CHROME
        val allowInsecure = uri.getQueryParameter("allowInsecure") == "1" ||
            uri.getQueryParameter("allowInsecure") == "true"
        val isBridge = uri.getQueryParameter("bridge") == "1"
        val presetId = matchPresetId(sni.ifBlank { hostHdr })

        V2RayProfile(
            name = remarks,
            protocol = if (isBridge) ProtocolType.VELO_BRIDGE else ProtocolType.VLESS,
            address = host,
            port = port,
            uuidOrPassword = uuid,
            encryption = if (isBridge) EncryptionCipher.AES_256_GCM else EncryptionCipher.NONE,
            transport = transport,
            disguisePresetId = presetId,
            customDisguiseDomain = if (presetId == "custom") sni else "",
            sni = sni,
            hostHeader = hostHdr,
            wsPath = path,
            tlsEnabled = !security.equals("none", ignoreCase = true),
            allowInsecure = allowInsecure,
            tlsFingerprint = fp,
            isPhoneBridge = isBridge
        )
    }

    private fun parseVmessUriStrict(uriText: String): Result<V2RayProfile> = runCatching {
        val encoded = uriText.removePrefix("vmess://").trim()
        val decoded = decodeBase64Flexible(encoded)
            ?: throw IllegalArgumentException("vmess:// contains invalid Base64 payload")
        val json = runCatching { JSONObject(decoded) }.getOrElse {
            throw IllegalArgumentException("vmess:// payload is not valid JSON")
        }
        val address = json.optString("add").trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("vmess:// missing server address ('add')")
        val portRaw = if (json.has("port")) json.optString("port").toIntOrNull() else 443
        val port = portRaw?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("vmess:// invalid port (must be 1–65535)")
        val uuid = json.optString("id").trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("vmess:// missing user UUID ('id')")
        val remarks = json.optString("ps").takeIf { it.isNotBlank() } ?: "VMess $address"
        val net = json.optString("net", "ws")
        val transport = when (net.lowercase()) {
            "ws" -> TransportType.WEBSOCKET
            "grpc" -> TransportType.GRPC
            "h2", "http" -> TransportType.TCP_HTTP
            else -> TransportType.TCP
        }
        val hostHdr = json.optString("host").ifBlank { address }
        val sni = json.optString("sni").ifBlank { hostHdr }
        val path = json.optString("path").ifBlank { "/ray" }
        val tls = json.optString("tls").equals("tls", ignoreCase = true)
        val scy = json.optString("scy", "auto")
        val cipher = EncryptionCipher.entries.find { it.wireValue.equals(scy, true) }
            ?: EncryptionCipher.AUTO
        val presetId = matchPresetId(sni.ifBlank { hostHdr })

        V2RayProfile(
            name = remarks,
            protocol = ProtocolType.VMESS,
            address = address,
            port = port,
            uuidOrPassword = uuid,
            encryption = cipher,
            transport = transport,
            disguisePresetId = presetId,
            customDisguiseDomain = if (presetId == "custom") sni else "",
            sni = sni,
            hostHeader = hostHdr,
            wsPath = path,
            tlsEnabled = tls
        )
    }

    private fun parseTrojanUriStrict(uriText: String): Result<V2RayProfile> = runCatching {
        val uri = Uri.parse(uriText)
        val password = uri.userInfo?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("trojan:// missing password")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("trojan:// missing server host")
        val port = extractValidatedPort(uri, 443)
        val remarks = uri.fragment?.let { urlDecode(it) }?.takeIf { it.isNotBlank() }
            ?: "Trojan $host"
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: host
        val hostHdr = uri.getQueryParameter("host") ?: sni
        val path = uri.getQueryParameter("path")?.let { urlDecode(it) } ?: "/"
        val typeRaw = uri.getQueryParameter("type") ?: "tcp"
        val transport = if (typeRaw.equals("ws", true)) TransportType.WEBSOCKET else TransportType.TCP
        val presetId = matchPresetId(sni)

        V2RayProfile(
            name = remarks,
            protocol = ProtocolType.TROJAN,
            address = host,
            port = port,
            uuidOrPassword = password,
            encryption = EncryptionCipher.AES_256_GCM,
            transport = transport,
            disguisePresetId = presetId,
            customDisguiseDomain = if (presetId == "custom") sni else "",
            sni = sni,
            hostHeader = hostHdr,
            wsPath = path,
            tlsEnabled = true
        )
    }

    private fun parseShadowsocksUriStrict(uriText: String): Result<V2RayProfile> = runCatching {
        val withoutScheme = uriText.removePrefix("ss://")
        val hashIdx = withoutScheme.indexOf('#')
        val remarks = if (hashIdx >= 0) {
            urlDecode(withoutScheme.substring(hashIdx + 1)).ifBlank { "Shadowsocks" }
        } else {
            "Shadowsocks"
        }
        val mainPart = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val queryIdx = mainPart.indexOf('?')
        val corePart = if (queryIdx >= 0) mainPart.substring(0, queryIdx) else mainPart

        val decodedCore = if (corePart.contains("@")) {
            val atIdx = corePart.lastIndexOf('@')
            val userInfo = corePart.substring(0, atIdx)
            val hostPort = corePart.substring(atIdx + 1)
            val decodedCreds = if (userInfo.contains(":")) {
                userInfo
            } else {
                decodeBase64Flexible(userInfo)
                    ?: throw IllegalArgumentException("ss:// invalid Base64 credentials")
            }
            "$decodedCreds@$hostPort"
        } else {
            decodeBase64Flexible(corePart)
                ?: throw IllegalArgumentException("ss:// invalid Base64 configuration")
        }

        val atIdx = decodedCore.lastIndexOf('@')
        if (atIdx < 0) throw IllegalArgumentException("ss:// missing '@' host separator")
        val creds = decodedCore.substring(0, atIdx)
        val hostPort = decodedCore.substring(atIdx + 1)
        val colonCred = creds.indexOf(':')
        val method = if (colonCred >= 0) creds.substring(0, colonCred) else "aes-256-gcm"
        val password = (if (colonCred >= 0) creds.substring(colonCred + 1) else creds).trim()
        if (password.isEmpty()) throw IllegalArgumentException("ss:// missing password")

        val lastColonHost = hostPort.lastIndexOf(':')
        if (lastColonHost < 0) throw IllegalArgumentException("ss:// missing ':port'")
        val host = hostPort.substring(0, lastColonHost).trim('[', ']', ' ')
        if (host.isEmpty()) throw IllegalArgumentException("ss:// missing server host")
        val port = hostPort.substring(lastColonHost + 1).toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("ss:// invalid port (must be 1–65535)")

        val cipher = when {
            method.contains("chacha", true) -> EncryptionCipher.CHACHA20_POLY1305
            else -> EncryptionCipher.AES_256_GCM
        }

        V2RayProfile(
            name = remarks,
            protocol = ProtocolType.SHADOWSOCKS,
            address = host,
            port = port,
            uuidOrPassword = password,
            encryption = cipher,
            transport = TransportType.TCP,
            disguisePresetId = "youtube",
            sni = "www.youtube.com",
            hostHeader = "www.youtube.com",
            tlsEnabled = false
        )
    }

    private fun parseXrayJson(rawJson: String): ParseImportResult {
        return runCatching {
            val root = JSONObject(rawJson)
            val outbounds = root.optJSONArray("outbounds")
                ?: return ParseImportResult.Error("JSON missing 'outbounds' array.")

            val profiles = mutableListOf<V2RayProfile>()
            val errors = mutableListOf<String>()

            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val protoRaw = ob.optString("protocol").lowercase()
                if (protoRaw == "freedom" || protoRaw == "blackhole" || protoRaw == "dns") continue

                val tag = ob.optString("tag").ifBlank { "Imported $protoRaw" }
                val settings = ob.optJSONObject("settings")
                val stream = ob.optJSONObject("streamSettings")

                val network = stream?.optString("network", "tcp")?.lowercase() ?: "tcp"
                val transport = when (network) {
                    "ws" -> TransportType.WEBSOCKET
                    "grpc" -> TransportType.GRPC
                    "http", "h2" -> TransportType.TCP_HTTP
                    else -> TransportType.TCP
                }
                val security = stream?.optString("security", "none")?.lowercase() ?: "none"
                val tlsObj = stream?.optJSONObject("tlsSettings") ?: stream?.optJSONObject("realitySettings")
                val wsObj = stream?.optJSONObject("wsSettings")
                val wsHeaders = wsObj?.optJSONObject("headers")
                val sni = tlsObj?.optString("serverName").orEmpty()
                val hostHeader = wsHeaders?.optString("Host").orEmpty().ifBlank { sni }
                val wsPath = wsObj?.optString("path").orEmpty().ifBlank { "/ray" }

                when (protoRaw) {
                    "vless", "vmess" -> {
                        val vnext = settings?.optJSONArray("vnext")?.optJSONObject(0)
                        if (vnext == null) {
                            errors.add("Outbound #${i + 1} ($protoRaw): missing settings.vnext[0]")
                            continue
                        }
                        val address = vnext.optString("address").trim()
                        val port = vnext.optInt("port", -1)
                        val user = vnext.optJSONArray("users")?.optJSONObject(0)
                        val uuid = user?.optString("id").orEmpty().trim()

                        if (address.isEmpty()) {
                            errors.add("Outbound #${i + 1} ($protoRaw): missing vnext address")
                            continue
                        }
                        if (port !in 1..65535) {
                            errors.add("Outbound #${i + 1} ($protoRaw): invalid port ($port)")
                            continue
                        }
                        if (uuid.isEmpty()) {
                            errors.add("Outbound #${i + 1} ($protoRaw): missing user id/UUID")
                            continue
                        }

                        val encRaw = user?.optString("security", "auto").orEmpty()
                        val cipher = EncryptionCipher.entries.find { it.wireValue.equals(encRaw, true) }
                            ?: if (protoRaw == "vless") EncryptionCipher.NONE else EncryptionCipher.AES_256_GCM
                        val effectiveDomain = sni.ifBlank { hostHeader }.ifBlank { address }
                        val presetId = matchPresetId(effectiveDomain)

                        profiles.add(
                            V2RayProfile(
                                name = tag,
                                protocol = if (protoRaw == "vless") ProtocolType.VLESS else ProtocolType.VMESS,
                                address = address,
                                port = port,
                                uuidOrPassword = uuid,
                                encryption = cipher,
                                transport = transport,
                                disguisePresetId = presetId,
                                customDisguiseDomain = if (presetId == "custom") effectiveDomain else "",
                                sni = sni.ifBlank { effectiveDomain },
                                hostHeader = hostHeader.ifBlank { effectiveDomain },
                                wsPath = wsPath,
                                tlsEnabled = security == "tls" || security == "reality",
                                rawJsonOverride = rawJson
                            )
                        )
                    }
                    "trojan", "shadowsocks" -> {
                        val server = settings?.optJSONArray("servers")?.optJSONObject(0)
                        if (server == null) {
                            errors.add("Outbound #${i + 1} ($protoRaw): missing settings.servers[0]")
                            continue
                        }
                        val address = server.optString("address").trim()
                        val port = server.optInt("port", -1)
                        val password = server.optString("password").trim()

                        if (address.isEmpty()) {
                            errors.add("Outbound #${i + 1} ($protoRaw): missing server address")
                            continue
                        }
                        if (port !in 1..65535) {
                            errors.add("Outbound #${i + 1} ($protoRaw): invalid port ($port)")
                            continue
                        }
                        if (password.isEmpty()) {
                            errors.add("Outbound #${i + 1} ($protoRaw): missing password")
                            continue
                        }

                        val effectiveDomain = sni.ifBlank { hostHeader }.ifBlank { address }
                        val presetId = matchPresetId(effectiveDomain)
                        profiles.add(
                            V2RayProfile(
                                name = tag,
                                protocol = if (protoRaw == "trojan") ProtocolType.TROJAN else ProtocolType.SHADOWSOCKS,
                                address = address,
                                port = port,
                                uuidOrPassword = password,
                                encryption = EncryptionCipher.AES_256_GCM,
                                transport = transport,
                                disguisePresetId = presetId,
                                customDisguiseDomain = if (presetId == "custom") effectiveDomain else "",
                                sni = effectiveDomain,
                                hostHeader = hostHeader.ifBlank { effectiveDomain },
                                wsPath = wsPath,
                                tlsEnabled = security == "tls" || protoRaw == "trojan",
                                rawJsonOverride = rawJson
                            )
                        )
                    }
                    else -> errors.add("Outbound #${i + 1}: unsupported protocol '$protoRaw'")
                }
            }

            if (profiles.isEmpty()) {
                ParseImportResult.Error(
                    errors.firstOrNull() ?: "No valid proxy outbounds found in V2Ray JSON."
                )
            } else {
                ParseImportResult.Success(profiles)
            }
        }.getOrElse {
            ParseImportResult.Error("Invalid JSON syntax: ${it.localizedMessage ?: "parse failed"}")
        }
    }

    fun toShareUri(profile: V2RayProfile): String {
        return when (profile.protocol) {
            ProtocolType.VELO_BRIDGE -> toVeloBridgeUri(
                ip = profile.address,
                port = profile.port,
                uuid = profile.uuidOrPassword,
                disguiseId = profile.disguisePresetId,
                disguiseDomain = profile.effectiveDisguiseDomain,
                wsPath = profile.wsPath,
                cipher = profile.encryption,
                name = profile.name
            )
            ProtocolType.VLESS -> {
                val sec = if (profile.tlsEnabled) "tls" else "none"
                val remark = urlEncode(profile.name)
                "vless://${profile.uuidOrPassword}@${profile.address}:${profile.port}" +
                    "?encryption=none&security=$sec&type=${profile.transport.wireValue}" +
                    "&sni=${urlEncode(profile.sni)}&host=${urlEncode(profile.hostHeader)}" +
                    "&path=${urlEncode(profile.wsPath)}&fp=${profile.tlsFingerprint.wireValue}#$remark"
            }
            ProtocolType.VMESS -> {
                val obj = JSONObject().apply {
                    put("v", "2")
                    put("ps", profile.name)
                    put("add", profile.address)
                    put("port", profile.port)
                    put("id", profile.uuidOrPassword)
                    put("aid", "0")
                    put("scy", profile.encryption.wireValue)
                    put("net", profile.transport.wireValue)
                    put("type", "none")
                    put("host", profile.hostHeader)
                    put("path", profile.wsPath)
                    put("tls", if (profile.tlsEnabled) "tls" else "")
                    put("sni", profile.sni)
                }
                val b64 = Base64.encodeToString(obj.toString().toByteArray(), Base64.NO_WRAP)
                "vmess://$b64"
            }
            ProtocolType.TROJAN -> {
                val remark = urlEncode(profile.name)
                "trojan://${urlEncode(profile.uuidOrPassword)}@${profile.address}:${profile.port}" +
                    "?security=tls&type=${profile.transport.wireValue}&sni=${urlEncode(profile.sni)}" +
                    "&host=${urlEncode(profile.hostHeader)}&path=${urlEncode(profile.wsPath)}#$remark"
            }
            ProtocolType.SHADOWSOCKS -> {
                val creds = Base64.encodeToString(
                    "${profile.encryption.wireValue}:${profile.uuidOrPassword}".toByteArray(),
                    Base64.NO_WRAP or Base64.URL_SAFE
                )
                "ss://$creds@${profile.address}:${profile.port}#${urlEncode(profile.name)}"
            }
        }
    }

    fun toVeloBridgeUri(
        ip: String,
        port: Int,
        uuid: String,
        disguiseId: String,
        disguiseDomain: String,
        wsPath: String,
        cipher: EncryptionCipher,
        name: String = "Phone Bridge"
    ): String {
        return "velobridge://$uuid@$ip:$port" +
            "?disguise=$disguiseId&sni=${urlEncode(disguiseDomain)}" +
            "&host=${urlEncode(disguiseDomain)}&path=${urlEncode(wsPath)}" +
            "&cipher=${cipher.wireValue}#${urlEncode(name)}"
    }

    fun toXrayClientJson(profile: V2RayProfile, localSocksPort: Int = 10809): String {
        profile.rawJsonOverride?.takeIf { it.isNotBlank() }?.let { return it }

        val root = JSONObject()
        val log = JSONObject().put("loglevel", "warning")
        root.put("log", log)

        val inbounds = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("tag", "socks-in")
                    put("port", localSocksPort)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().put("udp", true))
                }
            )
        }
        root.put("inbounds", inbounds)

        val streamSettings = JSONObject().apply {
            put("network", if (profile.transport == TransportType.TCP_HTTP) "tcp" else profile.transport.wireValue)
            put("security", if (profile.tlsEnabled) "tls" else "none")
            if (profile.tlsEnabled) {
                put(
                    "tlsSettings",
                    JSONObject().apply {
                        put("serverName", profile.sni.ifBlank { profile.effectiveDisguiseDomain })
                        put("allowInsecure", profile.allowInsecure)
                        put("fingerprint", profile.tlsFingerprint.wireValue)
                        put("alpn", JSONArray().put("h2").put("http/1.1"))
                    }
                )
            }
            if (profile.transport == TransportType.WEBSOCKET) {
                put(
                    "wsSettings",
                    JSONObject().apply {
                        put("path", profile.wsPath.ifBlank { "/ray" })
                        put("headers", JSONObject().put("Host", profile.hostHeader.ifBlank { profile.effectiveDisguiseDomain }))
                    }
                )
            }
        }

        val outboundProto = when (profile.protocol) {
            ProtocolType.VELO_BRIDGE, ProtocolType.VLESS -> "vless"
            ProtocolType.VMESS -> "vmess"
            ProtocolType.TROJAN -> "trojan"
            ProtocolType.SHADOWSOCKS -> "shadowsocks"
        }

        val settingsObj = when (outboundProto) {
            "vless", "vmess" -> JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put(
                            "users",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("id", profile.uuidOrPassword)
                                    put("encryption", if (outboundProto == "vless") "none" else profile.encryption.wireValue)
                                }
                            )
                        )
                    }
                )
            )
            else -> JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject().apply {
                        put("address", profile.address)
                        put("port", profile.port)
                        put("password", profile.uuidOrPassword)
                        put("method", profile.encryption.wireValue)
                    }
                )
            )
        }

        val outbounds = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("tag", profile.name)
                    put("protocol", outboundProto)
                    put("settings", settingsObj)
                    put("streamSettings", streamSettings)
                }
            )
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        }
        root.put("outbounds", outbounds)
        return root.toString(2)
    }

    fun toXrayServerJson(serverState: ServerRuntimeState): String {
        val domain = serverState.effectiveDisguiseDomain
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject().apply {
                    put("tag", "bridge-in")
                    put("listen", "0.0.0.0")
                    put("port", serverState.listenPort)
                    put("protocol", "vless")
                    put(
                        "settings",
                        JSONObject().apply {
                            put(
                                "clients",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("id", serverState.uuid)
                                        put("email", "client@velobridge.local")
                                    }
                                )
                            )
                            put("decryption", "none")
                        }
                    )
                    put(
                        "streamSettings",
                        JSONObject().apply {
                            put("network", "ws")
                            put(
                                "wsSettings",
                                JSONObject().apply {
                                    put("path", serverState.wsPath)
                                    put("headers", JSONObject().put("Host", domain))
                                }
                            )
                        }
                    )
                }
            )
        )
        root.put("outbounds", JSONArray().put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
        return root.toString(2)
    }

    private fun matchPresetId(domain: String, fallback: String = "custom"): String {
        val lower = domain.lowercase()
        return when {
            lower.contains("youtube") || lower.contains("googlevideo") -> "youtube"
            lower.contains("zoom.us") -> "zoom"
            lower.contains("github") -> "github"
            lower.contains("cloudflare") -> "cloudflare"
            fallback in listOf("youtube", "zoom", "github", "cloudflare") && lower.isBlank() -> fallback
            else -> "custom"
        }
    }

    private fun decodeBase64Flexible(input: String): String? {
        val cleaned = input.trim().replace("-", "+").replace("_", "/")
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
