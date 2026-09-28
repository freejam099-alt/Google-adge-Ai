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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.cloud.CloudModelCatalogClient
import com.google.ai.edge.gallery.cloud.CloudModelInfo
import com.google.ai.edge.gallery.cloud.CloudProviderRepository
import com.google.ai.edge.gallery.cloud.CloudTransportException
import com.google.ai.edge.gallery.cloud.HttpStatusException
import com.google.ai.edge.gallery.cloud.ProviderType
import com.google.ai.edge.gallery.cloud.SearchServiceType
import com.google.ai.edge.gallery.cloud.humanReadableError
import com.google.ai.edge.gallery.proto.CloudSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-provider progress of a "Fetch models" call. */
data class FetchState(val loading: Boolean = false, val error: String? = null)

/** Everything the provider settings screen renders for one provider. */
data class ProviderUiState(
  val provider: ProviderType,
  val hasApiKey: Boolean = false,
  val baseUrl: String = provider.descriptor.defaultBaseUrl,
  val baseUrlIsOverridden: Boolean = false,
  val models: List<CloudModelInfo> = emptyList(),
  val selectedModelId: String = "",
  val modelsFetchedAtMs: Long = 0L,
) {
  val descriptor
    get() = provider.descriptor

  val isReady: Boolean
    get() = !descriptor.requiresApiKey || hasApiKey

  val canFetch: Boolean
    get() = descriptor.anonymousModelList || hasApiKey
}

/** The whole settings screen state. */
data class CloudSettingsUiState(
  val providers: List<ProviderUiState> = ProviderType.entries.map { ProviderUiState(it) },
  val streamingEnabled: Boolean = true,
  val temperature: Double = CloudProviderRepository.DEFAULT_TEMPERATURE,
  val maxOutputTokens: Int = CloudProviderRepository.DEFAULT_MAX_OUTPUT_TOKENS,
  val systemPrompt: String = "",
  val showThinking: Boolean = false,
  val searchEnabled: Boolean = false,
  val searchServiceId: String = "",
  val searchResultCount: Int = CloudProviderRepository.DEFAULT_SEARCH_RESULT_COUNT,
  val searchHasApiKey: Boolean = false,
) {
  val configuredProviders: Int
    get() = providers.count { it.hasApiKey }

  val searchService: SearchServiceType?
    get() = SearchServiceType.fromId(searchServiceId)

  /** The search backend is usable when it is enabled and has whatever it needs. */
  val isSearchReady: Boolean
    get() {
      val service = searchService ?: return false
      return !service.descriptor.requiresApiKey || searchHasApiKey
    }
}

@HiltViewModel
class CloudSettingsViewModel
@Inject
constructor(
  private val repository: CloudProviderRepository,
  private val catalogClient: CloudModelCatalogClient,
) : ViewModel() {

  /**
   * API keys live in `SharedPreferences`, which has no change stream, so a manual revision counter
   * is mixed into the state to make the screen refresh after every write.
   */
  private val keyRevision = MutableStateFlow(0)
  private val fetchStates = MutableStateFlow<Map<ProviderType, FetchState>>(emptyMap())

  val uiState: StateFlow<CloudSettingsUiState> =
    combine(repository.settings, keyRevision) { settings, _ -> buildState(settings) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CloudSettingsUiState(),
      )

  // Exposed read-only on purpose: callers see a StateFlow, so they cannot mutate the backing map.
  val fetchState: StateFlow<Map<ProviderType, FetchState>> = fetchStates

  private fun buildState(settings: CloudSettings): CloudSettingsUiState {
    val providers =
      ProviderType.entries.map { type ->
        val config = repository.configOrDefault(settings, type)
        ProviderUiState(
          provider = type,
          hasApiKey = repository.apiKey(type).isNotBlank(),
          baseUrl = repository.baseUrl(settings, type),
          baseUrlIsOverridden = config.baseUrlOverride.isNotBlank(),
          models = repository.modelsOf(settings, type),
          selectedModelId = config.selectedModelId,
          modelsFetchedAtMs = config.modelsFetchedAtMs,
        )
      }
    return CloudSettingsUiState(
      providers = providers,
      streamingEnabled = settings.streamingEnabled,
      temperature = settings.temperature,
      maxOutputTokens = settings.maxOutputTokens,
      systemPrompt = settings.systemPrompt,
      showThinking = settings.showThinking,
      searchEnabled = settings.searchEnabled,
      searchServiceId = settings.searchProviderId,
      searchResultCount =
        if (settings.searchResultCount > 0) settings.searchResultCount
        else CloudProviderRepository.DEFAULT_SEARCH_RESULT_COUNT,
      searchHasApiKey = repository.searchApiKey().isNotBlank(),
    )
  }

  // ------------------------------------------------------------- API keys

  /**
   * Stores the key and immediately lists the provider's models.
   *
   * Fetching right here is the whole point: the app never ships a hardcoded model list, so a key is
   * only useful once the user can see what it unlocked.
   */
  fun setApiKey(type: ProviderType, value: String) {
    viewModelScope.launch {
      repository.setApiKey(type, value)
      bumpKeyRevision()
      if (value.isNotBlank()) {
        fetchModels(type)
      }
    }
  }

  fun clearApiKey(type: ProviderType) {
    viewModelScope.launch {
      repository.clearApiKey(type)
      bumpKeyRevision()
    }
  }

  fun revealApiKey(type: ProviderType): String = repository.apiKey(type)

  fun setSearchApiKey(value: String) {
    viewModelScope.launch {
      repository.setSearchApiKey(value)
      bumpKeyRevision()
    }
  }

  fun clearSearchApiKey() {
    viewModelScope.launch {
      repository.clearSearchApiKey()
      bumpKeyRevision()
    }
  }

  fun revealSearchApiKey(): String = repository.searchApiKey()

  // ------------------------------------------------------------- providers

  fun setBaseUrlOverride(type: ProviderType, value: String) {
    viewModelScope.launch { repository.setBaseUrlOverride(type, value) }
  }

  fun resetBaseUrl(type: ProviderType) {
    viewModelScope.launch { repository.setBaseUrlOverride(type, "") }
  }

  fun setSelectedModel(type: ProviderType, modelId: String) {
    viewModelScope.launch { repository.setSelectedModel(type, modelId) }
  }

  fun setModelFavorite(type: ProviderType, modelId: String, favorite: Boolean) {
    viewModelScope.launch { repository.setModelFavorite(type, modelId, favorite) }
  }

  /** Re-reads the live catalog from the provider and replaces the cached one. */
  fun fetchModels(type: ProviderType) {
    if (fetchStates.value[type]?.loading == true) return
    fetchStates.update { it + (type to FetchState(loading = true)) }
    viewModelScope.launch {
      try {
        val settings = repository.read()
        val baseUrl = repository.baseUrl(settings, type)
        val models =
          catalogClient.fetchModels(
            type = type,
            baseUrl = baseUrl,
            apiKey = repository.apiKey(type),
          )
        if (models.isEmpty()) {
          fetchStates.update {
            it + (type to FetchState(error = "${type.descriptor.displayName} returned no models."))
          }
          return@launch
        }
        val favorites = repository.modelsOf(settings, type).filter { it.favorite }.map { it.id }.toSet()
        repository.saveFetchedModels(type, models, favorites)
        fetchStates.update { it + (type to FetchState()) }
      } catch (e: CancellationException) {
        fetchStates.update { it + (type to FetchState()) }
        throw e
      } catch (e: HttpStatusException) {
        fetchStates.update {
          it + (type to FetchState(error = humanReadableError(e.statusCode, e.body)))
        }
      } catch (e: CloudTransportException) {
        fetchStates.update { it + (type to FetchState(error = e.message ?: "The request failed.")) }
      } catch (e: Exception) {
        fetchStates.update {
          it + (type to FetchState(error = e.message ?: "Fetching models failed."))
        }
      }
    }
  }

  fun dismissFetchError(type: ProviderType) {
    fetchStates.update { it + (type to FetchState()) }
  }

  // ------------------------------------------------------------- settings

  fun setStreamingEnabled(enabled: Boolean) {
    viewModelScope.launch { repository.setStreamingEnabled(enabled) }
  }

  fun setTemperature(value: Double) {
    viewModelScope.launch { repository.setTemperature(value) }
  }

  fun setMaxOutputTokens(value: Int) {
    viewModelScope.launch { repository.setMaxOutputTokens(value) }
  }

  fun setSystemPrompt(value: String) {
    viewModelScope.launch { repository.setSystemPrompt(value) }
  }

  fun setShowThinking(enabled: Boolean) {
    viewModelScope.launch { repository.setShowThinking(enabled) }
  }

  fun setSearchEnabled(enabled: Boolean) {
    viewModelScope.launch { repository.setSearchEnabled(enabled) }
  }

  fun setSearchService(serviceId: String) {
    viewModelScope.launch { repository.setSearchProvider(serviceId) }
  }

  fun setSearchResultCount(value: Int) {
    viewModelScope.launch { repository.setSearchResultCount(value) }
  }

  private fun bumpKeyRevision() {
    keyRevision.update { it + 1 }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
