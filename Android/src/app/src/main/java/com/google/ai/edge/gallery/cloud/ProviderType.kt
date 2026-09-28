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

/** Wire format a provider speaks. Seven of the nine supported backends share [OPENAI_COMPAT]. */
enum class CloudApiStyle {
  /** `POST {base}/chat/completions` + `GET {base}/models`, `Authorization: Bearer`. */
  OPENAI_COMPAT,
  /** `POST {base}/models/{model}:streamGenerateContent`, `x-goog-api-key`. */
  GEMINI,
  /** `POST {base}/v1/messages`, `x-api-key` + `anthropic-version`. */
  ANTHROPIC,
}

/** How a provider spells the output length limit. */
enum class MaxTokensField {
  /** `max_tokens` — OpenRouter, NVIDIA, Qwen, Ollama, Anthropic. */
  MAX_TOKENS,
  /** `max_completion_tokens` — OpenAI, xAI, Groq. */
  MAX_COMPLETION_TOKENS,
  /** `generationConfig.maxOutputTokens` — Gemini. */
  MAX_OUTPUT_TOKENS,
}

/** How a provider accepts a "please reason before answering" instruction. */
enum class ThinkingParam {
  /** No documented request knob. Reasoning traces are parsed when the model emits them anyway. */
  NONE,
  /** Qwen: top level `enable_thinking` boolean. */
  QWEN_ENABLE_THINKING,
  /** NVIDIA: top level `chat_template_kwargs: {"enable_thinking": true}`. */
  NVIDIA_CHAT_TEMPLATE,
}

/**
 * Static, provider level configuration.
 *
 * Everything here is derived from the providers' public REST documentation. No network call is
 * needed to resolve a descriptor, which keeps the UI renderable offline.
 *
 * @param apiStyle the request/response dialect spoken by the provider.
 * @param defaultBaseUrl base url without a trailing slash.
 * @param modelsPath path appended verbatim to the base url to list models.
 * @param chatPath path appended verbatim to the base url to stream a chat completion.
 * @param requiresApiKey whether chat completions need a key. Listing may still be anonymous.
 * @param anonymousModelList whether [modelsPath] works without credentials.
 * @param maxTokensField which JSON field carries the output length limit.
 * @param thinkingParam how to request reasoning, when the provider documents a knob.
 * @param streamRequiresUsageField some providers reject `stream_options`; all nine accept it.
 */
data class ProviderDescriptor(
  val id: String,
  val displayName: String,
  val tagline: String,
  val apiStyle: CloudApiStyle,
  val defaultBaseUrl: String,
  val modelsPath: String,
  val chatPath: String,
  @DrawableRes val iconRes: Int,
  val accentArgb: Long,
  val keyHint: String,
  val apiKeyUrl: String,
  val docsUrl: String,
  val requiresApiKey: Boolean,
  val anonymousModelList: Boolean,
  val allowsBaseUrlOverride: Boolean,
  val maxTokensField: MaxTokensField,
  val thinkingParam: ThinkingParam,
  val defaultModelId: String,
) {
  /** `https://host/v1` -> `https://host/v1/chat/completions` */
  fun resolveUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val suffix = if (path.startsWith("/")) path else "/$path"
    return base + suffix
  }

  fun chatUrl(baseUrl: String, modelId: String): String {
    if (apiStyle == CloudApiStyle.GEMINI) {
      // The verb is a suffix of the *path segment*, not a separate path element.
      return resolveUrl(baseUrl, chatPath.replace("{model}", modelId))
    }
    return resolveUrl(baseUrl, chatPath)
  }

  fun modelsUrl(baseUrl: String): String = resolveUrl(baseUrl, modelsPath)
}

/**
 * The nine supported cloud providers.
 *
 * Ordering is intentional: it is the order shown in the provider picker.
 */
enum class ProviderType(
  val descriptor: ProviderDescriptor,
) {
  OPENAI(
    ProviderDescriptor(
      id = "openai",
      displayName = "OpenAI",
      tagline = "GPT and o-series models",
      apiStyle = CloudApiStyle.OPENAI_COMPAT,
      defaultBaseUrl = "https://api.openai.com/v1",
      modelsPath = "/models",
      chatPath = "/chat/completions",
      iconRes = R.drawable.provider_openai,
      accentArgb = 0xFF10A37FL,
      keyHint = "sk-…",
      apiKeyUrl = "https://platform.openai.com/api-keys",
      docsUrl = "https://platform.openai.com/docs/api-reference/chat",
      requiresApiKey = true,
      anonymousModelList = false,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
      thinkingParam = ThinkingParam.NONE,
      defaultModelId = "gpt-4o-mini",
    )
  ),
  GEMINI(
    ProviderDescriptor(
      id = "gemini",
      displayName = "Gemini",
      tagline = "Google Generative Language API",
      apiStyle = CloudApiStyle.GEMINI,
      defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
      modelsPath = "/models",
      chatPath = "/models/{model}:streamGenerateContent",
      iconRes = R.drawable.provider_gemini,
      accentArgb = 0xFF4285F4L,
      keyHint = "AIza…",
      apiKeyUrl = "https://aistudio.google.com/app/apikey",
      docsUrl = "https://ai.google.dev/gemini-api/docs/text-generation",
      requiresApiKey = true,
      anonymousModelList = false,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_OUTPUT_TOKENS,
      thinkingParam = ThinkingParam.NONE,
      defaultModelId = "gemini-2.0-flash",
    )
  ),
  GROK(
    ProviderDescriptor(
      id = "grok",
      displayName = "Grok",
      tagline = "xAI",
      apiStyle = CloudApiStyle.OPENAI_COMPAT,
      defaultBaseUrl = "https://api.x.ai/v1",
      modelsPath = "/models",
      chatPath = "/chat/completions",
      iconRes = R.drawable.provider_xai,
      accentArgb = 0xFF6E7681L,
      keyHint = "xai-…",
      apiKeyUrl = "https://console.x.ai",
      docsUrl = "https://docs.x.ai/docs/api-reference",
      requiresApiKey = true,
      anonymousModelList = false,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
      thinkingParam = ThinkingParam.NONE,
      defaultModelId = "grok-2-latest",
    )
  ),
  GROQ(
    ProviderDescriptor(
      id = "groq",
      displayName = "Groq",
      tagline = "Low latency inference",
      apiStyle = CloudApiStyle.OPENAI_COMPAT,
      defaultBaseUrl = "https://api.groq.com/openai/v1",
      modelsPath = "/models",
      chatPath = "/chat/completions",
      iconRes = R.drawable.provider_groq,
      accentArgb = 0xFFF55036L,
      keyHint = "gsk_…",
      apiKeyUrl = "https://console.groq.com/keys",
      docsUrl = "https://console.groq.com/docs/api-reference",
      requiresApiKey = true,
      anonymousModelList = false,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
      thinkingParam = ThinkingParam.NONE,
      defaultModelId = "llama-3.3-70b-versatile",
    )
  ),
  OPENROUTER(
    ProviderDescriptor(
      id = "openrouter",
      displayName = "OpenRouter",
      tagline = "One key, many vendors",
      apiStyle = CloudApiStyle.OPENAI_COMPAT,
      defaultBaseUrl = "https://openrouter.ai/api/v1",
      modelsPath = "/models",
      chatPath = "/chat/completions",
      iconRes = R.drawable.provider_openrouter,
      accentArgb = 0xFF6467F2L,
      keyHint = "sk-or-v1-…",
      apiKeyUrl = "https://openrouter.ai/keys",
      docsUrl = "https://openrouter.ai/docs/api-reference/overview",
      requiresApiKey = true,
      anonymousModelList = true,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_TOKENS,
      thinkingParam = ThinkingParam.NONE,
      defaultModelId = "openai/gpt-4o-mini",
    )
  ),
  NVIDIA(
    ProviderDescriptor(
      id = "nvidia",
      displayName = "NVIDIA",
      tagline = "NIM / API Catalog",
      apiStyle = CloudApiStyle.OPENAI_COMPAT,
      defaultBaseUrl = "https://integrate.api.nvidia.com/v1",
      modelsPath = "/models",
      chatPath = "/chat/completions",
      iconRes = R.drawable.provider_nvidia,
      accentArgb = 0xFF76B900L,
      keyHint = "nvapi-…",
      apiKeyUrl = "https://build.nvidia.com/settings",
      docsUrl = "https://docs.api.nvidia.com/",
      requiresApiKey = true,
      anonymousModelList = true,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_TOKENS,
      thinkingParam = ThinkingParam.NVIDIA_CHAT_TEMPLATE,
      defaultModelId = "nvidia/llama-3.3-nemotron-super-49b-v1.5",
    )
  ),
  ANTHROPIC(
    ProviderDescriptor(
      id = "anthropic",
      displayName = "Claude",
      tagline = "Anthropic",
      apiStyle = CloudApiStyle.ANTHROPIC,
      defaultBaseUrl = "https://api.anthropic.com",
      modelsPath = "/v1/models",
      chatPath = "/v1/messages",
      iconRes = R.drawable.provider_anthropic,
      accentArgb = 0xFFD97757L,
      keyHint = "sk-ant-…",
      apiKeyUrl = "https://console.anthropic.com/settings/keys",
      docsUrl = "https://docs.anthropic.com/en/api/messages",
      requiresApiKey = true,
      anonymousModelList = false,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_TOKENS,
      thinkingParam = ThinkingParam.NONE,
      defaultModelId = "claude-sonnet-4-5",
    )
  ),
  QWEN(
    ProviderDescriptor(
      id = "qwen",
      displayName = "Qwen",
      tagline = "Alibaba Cloud Model Studio",
      apiStyle = CloudApiStyle.OPENAI_COMPAT,
      defaultBaseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
      modelsPath = "/models",
      chatPath = "/chat/completions",
      iconRes = R.drawable.provider_qwen,
      accentArgb = 0xFF6B5BFFL,
      keyHint = "sk-…",
      apiKeyUrl = "https://bailian.console.alibabacloud.com/",
      docsUrl =
        "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope",
      requiresApiKey = true,
      anonymousModelList = false,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_TOKENS,
      thinkingParam = ThinkingParam.QWEN_ENABLE_THINKING,
      defaultModelId = "qwen-plus",
    )
  ),
  OLLAMA(
    ProviderDescriptor(
      id = "ollama",
      displayName = "Ollama",
      tagline = "Cloud, via ollama.com",
      apiStyle = CloudApiStyle.OPENAI_COMPAT,
      defaultBaseUrl = "https://ollama.com/v1",
      modelsPath = "/models",
      chatPath = "/chat/completions",
      iconRes = R.drawable.provider_ollama,
      accentArgb = 0xFF9E9E9EL,
      keyHint = "ollama…",
      apiKeyUrl = "https://ollama.com/settings/keys",
      docsUrl = "https://docs.ollama.com/cloud",
      requiresApiKey = true,
      anonymousModelList = true,
      allowsBaseUrlOverride = true,
      maxTokensField = MaxTokensField.MAX_TOKENS,
      thinkingParam = ThinkingParam.NONE,
      defaultModelId = "gpt-oss:120b",
    )
  ),
  ;

  companion object {
    private val BY_ID: Map<String, ProviderType> = entries.associateBy { it.descriptor.id }

    /** Returns the provider whose [ProviderDescriptor.id] matches, or `null`. */
    fun fromId(id: String): ProviderType? = BY_ID[id]
  }
}
