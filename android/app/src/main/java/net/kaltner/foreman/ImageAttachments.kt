package net.kaltner.foreman

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MAX_IMAGES_PER_MESSAGE = 4
const val MAX_IMAGE_EDGE = 2048
const val MAX_ENCODED_IMAGE_BYTES = 8 * 1024 * 1024

internal fun scaledImageSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Image dimensions are invalid" }
    val longest = maxOf(width, height)
    if (longest <= MAX_IMAGE_EDGE) return width to height
    val scale = MAX_IMAGE_EDGE.toDouble() / longest
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

internal fun imageSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (maxOf(width / sample, height / sample) > MAX_IMAGE_EDGE * 2) sample *= 2
    return sample
}

internal fun encodedImageBytes(images: List<ImagePayload>): Int =
    images.sumOf { it.data.toByteArray(Charsets.US_ASCII).size }

internal class ImageBudgetExceeded : RuntimeException()

internal class BoundedImageOutputStream(
    private val maximum: Int,
) : ByteArrayOutputStream(minOf(maximum, 8192)) {
    override fun write(value: Int) {
        ensureCapacityFor(1)
        super.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacityFor(length)
        super.write(buffer, offset, length)
    }

    private fun ensureCapacityFor(additional: Int) {
        if (additional > maximum - size()) throw ImageBudgetExceeded()
    }
}

internal fun maximumDecodedImageBytes(encodedBudget: Int): Int =
    (encodedBudget / 4) * 3

suspend fun preparePickedImages(
    context: Context,
    uris: List<Uri>,
): List<ImagePayload> = withContext(Dispatchers.IO) {
    require(uris.size <= MAX_IMAGES_PER_MESSAGE) {
        "Choose at most $MAX_IMAGES_PER_MESSAGE images"
    }
    val prepared = mutableListOf<ImagePayload>()
    var remaining = MAX_ENCODED_IMAGE_BYTES
    uris.forEach { uri ->
        val image = prepareImage(context, uri, remaining)
        remaining -= image.data.length
        prepared += image
    }
    prepared
}

private fun prepareImage(
    context: Context,
    uri: Uri,
    encodedBudget: Int,
): ImagePayload {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { input ->
        require(input != null) { "The selected image could not be opened" }
        BitmapFactory.decodeStream(input, null, bounds)
    }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
        "The selected file is not a supported image"
    }
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight)
        }
    val decoded =
        resolver.openInputStream(uri).use { input ->
            require(input != null) { "The selected image could not be opened" }
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("The selected file is not a supported image")
    val (targetWidth, targetHeight) = scaledImageSize(decoded.width, decoded.height)
    val bitmap =
        if (decoded.width == targetWidth && decoded.height == targetHeight) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                .also { decoded.recycle() }
        }
    return try {
        val png = bitmap.hasAlpha()
        val maximumBytes = maximumDecodedImageBytes(encodedBudget)
        val (mimeType, bytes) =
            try {
                if (!png) throw ImageBudgetExceeded()
                "image/png" to compress(bitmap, Bitmap.CompressFormat.PNG, 100, maximumBytes)
            } catch (_: ImageBudgetExceeded) {
                "image/jpeg" to
                    compress(bitmap, Bitmap.CompressFormat.JPEG, 85, maximumBytes)
            }
        ImagePayload(
            mimeType = mimeType,
            data = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    } catch (_: ImageBudgetExceeded) {
        throw IllegalArgumentException("Combined images must be at most 8 MiB")
    } finally {
        bitmap.recycle()
    }
}

private fun compress(
    bitmap: Bitmap,
    format: Bitmap.CompressFormat,
    quality: Int,
    maximumBytes: Int,
): ByteArray {
    val output = BoundedImageOutputStream(maximumBytes)
    val compressed = bitmap.compress(format, quality, output)
    require(compressed) { "The selected image could not be compressed" }
    return output.toByteArray()
}
