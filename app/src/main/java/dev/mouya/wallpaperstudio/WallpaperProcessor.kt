package dev.mouya.wallpaperstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class FillMode(val title: String, val detail: String) {
    ADVANCED_BLUR("高级渐变模糊", "延展边缘，一次函数渐变过渡")
}

data class BlurMaskConfig(
    val clearAreaRatio: Float = 0.6f, 
    val edgeBlurRadius: Int = 60,     
    val scale: Float = 1f,
    val verticalPosition: Float = 0.5f
)

object WallpaperProcessor {
    
    fun render(
        source: Bitmap,
        width: Int,
        height: Int,
        mode: FillMode,
        scale: Float = 1f,
        verticalPosition: Float = 0.5f,
        blurConfig: BlurMaskConfig = BlurMaskConfig()
    ): Bitmap {
        require(width > 0 && height > 0) { "目标尺寸无效" }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        drawAdvancedBlur(canvas, source, width, height, scale, verticalPosition, blurConfig)
        
        return result
    }

    private fun drawAdvancedBlur(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        height: Int,
        zoom: Float,
        position: Float,
        config: BlurMaskConfig
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        
        // 1. 完全复用你原代码的宽高计算
        val factor = width.toFloat() / source.width * zoom
        val drawWidth = source.width * factor
        val drawHeight = source.height * factor
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) * position
        val bottom = top + drawHeight

        // 2. 先画上边缘延展区（带模糊与渐变）
        if (top > 0f) {
            drawBlurredBand(canvas, source, width, top, true, config.edgeBlurRadius, config.clearAreaRatio, paint)
        }
        
        // 3. 再画下边缘延展区
        if (bottom < height) {
            drawBlurredBand(canvas, source, width, height - bottom, false, config.edgeBlurRadius, config.clearAreaRatio, paint, topOffset = bottom)
        }

        // 4. 最后画上原图主体
        canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }

    /**
     * 核心改进：保留原代码的 2.5% 拉伸逻辑，但在其上叠加高斯模糊和边缘渐变遮罩
     */
    private fun drawBlurredBand(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        gapHeight: Float,
        topBand: Boolean,
        blurRadius: Int,
        clearAreaRatio: Float,
        paint: Paint,
        topOffset: Float = 0f
    ) {
        if (gapHeight <= 0f) return
        
        // 截取 2.5% 的窄条（复用原代码逻辑）
        val sliceHeight = max(1, (source.height * 0.025f).roundToInt()).coerceAtMost(source.height)
        val sourceY = if (topBand) 0 else source.height - sliceHeight
        val strip = Bitmap.createBitmap(source, 0, sourceY, source.width, sliceHeight)

        // 把这个窄条模糊掉
        val blurredStrip = gaussianBlur(strip, blurRadius)
        strip.recycle()

        // 计算绘制区域
        val destTop = if (topBand) 0f else topOffset
        val destBottom = if (topBand) gapHeight else topOffset + gapHeight
        val destRect = RectF(0f, destTop, width.toFloat(), destBottom)

        // 核心：用一次函数渐变做遮罩！
        // 让模糊条靠近屏幕边缘的地方不透明（显实），靠近原图接缝的地方变透明（融入原图）
        val maskBitmap = Bitmap.createBitmap(width, gapHeight.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        
        // 画上模糊条
        maskCanvas.drawBitmap(blurredStrip, null, RectF(0f, 0f, width.toFloat(), maskBitmap.height.toFloat()), paint)
        
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gradient = if (topBand) {
            // 顶部：从屏幕顶端（不透明）向接缝处（透明）渐变
            LinearGradient(0f, 0f, 0f, gapHeight, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        } else {
            // 底部：从接缝处（透明）向屏幕底端（不透明）渐变
            LinearGradient(0f, 0f, 0f, gapHeight, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }
        maskPaint.shader = gradient
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        
        // 用渐变把模糊条“抠”出来
        maskCanvas.drawRect(0f, 0f, width.toFloat(), maskBitmap.height.toFloat(), maskPaint)

        // 将处理好的渐变模糊条盖到屏幕上
        canvas.drawBitmap(maskBitmap, null, destRect, paint)

        if (blurredStrip !== strip) blurredStrip.recycle()
        maskBitmap.recycle()
    }

    // 高斯模糊底层实现
    private fun gaussianBlur(source: Bitmap, radius: Int): Bitmap {
        val scale = 0.2f
        val smallWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val smallHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)

        var blurred = boxBlur(scaled, radius / 2)
        blurred = boxBlur(blurred, radius / 2)
        blurred = boxBlur(blurred, radius / 2)

        val result = Bitmap.createScaledBitmap(blurred, source.width, source.height, true)
        
        if (scaled !== source) scaled.recycle()
        if (blurred !== scaled) blurred.recycle()
        
        return result
    }

    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceAtLeast(1)
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val horizontal = IntArray(width * height)
        val output = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            val row = y * width
            var a = 0; var red = 0; var green = 0; var blue = 0
            var count = 0
            for (x in 0..minOf(width - 1, r)) {
                val color = input[row + x]
                a += Color.alpha(color); red += Color.red(color)
                green += Color.green(color); blue += Color.blue(color); count++
            }
            for (x in 0 until width) {
                horizontal[row + x] = Color.argb(a / count, red / count, green / count, blue / count)
                val removeX = x - r
                if (removeX >= 0) {
                    val color = input[row + removeX]
                    a -= Color.alpha(color); red -= Color.red(color)
                    green -= Color.green(color); blue -= Color.blue(color); count--
                }
                val addX = x + r + 1
                if (addX < width) {
                    val color = input[row + addX]
                    a += Color.alpha(color); red += Color.red(color)
                    green += Color.green(color); blue += Color.blue(color); count++
                }
            }
        }

        for (x in 0 until width) {
            var a = 0; var red = 0; var green = 0; var blue = 0
            var count = 0
            for (y in 0..minOf(height - 1, r)) {
                val color = horizontal[y * width + x]
                a += Color.alpha(color); red += Color.red(color)
                green += Color.green(color); blue += Color.blue(color); count++
            }
            for (y in 0 until height) {
                output[y * width + x] = Color.argb(a / count, red / count, green / count, blue / count)
                val removeY = y - r
                if (removeY >= 0) {
                    val color = horizontal[removeY * width + x]
                    a -= Color.alpha(color); red -= Color.red(color)
                    green -= Color.green(color); blue -= Color.blue(color); count--
                }
                val addY = y + r + 1
                if (addY < height) {
                    val color = horizontal[addY * width + x]
                    a += Color.alpha(color); red += Color.red(color)
                    green += Color.green(color); blue += Color.blue(color); count++
                }
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(output, 0, width, 0, 0, width, height)
        }
    }
}
