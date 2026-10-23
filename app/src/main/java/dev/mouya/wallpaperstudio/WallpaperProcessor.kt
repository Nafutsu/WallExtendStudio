package dev.mouya.wallpaperstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Deterministic wallpaper expansion. No network or generative service is used. */
enum class FillMode(val title: String, val detail: String) {
    EDGE("上下延展", "拉伸边缘，保留主体比例"),
    MIRROR("镜像延展", "镜像边缘补足留白"),
    BLUR("柔和填充", "模糊背景，完整保留主体"),
    CROP("铺满裁切", "铺满屏幕，多余部分裁切"),
    STRETCH("整体拉伸", "将整张图拉伸到屏幕比例")
}

object WallpaperProcessor {
    fun render(
        source: Bitmap,
        width: Int,
        height: Int,
        mode: FillMode,
        scale: Float = 1f,
        verticalPosition: Float = 0.5f
    ): Bitmap {
        require(width > 0 && height > 0) { "目标尺寸无效" }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val zoom = scale.coerceIn(0.65f, 1.35f)
        val position = verticalPosition.coerceIn(0f, 1f)

        when (mode) {
            FillMode.EDGE -> drawEdgeExtended(canvas, source, width, height, zoom, position, false, paint)
            FillMode.MIRROR -> drawEdgeExtended(canvas, source, width, height, zoom, position, true, paint)
            FillMode.BLUR -> drawBlurFill(canvas, source, width, height, zoom, position, paint)
            FillMode.CROP -> drawCover(canvas, source, width, height, zoom, position, paint)
            FillMode.STRETCH -> canvas.drawBitmap(source, null, Rect(0, 0, width, height), paint)
        }
        return result
    }

    private fun drawEdgeExtended(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        height: Int,
        zoom: Float,
        position: Float,
        mirror: Boolean,
        paint: Paint
    ) {
        val factor = width.toFloat() / source.width * zoom
        val drawWidth = source.width * factor
        val drawHeight = source.height * factor
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) * position
        val right = left + drawWidth
        val bottom = top + drawHeight

        // Fill empty areas first, then draw the original photo over the seams.
        if (top > 0f) drawVerticalBand(canvas, source, width, top, true, mirror, paint)
        if (bottom < height) drawVerticalBand(canvas, source, width, height - bottom, false, mirror, paint, topOffset = bottom)
        if (left > 0f) drawHorizontalBand(canvas, source, height, left, true, mirror, paint)
        if (right < width) drawHorizontalBand(canvas, source, height, width - right, false, mirror, paint, leftOffset = right)

        canvas.drawBitmap(source, null, RectF(left, top, right, bottom), paint)
    }

    private fun drawVerticalBand(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        gapHeight: Float,
        topBand: Boolean,
        mirror: Boolean,
        paint: Paint,
        topOffset: Float = 0f
    ) {
        if (gapHeight <= 0f) return
        val sliceHeight = max(1, (source.height * 0.025f).roundToInt()).coerceAtMost(source.height)
        val sourceY = if (topBand) 0 else source.height - sliceHeight
        var strip = Bitmap.createBitmap(source, 0, sourceY, source.width, sliceHeight)
        if (mirror) {
            val matrix = Matrix().apply { setScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(strip, 0, 0, strip.width, strip.height, matrix, true)
            if (flipped !== strip) strip.recycle()
            strip = flipped
        }
        val destination = if (topBand) {
            RectF(0f, 0f, width.toFloat(), gapHeight)
        } else {
            RectF(0f, topOffset, width.toFloat(), topOffset + gapHeight)
        }
        canvas.drawBitmap(strip, null, destination, paint)
        strip.recycle()
    }

    private fun drawHorizontalBand(
        canvas: Canvas,
        source: Bitmap,
        targetHeight: Int,
        gapWidth: Float,
        leftBand: Boolean,
        mirror: Boolean,
        paint: Paint,
        leftOffset: Float = 0f
    ) {
        if (gapWidth <= 0f) return
        val sliceWidth = max(1, (source.width * 0.025f).roundToInt()).coerceAtMost(source.width)
        val sourceX = if (leftBand) 0 else source.width - sliceWidth
        var strip = Bitmap.createBitmap(source, sourceX, 0, sliceWidth, source.height)
        if (mirror) {
            val flipped = Bitmap.createBitmap(
                strip, 0, 0, strip.width, strip.height,
                Matrix().apply { setScale(-1f, 1f) }, true
            )
            if (flipped !== strip) strip.recycle()
            strip = flipped
        }
        val destination = if (leftBand) {
            RectF(0f, 0f, gapWidth, targetHeight.toFloat())
        } else {
            RectF(leftOffset, 0f, leftOffset + gapWidth, targetHeight.toFloat())
        }
        canvas.drawBitmap(strip, null, destination, paint)
        strip.recycle()
    }

    private fun drawCover(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        height: Int,
        zoom: Float,
        position: Float,
        paint: Paint
    ) {
        val factor = max(width.toFloat() / source.width, height.toFloat() / source.height) * zoom
        val drawWidth = source.width * factor
        val drawHeight = source.height * factor
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) * position
        canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }

    private fun drawBlurFill(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        height: Int,
        zoom: Float,
        position: Float,
        paint: Paint
    ) {
        val smallWidth = min(240, max(64, width / 6))
        val smallHeight = max(64, (smallWidth.toFloat() * height / width).roundToInt())
        val lowRes = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
        val lowCanvas = Canvas(lowRes)
        val bgScale = max(smallWidth.toFloat() / source.width, smallHeight.toFloat() / source.height)
        val bgWidth = source.width * bgScale
        val bgHeight = source.height * bgScale
        lowCanvas.drawBitmap(
            source,
            null,
            RectF(
                (smallWidth - bgWidth) / 2f,
                (smallHeight - bgHeight) / 2f,
                (smallWidth + bgWidth) / 2f,
                (smallHeight + bgHeight) / 2f
            ),
            paint
        )
        val blurred = boxBlur(lowRes, 5)
        canvas.drawBitmap(blurred, null, Rect(0, 0, width, height), paint)
        canvas.drawColor(Color.argb(44, 12, 16, 26))

        val foregroundScale = min(width.toFloat() / source.width, height.toFloat() / source.height) * zoom
        val foregroundWidth = source.width * foregroundScale
        val foregroundHeight = source.height * foregroundScale
        val left = (width - foregroundWidth) / 2f
        val top = (height - foregroundHeight) * position
        canvas.drawBitmap(source, null, RectF(left, top, left + foregroundWidth, top + foregroundHeight), paint)

        if (blurred !== lowRes) blurred.recycle()
        lowRes.recycle()
    }

    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val horizontal = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val r = radius.coerceAtLeast(1)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var a = 0; var red = 0; var green = 0; var blue = 0; var count = 0
                val start = max(0, x - r)
                val end = min(width - 1, x + r)
                for (sampleX in start..end) {
                    val color = input[row + sampleX]
                    a += Color.alpha(color); red += Color.red(color)
                    green += Color.green(color); blue += Color.blue(color); count++
                }
                horizontal[row + x] = Color.argb(a / count, red / count, green / count, blue / count)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var a = 0; var red = 0; var green = 0; var blue = 0; var count = 0
                for (sampleY in max(0, y - r)..min(height - 1, y + r)) {
                    val color = horizontal[sampleY * width + x]
                    a += Color.alpha(color); red += Color.red(color)
                    green += Color.green(color); blue += Color.blue(color); count++
                }
                output[y * width + x] = Color.argb(a / count, red / count, green / count, blue / count)
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(output, 0, width, 0, 0, width, height)
        }
    }
}
