# WallExtend

WallExtend is a lightweight Android wallpaper editor built with Kotlin, Jetpack Compose, and [Miuix UI](https://github.com/compose-miuix-ui/miuix). Pick a photo, preview it in your device's screen ratio, adjust the fill method, then save or apply the result.

## Features

- Opens the Android photo picker without requesting broad photo-library access.
- Decodes content URIs with Android `ImageDecoder`, with a `BitmapFactory` fallback for providers or formats that need it.
- Reads the device's physical display dimensions and exports a wallpaper at the matching portrait resolution.
- Five fill modes: edge extension, mirrored edges, blurred background, cover crop, and stretch.
- Live preview, zoom control, and vertical positioning.
- Corrects EXIF orientation and reduces decode size to avoid unnecessary memory use.
- Saves to `Pictures/WallExtend` or applies the generated image to the home screen and lock screen.
- Works locally. Photos are not uploaded.

## Fill modes

| Mode | Result |
| --- | --- |
| Edge extension | Stretches edge pixels to fill the empty top and bottom space. |
| Mirrored edges | Reflects the edge strips to soften the transition. |
| Blurred background | Uses a blurred, low-resolution copy behind the full photo. |
| Cover crop | Fills the screen and crops the excess. |
| Stretch | Resizes the full image to the screen dimensions. |

These are deterministic local image-processing methods. They do not generate new scene content like a generative image model.

## Build

Requirements: Android Studio, JDK 21, Android SDK 37, and an internet connection for Gradle and Maven dependencies.

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

Linux or macOS:

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Project layout

```text
app/src/main/
├── AndroidManifest.xml
├── java/dev/mouya/wallpaperstudio/
│   ├── MainActivity.kt
│   ├── PhotoLoader.kt
│   └── WallpaperProcessor.kt
└── res/
    ├── drawable/
    ├── mipmap-anydpi-v26/
    └── values/
```

## Notes

- The target image uses the device's current physical display size in portrait orientation.
- Zoom and vertical positioning are disabled in stretch mode because the image is mapped directly to the output dimensions.
- The editor currently targets phone portrait wallpapers. It does not implement generative AI extension or multi-screen panoramic desktop wallpaper sizing.

## License

Application code is licensed under the MIT License. Miuix and all transitive dependencies remain under their respective upstream licenses.
