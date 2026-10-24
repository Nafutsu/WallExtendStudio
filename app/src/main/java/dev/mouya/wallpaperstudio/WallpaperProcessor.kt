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
    ADVANCED_BLUR("高级渐变模糊", "保留主体，上下边缘延展与渐变模糊")
}

data class BlurMaskConfig(
    val clearAreaRatio: Float = 0.5f, 
    val edgeBlurRadius: Int = 40,     
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
        
        // --- 1. 计算前景原图的位置（限制最大高度，强制留出上下空间） ---
        // 即使原图比屏幕长，我们也最多只让它占屏幕高度的 75% ~ 85%，这样上下才有空隙做延展
        val maxForegroundHeight = height * 0.85f
        val maxForegroundWidth = width * 0.95f
        
        var fgWidth = source.width.toFloat()
        var fgHeight = source.height.toFloat()
        
        // 按比例缩放原图，使其适应屏幕
        val scaleFactor = min(maxForegroundWidth / fgWidth, maxForegroundHeight / fgHeight) * config.scale
        fgWidth *= scaleFactor
        fgHeight *= scaleFactor
        
        val left = (width - fgWidth) / 2f
        val top = (height - fgHeight) * config.verticalPosition
        val drawRect = RectF(left, top, left + fgWidth, top + fgHeight)

        // --- 2. 构建高级背景层（边缘1像素拉伸 + 高斯模糊） ---
        // 为了模拟 iOS 的背景，我们截取原图最顶部的 1 像素，和底部的 1 像素，分别向上和向下拉伸
        val bgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bgCanvas = Canvas(bgBitmap)
        
        val topRow = Bitmap.createBitmap(source, 0, 0, source.width, 1)
        val bottomRow = Bitmap.createBitmap(source, 0, source.height - 1, source.width, 1)
        
        // 将顶部的 1 像素拉伸填满上半部分
        bgCanvas.drawBitmap(topRow, null, RectF(0f, 0f, width.toFloat(), top + 2f), paint)
        // 将底部的 1 像素拉伸填满下半部分
        bgCanvas.drawBitmap(bottomRow, null, RectF(0f, top + fgHeight - 2f, width.toFloat(), height.toFloat()), paint)
        
        topRow.recycle()
        bottomRow.recycle()

        // 对这个拉伸的背景进行真正的高斯模糊
        val blurredBg = gaussianBlur(bgBitmap, config.edgeBlurRadius)
        canvas.drawBitmap(blurredBg, 0f, 0f, paint)
        if (blurredBg !== bgBitmap) blurredBg.recycle()
        bgBitmap.recycle()

        // --- 3. 绘制前景原图，并应用一次函数渐变遮罩 ---
        val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgBitmap)
        
        // 画上清晰的主体（稍微做一点圆角处理，显得更高级，如果要直角可以把圆角去掉）
        val roundedRect = RectF(drawRect)
        val cornerRadius = width * 0.08f
        fgCanvas.drawRoundRect(roundedRect, cornerRadius, cornerRadius, paint)
        
        // 构建渐变遮罩：让原图的上下边缘逐渐融入模糊背景
        val fadeDistance = (fgHeight * (1f - config.clearAreaRatio)) / 2f
        val fadeTop = top + fadeDistance
        val fadeBottom = top + fgHeight - fadeDistance

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.shader = LinearGradient(
            0f, top, 0f, top + fgHeight,
            intArrayOf(
                Color.TRANSPARENT, // 原图最顶部：完全透明（融入背景）
                Color.BLACK,       // 过渡到主体：显示原图
                Color.BLACK,       // 主体下部：显示原图
                Color.TRANSPARENT  // 原图最底部：完全透明（融入背景）
            ),
            floatArrayOf(
                0f, 
                fadeDistance / fgHeight, 
                (fgHeight - fadeDistance) / fgHeight, 
                1f
            ),
            Shader.TileMode.CLAMP
        )
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        fgCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        
        // 将处理好的前景盖上去
        canvas.drawBitmap(fgBitmap, 0f, 0f, paint)
        fgBitmap.recycle()
    }

    /**
     * 真正的高斯模糊（3次 BoxBlur 叠加逼近高斯）
     * 相比单次 BoxBlur，这种方式边缘极其柔和，绝对不会出现鬼影和发光
     */
    private fun gaussianBlur(source: Bitmap, radius: Int): Bitmap {
        // 先缩小图片，极大提高性能并增加柔滑感
        val scale = 0.25f
        val smallWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val smallHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)

        // 3次 BoxBlur 叠加
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