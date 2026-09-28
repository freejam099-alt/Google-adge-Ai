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

import androidx.annotation.DrawableRes
import com.google.ai.edge.gallery.R
import java.net.URLEncoder

/** A single web search hit, normalised across the supported search backends. */
data class WebSearchResult(
  val title: String,
  val url: String,
  val snippet: String,
)

/**
 * Static configuration for a web-search backend.
 *
 * Auth is deliberately sent through *both* the documented header and the documented body/query
 * field. The backends below accept either, so sending both removes the failure mode where a wrong
 * header name silently returns an empty result set.
 */
data class SearchServiceDescriptor(
  val id: String,
  val displayName: String,
  val tagline: String,
  val method: String,
  val defaultBaseUrl: String,
  val path: String,
  @DrawableRes val iconRes: Int,
  val keyHint: String,
  val apiKeyUrl: String,
  val requiresApiKey: Boolean,
  val allowsBaseUrlOverride: Boolean,
  val defaultResultCount: Int,
  val maxResultCount: Int,
) {
  /**
   * Builds the request url for a search.
   *
   * [params] carries the backend specific extras that only make sense on a GET.
   */
  fun url(baseUrl: String, query: String, params: Map<String, String> = emptyMap()): String {
    val base = baseUrl.trim().trimEnd('/')
    val suffix = if (path.startsWith("/")) path else "/$path"
    if (method != "GET") return "$base$suffix"
    val parts = mutableListOf("q=${URLEncoder.encode(query, "UTF-8")}")
    params.forEach { (name, value) -> parts.add("$name=${URLEncoder.encode(value, "UTF-8")}") }
    return "$base$suffix?" + parts.joinToString("&")
  }
}

/** Web search backends offered under Settings > Search Service. */
enum class SearchServiceType(val descriptor: SearchServiceDescriptor) {
  TAVILY(
    SearchServiceDescriptor(
      id = "tavily",
      displayName = "Tavily",
      tagline = "Search API built for agents",
      method = "POST",
      defaultBaseUrl = "https://api.tavily.com",
      path = "/search",
      iconRes = R.drawable.ic_search_service,
      keyHint = "tvly-…",
      apiKeyUrl = "https://app.tavily.com/home",
      requiresApiKey = true,
      allowsBaseUrlOverride = false,
      defaultResultCount = 5,
      maxResultCount = 20,
    )
  ),
  SERPER(
    SearchServiceDescriptor(
      id = "serper",
      displayName = "Serper",
      tagline = "Google Search results",
      method = "POST",
      defaultBaseUrl = "https://google.serper.dev",
      path = "/search",
      iconRes = R.drawable.ic_search_service,
      keyHint = "…",
      apiKeyUrl = "https://serper.dev/",
      requiresApiKey = true,
      allowsBaseUrlOverride = false,
      defaultResultCount = 5,
      maxResultCount = 20,
    )
  ),
  BRAVE(
    SearchServiceDescriptor(
      id = "brave",
      displayName = "Brave Search",
      tagline = "Independent search index",
      method = "GET",
      defaultBaseUrl = "https://api.search.brave.com",
      path = "/res/v1/web/search",
      iconRes = R.drawable.ic_search_service,
      keyHint = "BSA…",
      apiKeyUrl = "https://brave.com/search/api/",
      requiresApiKey = true,
      allowsBaseUrlOverride = false,
      defaultResultCount = 5,
      maxResultCount = 20,
    )
  ),
  SEARXNG(
    SearchServiceDescriptor(
      id = "searxng",
      displayName = "SearXNG",
      tagline = "Self-hosted metasearch",
      method = "GET",
      defaultBaseUrl = "https://searx.be",
      path = "/search",
      iconRes = R.drawable.ic_search_service,
      keyHint = "(none)",
      apiKeyUrl = "https://docs.searxng.org/",
      requiresApiKey = false,
      allowsBaseUrlOverride = true,
      defaultResultCount = 5,
      maxResultCount = 20,
    )
  ),
  ;

  /**
   * Query string parameters this backend needs in addition to the search term.
   *
   * These live on the enum rather than on [SearchServiceDescriptor] because they are named after enum
   * entries, which are not in scope from inside the descriptor.
   */
  fun extraQueryParams(resultCount: Int): Map<String, String> =
    when (this) {
      // Without format=json SearXNG answers with an HTML page that cannot be parsed.
      SEARXNG -> mapOf("format" to "json", "language" to "en")
      BRAVE -> mapOf("count" to resultCount.coerceIn(1, descriptor.maxResultCount).toString())
      else -> emptyMap()
    }

  companion object {
    private val BY_ID: Map<String, SearchServiceType> =
      entries.associateBy { it.descriptor.id }

    fun fromId(id: String): SearchServiceType? = BY_ID[id]
  }
}
