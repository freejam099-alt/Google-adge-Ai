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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for the API keys of the nine cloud providers and of the web search backend.
 *
 * Keys go through [EncryptedSharedPreferences] (AES-256-GCM values, Android Keystore backed) rather
 * than the proto DataStore used for ordinary settings, because they are bearer credentials for paid
 * accounts.
 *
 * If the Keystore is unavailable — which happens on some devices with a broken or locked keystore,
 * and on a few rooted/custom ROM builds — [createPreferences] silently degrades to a plain
 * `SharedPreferences` so the app stays usable instead of crashing on first read.
 */
@Singleton
class SecureApiKeyStore @Inject constructor(@ApplicationContext private val context: Context) {

  private val preferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    createPreferences()
  }

  private fun createPreferences(): SharedPreferences {
    val fileName = PREFS_FILE
    return try {
      val masterKey =
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
      EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Keystore unavailable, storing API keys unencrypted", e)
      runCatching { context.deleteSharedPreferences(fileName) }
      context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }
  }

  /** Returns the stored key for [id], or an empty string. */
  fun getApiKey(id: String): String = preferences.getString(id, "").orEmpty()

  fun setApiKey(id: String, value: String) {
    preferences.edit().putString(id, value).apply()
  }

  fun clearApiKey(id: String) {
    preferences.edit().remove(id).apply()
  }

  /** True when a non-blank key is stored. */
  fun hasApiKey(id: String): Boolean = getApiKey(id).isNotBlank()

  private companion object {
    const val TAG = "AGSecureApiKeyStore"
    const val PREFS_FILE = "cloud_api_keys"
  }
}
