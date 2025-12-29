# Project Structure

```
VisualMP/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/skul9x/visualmp/
│   │   │   │   ├── adapter/
│   │   │   │   │   └── SongAdapter.kt          # RecyclerView adapter cho danh sách bài hát
│   │   │   │   ├── model/
│   │   │   │   │   └── Song.kt                 # Data class cho bài hát
│   │   │   │   ├── service/
│   │   │   │   │   └── MusicPlayerService.kt   # Foreground service xử lý phát nhạc
│   │   │   │   ├── util/
│   │   │   │   │   └── MusicScanner.kt         # Quét file nhạc từ MediaStore
│   │   │   │   ├── MainActivity.kt             # Màn hình chính với danh sách và mini player
│   │   │   │   └── PlayerActivity.kt           # Full screen player
│   │   │   │
│   │   │   ├── res/
│   │   │   │   ├── drawable/
│   │   │   │   │   ├── ic_arrow_back.xml       # Back navigation icon
│   │   │   │   │   ├── ic_music_note.xml       # Placeholder album art
│   │   │   │   │   ├── ic_next.xml             # Next track icon
│   │   │   │   │   ├── ic_pause.xml            # Pause icon
│   │   │   │   │   ├── ic_play.xml             # Play icon
│   │   │   │   │   ├── ic_previous.xml         # Previous track icon
│   │   │   │   │   ├── ic_repeat.xml           # Repeat all icon
│   │   │   │   │   ├── ic_repeat_one.xml       # Repeat one icon
│   │   │   │   │   └── ic_shuffle.xml          # Shuffle icon
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml       # Layout màn hình chính
│   │   │   │   │   ├── activity_player.xml     # Layout full player
│   │   │   │   │   └── item_song.xml           # Layout item trong danh sách
│   │   │   │   ├── values/
│   │   │   │   │   ├── colors.xml              # Color palette
│   │   │   │   │   ├── strings.xml             # String resources (Vietnamese)
│   │   │   │   │   └── themes.xml              # App theme với Material 3
│   │   │   │   └── values-night/
│   │   │   │       └── themes.xml              # Dark theme
│   │   │   │
│   │   │   └── AndroidManifest.xml             # App permissions và components
│   │   │
│   │   ├── androidTest/                        # Instrumented tests
│   │   └── test/                               # Unit tests
│   │
│   ├── build.gradle.kts                        # Module dependencies
│   └── proguard-rules.pro                      # ProGuard rules
│
├── gradle/
│   └── libs.versions.toml                      # Version catalog
│
├── build.gradle.kts                            # Project-level build config
├── settings.gradle.kts                         # Project settings
├── README.md                                   # Project documentation
└── structure.md                                # This file
```

## Component Overview

### Activities

| Component | Responsibility |
|-----------|----------------|
| `MainActivity` | Hiển thị danh sách bài hát, xử lý permissions, mini player |
| `PlayerActivity` | Full screen player với seekbar và controls |

### Services

| Component | Responsibility |
|-----------|----------------|
| `MusicPlayerService` | Background playback, notification, MediaPlayer management |

### Adapters

| Component | Responsibility |
|-----------|----------------|
| `SongAdapter` | Bind Song data vào RecyclerView, highlight playing song |

### Models

| Component | Responsibility |
|-----------|----------------|
| `Song` | Data class chứa metadata bài hát |

### Utilities

| Component | Responsibility |
|-----------|----------------|
| `MusicScanner` | Query MediaStore để lấy danh sách file nhạc |

## Data Flow

```
┌─────────────────┐
│   MediaStore    │
└────────┬────────┘
         │ Query audio files
         ▼
┌─────────────────┐
│  MusicScanner   │
└────────┬────────┘
         │ List<Song>
         ▼
┌─────────────────┐     ┌─────────────────────┐
│  MainActivity   │────▶│  MusicPlayerService │
└────────┬────────┘     └──────────┬──────────┘
         │                         │
         │ Song clicked            │ Playback state
         ▼                         ▼
┌─────────────────┐     ┌─────────────────────┐
│   SongAdapter   │     │    Notification     │
└─────────────────┘     └─────────────────────┘
```

## Key Dependencies

| Dependency | Purpose |
|------------|---------|
| `glide` | Load và cache album art images |
| `recyclerview` | Efficient list rendering |
| `media` | MediaStyle notification |
| `material` | Material Design 3 components |
| `constraintlayout` | Flexible layouts |
