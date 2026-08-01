package com.sluggyard.tv.ui.app.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a scannable QR code bitmap. Used for the Trakt device-auth
 * login flow so the user can scan with a phone instead of typing a code by hand.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 220,
) {
    val bitmap = remember(content, sizeDp) { generateQrBitmap(content, sizeDp) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code to authorize this device",
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp),
        )
    }
}

private fun generateQrBitmap(content: String, sizeDp: Int): Bitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        )
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizeDp, sizeDp, hints)
        val width = matrix.width
        val height = matrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}
