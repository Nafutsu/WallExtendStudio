# Changelog

## 0.2.0

- Rename the app to **WallExtend** and simplify the launcher icon.
- Replace the decorative landing page with a restrained, iOS-inspired layout.
- Use Android's system photo picker instead of the previous content-selection contract.
- Fix the image-loading failure: bounds-only `BitmapFactory` decoding intentionally returns no bitmap, so it must not be treated as a failed stream open.
- Decode through Android `ImageDecoder` first, with `BitmapFactory` and file-descriptor fallbacks.
- Keep URI access where the picker supports persistable grants and add useful decode diagnostics.
- Store exported images under `Pictures/WallExtend`.
