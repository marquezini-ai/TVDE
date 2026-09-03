package com.daniel.tvdeinsight.data.sheets

import android.content.Context
import android.net.Uri
import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers

@Singleton
class GoogleSheetsClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isConfigured: Boolean
        get() = BuildConfig.GOOGLE_SHEETS_SPREADSHEET_ID.isNotBlank() &&
            BuildConfig.GOOGLE_SERVICE_ACCOUNT_ASSET.isNotBlank()

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiresAtMillis = 0L

    suspend fun getRows(): List<List<String>> = withContext(Dispatchers.IO) {
        val response = request(
            method = "GET",
            url = valuesUrl(),
            body = null
        )
        val values = JSONObject(response).optJSONArray("values") ?: return@withContext emptyList()
        values.toRows()
    }

    suspend fun ensureHeader(rows: List<List<String>>) = withContext(Dispatchers.IO) {
        val existingHeader = rows.firstOrNull()
        if (existingHeader != null && existingHeader.size >= TripSheetCodec.header.size) return@withContext
        val updateUrl = Uri.parse(valuesUrl())
            .buildUpon()
            .appendQueryParameter("valueInputOption", "RAW")
            .build()
            .toString()
        request(
            method = "PUT",
            url = updateUrl,
            body = JSONObject().put("majorDimension", "ROWS").put(
                "values", JSONArray().put(JSONArray(TripSheetCodec.header))
            ).toString()
        )
    }

    suspend fun appendRows(rows: List<List<String>>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val encodedRange = URLEncoder.encode(SHEET_NAME, StandardCharsets.UTF_8.name())
        // Os parâmetros são construídos pela API Uri para garantir que chegam
        // no query string mesmo em versões diferentes do Android/HttpURLConnection.
        val appendUrl = Uri.parse("${valuesUrl(encodedRange)}:append")
            .buildUpon()
            .appendQueryParameter("valueInputOption", "RAW")
            .appendQueryParameter("insertDataOption", "INSERT_ROWS")
            .build()
            .toString()
        request(
            method = "POST",
            url = appendUrl,
            body = JSONObject().put("majorDimension", "ROWS").put("values", JSONArray(rows.map(::JSONArray))).toString()
        )
    }

    private fun valuesUrl(range: String = URLEncoder.encode(SHEET_NAME, StandardCharsets.UTF_8.name())) =
        "https://sheets.googleapis.com/v4/spreadsheets/${BuildConfig.GOOGLE_SHEETS_SPREADSHEET_ID}/values/$range"

    private suspend fun request(method: String, url: String, body: String?): String {
        check(isConfigured) { "Google Sheets não configurado: falta o ID da planilha ou o JSON" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(20).toInt()
            setRequestProperty("Authorization", "Bearer ${accessToken()}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doInput = true
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (status !in 200..299) error("Google Sheets HTTP $status: ${response.take(500)}")
            response
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun accessToken(): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { tokenExpiresAtMillis - now > 60_000L }?.let { return@withLock it }
        val credentials = loadCredentials()
        val issuedAt = Instant.now().epochSecond
        val assertion = createJwt(credentials, issuedAt)
        val connection = (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val form = "grant_type=${URLEncoder.encode(GRANT_TYPE, "UTF-8")}" +
            "&assertion=${URLEncoder.encode(assertion, "UTF-8")}"
        connection.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        connection.disconnect()
        if (status !in 200..299) error("Falha ao obter token Google: HTTP $status")
        val json = JSONObject(response)
        cachedToken = json.getString("access_token")
        tokenExpiresAtMillis = now + json.optLong("expires_in", 3600L) * 1000L
        cachedToken!!
    }

    private fun loadCredentials(): ServiceAccountCredentials {
        val assetName = BuildConfig.GOOGLE_SERVICE_ACCOUNT_ASSET
        val json = context.assets.open(assetName).use { it.readBytes().toString(StandardCharsets.UTF_8) }
        val objectJson = JSONObject(json)
        return ServiceAccountCredentials(
            clientEmail = objectJson.getString("client_email"),
            privateKey = privateKey(objectJson.getString("private_key"))
        )
    }

    private fun createJwt(credentials: ServiceAccountCredentials, issuedAt: Long): String {
        val header = JSONObject().put("alg", "RS256").put("typ", "JWT").toString()
        val claims = JSONObject()
            .put("iss", credentials.clientEmail)
            .put("scope", SHEETS_SCOPE)
            .put("aud", TOKEN_URL)
            .put("iat", issuedAt)
            .put("exp", issuedAt + 3600L)
            .toString()
        val unsigned = "${base64Url(header.toByteArray())}.${base64Url(claims.toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(credentials.privateKey)
            update(unsigned.toByteArray(StandardCharsets.UTF_8))
        }.sign()
        return "$unsigned.${base64Url(signature)}"
    }

    private fun privateKey(pem: String): PrivateKey {
        val normalized = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        return KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalized))
        )
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun JSONArray.toRows(): List<List<String>> = buildList {
        for (index in 0 until length()) {
            val row = optJSONArray(index) ?: continue
            add(buildList { for (column in 0 until row.length()) add(row.optString(column)) })
        }
    }

    private data class ServiceAccountCredentials(val clientEmail: String, val privateKey: PrivateKey)

    private companion object {
        const val SHEET_NAME = "Viagens"
        const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
    }
}
