package dev.mouya.wallpaperstudio

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import top.yukonga.miuix.kmp.basic.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CanvasColor = Color(0xFFF5F4F1)
private val Ink = Color(0xFF1E2026)
private val Muted = Color(0xFF777B86)
private val Accent = Color(0xFF5368F8)
private val Line = Color(0xFFE7E6E2)
private val White = Color(0xFFFFFFFF)

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
                WallpaperStudioApp(screen)
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
private fun WallpaperStudioApp(screen: ScreenSpec) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var originalDimensions by remember { mutableStateOf(0 to 0) }
    var loadingPhoto by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(FillMode.EDGE) }
    var scale by remember { mutableFloatStateOf(1f) }
    var verticalPosition by remember { mutableFloatStateOf(0.5f) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
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
                    mode = FillMode.EDGE
                    scale = 1f
                    verticalPosition = 0.5f
                    releaseBitmapLater(scope, oldPhoto, 250L)
                    releaseBitmapLater(scope, oldPreview, 180L)
                } catch (error: Exception) {
                    statusMessage = error.message ?: "图片读取失败，请换一张试试。"
                } finally {
                    loadingPhoto = false
                }
            }
        }
    }

    LaunchedEffect(photoBitmap, mode, scale, verticalPosition, screen.widthPx, screen.heightPx) {
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
            WallpaperProcessor.render(source, previewWidth, previewHeight, mode, scale, verticalPosition)
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
            HomeScreen(screen = screen, statusMessage = statusMessage, onPick = { picker.launch("image/*") })
        } else {
            EditorScreen(
                screen = screen,
                previewBitmap = previewBitmap,
                originalDimensions = originalDimensions,
                mode = mode,
                scale = scale,
                verticalPosition = verticalPosition,
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
                onPick = { picker.launch("image/*") },
                onModeChange = { mode = it },
                onScaleChange = { scale = it },
                onPositionChange = { verticalPosition = it },
                onSave = {
                    val uri = selectedUri
                    if (uri != null && !processing) {
                        processing = true
                        statusMessage = "正在生成高清壁纸…"
                        scope.launch {
                            var output: Bitmap? = null
                            try {
                                output = withContext(Dispatchers.IO) { renderFullSize(context, uri, screen, mode, scale, verticalPosition) }
                                val saved = withContext(Dispatchers.IO) { saveToPictures(context, output!!) }
                                statusMessage = if (saved != null) "已保存到相册 · Pictures/Wallpaper Studio" else "保存失败，请检查存储空间后重试。"
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
                                output = withContext(Dispatchers.IO) { renderFullSize(context, uri, screen, mode, scale, verticalPosition) }
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
    mode: FillMode,
    scale: Float,
    verticalPosition: Float
): Bitmap = withContext(Dispatchers.IO) {
    val loaded = PhotoLoader.load(context, uri, minOf(5200, maxOf(screen.heightPx, screen.widthPx) * 2))
    try {
        WallpaperProcessor.render(loaded.bitmap, screen.widthPx, screen.heightPx, mode, scale, verticalPosition)
    } finally {
        loaded.bitmap.recycle()
    }
}

private fun saveToPictures(context: Context, bitmap: Bitmap): Uri? {
    val resolver = context.contentResolver
    val name = "WallpaperStudio_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Wallpaper Studio")
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Text("W", color = White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Column {
                Text("WALLPAPER STUDIO", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Miuix Edition", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).background(Color(0xFFE8E9FF)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text("本机处理", color = Color(0xFF4355D6), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("让壁纸，刚好适配。", color = Ink, fontSize = 31.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            Text("选一张喜欢的图片。剩余的屏幕空间，交给壁纸工坊。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        }

        HeroCard(screen)

        MiuixCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            insideMargin = PaddingValues(0.dp)
        ) {
            Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("按当前屏幕生成", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("自动读取设备分辨率与比例", color = Muted, fontSize = 12.sp)
                    }
                    Text("↗", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoBlock("目标尺寸", screen.resolutionLabel, Modifier.weight(1f))
                    InfoBlock("屏幕比例", screen.aspectLabel, Modifier.weight(1f))
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("一张图，几种适配方式", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureTile("01", "上下延展", "拉伸边缘补齐", Modifier.weight(1f))
                FeatureTile("02", "镜像延展", "反射边缘纹理", Modifier.weight(1f))
                FeatureTile("03", "柔和填充", "模糊背景补位", Modifier.weight(1f))
            }
        }

        MiuixButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("从相册选择图片", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("图片只在本机处理，不会上传。", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (statusMessage.isNotBlank()) StatusLine(statusMessage)
    }
}

@Composable
private fun HeroCard(screen: ScreenSpec) {
    Box(
        modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF202643), Color(0xFF4B5DE1), Color(0xFFB0B9FF))))
    ) {
        Box(Modifier.size(185.dp).align(Alignment.CenterEnd).padding(end = 10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
        Box(Modifier.size(132.dp).align(Alignment.CenterEnd).padding(end = 38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WALLPAPER FIT", color = Color(0xFFDDE1FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("尽量保留喜欢的画面，\n把边缘自然补齐。", color = White, fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
            Text("为 ${screen.widthPx} × ${screen.heightPx} px 设备定制", color = Color.White.copy(alpha = 0.82f), fontSize = 11.sp)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 15.dp).width(38.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.75f)))
    }
}

@Composable
private fun InfoBlock(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Muted, fontSize = 11.sp)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeatureTile(number: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(17.dp)).background(Color(0xFFECEBF0)).padding(horizontal = 10.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(number, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(subtitle, color = Muted, fontSize = 10.sp, maxLines = 2, lineHeight = 14.sp)
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Text("W", color = White, fontSize = 23.sp, fontWeight = FontWeight.Black)
            }
            Text("正在读取图片…", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("会自动校正照片方向", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EditorScreen(
    screen: ScreenSpec,
    previewBitmap: Bitmap?,
    originalDimensions: Pair<Int, Int>,
    mode: FillMode,
    scale: Float,
    verticalPosition: Float,
    processing: Boolean,
    statusMessage: String,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onModeChange: (FillMode) -> Unit,
    onScaleChange: (Float) -> Unit,
    onPositionChange: (Float) -> Unit,
    onSave: () -> Unit,
    onApply: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 6.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Box(Modifier.clip(RoundedCornerShape(13.dp)).clickable(onClick = onBack).background(Color(0xFFE9E8E4)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text("‹ 返回", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("编辑壁纸", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("预览与你的屏幕同比例", color = Muted, fontSize = 11.sp)
            }
            Box(Modifier.clip(RoundedCornerShape(13.dp)).clickable(onClick = onPick).background(White).border(1.dp, Line, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text("换一张", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            DevicePreview(screen, previewBitmap)
            Spacer(Modifier.height(9.dp))
            Text("${screen.resolutionLabel}   ·   长宽比 ${screen.aspectLabel}", color = Muted, fontSize = 11.sp)
            Text("原图 ${originalDimensions.first} × ${originalDimensions.second} px", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 15.dp))

            MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(0.dp)) {
                Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("填充方式", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(mode.detail, color = Muted, fontSize = 11.sp)
                        }
                        Text("${FillMode.entries.indexOf(mode) + 1}/5", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FillMode.entries.forEach { item ->
                            ModeChip(item = item, selected = mode == item, onClick = { onModeChange(item) })
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("画面缩放", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${String.format(Locale.US, "%.2f", scale)}×", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        MiuixSlider(value = scale, onValueChange = onScaleChange, valueRange = 0.75f..1.25f, enabled = mode != FillMode.STRETCH, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("保留更多画面", color = Muted, fontSize = 10.sp)
                            Text("放大主体", color = Muted, fontSize = 10.sp)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("上下位置", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(when {
                                verticalPosition < 0.35f -> "偏上"
                                verticalPosition > 0.65f -> "偏下"
                                else -> "居中"
                            }, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        MiuixSlider(value = verticalPosition, onValueChange = onPositionChange, valueRange = 0f..1f, enabled = mode != FillMode.STRETCH, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("靠上", color = Muted, fontSize = 10.sp)
                            Text("居中", color = Muted, fontSize = 10.sp)
                            Text("靠下", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFEAE9E5)).padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("i", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text("上下延展是本地边缘拉伸，不会凭空生成新场景。若想保留照片全部内容，可试试“柔和填充”。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                StatusLine(statusMessage)
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(0.95f)) {
                MiuixButton(onClick = onSave, enabled = !processing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (processing) "处理中…" else "保存图片", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(Modifier.weight(1.25f)) {
                MiuixButton(onClick = onApply, enabled = !processing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (processing) "请稍候…" else "应用到桌面与锁屏", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DevicePreview(screen: ScreenSpec, bitmap: Bitmap?) {
    val dateLabel = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 1.dp), contentAlignment = Alignment.Center) {
        val phoneHeight = minOf(316.dp, maxHeight)
        val phoneWidth = phoneHeight * screen.aspect
        Box(
            modifier = Modifier.width(phoneWidth).height(phoneHeight)
                .clip(RoundedCornerShape(34.dp)).background(Color(0xFF17181B))
                .padding(4.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF2D3038))
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = "处理后的壁纸预览", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF636A91), Color(0xFF292E43)))))
            }
            Column(Modifier.fillMaxSize().padding(top = 36.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dateLabel, color = White.copy(alpha = 0.9f), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                Text("9:41", color = White, fontSize = 35.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.weight(1f))
                Text("壁纸预览", color = White.copy(alpha = 0.85f), fontSize = 9.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(42.dp), modifier = Modifier.padding(top = 9.dp)) {
                    Box(Modifier.size(23.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.24f)).border(1.dp, White.copy(alpha = 0.4f), CircleShape))
                    Box(Modifier.size(23.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.24f)).border(1.dp, White.copy(alpha = 0.4f), CircleShape))
                }
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).width(54.dp).height(14.dp).clip(CircleShape).background(Color.Black))
        }
    }
}

@Composable
private fun ModeChip(item: FillMode, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) Accent else Color(0xFFF1F1F4)
    val foreground = if (selected) White else Ink
    Box(
        modifier = Modifier.clip(RoundedCornerShape(13.dp)).background(background)
            .border(BorderStroke(1.dp, if (selected) Accent else Line), RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(item.title, color = foreground, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun StatusLine(message: String) {
    Text(message, color = Accent, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), textAlign = TextAlign.Center)
}

