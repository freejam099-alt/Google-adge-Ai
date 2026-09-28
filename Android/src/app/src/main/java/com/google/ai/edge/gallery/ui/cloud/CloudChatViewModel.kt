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

package com.google.ai.edge.gallery.ui.cloud

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.cloud.ChatEvent
import com.google.ai.edge.gallery.cloud.ChatImage
import com.google.ai.edge.gallery.cloud.ChatImageEncoder
import com.google.ai.edge.gallery.cloud.ChatRole
import com.google.ai.edge.gallery.cloud.CloudChatClient
import com.google.ai.edge.gallery.cloud.CloudChatMessage
import com.google.ai.edge.gallery.cloud.CloudChatRequest
import com.google.ai.edge.gallery.cloud.CloudModelInfo
import com.google.ai.edge.gallery.cloud.CloudProviderRepository
import com.google.ai.edge.gallery.cloud.ImageEncodingException
import com.google.ai.edge.gallery.cloud.ProviderType
import com.google.ai.edge.gallery.cloud.SearchServiceType
import com.google.ai.edge.gallery.cloud.TokenUsage
import com.google.ai.edge.gallery.cloud.WebSearchClient
import com.google.ai.edge.gallery.cloud.WebSearchResult
import com.google.ai.edge.gallery.cloud.buildSearchContextMessage
import com.google.ai.edge.gallery.proto.CloudSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One bubble in the transcript. */
data class CloudChatUiMessage(
  val id: Long,
  val role: ChatRole,
  val text: String = "",
  val thinking: String = "",
  val thinkingSignature: String = "",
  val images: List<ChatImage> = emptyList(),
  val streaming: Boolean = false,
  val error: String? = null,
  val searchResults: List<WebSearchResult> = emptyList(),
  val usage: TokenUsage? = null,
  val showThinking: Boolean = false,
) {
  val isEmpty: Boolean
    get() = text.isBlank() && thinking.isBlank() && error == null
}

data class CloudChatUiState(
  val provider: ProviderType = ProviderType.OPENAI,
  val modelId: String = "",
  val models: List<CloudModelInfo> = emptyList(),
  val messages: List<CloudChatUiMessage> = emptyList(),
  val isGenerating: Boolean = false,
  val inputText: String = "",
  val pendingImages: List<ChatImage> = emptyList(),
  val error: String? = null,
  val imageError: String? = null,
  val streamingEnabled: Boolean = true,
  val showThinking: Boolean = false,
  val systemPrompt: String = "",
  val searchEnabled: Boolean = false,
  val searchReady: Boolean = false,
  val providerReady: Boolean = false,
) {
  val canSend: Boolean
    get() = !isGenerating && (inputText.isNotBlank() || pendingImages.isNotEmpty()) && providerReady

  val hasTranscript: Boolean
    get() = messages.isNotEmpty()
}

@HiltViewModel
class CloudChatViewModel
@Inject
constructor(
  private val repository: CloudProviderRepository,
  private val chatClient: CloudChatClient,
  private val searchClient: WebSearchClient,
  @ApplicationContext private val context: Context,
) : ViewModel() {

  /** Screen-owned state. Everything else is derived from the repository on each recomposition. */
  private data class LocalState(
    val provider: ProviderType? = null,
    val messages: List<CloudChatUiMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val inputText: String = "",
    val pendingImages: List<ChatImage> = emptyList(),
    val error: String? = null,
    val imageError: String? = null,
    /** `null` means "follow the persisted default"; set by the in-chat thinking toggle. */
    val showThinkingOverride: Boolean? = null,
  )

  private val local = MutableStateFlow(LocalState())
  private val keyRevision = MutableStateFlow(0)
  private var turnJob: Job? = null
  private var nextMessageId = 0L

  val uiState: StateFlow<CloudChatUiState> =
    combine(repository.settings, local, keyRevision) { settings, local, _ ->
      buildState(settings, local)
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CloudChatUiState(),
      )

  private fun buildState(settings: CloudSettings, local: LocalState): CloudChatUiState {
    val provider =
      local.provider
        ?: ProviderType.fromId(settings.lastProviderId)
        ?: ProviderType.entries.firstOrNull { repository.apiKey(it).isNotBlank() }
        ?: ProviderType.OPENAI
    val config = repository.configOrDefault(settings, provider)
    val models = repository.modelsOf(settings, provider)
    val modelId =
      config.selectedModelId
        .takeIf { candidate -> models.any { it.id == candidate } }
        ?: models.firstOrNull()?.id
        ?: provider.descriptor.defaultModelId

    val searchService = SearchServiceType.fromId(settings.searchProviderId)
    val searchReady =
      searchService != null &&
        (!searchService.descriptor.requiresApiKey || repository.searchApiKey().isNotBlank())

    return CloudChatUiState(
      provider = provider,
      modelId = modelId,
      models = models,
      messages = local.messages,
      isGenerating = local.isGenerating,
      inputText = local.inputText,
      pendingImages = local.pendingImages,
      error = local.error,
      imageError = local.imageError,
      streamingEnabled = settings.streamingEnabled,
      showThinking = local.showThinkingOverride ?: settings.showThinking,
      systemPrompt = settings.systemPrompt,
      searchEnabled = settings.searchEnabled,
      searchReady = searchReady,
      providerReady =
        repository.apiKey(provider).isNotBlank() ||
          !provider.descriptor.requiresApiKey,
    )
  }

  // ------------------------------------------------------------- selection

  fun selectProvider(type: ProviderType) {
    // A different provider has different capabilities, so the old transcript would be misleading.
    local.update { it.copy(provider = type, error = null) }
    viewModelScope.launch { repository.setSelectedModel(type, type.descriptor.defaultModelId) }
  }

  fun selectModel(modelId: String) {
    val provider = uiState.value.provider
    viewModelScope.launch { repository.setSelectedModel(provider, modelId) }
  }

  fun refreshKeys() {
    keyRevision.update { it + 1 }
  }

  // ----------------------------------------------------------------- input

  fun updateInput(text: String) {
    local.update { it.copy(inputText = text) }
  }

  fun attachImage(uri: Uri) {
    viewModelScope.launch {
      try {
        val image = ChatImageEncoder.encode(context, uri)
        local.update { it.copy(pendingImages = it.pendingImages + image, imageError = null) }
      } catch (e: ImageEncodingException) {
        local.update { it.copy(imageError = e.message) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        local.update { it.copy(imageError = "That image could not be attached.") }
      }
    }
  }

  fun removePendingImage(index: Int) {
    local.update { state ->
      if (index !in state.pendingImages.indices) {
        state
      } else {
        state.copy(pendingImages = state.pendingImages.filterIndexed { i, _ -> i != index })
      }
    }
  }

  fun dismissImageError() {
    local.update { it.copy(imageError = null) }
  }

  fun dismissError() {
    local.update { it.copy(error = null) }
  }

  fun toggleThinking() {
    val current = uiState.value.showThinking
    local.update { it.copy(showThinkingOverride = !current) }
  }

  fun clearChat() {
    turnJob?.cancel()
    turnJob = null
    local.update {
      it.copy(
        messages = emptyList(),
        isGenerating = false,
        error = null,
        inputText = "",
        pendingImages = emptyList(),
      )
    }
  }

  // ------------------------------------------------------------------ turn

  /** Sends the current input, then streams the answer into a fresh assistant bubble. */
  fun send() {
    val snapshot = uiState.value
    if (!snapshot.canSend) return

    val userMessage =
      CloudChatUiMessage(
        id = nextMessageId++,
        role = ChatRole.USER,
        text = snapshot.inputText.trim(),
        images = snapshot.pendingImages,
      )
    val assistantId = nextMessageId++

    val assistantPlaceholder = CloudChatUiMessage(id = assistantId, role = ChatRole.ASSISTANT, streaming = true)

    local.update {
      it.copy(
        messages = it.messages + userMessage + assistantPlaceholder,
        isGenerating = true,
        inputText = "",
        pendingImages = emptyList(),
        error = null,
        showThinkingOverride = snapshot.showThinking,
      )
    }

    turnJob =
      viewModelScope.launch {
        try {
          runTurn(snapshot, userMessage, assistantId)
        } catch (e: CancellationException) {
          markCancelled(assistantId)
          throw e
        } catch (e: Exception) {
          failMessage(assistantId, e.message ?: "The request failed.")
        }
      }
  }

  /** Aborts the in-flight turn. Partial text stays on screen. */
  fun stop() {
    turnJob?.cancel()
    turnJob = null
    local.update { it.copy(isGenerating = false) }
  }

  private suspend fun runTurn(
    snapshot: CloudChatUiState,
    userMessage: CloudChatUiMessage,
    assistantId: Long,
  ) {
    val settings = repository.read()
    val provider = snapshot.provider
    val query = userMessage.text.ifBlank { DEFAULT_SEARCH_QUERY }
    val transcript = uiState.value.messages

    // Web search runs before the model call so its results can be part of the same turn.
    val searchResults =
      if (snapshot.searchEnabled && snapshot.searchReady) {
        runWebSearch(settings = settings, query = query, attachToId = userMessage.id)
      } else {
        emptyList()
      }

    val request =
      CloudChatRequest(
        provider = provider,
        baseUrl = repository.baseUrl(settings, provider),
        apiKey = repository.apiKey(provider),
        modelId = snapshot.modelId,
        messages = buildRequestMessages(settings, transcript, searchResults, query),
        stream = snapshot.streamingEnabled,
        temperature = settings.temperature,
        maxOutputTokens = settings.maxOutputTokens,
        showThinking = snapshot.showThinking,
      )

    // The client reports a failure and then ends the flow, but a streaming backend can still emit a
    // terminal frame afterwards. Latch the failure so a stale Completed does not clear the reason.
    var failed = false
    chatClient.stream(request).collect { event ->
      when (event) {
        is ChatEvent.TextDelta ->
          if (!failed) appendTo(assistantId) { it.copy(text = it.text + event.text) }
        is ChatEvent.ThinkingDelta ->
          if (!failed) appendTo(assistantId) { it.copy(thinking = it.thinking + event.text) }
        is ChatEvent.Completed ->
          if (!failed) {
            appendTo(assistantId) {
              it.copy(
                streaming = false,
                usage = event.usage,
                thinkingSignature = event.thinkingSignature,
              )
            }
          }
        is ChatEvent.Failed -> {
          failed = true
          failMessage(assistantId, event.message)
        }
      }
    }

    // Terminal fallback. A healthy turn clears this through Completed, and a broken one through
    // Failed, but a backend that simply drops the connection would otherwise leave the bubble
    // spinning and the composer disabled for the rest of the session.
    if (!failed) {
      val pending = local.value.messages.firstOrNull { it.id == assistantId }
      if (pending?.streaming == true) {
        val reason = if (pending.text.isBlank()) "The stream ended unexpectedly." else null
        if (reason != null) {
          failMessage(assistantId, reason)
        } else {
          appendTo(assistantId) { it.copy(streaming = false) }
          local.update { it.copy(isGenerating = false) }
        }
      } else {
        local.update { it.copy(isGenerating = false) }
      }
    }
  }

  private suspend fun runWebSearch(
    settings: CloudSettings,
    query: String,
    attachToId: Long,
  ): List<WebSearchResult> {
    val service = SearchServiceType.fromId(settings.searchProviderId) ?: return emptyList()
    return try {
      val results =
        searchClient.search(
          service = service,
          baseUrl = service.descriptor.defaultBaseUrl,
          apiKey = repository.searchApiKey(),
          query = query,
          resultCount =
            if (settings.searchResultCount > 0) settings.searchResultCount
            else CloudProviderRepository.DEFAULT_SEARCH_RESULT_COUNT,
        )
      if (results.isNotEmpty()) {
        // Attach the citations to the user turn so the transcript explains where facts came from.
        appendTo(attachToId) { it.copy(searchResults = results) }
      }
      results
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // A failed search should not kill the turn; the model answers from its own knowledge.
      local.update { it.copy(error = "Web search failed: ${e.message}. Answering without it.") }
      emptyList()
    }
  }

  /**
   * Assembles the provider payload.
   *
   * The transcript is trimmed from the front because every provider has a hard context window, and a
   * chat that dies with HTTP 413 after twenty turns is worse than one that quietly forgets the
   * beginning. Whole turns are dropped so user and assistant messages stay paired.
   */
  private fun buildRequestMessages(
    settings: CloudSettings,
    transcript: List<CloudChatUiMessage>,
    searchResults: List<WebSearchResult>,
    query: String,
  ): List<CloudChatMessage> {
    val out = mutableListOf<CloudChatMessage>()

    val systemParts = mutableListOf<String>()
    if (settings.systemPrompt.isNotBlank()) systemParts.add(settings.systemPrompt)
    if (searchResults.isNotEmpty()) {
      systemParts.add(buildSearchContextMessage(query, searchResults))
    }
    if (systemParts.isNotEmpty()) {
      out.add(CloudChatMessage(role = ChatRole.SYSTEM, content = systemParts.joinToString("\n\n")))
    }

    val history = trimToBudget(transcript)
    for (message in history) {
      when (message.role) {
        ChatRole.USER ->
          if (message.text.isNotBlank() || message.images.isNotEmpty()) {
            out.add(
              CloudChatMessage(
                role = ChatRole.USER,
                content = message.text,
                images = message.images,
              )
            )
          }
        ChatRole.ASSISTANT ->
          if (message.text.isNotBlank() || message.thinking.isNotBlank()) {
            out.add(
              CloudChatMessage(
                role = ChatRole.ASSISTANT,
                content = message.text,
                thinking = message.thinking,
                // Required by Anthropic to replay a thinking block on the next turn.
                thinkingSignature = message.thinkingSignature,
              )
            )
          }
        ChatRole.SYSTEM -> Unit
      }
    }
    return out
  }

  /** Keeps the newest [MAX_HISTORY_MESSAGES] entries. */
  private fun trimToBudget(transcript: List<CloudChatUiMessage>): List<CloudChatUiMessage> {
    if (transcript.size <= MAX_HISTORY_MESSAGES) return transcript
    val dropped = transcript.size - MAX_HISTORY_MESSAGES
    val tail = transcript.drop(dropped)
    // Never start on an assistant turn: providers reject a leading assistant message.
    return if (tail.first().role == ChatRole.ASSISTANT) tail.drop(1) else tail
  }

  // --------------------------------------------------------------- helpers

  private fun appendTo(id: Long, transform: (CloudChatUiMessage) -> CloudChatUiMessage) {
    local.update { state ->
      state.copy(
        messages =
          state.messages.map { message ->
            if (message.id == id) transform(message) else message
          }
      )
    }
  }

  private fun failMessage(id: Long, message: String) {
    appendTo(id) { it.copy(streaming = false, error = message) }
    local.update { it.copy(isGenerating = false) }
  }

  private fun markCancelled(id: Long) {
    appendTo(id) { it.copy(streaming = false) }
    local.update { it.copy(isGenerating = false) }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
    const val MAX_HISTORY_MESSAGES = 40

    /** Used when a turn is sent with only an image attached. */
    const val DEFAULT_SEARCH_QUERY = "latest information"
  }
}
