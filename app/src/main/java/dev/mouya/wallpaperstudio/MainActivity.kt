package dev.mouya.wallpaperstudio

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val CanvasColor = Color(0xFFF5F5F7)
private val Ink = Color(0xFF1D1D1F)
private val Muted = Color(0xFF77777D)
private val Accent = Color(0xFF007AFF)
private val Line = Color(0xFFE5E5EA)
private val White = Color(0xFFFFFFFF)
private val SecondarySurface = Color(0xFFF0F0F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                val screen = rememberScreenSpec(this@MainActivity)
                WallExtendApp(screen)
            }
        }
    }
}

private data class ScreenSpec(val widthPx: Int, val heightPx: Int) {
    val aspect: Float get() = widthPx.toFloat() / heightPx.toFloat()
    val aspectLabel: String get() = "${(heightPx.toFloat() / widthPx.toFloat()).let { String.format(Locale.US, "%.2f", it) }}:1"
    val resolutionLabel: String get() = "${widthPx} × ${heightPx} px"
}

@Composable
private fun rememberScreenSpec(context: Context): ScreenSpec {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val point = remember(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        Point().also { p ->
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
        }
    }
    val w = minOf(point.x, point.y).coerceAtLeast(1)
    val h = maxOf(point.x, point.y).coerceAtLeast(1)
    return ScreenSpec(w, h)
}

@Composable
private fun WallExtendApp(screen: ScreenSpec) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var originalDimensions by remember { mutableStateOf(0 to 0) }
    var loadingPhoto by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(FillMode.ADVANCED_BLUR) }
    var scale by remember { mutableFloatStateOf(1f) }
    var verticalPosition by remember { mutableFloatStateOf(0.5f) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var clearAreaRatio by remember { mutableFloatStateOf(0.5f) }
    var edgeBlurRadius by remember { mutableFloatStateOf(40f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {}
            loadingPhoto = true
            statusMessage = ""
            scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) { PhotoLoader.load(context, uri, maxOf(1400, minOf(screen.heightPx, 2800))) }
                    val oldPhoto = photoBitmap
                    val oldPreview = previewBitmap
                    selectedUri = uri
                    photoBitmap = loaded.bitmap
                    previewBitmap = null
                    originalDimensions = loaded.originalWidth to loaded.originalHeight
                    scale = 1f
                    verticalPosition = 0.5f
                    clearAreaRatio = 0.5f
                    edgeBlurRadius = 40f
                    releaseBitmapLater(scope, oldPhoto, 250L)
                    releaseBitmapLater(scope, oldPreview, 180L)
                } catch (error: Exception) {
                    Log.e("WallExtend", "Photo decoding failed", error)
                    statusMessage = error.message ?: "无法打开这张图片。请重新选择后重试。"
                } finally {
                    loadingPhoto = false
                }
            }
        }
    }

    LaunchedEffect(photoBitmap, scale, verticalPosition, clearAreaRatio, edgeBlurRadius, screen.widthPx, screen.heightPx) {
        val source = photoBitmap ?: run {
            val oldPreview = previewBitmap
            previewBitmap = null
            releaseBitmapLater(scope, oldPreview, 180L)
            return@LaunchedEffect
        }
        delay(90)
        val previewWidth = 360
        val previewHeight = (previewWidth * screen.heightPx.toFloat() / screen.widthPx).roundToInt().coerceAtLeast(1)
        val ownerContext = currentCoroutineContext()
        val rendered = withContext(Dispatchers.Default + NonCancellable) {
            WallpaperProcessor.render(
                source, previewWidth, previewHeight, mode, scale, verticalPosition,
                blurConfig = BlurMaskConfig(
                    clearAreaRatio = clearAreaRatio,
                    edgeBlurRadius = edgeBlurRadius.toInt(),
                    scale = scale,
                    verticalPosition = verticalPosition
                )
            )
        }
        if (!ownerContext.isActive) {
            rendered.recycle()
            return@LaunchedEffect
        }
        val old = previewBitmap
        previewBitmap = rendered
        releaseBitmapLater(scope, old, 180L)
    }

    DisposableEffect(Unit) {
        onDispose {
            photoBitmap?.recycle()
            previewBitmap?.recycle()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasColor)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp)
    ) {
        if (loadingPhoto) {
            LoadingScreen()
        } else if (photoBitmap == null) {
            HomeScreen(screen = screen, statusMessage = statusMessage, onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        } else {
            EditorScreen(
                screen = screen,
                previewBitmap = previewBitmap,
                originalDimensions = originalDimensions,
                scale = scale,
                verticalPosition = verticalPosition,
                clearAreaRatio = clearAreaRatio,
                edgeBlurRadius = edgeBlurRadius,
                processing = processing,
                statusMessage = statusMessage,
                onBack = {
                    val oldPhoto = photoBitmap
                    val oldPreview = previewBitmap
                    photoBitmap = null
                    previewBitmap = null
                    selectedUri = null
                    statusMessage = ""
                    releaseBitmapLater(scope, oldPhoto, 220L)
                    releaseBitmapLater(scope, oldPreview, 180L)
                },
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onScaleChange = { scale = it },
                onPositionChange = { verticalPosition = it },
                onClearAreaChange = { clearAreaRatio = it },
                onBlurRadiusChange = { edgeBlurRadius = it },
                onSave = {
                    val uri = selectedUri
                    if (uri != null && !processing) {
                        processing = true
                        statusMessage = "正在生成高清壁纸…"
                        scope.launch {
                            var output: Bitmap? = null
                            try {
                                output = withContext(Dispatchers.IO) { 
                                    renderFullSize(context, uri, screen, scale, verticalPosition, clearAreaRatio, edgeBlurRadius) 
                                }
                                val saved = withContext(Dispatchers.IO) { saveToPictures(context, output!!) }
                                statusMessage = if (saved != null) "已保存到相册 · Pictures/WallExtend" else "保存失败，请检查存储空间后重试。"
                            } catch (error: Exception) {
                                statusMessage = error.message ?: "保存失败，请重试。"
                            } finally {
                                output?.recycle()
                                processing = false
                            }
                        }
                    }
                },
                onApply = {
                    val uri = selectedUri
                    if (uri != null && !processing) {
                        processing = true
                        statusMessage = "正在生成并应用壁纸…"
                        scope.launch {
                            var output: Bitmap? = null
                            try {
                                output = withContext(Dispatchers.IO) { 
                                    renderFullSize(context, uri, screen, scale, verticalPosition, clearAreaRatio, edgeBlurRadius) 
                                }
                                withContext(Dispatchers.IO) {
                                    WallpaperManager.getInstance(context).setBitmap(
                                        output!!,
                                        null,
                                        true,
                                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                                    )
                                }
                                statusMessage = "已应用到桌面与锁屏"
                            } catch (error: Exception) {
                                statusMessage = error.message ?: "应用失败，请稍后重试。"
                            } finally {
                                output?.recycle()
                                processing = false
                            }
                        }
                    }
                }
            )
        }
    }
}

private fun releaseBitmapLater(scope: CoroutineScope, bitmap: Bitmap?, delayMillis: Long) {
    if (bitmap == null || bitmap.isRecycled) return
    scope.launch {
        delay(delayMillis)
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private suspend fun renderFullSize(
    context: Context,
    uri: Uri,
    screen: ScreenSpec,
    scale: Float,
    verticalPosition: Float,
    clearAreaRatio: Float,
    edgeBlurRadius: Float
): Bitmap = withContext(Dispatchers.IO) {
    val loaded = PhotoLoader.load(context, uri, minOf(5200, maxOf(screen.heightPx, screen.widthPx) * 2))
    try {
        WallpaperProcessor.render(
            loaded.bitmap, screen.widthPx, screen.heightPx, FillMode.ADVANCED_BLUR, scale, verticalPosition,
            blurConfig = BlurMaskConfig(
                clearAreaRatio = clearAreaRatio,
                edgeBlurRadius = edgeBlurRadius.toInt(),
                scale = scale,
                verticalPosition = verticalPosition
            )
        )
    } finally {
        loaded.bitmap.recycle()
    }
}

private fun saveToPictures(context: Context, bitmap: Bitmap): Uri? {
    val resolver = context.contentResolver
    val name = "WallExtend_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WallExtend")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    try {
        resolver.openOutputStream(uri, "w")?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IOException("图片写入失败")
        } ?: throw IOException("无法创建图片文件")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}

@Composable
private fun HomeScreen(screen: ScreenSpec, statusMessage: String, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 10.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(White)
                    .border(1.dp, Line, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("↕", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("WallExtend", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("Wallpaper fitting", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("ON DEVICE", color = Muted, fontSize = 10.sp, letterSpacing = 0.6.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("让壁纸适配\n你的屏幕。", color = Ink, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
            Text("保留喜欢的画面，补齐屏幕留白。", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(White)
                .border(1.dp, Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Text("当前设备", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("屏幕分辨率", color = Muted, fontSize = 12.sp)
                    Text(screen.resolutionLabel, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.width(1.dp).height(34.dp).background(Line))
                Column(Modifier.weight(0.8f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("纵横比例", color = Muted, fontSize = 12.sp)
                    Text(screen.aspectLabel, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Text("生成图片将匹配该屏幕尺寸。", color = Muted, fontSize = 12.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            MiuixButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text("选择照片", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("照片只在本机处理，不会上传。", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (statusMessage.isNotBlank()) StatusLine(statusMessage)
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("正在读取照片…", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("自动校正照片方向", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EditorScreen(
    screen: ScreenSpec,
    previewBitmap: Bitmap?,
    originalDimensions: Pair<Int, Int>,
    scale: Float,
    verticalPosition: Float,
    clearAreaRatio: Float,
    edgeBlurRadius: Float,
    processing: Boolean,
    statusMessage: String,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onScaleChange: (Float) -> Unit,
    onPositionChange: (Float) -> Unit,
    onClearAreaChange: (Float) -> Unit,
    onBlurRadiusChange: (Float) -> Unit,
    onSave: () -> Unit,
    onApply: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Box(
                Modifier.clip(CircleShape).clickable(onClick = onBack).size(38.dp).background(White).border(1.dp, Line, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Light)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("编辑壁纸", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("预览将匹配当前屏幕比例", color = Muted, fontSize = 11.sp)
            }
            Box(
                Modifier.clip(CircleShape).clickable(onClick = onPick).background(White).border(1.dp, Line, CircleShape).padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text("更换", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DevicePreview(screen, previewBitmap)
            Text(
                "${screen.resolutionLabel} · 原图 ${originalDimensions.first} × ${originalDimensions.second} px",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 18.dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(White).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)
            ) {
                // 画面缩放
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("画面缩放", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("${String.format(Locale.US, "%.2f", scale)}×", color = Accent, fontSize = 13.sp)
                    }
                    MiuixSlider(
                        value = scale, onValueChange = onScaleChange, valueRange = 0.65f..1.35f, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("更多画面", color = Muted, fontSize = 11.sp)
                        Text("放大主体", color = Muted, fontSize = 11.sp)
                    }
                }
                
                // 上下位置
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("上下位置", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            when {
                                verticalPosition < 0.35f -> "偏上"
                                verticalPosition > 0.65f -> "偏下"
                                else -> "居中"
                            }, color = Accent, fontSize = 13.sp
                        )
                    }
                    MiuixSlider(value = verticalPosition, onValueChange = onPositionChange, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("靠上", color = Muted, fontSize = 11.sp)
                        Text("居中", color = Muted, fontSize = 11.sp)
                        Text("靠下", color = Muted, fontSize = 11.sp)
                    }
                }
                
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                
                // 调整模糊厚度
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("顶部模糊厚度", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("${edgeBlurRadius.toInt()}", color = Accent, fontSize = 13.sp)
                    }
                    MiuixSlider(
                        value = edgeBlurRadius, onValueChange = onBlurRadiusChange, valueRange = 10f..80f, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("薄", color = Muted, fontSize = 11.sp)
                        Text("厚", color = Muted, fontSize = 11.sp)
                    }
                }

                // 调整清晰范围（控制模糊过渡到哪里）
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模糊过渡深度", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("${(clearAreaRatio * 100).toInt()}%", color = Accent, fontSize = 13.sp)
                    }
                    MiuixSlider(
                        value = clearAreaRatio, onValueChange = onClearAreaChange, valueRange = 0.1f..0.9f, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("过渡浅", color = Muted, fontSize = 11.sp)
                        Text("过渡深", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                StatusLine(statusMessage)
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                MiuixButton(onClick = onSave, enabled = !processing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (processing) "处理中…" else "保存图片", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(White).border(1.dp, Line, RoundedCornerShape(16.dp))) {
                Box(Modifier.fillMaxWidth().height(48.dp).clickable(enabled = !processing, onClick = onApply), contentAlignment = Alignment.Center) {
                    Text(if (processing) "请稍候…" else "应用壁纸", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun DevicePreview(screen: ScreenSpec, bitmap: Bitmap?) {
    val dateLabel = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val phoneHeight = minOf(300.dp, maxHeight)
        val phoneWidth = phoneHeight * screen.aspect
        Box(
            modifier = Modifier
                .width(phoneWidth)
                .height(phoneHeight)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF161618))
                .padding(3.dp)
                .clip(RoundedCornerShape(27.dp))
                .background(Color(0xFFF2F2F7))
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = "Wallpaper preview", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFFE5E5EA)))
            }
            Column(Modifier.fillMaxSize().padding(top = 34.dp, bottom = 17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dateLabel, color = White.copy(alpha = 0.94f), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                Text("9:41", color = White, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(38.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)))
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).width(76.dp).height(3.dp).clip(CircleShape).background(White.copy(alpha = 0.85f)))
        }
    }
}

@Composable
private fun StatusLine(message: String) {
    Text(message, color = Accent, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), textAlign = TextAlign.Center)
}