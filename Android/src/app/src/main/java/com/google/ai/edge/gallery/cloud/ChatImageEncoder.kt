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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Raised when an attachment cannot be turned into a [ChatImage]. */
class ImageEncodingException(message: String) : Exception(message)

/**
 * Turns a picked image into the base64 payload every provider expects.
 *
 * Images are downscaled and re-encoded as JPEG first. The nine backends reject large bodies
 * (OpenAI caps the whole request at 20 MB, Anthropic at 5 MB per image), and a modern phone camera
 * image is routinely 4 to 8 MB, so sending the original bytes fails on large photos.
 */
object ChatImageEncoder {

  private const val TAG = "AGImageEncoder"
  private const val MAX_EDGE_PX = 1568
  private const val JPEG_QUALITY = 85

  /**
   * Reads [uri] and returns a [ChatImage].
   *
   * @throws ImageEncodingException when the URI cannot be read or is not an image.
   */
  suspend fun encode(context: Context, uri: Uri): ChatImage =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver

      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw ImageEncodingException("That file could not be read as an image.")
      }

      val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds) }
      val decoded =
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
          ?: throw ImageEncodingException("That image could not be decoded.")

      val scaled = decoded.scaleToFit(MAX_EDGE_PX)
      val rotated = scaled.applyExifRotation(context, uri)

      val bytes =
        ByteArrayOutputStream().use { out ->
          rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
          out.toByteArray()
        }
      // Each step returns the receiver untouched when it is a no-op, so the same bitmap can occupy
      // several of these variables. Recycling by identity, rather than by name, is what keeps the
      // "no resize and no rotation" case from calling recycle twice on one bitmap.
      setOf(decoded, scaled, rotated).forEach { it.recycle() }

      // Pickers frequently report no type, and some report HEIC which no provider accepts, so the
      // re-encoded payload is always plain JPEG.
      ChatImage(mimeType = "image/jpeg", base64Data = Base64.encodeToString(bytes))
    }.also { Log.d(TAG, "Encoded attachment as ${it.base64Data.length} base64 chars") }

  /** Largest power of two that keeps both edges at or above [MAX_EDGE_PX]. */
  private fun sampleSize(bounds: BitmapFactory.Options): Int {
    var sample = 1
    var width = bounds.outWidth
    var height = bounds.outHeight
    while (width / 2 >= MAX_EDGE_PX && height / 2 >= MAX_EDGE_PX) {
      width /= 2
      height /= 2
      sample *= 2
    }
    return sample
  }

  private fun Bitmap.scaleToFit(maxEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
  }

  /** Honours the EXIF orientation tag, which is what makes a portrait photo arrive sideways. */
  private fun Bitmap.applyExifRotation(context: Context, uri: Uri): Bitmap {
    val orientation =
      try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
          ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "Could not read EXIF orientation", e)
        null
      } ?: ExifInterface.ORIENTATION_NORMAL

    val degrees =
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return this
      }

    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
  }
}
