# ColorLyric 0.4 - Native Unlocker

ColorLyric is a focused libxposed module for OPlus SystemUI. It does **not** draw a custom lyric
view and does **not** create a Live Update notification. Its purpose is to let Spotify, after a
separate Provider has published `MediaMetadata["lyricInfo"]`, enter the same stock lock-screen lyric
page that QQ Music uses on supported ColorOS/OxygenOS builds.

## Data flow

```text
Spotify
  -> ColorOS Live Lyrics Spotify Provider
  -> Spotify MediaSession["lyricInfo"]
  -> OPlus SystemUI native lyric loader
  -> ColorLyric native-gate compatibility
  -> stock LyricsRecyclerView / immersive lock-screen lyric page
```

## What this build changes

Only `com.android.systemui` is hooked.

For `com.spotify.music`:

- `getLyricEntrance(packageName)` is evaluated with QQ Music's package identity and the returned
  vendor value is reused for Spotify. No numeric vendor constant is guessed.
- `getLyricEnable(packageName)` is handled the same way when that method exists.
- Spotify is appended to the OPlus media RUS whitelist getter when necessary.
- The native `lyricInfo` loader is observed to confirm that SystemUI receives the Spotify payload.
- Seedling `mediaDataToBundle(...)` keeps `shouldShowLyric=true` for a Spotify item that has lyric
  state.

The module intentionally does not replace media actions globally and does not alias Spotify to QQ
Music outside these lyric-specific gates.

## Installation

1. Install and enable the Spotify Provider that publishes `lyricInfo` in `com.spotify.music`.
2. Install ColorLyric.
3. Enable only `com.android.systemui` in the ColorLyric LSPosed scope.
4. Restart SystemUI or reboot.
5. Start Spotify and play a track with lyrics.
6. On the lock-screen media card, check for the native lyric-page entrance icon.

Debug:

```sh
adb logcat -s ColorLyric
```

Useful events include:

```text
Resolved OPlus media gates with DexKit
Added Spotify to ... whitelist
Native gate lyricEntrance: Spotify -> QQ Music policy, value=...
Native gate lyricEnable: Spotify -> QQ Music policy, value=...
Spotify native lyric loader: lyricInfo=present
Seedling Spotify: shouldShowLyric ... -> true
```

## Compatibility

The OPlus lock-screen lyric page is a private SystemUI feature. This build resolves current
ColorOS/OxygenOS targets using the same stable string anchors documented by ColorOS Live Lyrics
Bridge, with legacy class-name fallbacks. A SystemUI update can still require renewed adaptation.

## Credits and license

Apache-2.0.

OPlus SystemUI target discovery and compatibility research is derived from and informed by
**ColorOS Live Lyrics Bridge** by Andrea-lyz / Yunzhe Liao, also licensed under Apache-2.0.
See `NOTICE`.
