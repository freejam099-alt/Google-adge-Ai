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
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Streams a chat turn from any of the nine supported providers.
 *
 * Seven of them speak the OpenAI `chat/completions` dialect, so [streamOpenAiCompatible] covers
 * OpenAI, Grok, Groq, OpenRouter, NVIDIA, Qwen and Ollama. Gemini and Anthropic need their own
 * request shape and their own SSE event grammar, handled by [streamGemini] and [streamAnthropic].
 *
 * Failures are surfaced as [ChatEvent.Failed] rather than thrown, so a view model only has to
 * collect one flow type. Cancellation always propagates.
 */
@Singleton
open class CloudChatClient @Inject constructor(private val transport: CloudHttpTransport) {

  companion object {
    private const val TAG = "AGCloudChat"
    // Groq rewrites temperature 0 to 1e-8, and every other backend accepts a small positive value,
    // so clamp into a range that is valid everywhere.
    private const val MIN_TEMPERATURE = 0.01
    private const val MAX_TEMPERATURE = 2.0
  }

  /** Emits deltas for one turn, ending with exactly one [ChatEvent.Completed] or [ChatEvent.Failed]. */
  open fun stream(request: CloudChatRequest): Flow<ChatEvent> = flow {
    val guard = TerminalGuard(this)
    try {
      when (request.provider.descriptor.apiStyle) {
        CloudApiStyle.OPENAI_COMPAT -> streamOpenAiCompatible(request, guard)
        CloudApiStyle.GEMINI -> streamGemini(request, guard)
        CloudApiStyle.ANTHROPIC -> streamAnthropic(request, guard)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Chat request failed", e)
      guard.emit(ChatEvent.Failed(describeFailure(e), (e as? HttpStatusException)?.statusCode))
    }
    guard.seal()
  }

  /**
   * Enforces the single terminal event contract in one place.
   *
   * Several backends report a mid-stream failure with HTTP 200 and then close the socket normally,
   * so a parser can reach its trailing `Completed` after it already emitted a `Failed`. Rather than
   * duplicating a failure flag in all three parsers, everything flows through this guard: the first
   * terminal event wins and everything after it is dropped.
   */
  private class TerminalGuard(private val delegate: FlowCollector<ChatEvent>) : FlowCollector<ChatEvent> {
    private var terminalSent = false

    override suspend fun emit(value: ChatEvent) {
      if (terminalSent) return
      if (value is ChatEvent.Completed || value is ChatEvent.Failed) terminalSent = true
      delegate.emit(value)
    }

    /** Emits a failure when a parser finished without ever reporting an outcome. */
    suspend fun seal() {
      if (terminalSent) return
      terminalSent = true
      delegate.emit(ChatEvent.Failed("The provider closed the connection without a response.", null))
    }
  }

  private fun describeFailure(e: Exception): String =
    when (e) {
      is HttpStatusException -> humanReadableError(e.statusCode, e.body)
      is CloudTransportException -> e.message ?: "The request failed."
      else -> e.message?.takeIf { it.isNotBlank() } ?: "The request failed."
    }

  // ------------------------------------------------------------ OpenAI family

  private suspend fun streamOpenAiCompatible(
    request: CloudChatRequest,
    collector: FlowCollector<ChatEvent>,
  ) {
    val descriptor = request.provider.descriptor
    val url = descriptor.chatUrl(request.baseUrl, request.modelId)
    val body = buildOpenAiBody(request)
    val headers = authHeaders(request.provider, request.apiKey)

    if (!request.stream) {
      val raw = transport.postJson(url = url, body = body, headers = headers)
      val root = parseJsonObject(raw)
      if (root == null) {
        collector.emit(ChatEvent.Failed("The provider returned an unreadable response.", null))
        return
      }
      if (root.objectOrNull("error") != null) {
        collector.emit(ChatEvent.Failed(humanReadableError(200, root.toString()), 200))
        return
      }
      val choice = firstChoice(root)
      val message = choice?.objectOrNull("message")
      val answer = message?.stringOrNull("content").orEmpty()
      if (answer.isNotEmpty()) collector.emit(ChatEvent.TextDelta(answer))
      val usageJson = root.objectOrNull("usage")
      collector.emit(
        ChatEvent.Completed(
          finishReason = choice?.stringOrNull("finish_reason") ?: "stop",
          usage =
            TokenUsage(
              promptTokens = usageJson?.longOrNull("prompt_tokens") ?: 0L,
              completionTokens = usageJson?.longOrNull("completion_tokens") ?: 0L,
              reasoningTokens =
                usageJson?.objectOrNull("completion_tokens_details")?.longOrNull("reasoning_tokens")
                  ?: 0L,
            ),
        )
      )
      return
    }

    val usage = TokenUsage()
    var finishReason = "stop"

    transport.streamSse(
      url = url,
      body = body,
      headers = headers,
    ) { frame ->
      if (frame.isDoneSentinel()) return@streamSse
      val chunk = parseJsonObject(frame.data) ?: return@streamSse

      // OpenRouter reports mid-stream failures as HTTP 200 with a top level error object.
      if (chunk.objectOrNull("error") != null) {
        collector.emit(ChatEvent.Failed(humanReadableError(200, chunk.toString()), 200))
        return@streamSse
      }

      val choice = firstChoice(chunk)
      val delta = choice?.objectOrNull("delta")

      if (delta != null) {
        val reasoning = delta.stringOrNull("reasoning_content") ?: delta.stringOrNull("reasoning")
        if (!reasoning.isNullOrEmpty()) collector.emit(ChatEvent.ThinkingDelta(reasoning))

        val text = delta.stringOrNull("content").orEmpty()
        if (text.isNotEmpty()) collector.emit(ChatEvent.TextDelta(text))
      }

      choice?.stringOrNull("finish_reason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }

      chunk.objectOrNull("usage")?.let { usageJson ->
        usage.promptTokens = usageJson.longOrNull("prompt_tokens") ?: usage.promptTokens
        usage.completionTokens = usageJson.longOrNull("completion_tokens") ?: usage.completionTokens
        usage.reasoningTokens =
          usageJson
            .objectOrNull("completion_tokens_details")
            ?.longOrNull("reasoning_tokens")
            ?: usage.reasoningTokens
      }
    }
    collector.emit(ChatEvent.Completed(finishReason, usage))
  }

  private fun buildOpenAiBody(request: CloudChatRequest): String {
    val messages = JsonArray()
    for (message in request.messages) {
      when (message.role) {
        ChatRole.SYSTEM ->
          messages.add(
            JsonObject().apply {
              addProperty("role", "system")
              addProperty("content", message.content)
            }
          )
        ChatRole.USER -> messages.add(buildOpenAiUserMessage(message))
        ChatRole.ASSISTANT ->
          messages.add(
            JsonObject().apply {
              addProperty("role", "assistant")
              addProperty("content", message.content)
            }
          )
      }
    }

    return JsonObject().apply {
      addProperty("model", request.modelId)
      add("messages", messages)
      addProperty("stream", request.stream)
      if (request.stream) {
        add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
      }

      val maxTokens = request.maxOutputTokens.coerceIn(1, 32_768)
      when (request.provider.descriptor.maxTokensField) {
        MaxTokensField.MAX_COMPLETION_TOKENS -> addProperty("max_completion_tokens", maxTokens)
        MaxTokensField.MAX_TOKENS -> addProperty("max_tokens", maxTokens)
        // Gemini never reaches this client; it uses generationConfig.maxOutputTokens.
        MaxTokensField.MAX_OUTPUT_TOKENS -> addProperty("max_tokens", maxTokens)
      }

      addProperty("temperature", request.temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE))

      when (request.provider.descriptor.thinkingParam) {
        ThinkingParam.QWEN_ENABLE_THINKING -> addProperty("enable_thinking", request.showThinking)
        ThinkingParam.NVIDIA_CHAT_TEMPLATE ->
          add(
            "chat_template_kwargs",
            JsonObject().apply { addProperty("enable_thinking", request.showThinking) },
          )
        // No documented request knob: reasoning traces are still parsed if the model emits them.
        ThinkingParam.NONE -> Unit
      }
    }.toString()
  }

  private fun buildOpenAiUserMessage(message: CloudChatMessage): JsonObject =
    if (message.images.isEmpty()) {
      JsonObject().apply {
        addProperty("role", "user")
        addProperty("content", message.content)
      }
    } else {
      val parts = JsonArray()
      message.images.forEach { image ->
        parts.add(
          JsonObject().apply {
            addProperty("type", "image_url")
            add("image_url", JsonObject().apply { addProperty("url", image.dataUrl) })
          }
        )
      }
      parts.add(
        JsonObject().apply {
          addProperty("type", "text")
          addProperty("text", message.content)
        }
      )
      JsonObject().apply {
        addProperty("role", "user")
        add("content", parts)
      }
    }

  // ------------------------------------------------------------------- Gemini

  private suspend fun streamGemini(
    request: CloudChatRequest,
    collector: FlowCollector<ChatEvent>,
  ) {
    val descriptor = request.provider.descriptor
    // The verb is part of the path segment, and it differs between the two modes.
    val verb = if (request.stream) "streamGenerateContent" else "generateContent"
    val modelPath = descriptor.chatPath.replace("{model}", request.modelId)
    val path = modelPath.substringBeforeLast(':') + ":" + verb
    val url = descriptor.resolveUrl(request.baseUrl, path) + if (request.stream) "?alt=sse" else ""
    val body = buildGeminiBody(request)
    val headers = authHeaders(request.provider, request.apiKey)

    if (!request.stream) {
      val raw = transport.postJson(url = url, body = body, headers = headers)
      val root = parseJsonObject(raw)
      if (root == null) {
        collector.emit(ChatEvent.Failed("Gemini returned an unreadable response.", null))
        return
      }
      if (root.objectOrNull("error") != null) {
        collector.emit(ChatEvent.Failed(humanReadableError(200, root.toString()), 200))
        return
      }
      if (blockedReason(root) != null) {
        collector.emit(ChatEvent.Failed("Gemini blocked this prompt (${blockedReason(root)}).", null))
        return
      }
      emitGeminiCandidate(collector, root)
      val usageJson = root.objectOrNull("usageMetadata")
      collector.emit(
        ChatEvent.Completed(
          finishReason = firstCandidate(root)?.stringOrNull("finishReason") ?: "STOP",
          usage =
            TokenUsage(
              promptTokens = usageJson?.longOrNull("promptTokenCount") ?: 0L,
              completionTokens = usageJson?.longOrNull("candidatesTokenCount") ?: 0L,
              reasoningTokens = usageJson?.longOrNull("thoughtsTokenCount") ?: 0L,
            ),
        )
      )
      return
    }

    val usage = TokenUsage()
    var finishReason = "STOP"

    transport.streamSse(
      url = url,
      body = body,
      headers = headers,
    ) { frame ->
      val chunk = parseJsonObject(frame.data) ?: return@streamSse

      // Content filtering is not an HTTP error: HTTP 200 with a block reason and no candidates.
      val block = blockedReason(chunk)
      if (block != null) {
        collector.emit(ChatEvent.Failed("Gemini blocked this prompt ($block).", null))
        return@streamSse
      }

      emitGeminiCandidate(collector, chunk)

      firstCandidate(chunk)?.stringOrNull("finishReason")?.takeIf { it.isNotBlank() }?.let {
        finishReason = it
      }

      chunk.objectOrNull("usageMetadata")?.let { usageJson ->
        usage.promptTokens = usageJson.longOrNull("promptTokenCount") ?: usage.promptTokens
        usage.completionTokens =
          usageJson.longOrNull("candidatesTokenCount") ?: usage.completionTokens
        usage.reasoningTokens = usageJson.longOrNull("thoughtsTokenCount") ?: usage.reasoningTokens
      }
    }
    collector.emit(ChatEvent.Completed(finishReason, usage))
  }

  /** Emits the text of the first candidate, routing `thought` parts to the thinking channel. */
  private suspend fun emitGeminiCandidate(
    collector: FlowCollector<ChatEvent>,
    root: JsonObject,
  ) {
    val parts =
      firstCandidate(root)
        ?.objectOrNull("content")
        ?.get("parts")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?: return
    parts.forEach { element ->
      if (!element.isJsonObject) return@forEach
      val part = element.asJsonObject
      val text = part.stringOrNull("text").orEmpty()
      if (text.isEmpty()) return@forEach
      if (part.boolOrNull("thought") == true) {
        collector.emit(ChatEvent.ThinkingDelta(text))
      } else {
        collector.emit(ChatEvent.TextDelta(text))
      }
    }
  }

  /** Returns the block reason when Gemini refused the prompt outright, otherwise `null`. */
  private fun blockedReason(root: JsonObject): String? {
    val reason = root.objectOrNull("promptFeedback")?.stringOrNull("blockReason") ?: return null
    return if (reason == "BLOCK_REASON_UNSPECIFIED" || reason == "OFF") null else reason
  }

  private fun buildGeminiBody(request: CloudChatRequest): String {
    val contents = JsonArray()
    val systemParts = JsonArray()

    for (message in request.messages) {
      when (message.role) {
        ChatRole.SYSTEM -> systemParts.add(JsonObject().apply { addProperty("text", message.content) })
        ChatRole.USER -> {
          val parts = JsonArray()
          message.images.forEach { image ->
            parts.add(
              JsonObject().apply {
                add(
                  "inlineData",
                  JsonObject().apply {
                    addProperty("mimeType", image.mimeType)
                    addProperty("data", image.base64Data)
                  },
                )
              }
            )
          }
          parts.add(JsonObject().apply { addProperty("text", message.content) })
          contents.add(
            JsonObject().apply {
              addProperty("role", "user")
              add("parts", parts)
            }
          )
        }
        ChatRole.ASSISTANT -> {
          if (message.content.isBlank()) continue
          val parts = JsonArray()
          parts.add(JsonObject().apply { addProperty("text", message.content) })
          contents.add(
            JsonObject().apply {
              addProperty("role", "model")
              add("parts", parts)
            }
          )
        }
      }
    }

    return JsonObject().apply {
      add("contents", contents)
      if (systemParts.size() > 0) {
        add(
          "systemInstruction",
          JsonObject().apply { add("parts", systemParts) },
        )
      }
      add(
        "generationConfig",
        JsonObject().apply {
          addProperty("maxOutputTokens", request.maxOutputTokens.coerceIn(1, 32_768))
          addProperty("temperature", request.temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE))
          addProperty("topP", 0.95)
          addProperty("topK", 40)
        },
      )
    }.toString()
  }

  // ---------------------------------------------------------------- Anthropic

  private suspend fun streamAnthropic(
    request: CloudChatRequest,
    collector: FlowCollector<ChatEvent>,
  ) {
    val descriptor = request.provider.descriptor
    val url = descriptor.chatUrl(request.baseUrl, request.modelId)
    val body = buildAnthropicBody(request)
    val headers = authHeaders(request.provider, request.apiKey)

    if (!request.stream) {
      val raw = transport.postJson(url = url, body = body, headers = headers)
      val root = parseJsonObject(raw)
      if (root == null) {
        collector.emit(ChatEvent.Failed("Anthropic returned an unreadable response.", null))
        return
      }
      if (root.stringOrNull("type") == "error" || root.objectOrNull("error") != null) {
        collector.emit(ChatEvent.Failed(humanReadableError(200, root.toString()), 200))
        return
      }
      // The non-streaming response carries the thinking signature in the same block as the trace, so
      // collect it here too. Without it the next turn would replay an unsigned thinking block and
      // Anthropic rejects the whole request.
      val signatures = mutableListOf<String>()
      root.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
        if (!element.isJsonObject) return@forEach
        val block = element.asJsonObject
        val type = block.stringOrNull("type")
        if (type == "text") {
          block.stringOrNull("text")?.takeIf { it.isNotEmpty() }?.let {
            collector.emit(ChatEvent.TextDelta(it))
          }
        } else if (type == "thinking") {
          block.stringOrNull("thinking")?.takeIf { it.isNotEmpty() }?.let {
            collector.emit(ChatEvent.ThinkingDelta(it))
          }
        }
        block.stringOrNull("signature")?.takeIf { it.isNotBlank() }?.let { signatures.add(it) }
      }
      val usageJson = root.objectOrNull("usage")
      collector.emit(
        ChatEvent.Completed(
          finishReason = root.stringOrNull("stop_reason") ?: "end_turn",
          usage =
            TokenUsage(
              promptTokens = usageJson?.longOrNull("input_tokens") ?: 0L,
              completionTokens = usageJson?.longOrNull("output_tokens") ?: 0L,
            ),
          thinkingSignature = signatures.joinToString(separator = ""),
        )
      )
      return
    }

    var finishReason = "end_turn"
    var promptTokens = 0L
    var outputTokens = 0L
    // Signature deltas arrive per content block index; keep them keyed by that index.
    val signatureByIndex = HashMap<Int, StringBuilder>()

    transport.streamSse(
      url = url,
      body = body,
      headers = headers,
    ) { frame ->
      val event = parseJsonObject(frame.data) ?: return@streamSse
      when (event.stringOrNull("type")) {
        "message_start" ->
          event
            .objectOrNull("message")
            ?.objectOrNull("usage")
            ?.let { promptTokens = it.longOrNull("input_tokens") ?: 0L }

        "content_block_start" -> {
          val index = event.longOrNull("index")?.toInt() ?: return@streamSse
          val blockType = event.objectOrNull("content_block")?.stringOrNull("type")
          if (blockType == "thinking" || blockType == "redacted_thinking") {
            signatureByIndex.getOrPut(index) { StringBuilder() }
          }
        }

        "content_block_delta" -> {
          val index = event.longOrNull("index")?.toInt() ?: 0
          val delta = event.objectOrNull("delta")
          when (delta?.stringOrNull("type")) {
            "text_delta" ->
              delta.stringOrNull("text")?.takeIf { it.isNotEmpty() }?.let {
                collector.emit(ChatEvent.TextDelta(it))
              }
            "thinking_delta" ->
              delta.stringOrNull("thinking")?.takeIf { it.isNotEmpty() }?.let {
                signatureByIndex.getOrPut(index) { StringBuilder() }
                collector.emit(ChatEvent.ThinkingDelta(it))
              }
            "signature_delta" ->
              delta.stringOrNull("signature")?.let { signature ->
                signatureByIndex.getOrPut(index) { StringBuilder() }.append(signature)
              }
          }
        }

        "message_delta" -> {
          event.objectOrNull("delta")?.stringOrNull("stop_reason")?.let { finishReason = it }
          event.objectOrNull("usage")?.longOrNull("output_tokens")?.let { outputTokens = it }
        }

        // Errors can arrive after HTTP 200 on the stream.
        "error" -> collector.emit(ChatEvent.Failed(humanReadableError(200, frame.data), null))
      }
    }

    collector.emit(
      ChatEvent.Completed(
        finishReason = finishReason,
        usage = TokenUsage(promptTokens = promptTokens, completionTokens = outputTokens),
        // Keyed by block index, so order the signatures back together before replaying them.
        thinkingSignature =
          signatureByIndex.entries.sortedBy { it.key }.joinToString(separator = "") { it.value.toString() },
      )
    )
  }

  private fun buildAnthropicBody(request: CloudChatRequest): String {
    // Anthropic requires strictly alternating user/assistant turns, so merge runs of one role.
    val merged = ArrayList<Pair<ChatRole, MutableList<JsonObject>>>()

    // Takes content blocks, not a wrapper object: the serializer below flattens these straight into
    // the message `content` array, and wrapping here would nest one `content` array inside another.
    fun append(role: ChatRole, blocks: List<JsonObject>) {
      if (blocks.isEmpty()) return
      val last = merged.lastOrNull()
      if (last != null && last.first == role) {
        last.second.addAll(blocks)
      } else {
        merged.add(role to blocks.toMutableList())
      }
    }

    fun textBlock(text: String): JsonObject =
      JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
      }

    for (message in request.messages) {
      when (message.role) {
        // Handled through the top level `system` field, which Anthropic requires.
        ChatRole.SYSTEM -> Unit
        ChatRole.USER -> {
          val blocks = mutableListOf<JsonObject>()
          message.images.forEach { image ->
            blocks.add(
              JsonObject().apply {
                addProperty("type", "image")
                add(
                  "source",
                  JsonObject().apply {
                    addProperty("type", "base64")
                    addProperty("media_type", image.mimeType)
                    addProperty("data", image.base64Data)
                  },
                )
              }
            )
          }
          blocks.add(textBlock(message.content))
          append(ChatRole.USER, blocks)
        }
        ChatRole.ASSISTANT -> {
          if (message.content.isBlank() && message.thinking.isBlank()) continue
          val blocks = mutableListOf<JsonObject>()
          // Thinking blocks must be echoed back byte-identical, signature included. Anthropic rejects
          // the whole request when a thinking block arrives without its signature, so an unsigned
          // trace is dropped rather than sent malformed.
          if (message.thinking.isNotBlank() && message.thinkingSignature.isNotBlank()) {
            blocks.add(
              JsonObject().apply {
                addProperty("type", "thinking")
                addProperty("thinking", message.thinking)
                addProperty("signature", message.thinkingSignature)
              }
            )
          }
          if (message.content.isNotBlank()) blocks.add(textBlock(message.content))
          append(ChatRole.ASSISTANT, blocks)
        }
      }
    }

    // The first turn must be the user's, and the conversation has to end on a user turn to be
    // answerable at all.
    if (merged.isEmpty() || merged.first().first != ChatRole.USER) {
      merged.add(0, ChatRole.USER to mutableListOf(textBlock("Continue.")))
    }
    if (merged.last().first != ChatRole.USER) {
      merged.add(ChatRole.USER to mutableListOf(textBlock("Continue.")))
    }

    // Several system messages can be produced (prompt plus web search context), so join them.
    val systemPrompt =
      request.messages
        .filter { it.role == ChatRole.SYSTEM }
        .joinToString(separator = "\n\n") { it.content }
        .takeIf { it.isNotBlank() }

    return JsonObject().apply {
      addProperty("model", request.modelId)
      // `max_tokens` is mandatory for the Messages API.
      addProperty("max_tokens", request.maxOutputTokens.coerceIn(1, 32_768))
      if (systemPrompt != null) addProperty("system", systemPrompt)
      add(
        "messages",
        JsonArray().apply {
          merged.forEach { (role, blocks) ->
            add(
              JsonObject().apply {
                addProperty("role", if (role == ChatRole.ASSISTANT) "assistant" else "user")
                add("content", JsonArray().apply { blocks.forEach { add(it) } })
              }
            )
          }
        },
      )
      addProperty("stream", request.stream)
      addProperty("temperature", request.temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE))
    }.toString()
  }
}

/** Parses [text] as a JSON object, returning `null` instead of throwing. */
private fun parseJsonObject(text: String): JsonObject? =
  try {
    val element = JsonParser.parseString(text)
    if (element.isJsonObject) element.asJsonObject else null
  } catch (e: Exception) {
    null
  }

/** The first element of a `choices` array, when the response is an OpenAI-style completion. */
private fun firstChoice(root: JsonObject): JsonObject? =
  root
    .get("choices")
    ?.takeIf { it.isJsonArray }
    ?.asJsonArray
    ?.firstOrNull()
    ?.takeIf { it.isJsonObject }
    ?.asJsonObject

/** The first element of a `candidates` array, used by Gemini. */
private fun firstCandidate(root: JsonObject): JsonObject? =
  root
    .get("candidates")
    ?.takeIf { it.isJsonArray }
    ?.asJsonArray
    ?.firstOrNull()
    ?.takeIf { it.isJsonObject }
    ?.asJsonObject
