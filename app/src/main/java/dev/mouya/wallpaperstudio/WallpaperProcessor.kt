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
import kotlin.math.roundToInt

/** 只保留高级渐变模糊 */
enum class FillMode(val title: String, val detail: String) {
    ADVANCED_BLUR("高级渐变模糊", "仅模糊顶部区域，底部保持清晰")
}

/** 参数容器 */
data class BlurMaskConfig(
    val clearAreaRatio: Float = 0.5f, // 底部清晰区域占比
    val edgeBlurRadius: Int = 40,     // 模糊厚度（半径）
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

        // 目前只有这一种模式
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
        
        // 1. 计算原图 Cover 模式的绘制区域
        val factor = max(width.toFloat() / source.width, height.toFloat() / source.height) * config.scale
        val drawWidth = source.width * factor
        val drawHeight = source.height * factor
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) * config.verticalPosition
        val drawRect = RectF(left, top, left + drawWidth, top + drawHeight)

        // 2. 绘制底层：稍微放大一点的重度模糊背景
        val bgRect = RectF(
            left - width * 0.1f, 
            top - height * 0.1f, 
            left + drawWidth + width * 0.1f, 
            top + drawHeight + height * 0.1f
        )
        // 这里的半径调大了，让顶部模糊更厚
        val heavyBlur = boxBlur(source, config.edgeBlurRadius, max(width, height))
        canvas.drawBitmap(heavyBlur, null, bgRect, paint)
        if (heavyBlur !== source) heavyBlur.recycle()

        // 3. 计算渐变遮罩坐标
        // 我们希望底部保持完全清晰，只在顶部留出模糊渐变的过渡
        val clearTop = height * (1f - config.clearAreaRatio)
        
        val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgBitmap)
        
        fgCanvas.drawBitmap(source, null, drawRect, paint)
        
        // 构建线性渐变：顶部透明（显示模糊） -> 中间不透明（显示原图） -> 底部完全不透明
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                Color.TRANSPARENT, // 顶部边缘：透明，露出下面模糊的背景
                Color.BLACK,       // 过渡结束：不透明，显示清晰原图
                Color.BLACK        // 底部：保持不透明，完全不模糊
            ),
            floatArrayOf(
                0f, 
                clearTop / height, 
                1f
            ),
            Shader.TileMode.CLAMP
        )
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        fgCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        
        // 将处理好的前景盖上去（去掉了之前的整体压暗代码）
        canvas.drawBitmap(fgBitmap, 0f, 0f, paint)
        fgBitmap.recycle()
    }

    // 保留了优化后的模糊算法
    private fun boxBlur(source: Bitmap, radius: Int, maxSize: Int = 1080): Bitmap {
        val r = radius.coerceAtLeast(1)
        
        val scale = if (max(source.width, source.height) > maxSize) {
            maxSize.toFloat() / max(source.width, source.height)
        } else 1f
        
        val workWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val workHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        
        val workBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, workWidth, workHeight, true)
        } else source

        val width = workBitmap.width
        val height = workBitmap.height
        val input = IntArray(width * height)
        val horizontal = IntArray(width * height)
        val output = IntArray(width * height)
        workBitmap.getPixels(input, 0, width, 0, 0, width, height)

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

        val blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(output, 0, width, 0, 0, width, height)
        }
        
        val result = if (scale < 1f) {
            Bitmap.createScaledBitmap(blurred, source.width, source.height, true).also {
                if (it !== blurred) blurred.recycle()
                if (workBitmap !== source) workBitmap.recycle()
            }
        } else {
            blurred
        }
        
        return result
    }
}