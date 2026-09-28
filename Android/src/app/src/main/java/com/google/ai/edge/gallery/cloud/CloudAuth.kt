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

/**
 * Builds the authentication and identification headers for a provider request.
 *
 * @param apiKey may be blank for the providers whose model listing is public
 *   (OpenRouter, NVIDIA, Ollama). A blank key simply omits the credential header.
 */
internal fun authHeaders(type: ProviderType, apiKey: String): Map<String, String> {
  val headers = LinkedHashMap<String, String>()
  val key = apiKey.trim()

  when (type.descriptor.apiStyle) {
    CloudApiStyle.GEMINI -> {
      if (key.isNotEmpty()) headers["x-goog-api-key"] = key
    }
    CloudApiStyle.ANTHROPIC -> {
      if (key.isNotEmpty()) headers["x-api-key"] = key
      headers["anthropic-version"] = ANTHROPIC_VERSION
    }
    CloudApiStyle.OPENAI_COMPAT -> {
      if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
      if (type == ProviderType.OPENROUTER) {
        // Recommended by OpenRouter for leaderboard attribution. Optional, but harmless.
        headers["HTTP-Referer"] = "https://github.com/google-ai-edge/gallery"
        headers["X-Title"] = "AI Edge Gallery"
      }
    }
  }
  return headers
}

/** The `anthropic-version` value required on every Anthropic request. */
internal const val ANTHROPIC_VERSION = "2023-06-01"
