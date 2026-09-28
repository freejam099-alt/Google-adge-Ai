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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.cloud.ChatImage
import com.google.ai.edge.gallery.cloud.ChatRole
import com.google.ai.edge.gallery.cloud.ProviderType
import com.google.ai.edge.gallery.cloud.WebSearchResult
import kotlin.io.encoding.Base64

/**
 * The chat screen for the nine cloud providers.
 *
 * Provider and model are picked in the app bar rather than behind a settings trip, because the
 * whole point of supporting several vendors is switching between them mid session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudChatScreen(
  navigateUp: () -> Unit,
  viewModel: CloudChatViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val listState = rememberLazyListState()
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) viewModel.attachImage(uri)
    }

  // Keep the newest turn in view while tokens stream in.
  val lastMessage = state.messages.lastOrNull()
  LaunchedEffect(state.messages.size, lastMessage?.text?.length, lastMessage?.thinking?.length) {
    val count = state.messages.size
    if (count > 0) {
      listState.animateScrollToItem(count - 1)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = navigateUp) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
          }
        },
        title = {
          Column {
            ProviderTitle(
              state = state,
              onSelect = viewModel::selectProvider,
              onSelectModel = viewModel::selectModel,
            )
            if (!state.providerReady) {
              Text(
                stringResource(R.string.cloud_chat_needs_key),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
        },
        actions = {
          IconButton(onClick = viewModel::toggleThinking) {
            Icon(
              imageVector = Icons.Rounded.Psychology,
              contentDescription = stringResource(R.string.cloud_chat_show_thinking),
              tint =
                if (state.showThinking) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
          }
          IconButton(onClick = viewModel::clearChat, enabled = state.hasTranscript) {
            Icon(
              Icons.Rounded.DeleteSweep,
              contentDescription = stringResource(R.string.cloud_chat_clear),
            )
          }
        },
      )
    },
    bottomBar = {
      InputBar(
        state = state,
        onInputChange = viewModel::updateInput,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onAttachImage = {
          picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
          )
        },
        onRemoveImage = viewModel::removePendingImage,
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.padding(innerPadding).fillMaxSize().imePadding()) {
      state.error?.let { message ->
        ErrorBanner(message = message, onDismiss = viewModel::dismissError)
      }
      state.imageError?.let { message ->
        ErrorBanner(message = message, onDismiss = viewModel::dismissImageError)
      }

      if (state.messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            text = stringResource(R.string.cloud_chat_empty_state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
          )
        }
        return@Column
      }

      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(state.messages, key = { it.id }) { message ->
          MessageBubble(message = message, showThinking = state.showThinking)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTitle(
  state: CloudChatUiState,
  onSelect: (ProviderType) -> Unit,
  onSelectModel: (String) -> Unit,
) {
  var providerMenu by remember { mutableStateOf(false) }
  var modelMenu by remember { mutableStateOf(false) }

  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      painter = painterResource(state.provider.descriptor.iconRes),
      contentDescription = null,
      modifier = Modifier.size(18.dp),
    )
    Spacer(modifier = Modifier.width(6.dp))
    Box {
      Text(
        text = state.provider.descriptor.displayName,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 130.dp).clickable { providerMenu = true },
      )
      DropdownMenu(
        expanded = providerMenu,
        onDismissRequest = { providerMenu = false },
      ) {
        ProviderType.entries.forEach { provider ->
          DropdownMenuItem(
            text = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  painter = painterResource(provider.descriptor.iconRes),
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(provider.descriptor.displayName)
              }
            },
            onClick = {
              onSelect(provider)
              providerMenu = false
            },
          )
        }
      }
    }

    if (state.models.isNotEmpty()) {
      Text(" / ", style = MaterialTheme.typography.titleSmall)
      Box {
        Text(
          text = state.models.firstOrNull { it.id == state.modelId }?.label ?: state.modelId,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.widthIn(max = 170.dp).clickable { modelMenu = true },
        )
        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
          state.models.forEach { model ->
            DropdownMenuItem(
              text = { Text(model.label) },
              onClick = {
                onSelectModel(model.id)
                modelMenu = false
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MessageBubble(message: CloudChatUiMessage, showThinking: Boolean) {
  val isUser = message.role == ChatRole.USER
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
  ) {
    if (message.thinking.isNotBlank()) {
      ThinkingBlock(text = message.thinking, startExpanded = showThinking)
    }

    if (message.text.isNotBlank()) {
      Surface(
        color =
          if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceVariant
          },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.widthIn(max = 320.dp),
      ) {
        Text(
          text = message.text,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(12.dp),
        )
      }
    } else if (message.streaming && message.thinking.isBlank()) {
      // Show a placeholder as soon as the turn starts so the bubble does not pop in late.
      CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }

    if (message.images.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        message.images.forEach { image ->
          AttachedImage(image = image, maxEdge = 96.dp)
        }
      }
    }

    if (message.searchResults.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      SearchCitations(results = message.searchResults)
    }

    message.error?.let { error ->
      Spacer(modifier = Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          Icons.Rounded.WarningAmber,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = error,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    message.usage?.let { usage ->
      if (usage.totalTokens > 0) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = usageLabel(usage.promptTokens, usage.completionTokens),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ThinkingBlock(text: String, startExpanded: Boolean) {
  var expanded by remember(text) { mutableStateOf(startExpanded) }
  Column(
    modifier =
      Modifier
        .widthIn(max = 320.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(10.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.clickable { expanded = !expanded },
    ) {
      Icon(
        Icons.Rounded.Psychology,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = if (expanded) "Reasoning" else "Reasoning (${text.length} chars)",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
      )
    }
    AnimatedVisibility(visible = expanded) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
  Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun SearchCitations(results: List<WebSearchResult>) {
  var expanded by remember(results) { mutableStateOf(false) }
  Column(
    modifier =
      Modifier
        .widthIn(max = 320.dp)
        .clip(RoundedCornerShape(12.dp))
        .clickable { expanded = !expanded },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Rounded.Search,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = if (expanded) "${results.size} sources" else "${results.size} sources  ·  tap",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    AnimatedVisibility(visible = expanded) {
      Column(modifier = Modifier.padding(top = 6.dp)) {
        results.forEachIndexed { index, result ->
          Text(
            text = "[${index + 1}] ${result.title}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = result.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun AttachedImage(image: ChatImage, maxEdge: Dp) {
  val bytes = remember(image.base64Data) { decodeBase64(image.base64Data) }
  val bitmap =
    remember(bytes) {
      bytes?.let {
        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
      }
    }
  if (bitmap != null) {
    Image(
      bitmap = bitmap.asImageBitmap(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier =
        Modifier.size(maxEdge).clip(RoundedCornerShape(8.dp)),
    )
  }
}

private fun decodeBase64(value: String): ByteArray? =
  try {
    Base64.decode(value)
  } catch (e: IllegalArgumentException) {
    null
  }

@Composable
private fun InputBar(
  state: CloudChatUiState,
  onInputChange: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  onAttachImage: () -> Unit,
  onRemoveImage: (Int) -> Unit,
) {
  Surface(tonalElevation = 3.dp) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      if (state.pendingImages.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          state.pendingImages.forEachIndexed { index, image ->
            Box {
              AttachedImage(image = image, maxEdge = 64.dp)
              IconButton(
                onClick = { onRemoveImage(index) },
                modifier = Modifier.size(20.dp),
              ) {
                Icon(
                  Icons.Rounded.Close,
                  contentDescription = stringResource(R.string.cloud_chat_stop),
                  modifier = Modifier.size(14.dp),
                )
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
      }

      Row(verticalAlignment = Alignment.Bottom) {
        IconButton(onClick = onAttachImage, enabled = !state.isGenerating) {
          Icon(
            Icons.Rounded.AddPhotoAlternate,
            contentDescription = stringResource(R.string.cloud_chat_attach_image),
          )
        }
        OutlinedTextField(
          value = state.inputText,
          onValueChange = onInputChange,
          placeholder = { Text(stringResource(R.string.cloud_chat_input_hint)) },
          modifier = Modifier.weight(1f),
          maxLines = 5,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (state.isGenerating) {
          FilledIconButton(onClick = onStop) {
            Icon(
              Icons.Rounded.Stop,
              contentDescription = stringResource(R.string.cloud_chat_stop),
            )
          }
        } else {
          FilledIconButton(onClick = onSend, enabled = state.canSend) {
            Icon(
              Icons.AutoMirrored.Rounded.Send,
              contentDescription = stringResource(R.string.cloud_chat_send),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.errorContainer) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
    }
  }
}

private fun usageLabel(prompt: Long, completion: Long): String =
  buildString {
    append("↑ $prompt")
    append("  ↓ $completion")
    if (completion > 0 && prompt > 0) {
      val cached = (completion * 100.0 / (prompt + completion)).toInt()
      append("  ·  $cached% output")
    }
  }
