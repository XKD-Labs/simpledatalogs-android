package com.xkdlabs.simpledatalogs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPOutputStream

/** One HTTP answer from ingest: the status and the Retry-After header, if any. */
@InternalSimpleDataLogsApi
public class IngestHttpResponse(
    /** The HTTP status code. */
    public val statusCode: Int,

    /** The raw Retry-After header value, if the server sent one. */
    public val retryAfterHeader: String?,
)

/** Posts one request body; tests inject a scripted transport instead of the network. */
@InternalSimpleDataLogsApi
public interface IngestTransport {
    /** Sends a POST and returns the answer, or throws an IOException on a timeout or network error. */
    @Throws(IOException::class)
    public fun post(url: String, headers: Map<String, String>, body: ByteArray): IngestHttpResponse
}

/** The production transport: HttpURLConnection, with no cache, no cookies and no redirects. */
@InternalSimpleDataLogsApi
public class HttpUrlConnectionTransport(
    /** How long connecting, and then reading the answer, may each take. */
    private val requestTimeoutMillis: Int,
) : IngestTransport {
    /** Sends the POST and reads only the status line and headers. */
    override fun post(url: String, headers: Map<String, String>, body: ByteArray): IngestHttpResponse {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection

        try {
            // A POST with a known length, never cached and never redirected (a 3xx is retried like any failure).
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connectTimeout = requestTimeoutMillis
            connection.readTimeout = requestTimeoutMillis
            connection.setFixedLengthStreamingMode(body.size)

            // Every header the ingest API asks for.
            for ((headerName, headerValue) in headers) {
                connection.setRequestProperty(headerName, headerValue)
            }

            // Write the body, then wait for the status.
            connection.outputStream.use { requestStream -> requestStream.write(body) }
            val statusCode = connection.responseCode
            val retryAfterHeader = connection.getHeaderField("Retry-After")

            // Drain the answer, so the connection can be reused.
            drainQuietly(if (statusCode >= 400) connection.errorStream else connection.inputStream)

            return IngestHttpResponse(statusCode, retryAfterHeader)
        } finally {
            connection.disconnect()
        }
    }

    // Reads and discards a response body, ignoring any failure.
    private fun drainQuietly(responseStream: InputStream?) {
        try {
            responseStream?.use { stream ->
                val discardBuffer = ByteArray(1_024)
                while (stream.read(discardBuffer) != -1) {
                    // Keep reading until the end.
                }
            }
        } catch (drainError: IOException) {
            // The status is already known, so a broken body changes nothing.
        }
    }
}

/** What one ingest response means for the batch that was sent. */
internal sealed class IngestOutcome {
    /** 202: durably stored, so the batch leaves the outbox. */
    object Accepted : IngestOutcome()

    /** 401: the key is unknown, revoked or not yet live, so sending pauses and re-probes slowly. */
    object InvalidKey : IngestOutcome()

    /** 413: the body was too large, so the batch is split in half. */
    object PayloadTooLarge : IngestOutcome()

    /** 415: ingest can never take this batch as it is, so it is set aside, never resent. */
    object Unsupported : IngestOutcome()

    /** 429, 503, any other status, a timeout or a network error: keep the batch and retry. */
    data class Retryable(val retryAfterMillis: Long?, val reason: String) : IngestOutcome()

    /** Maps a status code onto the response table. */
    companion object {
        /** Classifies one HTTP answer. */
        fun fromResponse(response: IngestHttpResponse, nowMillis: Long): IngestOutcome {
            return when (response.statusCode) {
                202 -> Accepted
                401 -> InvalidKey
                413 -> PayloadTooLarge
                415 -> Unsupported
                else -> {
                    // Anything else, including other 2xx codes, was not stored, so it is retried.
                    val retryAfterMillis = RetryAfterHeader.parseDelayMillis(response.retryAfterHeader, nowMillis)
                    Retryable(retryAfterMillis, "HTTP ${response.statusCode}")
                }
            }
        }
    }
}

/** Posts NDJSON batches to `<endpoint>/v1/ingest` with the ingest API headers, gzipping larger bodies. */
internal class IngestClient(
    /** The full ingest URL. */
    private val ingestUrl: String,

    /** The bearer key. */
    private val ingestKey: String,

    /** The User-Agent every request carries. */
    private val userAgent: String,

    /** Bodies of at least this many bytes are gzipped. */
    private val gzipThresholdBytes: Int,

    /** Sends the request. */
    private val transport: IngestTransport,

    /** Reads the time for Retry-After dates. */
    private val clock: Clock,
) {
    /** Sends one batch and classifies the answer; it never throws. */
    fun send(batchBody: ByteArray): IngestOutcome {
        // Build the headers, compressing the body when it is worth it.
        val requestHeaders = linkedMapOf(
            "Authorization" to "Bearer $ingestKey",
            "Content-Type" to "application/x-ndjson",
            "User-Agent" to userAgent,
        )
        var requestBody = batchBody

        val compressedBody = if (batchBody.size >= gzipThresholdBytes) gzipOrNull(batchBody) else null
        if (compressedBody != null) {
            requestBody = compressedBody
            requestHeaders["Content-Encoding"] = "gzip"
        }

        // Send it; timeouts, offline and any other failure leave the batch unconfirmed.
        return try {
            val response = transport.post(ingestUrl, requestHeaders, requestBody)
            IngestOutcome.fromResponse(response, clock.currentTimeMillis())
        } catch (sendError: Exception) {
            IngestOutcome.Retryable(null, sendError.javaClass.simpleName + ": " + sendError.message)
        }
    }

    // Gzips a body, or returns null so it goes out uncompressed when compression fails.
    private fun gzipOrNull(batchBody: ByteArray): ByteArray? {
        return try {
            val compressedStream = ByteArrayOutputStream(batchBody.size / 4 + 64)
            GZIPOutputStream(compressedStream).use { gzipStream -> gzipStream.write(batchBody) }

            compressedStream.toByteArray()
        } catch (compressionError: IOException) {
            null
        }
    }
}
