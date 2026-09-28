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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.cloud.ProviderDescriptor
import com.google.ai.edge.gallery.cloud.ProviderType
import com.google.ai.edge.gallery.cloud.SearchServiceType

/**
 * Settings for the nine cloud providers, the web search backend and the chat defaults.
 *
 * The layout is one expandable card per provider so the screen stays scannable with nine entries,
 * and only the provider the user opened does any work. Nothing here needs a downloaded model, which
 * is the point: a cloud key is useful on a device with no local model installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSettingsScreen(
  navigateUp: () -> Unit,
  onOpenChat: () -> Unit,
  viewModel: CloudSettingsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val fetchStates by viewModel.fetchState.collectAsState()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.cloud_settings_title)) },
        navigationIcon = {
          IconButton(onClick = navigateUp) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
      OverviewCard(
        configured = state.configuredProviders,
        onOpenChat = onOpenChat,
      )

      SectionHeader(stringResource(R.string.cloud_settings_providers_header))
      state.providers.forEach { provider ->
        ProviderCard(
          providerState = provider,
          fetchState = fetchStates[provider.provider] ?: FetchState(),
          onSaveKey = { viewModel.setApiKey(provider.provider, it) },
          onClearKey = { viewModel.clearApiKey(provider.provider) },
          onFetch = { viewModel.fetchModels(provider.provider) },
          onDismissFetchError = { viewModel.dismissFetchError(provider.provider) },
          onBaseUrlChange = { viewModel.setBaseUrlOverride(provider.provider, it) },
          onResetBaseUrl = { viewModel.resetBaseUrl(provider.provider) },
          onSelectModel = { viewModel.setSelectedModel(provider.provider, it) },
          onToggleFavorite = { id, favorite ->
            viewModel.setModelFavorite(provider.provider, id, favorite)
          },
        )
      }

      SectionHeader(stringResource(R.string.cloud_settings_search_header))
      SearchServiceCard(state = state, viewModel = viewModel)

      SectionHeader(stringResource(R.string.cloud_settings_behaviour_header))
      BehaviourCard(state = state, viewModel = viewModel)

      SectionHeader(stringResource(R.string.cloud_settings_extensions_header))
      ExtensionsCard()

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 8.dp),
  )
}

@Composable
private fun OverviewCard(configured: Int, onOpenChat: () -> Unit) {
  Card(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Row(
      modifier = Modifier.padding(16.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.cloud_chat_title),
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text =
            if (configured == 0) {
              "No provider has an API key yet."
            } else {
              "$configured of ${ProviderType.entries.size} providers configured."
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      FilledTonalButton(onClick = onOpenChat) {
        Text(stringResource(R.string.cloud_settings_open_chat))
      }
    }
  }
}

/** One provider: key entry, optional base url override, and the fetched model picker. */
@Composable
private fun ProviderCard(
  providerState: ProviderUiState,
  fetchState: FetchState,
  onSaveKey: (String) -> Unit,
  onClearKey: () -> Unit,
  onFetch: () -> Unit,
  onDismissFetchError: () -> Unit,
  onBaseUrlChange: (String) -> Unit,
  onResetBaseUrl: () -> Unit,
  onSelectModel: (String) -> Unit,
  onToggleFavorite: (String, Boolean) -> Unit,
) {
  val provider = providerState.provider
  val descriptor = provider.descriptor
  // Starts expanded when a key is already stored, so returning to the screen shows the models.
  var expanded by remember(provider) { mutableStateOf(providerState.hasApiKey) }

  var keyDraft by remember(provider) { mutableStateOf("") }
  var keyVisible by remember(provider) { mutableStateOf(false) }
  var baseUrlDraft by remember(provider) { mutableStateOf("") }

  Card(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ProviderIcon(descriptor)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(descriptor.displayName, style = MaterialTheme.typography.titleMedium)
          Text(
            text = descriptor.tagline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = { expanded = !expanded }) {
          Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))
      ProviderStatusRow(providerState, fetchState)

      if (expanded) {
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
          value = keyDraft,
          onValueChange = { keyDraft = it },
          label = { Text(stringResource(R.string.cloud_settings_api_key_label)) },
          placeholder = { Text(descriptor.keyHint) },
          singleLine = true,
          enabled = descriptor.requiresApiKey,
          visualTransformation =
            if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions =
            KeyboardOptions(
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Done,
            ),
          trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
              Icon(
                imageVector =
                  if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = null,
              )
            }
          },
          modifier = Modifier.fillMaxWidth(),
        )

        if (!descriptor.requiresApiKey) {
          HelperText("This provider serves models without a key, so the field is disabled.")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          // Saving requires a non-blank draft on purpose: submitting an empty field must never
          // silently wipe a key the user already stored.
          FilledTonalButton(
            onClick = { onSaveKey(keyDraft) },
            enabled = descriptor.requiresApiKey && keyDraft.isNotBlank(),
          ) {
            Text(stringResource(R.string.save))
          }
          OutlinedButton(
            onClick = onFetch,
            enabled = providerState.canFetch && !fetchState.loading,
          ) {
            Text(stringResource(R.string.cloud_settings_fetch_models))
          }
          if (providerState.hasApiKey) {
            TextButton(onClick = onClearKey) { Text(stringResource(R.string.clear)) }
          }
        }

        if (fetchState.loading) {
          Spacer(modifier = Modifier.height(12.dp))
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }

        fetchState.error?.let { error ->
          Spacer(modifier = Modifier.height(12.dp))
          ErrorRow(message = error, onDismiss = onDismissFetchError)
        }

        if (descriptor.allowsBaseUrlOverride) {
          Spacer(modifier = Modifier.height(16.dp))
          OutlinedTextField(
            value = baseUrlDraft,
            onValueChange = { value ->
              baseUrlDraft = value
              onBaseUrlChange(value)
            },
            label = { Text(stringResource(R.string.cloud_settings_base_url_label)) },
            placeholder = { Text(descriptor.defaultBaseUrl) },
            supportingText = { Text(descriptor.defaultBaseUrl) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
              if (providerState.baseUrlIsOverridden || baseUrlDraft.isNotEmpty()) {
                TextButton(onClick = { baseUrlDraft = ""; onResetBaseUrl() }) {
                  Text(stringResource(R.string.cloud_settings_reset_base_url))
                }
              }
            },
            modifier = Modifier.fillMaxWidth(),
          )
        }

        Spacer(modifier = Modifier.height(16.dp))
        ModelPicker(
          providerState = providerState,
          onSelectModel = onSelectModel,
          onToggleFavorite = onToggleFavorite,
        )
      }
    }
  }
}

@Composable
private fun ProviderIcon(descriptor: ProviderDescriptor) {
  Box(
    modifier =
      Modifier.size(36.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Color(descriptor.accentArgb).copy(alpha = 0.15f)),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painterResource(descriptor.iconRes),
      contentDescription = null,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun ProviderStatusRow(providerState: ProviderUiState, fetchState: FetchState) {
  val descriptor = providerState.provider.descriptor
  val (label, tint) =
    when {
      fetchState.loading -> "Listing models…" to MaterialTheme.colorScheme.primary
      !providerState.hasApiKey && descriptor.requiresApiKey ->
        stringResource(R.string.cloud_settings_not_configured) to MaterialTheme.colorScheme.error
      providerState.models.isEmpty() ->
        "Key saved" to MaterialTheme.colorScheme.onSurfaceVariant
      else ->
        "${providerState.models.size} models" to MaterialTheme.colorScheme.onSurfaceVariant
    }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = tint,
    )
    if (providerState.modelsFetchedAtMs > 0) {
      Text(
        text = "  updated ${relativeTime(providerState.modelsFetchedAtMs)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Horizontal list of the fetched models, with the favorite ones first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
  providerState: ProviderUiState,
  onSelectModel: (String) -> Unit,
  onToggleFavorite: (String, Boolean) -> Unit,
) {
  val models = providerState.models
  var menuOpen by remember { mutableStateOf(false) }
  val selected =
    models.firstOrNull { it.id == providerState.selectedModelId }
      ?: models.firstOrNull()

  Column {
    Text(
      stringResource(R.string.cloud_settings_model_label),
      style = MaterialTheme.typography.labelLarge,
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (models.isEmpty()) {
      HelperText(stringResource(R.string.cloud_settings_no_models))
      return
    }

    Box {
      OutlinedButton(
        onClick = { menuOpen = true },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = selected?.label ?: models.first().label,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        models.forEach { model ->
          DropdownMenuItem(
            text = {
              Column {
                Text(model.label, style = MaterialTheme.typography.bodyMedium)
                if (model.contextLength > 0) {
                  Text(
                    text = model.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            },
            onClick = {
              onSelectModel(model.id)
              menuOpen = false
            },
            trailingIcon = {
              TextButton(onClick = { onToggleFavorite(model.id, !model.favorite) }) {
                Text(if (model.favorite) "★" else "☆")
              }
            },
          )
        }
      }
    }
    selected?.let { model ->
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = modelSummary(model.contextLength, model.ownedBy, model.pricingPrompt),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SearchServiceCard(state: CloudSettingsUiState, viewModel: CloudSettingsViewModel) {
  var menuOpen by remember { mutableStateOf(false) }
  var keyDraft by remember { mutableStateOf("") }
  var keyVisible by remember { mutableStateOf(false) }

  Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
          checked = state.searchEnabled,
          onCheckedChange = viewModel::setSearchEnabled,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          stringResource(R.string.cloud_settings_search_enabled),
          style = MaterialTheme.typography.bodyLarge,
        )
      }

      Spacer(modifier = Modifier.height(16.dp))
      Text(
        stringResource(R.string.cloud_settings_search_backend),
        style = MaterialTheme.typography.labelLarge,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Box {
        OutlinedButton(
          onClick = { menuOpen = true },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text =
              state.searchService?.descriptor?.displayName
                ?: stringResource(R.string.cloud_settings_search_backend),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text("None") },
            onClick = {
              viewModel.setSearchService("")
              menuOpen = false
            },
          )
          SearchServiceType.entries.forEach { service ->
            DropdownMenuItem(
              text = {
                Column {
                  Text(service.descriptor.displayName)
                  Text(
                    text = service.descriptor.tagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
              onClick = {
                viewModel.setSearchService(service.descriptor.id)
                menuOpen = false
              },
            )
          }
        }
      }

      val service = state.searchService
      if (service != null) {
        if (service.descriptor.requiresApiKey) {
          Spacer(modifier = Modifier.height(16.dp))
          OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text(stringResource(R.string.cloud_settings_search_key_label)) },
            placeholder = { Text(service.descriptor.keyHint) },
            singleLine = true,
            visualTransformation =
              if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                  imageVector =
                    if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                  contentDescription = null,
                )
              }
            },
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Non-blank only, so an empty field cannot erase a stored search key.
            FilledTonalButton(
              onClick = { viewModel.setSearchApiKey(keyDraft) },
              enabled = keyDraft.isNotBlank(),
            ) {
              Text(stringResource(R.string.save))
            }
            if (state.searchHasApiKey) {
              TextButton(onClick = viewModel::clearSearchApiKey) {
                Text(stringResource(R.string.clear))
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "${stringResource(R.string.cloud_settings_search_result_count)}: ${state.searchResultCount}",
          style = MaterialTheme.typography.labelLarge,
        )
        Slider(
          value = state.searchResultCount.toFloat(),
          onValueChange = { viewModel.setSearchResultCount(it.toInt()) },
          valueRange = 1f..20f,
          steps = 18,
        )
      }
    }
  }
}

@Composable
private fun BehaviourCard(state: CloudSettingsUiState, viewModel: CloudSettingsViewModel) {
  Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      SwitchRow(
        label = stringResource(R.string.cloud_settings_streaming),
        checked = state.streamingEnabled,
        onCheckedChange = viewModel::setStreamingEnabled,
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      SwitchRow(
        label = stringResource(R.string.cloud_settings_thinking),
        checked = state.showThinking,
        onCheckedChange = viewModel::setShowThinking,
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      Text(
        text = "${stringResource(R.string.cloud_settings_temperature)}: ${"%.2f".format(state.temperature)}",
        style = MaterialTheme.typography.labelLarge,
      )
      Slider(
        value = state.temperature.toFloat(),
        onValueChange = { viewModel.setTemperature(it.toDouble()) },
        valueRange = 0f..2f,
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      Text(
        text = "${stringResource(R.string.cloud_settings_max_tokens)}: ${state.maxOutputTokens}",
        style = MaterialTheme.typography.labelLarge,
      )
      Slider(
        value = state.maxOutputTokens.toFloat(),
        onValueChange = { viewModel.setMaxOutputTokens(it.toInt()) },
        valueRange = 256f..32_768f,
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      OutlinedTextField(
        value = state.systemPrompt,
        onValueChange = viewModel::setSystemPrompt,
        label = { Text(stringResource(R.string.cloud_settings_system_prompt)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun ExtensionsCard() {
  Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      ExtensionsRow(
        title = stringResource(R.string.cloud_settings_open_skills),
        description =
          "Agent skills run in the Agent Chat task, on whichever model is selected there. " +
            "Configure and import them from that task.",
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
      ExtensionsRow(
        title = stringResource(R.string.cloud_settings_open_mcp),
        description =
          "MCP servers are registered from the Agent Chat task as well. Cloud chat does not " +
            "load MCP tools, so an MCP-only tool is unavailable here.",
      )
    }
  }
}

@Composable
private fun ExtensionsRow(title: String, description: String) {
  Row(verticalAlignment = Alignment.Top) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
  Row(verticalAlignment = Alignment.Top) {
    Icon(
      imageVector = Icons.Rounded.WarningAmber,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.error,
      modifier = Modifier.size(18.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
  }
}

@Composable
private fun HelperText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
  )
}

/** "3 models · openai · $0.15/M" style caption, omitting whatever the provider did not report. */
private fun modelSummary(contextLength: Long, ownedBy: String, pricingPrompt: String): String =
  buildList {
      if (contextLength > 0) add("${contextLength / 1000}k context")
      if (ownedBy.isNotBlank()) add(ownedBy)
      if (pricingPrompt.isNotBlank() && pricingPrompt != "0") add("$$pricingPrompt/M in")
    }
    .joinToString("  ·  ")

/** Turns a fetch timestamp into "just now" / "5 min ago" / a date. */
private fun relativeTime(epochMs: Long): String {
  val delta = System.currentTimeMillis() - epochMs
  return when {
    delta < 60_000 -> "just now"
    delta < 3_600_000 -> "${delta / 60_000} min ago"
    delta < 86_400_000 -> "${delta / 3_600_000} h ago"
    else -> java.time.Instant.ofEpochMilli(epochMs).toString().take(10)
  }
}
