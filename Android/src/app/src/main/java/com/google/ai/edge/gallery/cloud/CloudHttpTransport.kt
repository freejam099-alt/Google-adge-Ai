/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.cloud

import android.util.Log
import com.google.ai.edge.gallery.di.IoDispatcher
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A non-2xx response, carrying whatever the server said so the UI can surface it. */
class HttpStatusException(
  val statusCode: Int,
  val statusMessage: String,
  val body: String,
  val url: String,
) : IOException("HTTP $statusCode $statusMessage for $url: ${body.take(400)}")

/** Something went wrong before or during the request, in a form worth showing to a human. */
class CloudTransportException(message: String, cause: Throwable? = null) :
  IOException(message, cause)

/**
 * A thin [HttpURLConnection] wrapper.
 *
 * The app deliberately has no HTTP client dependency ([HuggingFaceApiClient] also uses
 * `HttpURLConnection`), so this mirrors that style rather than adding a new library and the build
 * risk that comes with it.
 *
 * The important behaviour is [withConnection]: it registers a cancellation hook that disconnects the
 * underlying socket, so a coroutine cancelled mid-stream unblocks the thread that is parked in
 * [BufferedReader.readLine] instead of leaking it.
 */
@Singleton
open class CloudHttpTransport @Inject constructor(@IoDispatcher private val ioDispatcher: CoroutineDispatcher) {

  companion object {
    private const val USER_AGENT = "AIEdgeGallery/1.1 (Android)"
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
    private const val DEFAULT_READ_TIMEOUT_MS = 90_000
    private const val STREAM_READ_TIMEOUT_MS = 300_000
    private const val TAG = "AGCloudTransport"
  }

  /** Performs a request and hands the live connection to [block]. */
  suspend fun <T> withConnection(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String? = null,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    // `suspend` because streaming callers invoke their onFrame callback from inside this block.
    block: suspend (HttpURLConnection) -> T,
  ): T = withContext(ioDispatcher) {
    val connection: HttpURLConnection
    try {
      connection = (URL(url).openConnection() as HttpURLConnection)
    } catch (e: Exception) {
      throw CloudTransportException("Cannot open $url", e)
    }

    try {
      connection.apply {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
        if (body != null) {
          doOutput = true
          setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
      }

      // Unblock a thread parked on readLine() as soon as the coroutine is cancelled.
      val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion {
        runCatching { connection.disconnect() }
      }

      try {
        if (body != null) {
          connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        block(connection)
      } finally {
        cancellationHandle?.dispose()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpStatusException) {
      throw e
    } catch (e: CloudTransportException) {
      throw e
    } catch (e: Exception) {
      throw CloudTransportException(describe(e), e)
    } finally {
      runCatching { connection.disconnect() }
    }
  }

  /** GETs [url] and returns the response body, throwing [HttpStatusException] on non-2xx. */
  suspend fun getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
  ): String =
    withConnection(
      method = "GET",
      url = url,
      headers = headers,
      readTimeoutMs = readTimeoutMs,
    ) { connection ->
      readBodyOrThrow(connection, url)
    }

  /** POSTs [body] and returns the response body, throwing [HttpStatusException] on non-2xx. */
  suspend fun postJson(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
  ): String =
    withConnection(
      method = "POST",
      url = url,
      headers = headers,
      body = body,
      readTimeoutMs = readTimeoutMs,
    ) { connection ->
      readBodyOrThrow(connection, url)
    }

  /**
   * POSTs [body] and streams `text/event-stream` frames to [onFrame].
   *
   * `onFrame` receives the raw payload of each SSE frame, with the `data:` prefix and the comment
   * keep-alive lines already stripped. The final frame is dispatched even when the server does not
   * terminate the stream with a blank line.
   */
  suspend fun streamSse(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    onFrame: suspend (SseFrame) -> Unit,
  ): Unit =
    withConnection(
      method = "POST",
      url = url,
      headers = headers + ("Accept" to "text/event-stream"),
      body = body,
      readTimeoutMs = STREAM_READ_TIMEOUT_MS,
    ) { connection ->
      val status = connection.responseCode
      if (status !in 200..299) {
        val errorBody = connection.errorStream.readTextSafely()
        throw HttpStatusException(status, connection.responseMessage ?: "", errorBody, url)
      }
      val parser = SseParser()
      val stream = connection.inputStream
      stream.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
          coroutineContext.ensureActive()
          val line = reader.readLine() ?: break
          val frame = parser.feed(line) ?: continue
          onFrame(frame)
        }
      }
      parser.flush()?.let { onFrame(it) }
    }

  private fun readBodyOrThrow(connection: HttpURLConnection, url: String): String {
    val status = connection.responseCode
    if (status !in 200..299) {
      val errorBody = connection.errorStream.readTextSafely()
      throw HttpStatusException(status, connection.responseMessage ?: "", errorBody, url)
    }
    return connection.inputStream.readTextSafely()
  }

  private fun InputStream?.readTextSafely(): String {
    if (this == null) return ""
    return try {
      bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to read response body", e)
      ""
    }
  }

  private fun describe(e: Exception): String =
    when (e) {
      is java.net.SocketTimeoutException -> "The request timed out. The server did not reply in time."
      is java.net.UnknownHostException -> "No internet connection, or the host could not be resolved."
      is javax.net.ssl.SSLException -> "The TLS handshake failed. Check the base url and your network."
      else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
}

/** A decoded `text/event-stream` frame. [event] is `null` for providers that omit event names. */
data class SseFrame(val event: String?, val data: String)

/**
 * Incremental [SseParser] for `text/event-stream` bodies.
 *
 * Handles the three differences that matter across the supported providers:
 *  - `data:` frames are concatenated until a blank line dispatches them.
 *  - Lines starting with `:` are SSE comments. OpenRouter sends `: OPENROUTER PROCESSING`
 *    keep-alives that must not be parsed as JSON.
 *  - Anthropic names its frames with `event:` lines; most others do not.
 */
class SseParser {
  private var eventName: String? = null
  private val data = StringBuilder()
  private var sawData = false

  /** Feeds one line, returning a frame when the line completed one. */
  fun feed(line: String): SseFrame? {
    if (line.isEmpty()) {
      return dispatch()
    }
    if (line.startsWith(":")) {
      // Comment / keep-alive.
      return null
    }
    when {
      line.startsWith("event:") -> {
        eventName = line.removePrefix("event:").trimStart(' ').ifEmpty { null }
      }
      line.startsWith("data:") -> {
        val raw = line.removePrefix("data:")
        val value = if (raw.startsWith(" ")) raw.substring(1) else raw
        if (sawData) data.append('\n')
        data.append(value)
        sawData = true
      }
      // `id:` and `retry:` carry no payload we care about.
    }
    return null
  }

  /** Disperts a trailing frame for servers that end the body without a final blank line. */
  fun flush(): SseFrame? = dispatch()

  private fun dispatch(): SseFrame? {
    if (!sawData) {
      eventName = null
      return null
    }
    val frame = SseFrame(event = eventName, data = data.toString())
    eventName = null
    data.setLength(0)
    sawData = false
    return frame
  }
}

/** The payload of the OpenAI style `data: [DONE]` sentinel. */
private const val SSE_DONE = "[DONE]"

internal fun SseFrame.isDoneSentinel(): Boolean = data.trim() == SSE_DONE
