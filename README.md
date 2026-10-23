# 壁纸工坊 · WallExtend Studio

一个以 [Miuix](https://github.com/compose-miuix-ui/miuix) 为 UI 基础的 Android 壁纸适配工具。选择本地图片后进入参数编辑页，实时预览处理结果，并按运行设备的物理屏幕尺寸输出壁纸。


## 功能

- 使用系统图片选择器从相册选图，不要求整库读取照片权限。
- 读取设备物理显示尺寸，生成与手机竖屏比例一致的预览和输出图。
- 5 种适配模式：上下边缘延展、镜像延展、模糊背景填充、铺满裁切、整体拉伸。
- 可调主体缩放及上下位置，参数变化后更新预览。
- 保留 EXIF 方向信息；解码采样限制输入图大小，减少大图引起的内存峰值。
- 导出高质量 JPEG 到 `Pictures/Wallpaper Studio`，或一次应用到桌面与锁屏。
- 本地处理，不上传照片。

## 关于“像 iOS 一样延展”

本项目采用可预测的本地图像处理，不伪装成生成式 AI：默认的“上下延展”会拉伸图片边缘来填充不够的区域；“镜像延展”会反射边缘；“柔和填充”使用低分辨率模糊背景并在中央保留主体。它们可以解决比例不匹配，但不会凭空生成真实的新场景。

近年 iOS 的壁纸扩展方案也经历过变化：较旧的版本会以模糊方式补足空缺，较新的“Extend”则能借助 Apple Intelligence 生成额外画面。相关背景可参阅 [iOS 17.1 Extend Wallpaper](https://www.macrumors.com/guide/ios-17-1-features/) 与 [iOS 27 Extend wallpaper](https://9to5mac.com/2026/10/02/ios-27-lock-screen-customization/)。本项目目前实现的是本地确定性算法，不等同于 iOS 的生成式扩图模型。

## 技术栈

- Kotlin + Jetpack Compose
- [Miuix UI](https://github.com/compose-miuix-ui/miuix) `miuix-ui-android:0.9.4`
- Activity Result API / Android Photo Picker
- Android `Bitmap`、`Canvas`、`WallpaperManager` 与 MediaStore

## 构建

要求 Android Studio、Android SDK 36、JDK 21。首次构建需要联网下载 Gradle 和 Maven 依赖。

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

Linux / macOS：

```bash
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。

## 工程结构

```text
app/src/main/
├── AndroidManifest.xml
├── java/dev/mouya/wallpaperstudio/
│   ├── MainActivity.kt       # 页面、屏幕规格、导出/应用流程
│   ├── PhotoLoader.kt        # 图片采样解码与 EXIF 方向校正
│   └── WallpaperProcessor.kt # 壁纸填充算法与导出渲染
└── res/values/styles.xml
```

## 说明

- 目标壁纸尺寸按设备当前物理显示区域的短边 × 长边生成，适合手机竖屏壁纸。
- “画面缩放”和“上下位置”用于控制主体构图；对“整体拉伸”模式而言，缩放和位置参数不产生额外效果。
- 当前版本面向手机竖屏壁纸。没有实现生成式 AI 扩图、手势裁剪框、全景桌面横向滚动尺寸或多屏折叠态专用模板。
- Miuix UI 仍在积极迭代，库版本和 API 可按后续仓库更新调整。

## License

本项目的应用代码使用 MIT License。Miuix 及传递依赖遵循各自的上游许可证。
