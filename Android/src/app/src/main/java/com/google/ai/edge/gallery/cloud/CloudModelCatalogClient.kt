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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the live model catalog from a provider.
 *
 * This is what powers the "Fetch models" action that becomes available as soon as an API key is
 * entered: the app asks the provider what it actually serves right now, instead of shipping a
 * hardcoded list that goes stale. The result is cached in [CloudProviderRepository] so the picker
 * still works offline.
 */
@Singleton
open class CloudModelCatalogClient @Inject constructor(private val transport: CloudHttpTransport) {

  companion object {
    private const val TAG = "AGModelCatalog"
  }

  /**
   * Lists the models a provider serves.
   *
   * @throws HttpStatusException when the provider rejects the request, so the caller can show
   *   [humanReadableError].
   */
  open suspend fun fetchModels(
    type: ProviderType,
    baseUrl: String,
    apiKey: String,
  ): List<CloudModelInfo> {
    val descriptor = type.descriptor
    val url = descriptor.modelsUrl(baseUrl)
    val body = transport.getText(url = url, headers = authHeaders(type, apiKey))
    val parsed =
      when (descriptor.apiStyle) {
        CloudApiStyle.GEMINI -> parseGeminiModels(body)
        CloudApiStyle.ANTHROPIC -> parseAnthropicModels(body)
        CloudApiStyle.OPENAI_COMPAT -> parseOpenAiCompatModels(type, body)
      }

    if (parsed.isEmpty()) {
      Log.w(TAG, "${descriptor.id} returned no usable models")
    }

    // Ollama's OpenAI-compat listing carries no capability data, so enrich from the native
    // endpoint when reachable. Failure is non-fatal.
    val enriched =
      if (type == ProviderType.OLLAMA && parsed.isNotEmpty()) {
        enrichOllamaFromNativeApi(baseUrl, apiKey, parsed)
      } else {
        parsed
      }

    return enriched.sortedWith(
      compareByDescending<CloudModelInfo> { it.favorite }
        .thenBy { it.displayName.lowercase() }
        .thenBy { it.id.lowercase() }
    )
  }

  // ------------------------------------------------------------------ parsing

  private fun parseGeminiModels(body: String): List<CloudModelInfo> =
    asArray(body, "models")
      // Keep only models that can actually answer a chat turn.
      .filter { obj ->
        val methods = obj.stringListOrNull("supportedGenerationMethods")
        methods.isNullOrEmpty() || methods.any { it.equals("generateContent", ignoreCase = true) }
      }
      .map { obj ->
        val fullName = obj.stringOrNull("name").orEmpty()
        val id = fullName.removePrefix("models/")
        CloudModelInfo(
          id = id,
          displayName = obj.stringOrNull("displayName").orEmpty().ifBlank { id },
          description = obj.stringOrNull("description").orEmpty(),
          contextLength = obj.longOrNull("inputTokenLimit") ?: 0L,
          maxOutputTokens = obj.longOrNull("outputTokenLimit") ?: 0L,
          ownedBy = "google",
          supportsVision = id.looksLikeVisionModel(),
          supportsTools = true,
          supportsThinking = obj.boolOrNull("thinking") ?: true,
          pricingPrompt = "",
          pricingCompletion = "",
        )
      }
      .filter { model -> model.id.isNotBlank() && !model.id.contains("embedding") }

  private fun parseAnthropicModels(body: String): List<CloudModelInfo> =
    asArray(body, "data")
      .map { obj ->
        val id = obj.stringOrNull("id").orEmpty()
        CloudModelInfo(
          id = id,
          displayName = obj.stringOrNull("display_name").orEmpty().ifBlank { id },
          description = obj.stringOrNull("description").orEmpty(),
          contextLength = obj.longOrNull("max_input_tokens") ?: 0L,
          maxOutputTokens = obj.longOrNull("max_tokens") ?: 0L,
          ownedBy = "anthropic",
          supportsVision = true,
          supportsTools = true,
          supportsThinking = id.startsWith("claude-") && !id.contains("haiku"),
          pricingPrompt = "",
          pricingCompletion = "",
        )
      }
      .filter { it.id.isNotBlank() }

  private fun parseOpenAiCompatModels(type: ProviderType, body: String): List<CloudModelInfo> =
    asArray(body, "data")
      .filter { obj ->
        // OpenRouter also serves image generators and embedders; keep text-output chat models.
        if (type != ProviderType.OPENROUTER) return@filter true
        val outputModalities = obj.objectOrNull("architecture")?.stringListOrNull("output_modalities")
        outputModalities.isNullOrEmpty() || outputModalities.contains("text")
      }
      .map { obj ->
        val id = obj.stringOrNull("id") ?: obj.stringOrNull("name").orEmpty()
        val architecture = obj.objectOrNull("architecture")
        val modalities = architecture?.stringListOrNull("input_modalities").orEmpty()
        val outputModalities = architecture?.stringListOrNull("output_modalities").orEmpty()
        val supportedParams = obj.stringListOrNull("supported_parameters").orEmpty()
        val pricing = obj.objectOrNull("pricing")
        val lowerId = id.lowercase()

        CloudModelInfo(
          id = id,
          displayName =
            obj.stringOrNull("name")?.takeIf { it.isNotBlank() && it != id }
              ?: obj.stringOrNull("display_name")?.takeIf { it.isNotBlank() }
              ?: id,
          description = obj.stringOrNull("description").orEmpty(),
          contextLength =
            obj.longOrNull("context_length")
              ?: obj.longOrNull("context_window")
              ?: obj.longOrNull("max_input_tokens")
              ?: 0L,
          maxOutputTokens =
            obj.longOrNull("max_completion_tokens")
              ?: obj.longOrNull("max_output_tokens")
              ?: 0L,
          ownedBy =
            obj.stringOrNull("owned_by").orEmpty().ifBlank { id.substringBefore("/") },
          supportsVision =
            modalities.contains("image") || modalities.contains("file") || id.looksLikeVisionModel(),
          supportsTools =
            if (supportedParams.isNotEmpty()) supportedParams.contains("tools") else true,
          supportsThinking =
            if (supportedParams.isNotEmpty()) {
              supportedParams.contains("reasoning") || supportedParams.contains("include_reasoning")
            } else {
              lowerId.looksLikeThinkingModel()
            },
          pricingPrompt = pricing?.let { it.stringOrNull("prompt") }.orEmpty(),
          pricingCompletion = pricing?.let { it.stringOrNull("completion") }.orEmpty(),
        )
      }
      .filter { it.id.isNotBlank() }
      .filter { it.id.isNotEmbeddingOrMediaModel() }

  /**
   * Ollama's `/v1/models` is an OpenAI-shaped stub with no capability data. `/api/tags` on the
   * cloud endpoint works anonymously, so use it to add thinking/vision hints.
   */
  private suspend fun enrichOllamaFromNativeApi(
    baseUrl: String,
    apiKey: String,
    models: List<CloudModelInfo>,
  ): List<CloudModelInfo> {
    val origin = baseUrl.trimEnd('/').removeSuffix("/v1")
    val nativeUrl = "$origin/api/tags"
    return try {
      val body = transport.getText(url = nativeUrl, headers = authHeaders(ProviderType.OLLAMA, apiKey))
      val byName = asArray(body, "models").associate { obj ->
        val name = obj.stringOrNull("name") ?: obj.stringOrNull("model").orEmpty()
        name to obj
      }
      models.map { model ->
        val extra = byName[model.id] ?: return@map model
        model.copy(
          contextLength = extra.longOrNull("context_length") ?: model.contextLength,
          description = model.description.ifBlank { extra.stringOrNull("description").orEmpty() },
        )
      }
    } catch (e: Exception) {
      Log.i(TAG, "Ollama native catalog unavailable, keeping OpenAI-compat data", e)
      models
    }
  }

  /** Parses [body] and returns the array under [field], or an empty list. */
  private fun asArray(body: String, field: String): List<JsonObject> {
    val root =
      try {
        JsonParser.parseString(body)
      } catch (e: Exception) {
        Log.w(TAG, "Model list is not valid JSON", e)
        return emptyList()
      }
    if (!root.isJsonObject) return emptyList()
    val array = root.asJsonObject.get(field) ?: return emptyList()
    if (!array.isJsonArray) return emptyList()
    return array.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
  }
}

// ----------------------------------------------------------------- heuristics

/** Model ids that indicate image input support, for providers that do not advertise it. */
internal fun String.looksLikeVisionModel(): Boolean {
  val id = lowercase()
  val markers =
    listOf(
      "vision",
      "-vl",
      "vl-",
      "vllm",
      "gpt-4o",
      "gpt-4.1",
      "gpt-5",
      "gemini-1.5",
      "gemini-2",
      "gemini-3",
      "claude-3",
      "claude-4",
      "claude-sonnet-4",
      "claude-opus-4",
      "grok-2-vision",
      "grok-vision",
      "pixtral",
      "llava",
      "internvl",
      "minicpm-v",
      "deepseek-vl",
      "qwen2.5-vl",
      "qwen2-vl",
      "qwen3-vl",
      "nemotron-nano-vl",
      "kimi-k2-thinking",
    )
  return markers.any { id.contains(it) }
}

/** Model ids that indicate reasoning support, for providers that do not advertise it. */
internal fun String.looksLikeThinkingModel(): Boolean {
  val id = lowercase()
  val markers =
    listOf(
      "thinking",
      "think",
      "reasoning",
      "reasoner",
      "qwq",
      "deepseek-r1",
      "r1-",
      "-r1",
      "nemotron-3",
      "nemotron-nano-3",
      "gpt-oss",
      "qwen3",
      "magistral",
      "exaone-deep",
    )
  return markers.any { id.contains(it) }
}

/** Filters out embeddings, moderation, TTS, transcription and image generation endpoints. */
internal fun String.isNotEmbeddingOrMediaModel(): Boolean {
  val id = lowercase()
  val excluded =
    listOf(
      "text-embedding",
      "embedding",
      "moderation",
      "whisper",
      "tts-",
      "-tts",
      "dall-e",
      "dalle",
      "gpt-image",
      "imagen",
      "stable-diffusion",
      "guard",
      "realtime",
      "audio",
      "tts",
    )
  return excluded.none { id.contains(it) }
}
