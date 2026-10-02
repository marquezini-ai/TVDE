package com.daniel.tvdeinsight.data.sync.backend

import com.daniel.tvdeinsight.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class BackendHttpResponse(
    val status: Int,
    val body: ByteArray,
    val retryAfterSeconds: Long?
)

@Singleton
class BackendHttpClient @Inject constructor() {
    suspend fun post(path: String, body: ByteArray, headers: Map<String, String>): BackendHttpResponse =
        withContext(Dispatchers.IO) {
            require(path.startsWith('/') && '?' !in path && '#' !in path)
            val url = URL(BuildConfig.BACKEND_BASE_URL.trimEnd('/') + path)
            require(url.protocol == "https") { "Backend must use HTTPS" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                BackendHttpResponse(
                    status = status,
                    body = stream?.use(::readLimited) ?: ByteArray(0),
                    retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESPONSE_BYTES) { "Backend response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        val json = Json { ignoreUnknownKeys = false; explicitNulls = true; encodeDefaults = true }
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
