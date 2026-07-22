package com.sluggyard.tv.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sluggyard.tv.R
import java.io.ByteArrayOutputStream

/**
 * Provides the packaged wordmark to the local configuration web servers.
 *
 * The wordmark is a drawable PNG rather than a raw resource, so decoding and
 * re-encoding it is the supported Resources API path.  Calling
 * [android.content.res.Resources.openRawResource] with a drawable silently
 * failed on some devices, leaving those pages without branding.
 */
fun Context.loadAppLogoPngBytes(): ByteArray? = runCatching {
    val bitmap = BitmapFactory.decodeResource(resources, R.drawable.app_logo_wordmark)
        ?: return null
    try {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}.getOrNull()
