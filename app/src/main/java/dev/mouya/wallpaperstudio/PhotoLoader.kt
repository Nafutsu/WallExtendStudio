package dev.mouya.wallpaperstudio

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes user-selected content URIs without assuming that they map to filesystem paths. */
data class LoadedPhoto(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int
)

object PhotoLoader {
    @Throws(IOException::class)
    fun load(context: Context, uri: Uri, maxDimension: Int): LoadedPhoto {
        require(maxDimension > 0) { "maxDimension must be greater than zero" }
        val resolver = context.contentResolver
        var modernDecodeError: Exception? = null

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                return decodeWithImageDecoder(resolver, uri, maxDimension)
            } catch (error: Exception) {
                modernDecodeError = error
            }
        }

        return try {
            decodeWithBitmapFactory(resolver, uri, maxDimension)
        } catch (fallbackError: Exception) {
            val failure = IOException(
                "无法打开这张图片。请重新选择，或尝试 JPEG、PNG 或 HEIF 格式。",
                modernDecodeError ?: fallbackError
            )
            if (modernDecodeError != null && fallbackError !== modernDecodeError) {
                failure.addSuppressed(fallbackError)
            }
            throw failure
        }
    }

    private fun decodeWithImageDecoder(
        resolver: ContentResolver,
        uri: Uri,
        maxDimension: Int
    ): LoadedPhoto {
        val source = ImageDecoder.createSource(resolver, uri)
        var sourceWidth = 0
        var sourceHeight = 0
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            val largestSide = max(sourceWidth, sourceHeight).coerceAtLeast(1)
            if (largestSide > maxDimension) {
                val ratio = maxDimension.toFloat() / largestSide.toFloat()
                decoder.setTargetSize(
                    (sourceWidth * ratio).roundToInt().coerceAtLeast(1),
                    (sourceHeight * ratio).roundToInt().coerceAtLeast(1)
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        val width = sourceWidth.takeIf { it > 0 } ?: bitmap.width
        val height = sourceHeight.takeIf { it > 0 } ?: bitmap.height
        val orientation = readExifOrientation(resolver, uri)
        val rotated = orientation in ROTATED_ORIENTATIONS
        return LoadedPhoto(bitmap, if (rotated) height else width, if (rotated) width else height)
    }

    private fun decodeWithBitmapFactory(
        resolver: ContentResolver,
        uri: Uri,
        maxDimension: Int
    ): LoadedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeWithAvailableSource(resolver, uri, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected file is not a supported image.")
        }

        val orientation = readExifOrientation(resolver, uri)
        val rotated = orientation in ROTATED_ORIENTATIONS
        val originalWidth = if (rotated) bounds.outHeight else bounds.outWidth
        val originalHeight = if (rotated) bounds.outWidth else bounds.outHeight
        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = decodeWithAvailableSource(resolver, uri, options)
            ?: throw IOException("The image decoder returned an empty bitmap.")

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> Unit
        }
        val oriented = if (!matrix.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }
        if (oriented !== decoded) decoded.recycle()
        return LoadedPhoto(oriented, originalWidth, originalHeight)
    }

    private fun readExifOrientation(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun decodeWithAvailableSource(
        resolver: ContentResolver,
        uri: Uri,
        options: BitmapFactory.Options
    ): Bitmap? {
        var streamError: Exception? = null
        try {
            val stream = resolver.openInputStream(uri)
            if (stream != null) {
                stream.use { return BitmapFactory.decodeStream(it, null, options) }
            }
        } catch (error: Exception) {
            streamError = error
        }

        try {
            val descriptor = resolver.openFileDescriptor(uri, "r")
            if (descriptor != null) {
                descriptor.use { return BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options) }
            }
        } catch (error: Exception) {
            if (streamError != null) error.addSuppressed(streamError)
            throw error
        }

        if (streamError != null) throw IOException("The content provider did not return a readable stream.", streamError)
        throw IOException("The content provider returned no readable file descriptor.")
    }

    private val ROTATED_ORIENTATIONS = setOf(
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE
    )
}
