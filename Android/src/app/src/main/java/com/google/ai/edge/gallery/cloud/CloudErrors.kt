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

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turns whatever an HTTP failure body looks like into one sentence a person can act on.
 *
 * The nine backends disagree about error envelopes, so all observed shapes are handled:
 *  - `{"error":{"message":"…"}}` — OpenAI, xAI, Groq, Qwen, Ollama (OpenAI-compat), OpenRouter
 *  - `{"type":"error","error":{"type":"…","message":"…"}}` — Anthropic
 *  - `{"error":{"code":400,"message":"…","status":"…"}}` — Gemini (`google.rpc.Status`)
 *  - `{"error":"…"}` — Ollama native
 *  - `{"detail":"…"}` — some gateways
 *  - plain text — NVIDIA returns a bare sentence for a missing auth header
 */
fun humanReadableError(status: Int, body: String): String {
  val trimmed = body.trim()
  val fromBody = if (trimmed.isEmpty()) null else extractMessage(trimmed)
  if (fromBody != null && fromBody.isNotBlank()) return fromBody

  return when (status) {
    400 -> "The provider rejected the request. Check the model id and the base url."
    401 -> "Invalid or missing API key. Open Settings > Cloud Providers and re-enter it."
    402 -> "The account is out of credit. Top up and try again."
    403 -> "Access denied. The key is not allowed to use this model or region."
    404 ->
      "Endpoint or model not found. The base url may be wrong, or the model was retired. " +
        "Try Fetch models again."
    413 -> "The request was too large. Shorten the conversation or attach a smaller image."
    422 -> "The provider could not process the request body."
    429 -> "Rate limited or out of quota. Wait a moment and try again."
    498 -> "The Groq Flex tier is at capacity right now."
    499 -> "The request was cancelled."
    500 -> "The provider had an internal error. Try again."
    502 -> "The upstream model is unavailable right now."
    503 -> "The provider is overloaded. Try again in a moment."
    504 -> "The provider timed out. Try a smaller model or a shorter prompt."
    529 -> "Anthropic is overloaded. Try again in a moment."
    else -> "Request failed with HTTP $status."
  }
}

private fun extractMessage(body: String): String? {
  val root =
    try {
      JsonParser.parseString(body)
    } catch (e: Exception) {
      return body.take(300)
    }

  if (root.isJsonPrimitive) {
    return root.asString
  }
  if (!root.isJsonObject) return null

  val obj = root.asJsonObject
  val error = obj.get("error")

  // Anthropic: {"type":"error","error":{"type":"…","message":"…"}}
  if (error != null && error.isJsonObject) {
    val errorObj = error.asJsonObject
    // Gemini: {"error":{"code":400,"message":"…","status":"…"}}
    errorObj.stringOrNull("message")?.let { message ->
      val status = errorObj.stringOrNull("status")
      return if (status.isNullOrBlank()) message else "$status: $message"
    }
    // OpenAI family: {"error":{"message":"…","code":"…","type":"…"}}
    errorObj.stringOrNull("message")?.let { message ->
      val qualifier = listOfNotNull(errorObj.stringOrNull("type"), errorObj.stringOrNull("code"))
        .firstOrNull { it.isNotBlank() }
      return if (qualifier == null) message else "$message ($qualifier)"
    }
  }

  // Ollama native / some gateways: {"error":"…"}
  error?.takeIf { it.isJsonPrimitive }?.let { return it.asString }

  // {"detail":"…"} or {"message":"…"}
  obj.stringOrNull("detail")?.let { return it }
  obj.stringOrNull("message")?.let { return it }
  return null
}

/** Reads [name] as a string, tolerating numbers and booleans. */
internal fun JsonObject.stringOrNull(name: String): String? {
  val element = get(name) ?: return null
  if (!element.isJsonPrimitive) return null
  return try {
    element.asString
  } catch (e: Exception) {
    null
  }
}

/** Reads [name] as a long, tolerating numeric strings. */
internal fun JsonObject.longOrNull(name: String): Long? {
  val raw = stringOrNull(name) ?: return null
  return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
}

/** Reads [name] as a boolean, tolerating `"true"` / `"false"` and 0 / 1. */
internal fun JsonObject.boolOrNull(name: String): Boolean? {
  val element = get(name) ?: return null
  if (!element.isJsonPrimitive) return null
  val primitive = element.asJsonPrimitive
  return when {
    primitive.isBoolean -> primitive.asBoolean
    primitive.isString -> primitive.asString.toBooleanStrictOrNull()
    primitive.isNumber -> primitive.asDouble != 0.0
    else -> null
  }
}

/** Reads [name] as an object, or `null` when absent or of another type. */
internal fun JsonObject.objectOrNull(name: String): JsonObject? {
  val element = get(name) ?: return null
  return if (element.isJsonObject) element.asJsonObject else null
}

/** Reads [name] as an array of strings, or an empty list. */
internal fun JsonObject.stringListOrNull(name: String): List<String> {
  val element = get(name) ?: return emptyList()
  if (!element.isJsonArray) return emptyList()
  return element.asJsonArray.mapNotNull { item ->
    if (item.isJsonPrimitive) item.asString else null
  }
}
