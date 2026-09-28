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

import com.google.ai.edge.gallery.proto.CloudModel

/** Who produced a chat message. */
enum class ChatRole {
  SYSTEM,
  USER,
  ASSISTANT,
}

/** A single image attached to a user turn, already base64 encoded. */
data class ChatImage(val mimeType: String, val base64Data: String) {
  val dataUrl: String
    get() = "data:$mimeType;base64,$base64Data"
}

/** One turn of a conversation. */
data class CloudChatMessage(
  val role: ChatRole,
  val content: String,
  val images: List<ChatImage> = emptyList(),
  /** Reasoning text accumulated for this assistant turn, rendered in a collapsible block. */
  val thinking: String = "",
  /**
   * Cryptographic signature Anthropic attaches to a thinking block.
   *
   * The Messages API requires the `thinking` block to be echoed back byte-identical on the next
   * turn, signature included. Without it, a follow-up request is rejected.
   */
  val thinkingSignature: String = "",
)

/** Token accounting, when the provider reports it. */
/**
 * Token counters for one turn.
 *
 * The fields are mutable because a stream only reveals its usage in the closing frames, so the
 * parser accumulates into a single instance and hands it to the terminal event.
 */
data class TokenUsage(
  var promptTokens: Long = 0,
  var completionTokens: Long = 0,
  var reasoningTokens: Long = 0,
) {
  val totalTokens: Long
    get() = promptTokens + completionTokens
}

/** Incremental updates emitted while a response streams in. */
sealed interface ChatEvent {
  /** Visible answer text. */
  data class TextDelta(val text: String) : ChatEvent

  /** Reasoning trace, when the provider streams one. */
  data class ThinkingDelta(val text: String) : ChatEvent

  /**
   * Terminal state. Exactly one of [Completed] / [Failed] is emitted per turn.
   *
   * @param thinkingSignature Anthropic signs each thinking block; the caller stores it on the
   *   assistant turn so the next request can echo the block back byte-identical.
   */
  data class Completed(
    val finishReason: String,
    val usage: TokenUsage,
    val thinkingSignature: String = "",
  ) : ChatEvent

  data class Failed(val message: String, val statusCode: Int?) : ChatEvent
}

/** Everything needed to issue one chat turn. */
data class CloudChatRequest(
  val provider: ProviderType,
  val baseUrl: String,
  val apiKey: String,
  val modelId: String,
  val messages: List<CloudChatMessage>,
  val stream: Boolean,
  val temperature: Double,
  val maxOutputTokens: Int,
  val showThinking: Boolean,
)

/** A model as offered by one provider, normalised across all nine dialects. */
data class CloudModelInfo(
  val id: String,
  val displayName: String,
  val description: String,
  val contextLength: Long,
  val maxOutputTokens: Long,
  val ownedBy: String,
  val supportsVision: Boolean,
  val supportsTools: Boolean,
  val supportsThinking: Boolean,
  val pricingPrompt: String,
  val pricingCompletion: String,
  val favorite: Boolean = false,
) {
  /** Best available label for a picker row. */
  val label: String
    get() = displayName.ifBlank { id }
}

/** Maps the proto cache entry back into the domain model. */
fun CloudModel.toCloudModelInfo(): CloudModelInfo =
  CloudModelInfo(
    id = id,
    displayName = displayName,
    description = description,
    contextLength = contextLength,
    maxOutputTokens = maxOutputTokens,
    ownedBy = ownedBy,
    supportsVision = supportsVision,
    supportsTools = supportsTools,
    supportsThinking = supportsThinking,
    pricingPrompt = pricingPrompt,
    pricingCompletion = pricingCompletion,
    favorite = favorite,
  )

/** Maps a domain model into the proto cache entry. */
fun CloudModelInfo.toProto(): CloudModel =
  CloudModel.newBuilder()
    .setId(id)
    .setDisplayName(displayName)
    .setDescription(description)
    .setContextLength(contextLength)
    .setMaxOutputTokens(maxOutputTokens)
    .setOwnedBy(ownedBy)
    .setSupportsVision(supportsVision)
    .setSupportsTools(supportsTools)
    .setSupportsThinking(supportsThinking)
    .setPricingPrompt(pricingPrompt)
    .setPricingCompletion(pricingCompletion)
    .setFavorite(favorite)
    .build()
