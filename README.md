# VisualMP 🎵

Ứng dụng nghe nhạc Android với giao diện đẹp và đầy đủ tính năng.

## Tính năng

- 📂 **Quét nhạc tự động** - Tự động tìm và liệt kê tất cả file âm thanh trên thiết bị
- 🎨 **Giao diện hiện đại** - Material Design 3 với dark mode support
- ▶️ **Điều khiển đầy đủ** - Play, Pause, Next, Previous, Seek
- 🔀 **Shuffle & Repeat** - 3 chế độ repeat: Off, All, One
- 🔔 **Background playback** - Nghe nhạc khi minimize app với notification controls
- 🖼️ **Album art** - Hiển thị ảnh album cho mỗi bài hát

## Screenshots

| Danh sách bài hát | Full Player |
|:-----------------:|:-----------:|
| Song list với mini player | Điều khiển chi tiết |

## Yêu cầu

- Android 7.0 (API 24) trở lên
- Quyền truy cập file âm thanh

## Cài đặt

### Từ source code

```bash
git clone https://github.com/yourusername/VisualMP.git
cd VisualMP
./gradlew assembleDebug
```

APK sẽ được tạo tại: `app/build/outputs/apk/debug/app-debug.apk`

### Build với Android Studio

1. Mở project trong Android Studio
2. Sync Gradle
3. Run trên device/emulator

## Sử dụng

1. Mở app và cấp quyền truy cập file
2. Danh sách bài hát sẽ tự động load
3. Tap vào bài hát để phát
4. Sử dụng mini player hoặc tap để mở full player

## Tech Stack

- **Language**: Kotlin
- **UI**: XML Layouts với ViewBinding
- **Architecture**: Service-based playback
- **Dependencies**:
  - Glide - Load album art
  - Material Components - UI
  - AndroidX Media - Media session

## Permissions

| Permission | Mục đích |
|------------|----------|
| `READ_MEDIA_AUDIO` | Đọc file nhạc (Android 13+) |
| `READ_EXTERNAL_STORAGE` | Đọc file nhạc (Android 12-) |
| `FOREGROUND_SERVICE` | Background playback |
| `POST_NOTIFICATIONS` | Notification controls |

## License

MIT License - Xem file [LICENSE](LICENSE) để biết thêm chi tiết.

## Contributing

Pull requests are welcome! Vui lòng mở issue trước khi tạo PR cho các thay đổi lớn.
