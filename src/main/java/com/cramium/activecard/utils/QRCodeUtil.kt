package com.cramium.activecard.utils

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.DecodeHintType
import com.google.zxing.Result

object QRCodeUtil {
    /**
     * Generates a QR code bitmap from the given text.
     *
     * @param text the content to encode in the QR code
     * @param width desired image width in pixels
     * @param height desired image height in pixels
     * @return a [Bitmap] containing the QR code
     * @throws com.google.zxing.WriterException if encoding fails
     */
    @Throws(com.google.zxing.WriterException::class)
    fun generateQRCode(text: String, width: Int, height: Int): Bitmap {
        val writer = MultiFormatWriter()
        val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bmp
    }

    /**
     * Decodes the first QR code found in the given bitmap.
     *
     * @param bitmap the image containing a QR code
     * @return the decoded text, or null if decoding fails
     */
    fun decodeQRCode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        return try {
            val hints = mapOf(DecodeHintType.PURE_BARCODE to true)
            val result: Result = MultiFormatReader().apply { setHints(hints) }.decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            null
        }
    }
}