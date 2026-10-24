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
    ADVANCED_BLUR("高级渐变模糊", "全屏模糊打底，边缘一次函数渐变融合")
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
        
        // --- 1. 绘制底层：全屏铺满的模糊背景 ---
        // 这样即使原图比例跟屏幕差很多，背景也永远有颜色，不会漏出难看的黑边或灰块
        val bgFactor = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val bgWidth = source.width * bgFactor
        val bgHeight = source.height * bgFactor
        val bgLeft = (width - bgWidth) / 2f
        val bgTop = (height - bgHeight) / 2f
        val bgRect = RectF(bgLeft, bgTop, bgLeft + bgWidth, bgTop + bgHeight)
        
        // 生成背景模糊图
        val blurredBg = boxBlur(source, config.edgeBlurRadius, max(width, height))
        canvas.drawBitmap(blurredBg, null, bgRect, paint)
        if (blurredBg !== source) blurredBg.recycle()

        // --- 2. 计算原图位置 ---
        val factor = width.toFloat() / source.width * zoom
        val drawWidth = source.width * factor
        val drawHeight = source.height * factor
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) * position
        val bottom = top + drawHeight
        val drawRect = RectF(left, top, left + drawWidth, top + drawHeight)

        // --- 3. 绘制前置延展边缘 (复用原代码 2.5% 拉伸) ---
        // 这一步确保如果有留白，边缘的颜色能完美衔接原图
        if (top > 0f) {
            drawEdgeBand(canvas, source, width, top, true, paint)
        }
        if (bottom < height) {
            drawEdgeBand(canvas, source, width, height - bottom, false, paint, topOffset = bottom)
        }

        // --- 4. 绘制原图主体并应用一次函数渐变融合 ---
        val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgBitmap)
        
        // 画上清晰的原图
        fgCanvas.drawBitmap(source, null, drawRect, paint)
        
        // 计算渐变深度（中间清晰的区域由 clearAreaRatio 控制）
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
        
        // 盖到屏幕上
        canvas.drawBitmap(fgBitmap, 0f, 0f, paint)
        fgBitmap.recycle()
    }

    /**
     * 提取边缘像素进行拉伸（你最初代码的完美复刻）
     */
    private fun drawEdgeBand(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        gapHeight: Float,
        topBand: Boolean,
        paint: Paint,
        topOffset: Float = 0f
    ) {
        if (gapHeight <= 0f) return
        val sliceHeight = max(1, (source.height * 0.025f).roundToInt()).coerceAtMost(source.height)
        val sourceY = if (topBand) 0 else source.height - sliceHeight
        val strip = Bitmap.createBitmap(source, 0, sourceY, source.width, sliceHeight)
        
        val destination = if (topBand) {
            RectF(0f, 0f, width.toFloat(), gapHeight + 5f) // 稍微多画一点防缝隙
        } else {
            RectF(0f, topOffset - 5f, width.toFloat(), topOffset + gapHeight)
        }
        canvas.drawBitmap(strip, null, destination, paint)
        strip.recycle()
    }

    // 参考代码原版的 BoxBlur（性能好，带降采样）
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
