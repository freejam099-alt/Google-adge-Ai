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

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.ai.edge.gallery.proto.CloudSettings
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Proto DataStore serializer for the multi provider cloud chat settings.
 *
 * API keys are intentionally *not* stored here; they live in [SecureApiKeyStore].
 */
object CloudSettingsSerializer : Serializer<CloudSettings> {
  override val defaultValue: CloudSettings = CloudSettings.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): CloudSettings {
    try {
      return CloudSettings.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read cloud settings proto.", exception)
    }
  }

  override suspend fun writeTo(t: CloudSettings, output: OutputStream) = t.writeTo(output)
}
