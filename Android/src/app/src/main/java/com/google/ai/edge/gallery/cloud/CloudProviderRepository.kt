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

import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.proto.CloudProviderConfig
import com.google.ai.edge.gallery.proto.CloudSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistence for the multi provider cloud chat.
 *
 * Non-secret settings (base url overrides, the fetched model catalog, the selected model, sampling
 * parameters) live in a proto DataStore. API keys live in [SecureApiKeyStore] because they are
 * bearer credentials for paid accounts.
 *
 * Note on generated accessors: `providers` is a proto `map` field, so the javalite generator emits
 * `getProvidersMap()` / `putProviders(k, v)` / `removeProviders(k)` — there is no `getProvidersList`.
 * Those Java-style names are used deliberately throughout this file.
 */
@Singleton
open class CloudProviderRepository
@Inject
constructor(
  private val dataStore: DataStore<CloudSettings>,
  val keyStore: SecureApiKeyStore,
) {

  companion object {
    const val DEFAULT_TEMPERATURE = 0.7
    const val DEFAULT_MAX_OUTPUT_TOKENS = 2048
    const val DEFAULT_SEARCH_RESULT_COUNT = 5
    const val SEARCH_KEY_ID = "search_service"
  }

  /** Settings as a stream, falling back to defaults if the file cannot be read. */
  val settings: Flow<CloudSettings> = dataStore.data.catch { emit(CloudSettings.getDefaultInstance()) }

  /** A per-provider view that the UI can bind to directly. */
  val providerConfigs: Flow<Map<String, CloudProviderConfig>> =
    settings.map { current -> current.getProvidersMap() }

  suspend fun read(): CloudSettings = settings.first()

  // ---------------------------------------------------------------- providers

  /** The stored config for [type], or a fresh default one. */
  fun configOrDefault(current: CloudSettings, type: ProviderType): CloudProviderConfig =
    current.getProvidersMap()[type.descriptor.id]
      ?: CloudProviderConfig.newBuilder().setProviderId(type.descriptor.id).build()

  /** Effective base url: the user override when set, otherwise the provider default. */
  fun baseUrl(current: CloudSettings, type: ProviderType): String {
    val override = configOrDefault(current, type).baseUrlOverride.trim()
    return override.ifBlank { type.descriptor.defaultBaseUrl }
  }

  fun modelsOf(current: CloudSettings, type: ProviderType): List<CloudModelInfo> =
    configOrDefault(current, type).modelsList.map { it.toCloudModelInfo() }

  fun apiKey(type: ProviderType): String = keyStore.getApiKey(type.descriptor.id)

  fun searchApiKey(): String = keyStore.getApiKey(SEARCH_KEY_ID)

  /** True when the provider has everything it needs to serve a chat turn. */
  fun isReady(current: CloudSettings, type: ProviderType): Boolean =
    !type.descriptor.requiresApiKey || apiKey(type).isNotBlank()

  suspend fun setApiKey(type: ProviderType, value: String) {
    keyStore.setApiKey(type.descriptor.id, value.trim())
  }

  suspend fun clearApiKey(type: ProviderType) {
    keyStore.clearApiKey(type.descriptor.id)
  }

  suspend fun setSearchApiKey(value: String) {
    keyStore.setApiKey(SEARCH_KEY_ID, value.trim())
  }

  suspend fun clearSearchApiKey() {
    keyStore.clearApiKey(SEARCH_KEY_ID)
  }

  suspend fun setBaseUrlOverride(type: ProviderType, value: String) {
    updateProvider(type) { setBaseUrlOverride(value.trim()) }
  }

  suspend fun setSelectedModel(type: ProviderType, modelId: String) {
    dataStore.updateData { current ->
      val provider = configOrDefault(current, type).toBuilder().setSelectedModelId(modelId).build()
      current.toBuilder()
        .setLastProviderId(type.descriptor.id)
        .setLastModelId(modelId)
        .putProviders(type.descriptor.id, provider)
        .build()
    }
  }

  suspend fun setFavoriteProvider(type: ProviderType, favorite: Boolean) {
    updateSettings { setFavoriteProvider(favorite) }
  }

  /**
   * Replaces the cached catalog for [type].
   *
   * [favorites] carries the pinned model ids so a refetch does not lose the user's ordering, and
   * the current selection is preserved when it still exists in the new list.
   */
  suspend fun saveFetchedModels(
    type: ProviderType,
    models: List<CloudModelInfo>,
    favorites: Set<String>,
  ) {
    val merged = models.map { model -> model.copy(favorite = model.id in favorites).toProto() }
    dataStore.updateData { current ->
      val previous = configOrDefault(current, type)
      val stillThere = merged.any { it.id == previous.selectedModelId }
      val fallbackId = merged.firstOrNull { it.favorite }?.id ?: merged.firstOrNull()?.id ?: ""
      val nextSelection = if (stillThere) previous.selectedModelId else fallbackId
      val provider =
        previous.toBuilder()
          .clearModels()
          .addAllModels(merged)
          .setModelsFetchedAtMs(System.currentTimeMillis())
          .setSelectedModelId(nextSelection)
          .build()
      current.toBuilder().putProviders(type.descriptor.id, provider).build()
    }
  }

  /** Flips the pinned flag on one cached model, leaving the rest untouched. */
  suspend fun setModelFavorite(type: ProviderType, modelId: String, favorite: Boolean) {
    updateProvider(type) {
      val updated =
        modelsList.map { model ->
          if (model.id == modelId) model.toBuilder().setFavorite(favorite).build() else model
        }
      clearModels()
      addAllModels(updated)
    }
  }

  // ----------------------------------------------------------------- settings

  suspend fun setStreamingEnabled(enabled: Boolean) = updateSettings { setStreamingEnabled(enabled) }

  suspend fun setTemperature(value: Double) =
    updateSettings { setTemperature(value.coerceIn(0.0, 2.0)) }

  suspend fun setMaxOutputTokens(value: Int) =
    updateSettings { setMaxOutputTokens(value.coerceIn(1, 32_768)) }

  suspend fun setSystemPrompt(value: String) = updateSettings { setSystemPrompt(value) }

  suspend fun setShowThinking(enabled: Boolean) = updateSettings { setShowThinking(enabled) }

  suspend fun setSearchEnabled(enabled: Boolean) = updateSettings { setSearchEnabled(enabled) }

  suspend fun setSearchProvider(serviceId: String) =
    updateSettings { setSearchProviderId(serviceId) }

  suspend fun setSearchResultCount(value: Int) =
    updateSettings { setSearchResultCount(value.coerceIn(1, 20)) }

  private suspend fun updateSettings(transform: CloudSettings.Builder.() -> Unit) {
    dataStore.updateData { current ->
      val builder = current.toBuilder()
      builder.transform()
      builder.build()
    }
  }

  private suspend fun updateProvider(
    type: ProviderType,
    transform: CloudProviderConfig.Builder.() -> Unit,
  ) {
    dataStore.updateData { current ->
      val builder = configOrDefault(current, type).toBuilder()
      builder.transform()
      current.toBuilder().putProviders(type.descriptor.id, builder.build()).build()
    }
  }
}
