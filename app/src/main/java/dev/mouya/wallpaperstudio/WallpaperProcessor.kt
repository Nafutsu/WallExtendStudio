package dev.mouya.wallpaperstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Deterministic wallpaper expansion. No network or generative service is used. */
enum class FillMode(val title: String, val detail: String) {
    EDGE("上下延展", "拉伸边缘，保留主体比例"),
    MIRROR("镜像延展", "镜像边缘补足留白"),
    BLUR("柔和填充", "模糊背景，完整保留主体"),
    CROP("铺满裁切", "铺满屏幕，多余部分裁切"),
    STRETCH("整体拉伸", "将整张图拉伸到屏幕比例"),
    ADVANCED_BLUR("高级渐变模糊", "边缘模糊，一次函数渐变过渡（图2/图3效果）")
}

/** 
 * 参数容器：对应需求“加入手动调整模糊覆盖范围”和“调整参数” 
 */
data class BlurMaskConfig(
    // 1. 清晰区域占比 (0.0 - 1.0)：屏幕中间清晰区域占整体高度的比例
    // 控制“手动调整模糊覆盖范围”
    val clearAreaRatio: Float = 0.5f,
    
    // 2. 边缘最大模糊半径：控制边缘的模糊程度
    val edgeBlurRadius: Int = 30,
    
    // 3. 额外色彩叠加：用于图3那种紫红氛围感，或图2的暗角托底
    val tintColor: Int = Color.TRANSPARENT,
    
    // 4. 全局缩放：调整主体大小
    val scale: Float = 1f,
    
    // 5. 垂直位置：调整主体在屏幕上的上下位置
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
        // 高级模式专用参数
        blurConfig: BlurMaskConfig = BlurMaskConfig()
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
            FillMode.ADVANCED_BLUR -> drawAdvancedBlur(canvas, source, width, height, blurConfig)
        }
        return result
    }

    /**
     * 核心实现：高级渐变模糊（还原图2、图3质感）
     * 使用一次函数（线性渐变）控制遮罩，实现模糊程度的递增/递减
     */
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

        // 2. 生成重度模糊的底层背景
        // 为了防止边缘漏出黑边，先稍微放大一点绘制尺寸
        val bgRect = RectF(
            left - width * 0.1f, 
            top - height * 0.1f, 
            left + drawWidth + width * 0.1f, 
            top + drawHeight + height * 0.1f
        )
        val heavyBlur = boxBlur(source, config.edgeBlurRadius, max(width, height))
        canvas.drawBitmap(heavyBlur, null, bgRect, paint)
        if (heavyBlur !== source) heavyBlur.recycle()

        // 3. 计算渐变遮罩的坐标与尺寸
        val clearTop = height * (1f - config.clearAreaRatio) / 2f
        val clearBottom = height - clearTop

        // 4. 准备前景图（原图），并应用一次函数渐变遮罩（PorterDuff）
        val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(fgBitmap)
        
        // 把清晰的原图画上去
        fgCanvas.drawBitmap(source, null, drawRect, paint)
        
        // 构建线性渐变：从透明 -> 不透明 -> 不透明 -> 透明
        // 对应群聊里说的“模糊程度递增及递减（一次函数）”
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                Color.TRANSPARENT, // 顶部边缘：完全透明（露出底部的模糊背景）
                Color.BLACK,       // 上边界：完全不透明（显示清晰原图）
                Color.BLACK,       // 下边界：完全不透明（显示清晰原图）
                Color.TRANSPARENT  // 底部边缘：完全透明（露出底部的模糊背景）
            ),
            floatArrayOf(
                0f, 
                clearTop / height, 
                clearBottom / height, 
                1f
            ),
            Shader.TileMode.CLAMP
        )
        // 使用 DST_IN 模式，用渐变遮罩裁剪出中间清晰、上下边缘渐隐的前景图
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        fgCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        
        // 将处理好边缘渐隐的原图盖到背景上
        canvas.drawBitmap(fgBitmap, 0f, 0f, paint)
        fgBitmap.recycle()

        // 5. 叠加氛围颜色（手动调整参数控制）
        if (Color.alpha(config.tintColor) > 0) {
            canvas.drawColor(config.tintColor)
        }
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

// 原版基础模糊（保留用于 BLUR 模式）
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

    /**
     * 优化后的滑动窗口高斯/均值模糊
     * 加入了 maxSize 限制，防止对大图直接进行 CPU 模糊导致 OOM 或卡顿
     */
    private fun boxBlur(source: Bitmap, radius: Int, maxSize: Int = 1080): Bitmap {
        val r = radius.coerceAtLeast(1)
        
        // 如果图片过大，先降采样，大幅提升性能
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

        // 1. 水平方向模糊 (滑动窗口优化，复杂度 O(n))
        for (y in 0 until height) {
            val row = y * width
            var a = 0; var red = 0; var green = 0; var blue = 0
            var count = 0
            
            // 初始化最左侧窗口
            for (x in 0..min(width - 1, r)) {
                val color = input[row + x]
                a += Color.alpha(color); red += Color.red(color)
                green += Color.green(color); blue += Color.blue(color); count++
            }
            
            for (x in 0 until width) {
                horizontal[row + x] = Color.argb(a / count, red / count, green / count, blue / count)
                
                // 滑动窗口：移除左边，加入右边
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

        // 2. 垂直方向模糊 (滑动窗口优化，复杂度 O(n))
        for (x in 0 until width) {
            var a = 0; var red = 0; var green = 0; var blue = 0
            var count = 0
            
            // 初始化最上方窗口
            for (y in 0..min(height - 1, r)) {
                val color = horizontal[y * width + x]
                a += Color.alpha(color); red += Color.red(color)
                green += Color.green(color); blue += Color.blue(color); count++
            }
            
            for (y in 0 until height) {
                output[y * width + x] = Color.argb(a / count, red / count, green / count, blue / count)
                
                // 滑动窗口：移除左边，加入右边
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

        // 2. 垂直方向模糊 (滑动窗口优化，复杂度 O(n))
        for (x in 0 until width) {
            var a = 0; var red = 0; var green = 0; var blue = 0
            var count = 0
            
            // 初始化最上方窗口
            for (y in 0..min(height - 1, r)) {
                val color = horizontal[y * width + x]
                a += Color.alpha(color); red += Color.red(color)
                green += Color.green(color); blue += Color.blue(color); count++
            }
            
            for (y in 0 until height) {
                output[y * width + x] = Color.argb(a / count, red / count, green / count, blue / count)
                
                // 滑动窗口：移除上边，加入下边
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
        
        // 恢复至目标尺寸
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