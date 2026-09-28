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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a web search through one of the backends in [SearchServiceType] and returns hits the model
 * can cite.
 *
 * Results are injected into the conversation as a single system message rather than through a
 * provider-specific native search tool. That keeps one code path for all nine providers, including
 * the ones (Anthropic's `web_search` tool, Gemini's `google_search` grounding) that do support
 * server-side search but gate it behind a preview flag.
 */
@Singleton
open class WebSearchClient @Inject constructor(private val transport: CloudHttpTransport) {

  companion object {
    private const val TAG = "AGWebSearch"
    private const val MAX_SNIPPET_CHARS = 600
  }

  /** Raised when the search backend rejects the request. */
  class SearchException(val statusCode: Int?, message: String) : Exception(message)

  /**
   * Performs one search.
   *
   * @param resultCount how many hits to request, clamped to the backend's documented maximum.
   * @throws SearchException when the backend fails or the response cannot be understood.
   */
  open suspend fun search(
    service: SearchServiceType,
    baseUrl: String,
    apiKey: String,
    query: String,
    resultCount: Int,
  ): List<WebSearchResult> {
    val descriptor = service.descriptor
    if (descriptor.requiresApiKey && apiKey.isBlank()) {
      throw SearchException(
        null,
        "${descriptor.displayName} needs an API key. Add one under Settings > Search Service.",
      )
    }
    val count = resultCount.coerceIn(1, descriptor.maxResultCount)
    val url =
      descriptor.url(
        baseUrl = baseUrl,
        query = query,
        params = service.extraQueryParams(count),
      )
    val headers = authHeaders(service, apiKey)

    val body =
      try {
        if (descriptor.method == "GET") {
          transport.getText(url = url, headers = headers)
        } else {
          transport.postJson(
            url = url,
            body = requestBody(service, query, count, apiKey),
            headers = headers,
          )
        }
      } catch (e: HttpStatusException) {
        throw SearchException(e.statusCode, humanReadableError(e.statusCode, e.body))
      } catch (e: CloudTransportException) {
        throw SearchException(null, e.message ?: "The search backend could not be reached.")
      }

    val results = parse(service, body)
    if (results.isEmpty()) {
      Log.w(TAG, "${descriptor.id} returned no results for '$query'")
    }
    return results.take(count)
  }

  /** Sends the credential in the header the backend documents, plus a harmless `Bearer`. */
  private fun authHeaders(service: SearchServiceType, apiKey: String): Map<String, String> {
    val key = apiKey.trim()
    val headers = LinkedHashMap<String, String>()
    if (key.isEmpty()) return headers
    when (service) {
      SearchServiceType.TAVILY -> {
        // Tavily documents the key in the body; the header is accepted too.
        headers["Authorization"] = "Bearer $key"
      }
      SearchServiceType.SERPER -> headers["X-API-KEY"] = key
      SearchServiceType.BRAVE -> headers["X-Subscription-Token"] = key
      SearchServiceType.SEARXNG -> Unit
    }
    return headers
  }

  private fun requestBody(
    service: SearchServiceType,
    query: String,
    count: Int,
    apiKey: String,
  ): String =
    JsonObject().apply {
      when (service) {
        SearchServiceType.TAVILY -> {
          addProperty("api_key", apiKey.trim())
          addProperty("query", query)
          addProperty("max_results", count)
          addProperty("search_depth", "basic")
          addProperty("include_answer", false)
        }
        SearchServiceType.SERPER -> {
          addProperty("q", query)
          addProperty("num", count)
        }
        else -> Unit
      }
    }.toString()

  private fun parse(service: SearchServiceType, body: String): List<WebSearchResult> {
    val root = runCatching { com.google.gson.JsonParser.parseString(body) }.getOrNull()
    if (root == null || !root.isJsonObject) return emptyList()

    val results: JsonArray =
      when (service) {
        // {"results":[{"title","url","content"}]}
        SearchServiceType.TAVILY -> root.asJsonObject.arrayOrNull("results")
        // {"organic":[{"title","link","snippet"}]}
        SearchServiceType.SERPER -> root.asJsonObject.arrayOrNull("organic")
        // {"web":{"results":[{"title","url","description"}]}}
        SearchServiceType.BRAVE ->
          root.asJsonObject.objectOrNull("web")?.arrayOrNull("results") ?: JsonArray()
        // {"results":[{"title","url","content"}]}
        SearchServiceType.SEARXNG -> root.asJsonObject.arrayOrNull("results")
      }

    return results
      .mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val obj = element.asJsonObject
        val url = obj.stringOrNull("url") ?: obj.stringOrNull("link") ?: return@mapNotNull null
        if (!url.startsWith("http")) return@mapNotNull null
        WebSearchResult(
          title = obj.stringOrNull("title").orEmpty().ifBlank { url },
          url = url,
          snippet =
            (obj.stringOrNull("content")
                ?: obj.stringOrNull("description")
                ?: obj.stringOrNull("snippet")
                ?: "")
              .take(MAX_SNIPPET_CHARS),
        )
      }
      .distinctBy { it.url }
  }
}

/** Reads [name] as an array of objects, or an empty array. */
private fun JsonObject.arrayOrNull(name: String): JsonArray {
  val element = get(name) ?: return JsonArray()
  if (!element.isJsonArray) return JsonArray()
  return element.asJsonArray
}

/**
 * Renders search hits as the system message that precedes a question.
 *
 * The numbered list plus the "cite as [n]" instruction is the format every model in the list
 * handles reliably, and it keeps the URLs inspectable in the transcript.
 */
internal fun buildSearchContextMessage(query: String, results: List<WebSearchResult>): String =
  buildString {
    appendLine("Web search results for: \"$query\"")
    appendLine("Use them where they help, and ignore the ones that do not apply.")
    appendLine("Cite the sources you relied on inline as [1], [2], and so on.")
    appendLine()
    results.forEachIndexed { index, result ->
      appendLine("[${index + 1}] ${result.title}")
      appendLine("    ${result.url}")
      if (result.snippet.isNotBlank()) appendLine("    ${result.snippet}")
    }
  }
