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
    ADVANCED_BLUR("高级渐变模糊", "上下留白延展，主体保留")
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

        drawAdvancedBlur(canvas, source, width, height, blurConfig)
        
        return result
    }

    private fun drawAdvancedBlur(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        height: Int,
        config: BlurMaskConfig
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        
        // --- 1. 绘制底层模糊背景 (Full Cover) ---
        // 先把原图按铺满屏幕的比例缩放并居中，作为背景
        val bgFactor = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val bgWidth = source.width * bgFactor
        val bgHeight = source.height * bgFactor
        val bgLeft = (width - bgWidth) / 2f
        val bgTop = (height - bgHeight) / 2f
        val bgRect = RectF(bgLeft, bgTop, bgLeft + bgWidth, bgTop + bgHeight)

        // 创建一个临时画布画背景
        val bgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bgCanvas = Canvas(bgBitmap)
        bgCanvas.drawBitmap(source, null, bgRect, paint)

        // 对背景进行重度模糊
        val blurredBg = gaussianBlur(bgBitmap, config.edgeBlurRadius)
        canvas.drawBitmap(blurredBg, 0f, 0f, paint)
        
        if (blurredBg !== bgBitmap) blurredBg.recycle()
        bgBitmap.recycle()

        // --- 2. 计算前景原图的位置 ---
        // 按照原图的宽度铺满屏幕（结合 scale 缩放）
        val factor = width.toFloat() / source.width * config.scale
        val drawWidth = source.width * factor
        val drawHeight = source.height * factor
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) * config.verticalPosition
        val drawRect = RectF(left, top, left + drawWidth, top + drawHeight)

        // --- 3. 绘制前景原图并添加渐变融合 ---
        val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgBitmap)
        
        // 绘制清晰的原始图片
        fgCanvas.drawBitmap(source, null, drawRect, paint)
        
        // 计算渐变遮罩（让边缘融化进背景）
        // clearAreaRatio 越大，原图清晰的部分越多，边缘渐隐部分越短
        val fadeHeight = (drawHeight * (1f - config.clearAreaRatio)) / 2f
        
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.shader = LinearGradient(
            0f, top, 0f, top + drawHeight,
            intArrayOf(
                Color.TRANSPARENT, // 图片顶部：完全透明（露出背景模糊）
                Color.BLACK,       // 渐变结束：完全不透明（显示清晰原图）
                Color.BLACK,       // 渐变开始：完全不透明
                Color.TRANSPARENT  // 图片底部：完全透明（露出背景模糊）
            ),
            floatArrayOf(
                0f, 
                fadeHeight / drawHeight, 
                (drawHeight - fadeHeight) / drawHeight, 
                1f
            ),
            Shader.TileMode.CLAMP
        )
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        fgCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        
        // 盖到背景上
        canvas.drawBitmap(fgBitmap, 0f, 0f, paint)
        fgBitmap.recycle()
    }

    /**
     * 真正的高斯模糊（3次 BoxBlur 叠加逼近高斯）
     */
    private fun gaussianBlur(source: Bitmap, radius: Int): Bitmap {
        val scale = 0.2f // 降低分辨率大幅提升性能，并让模糊更丝滑
        val smallWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val smallHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)

        // 3次 BoxBlur 叠加消除鬼影
        var blurred = boxBlur(scaled, radius / 2)
        blurred = boxBlur(blurred, radius / 2)
        blurred = boxBlur(blurred, radius / 2)

        // 放大回原尺寸
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