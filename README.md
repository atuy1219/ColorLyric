# ColorLyric

ColorLyric is a libxposed API 102 module for Spotify lyrics on ColorOS/OxygenOS.

## Scope

- `com.spotify.music`
- `com.android.systemui`

The SystemUI hook is intentionally minimal. It does **not** modify lyric parsing, rendering,
`LyricsRecyclerView`, notifications, media artwork or the stock immersive lyric UI.

## Data flow

```text
Spotify playback process
        ↓
Spotify auth header capture
        ↓
Spotify Color Lyrics API
        ↓
LINE_SYNCED / SYLLABLE_SYNCED decoder
        ↓
Spotify MediaSession / MediaMetadata["lyricInfo"]
        ↓
ColorOS / OxygenOS SystemUI
        ↓
stock Live Alert / lock-screen lyric UI
```

LRCLIB is not used.

## libxposed API

ColorLyric targets libxposed API 102 and uses only the modern interceptor-chain API.
Legacy `de.robv.android.xposed.*` APIs and legacy manifest/xposed_init metadata are not used.
Module registration is stored under `META-INF/xposed/`:

- `java_init.list`
- `scope.list`
- `module.prop`

## Lock-screen policy

On the tested OPlus ROM, Spotify lyrics already reach the stock `LyricsRecyclerView`, but OPlus
returns a disabled lyric package policy for `com.spotify.music` while QQ Music is enabled.

ColorLyric therefore hooks only the OPlus package-policy methods:

- `getLyricEntrance(String)`
- `getLyricEnable(String)` when present

For Spotify lookups only, the package argument is evaluated as `com.tencent.qqmusic`. No numeric
policy value such as `52` is hard-coded; the ROM's current QQ Music value is reused.

The implementation first tries the known OPlus media action selector class. If the class name differs,
it temporarily watches OPlus media class loading and hooks a class exposing the exact policy method
shape. The watcher is removed as soon as `getLyricEntrance` is secured; `getLyricEnable`, when it
exists on that selector, is discovered in the same method scan. Alias logging is emitted only once per
policy method.

## Spotify lyric source

ColorLyric captures only the headers needed by Spotify's Color Lyrics request path:

- `authorization`
- `client-token`
- `user-agent`
- `x-client-id`

Header values are never written to ColorLyric logs.

The current `spotify:track:` ID is requested from Spotify Color Lyrics. This keeps lyric timing aligned
with Spotify playback instead of a third-party LRC database.

Supported timing:

- `LINE_SYNCED`
- `SYLLABLE_SYNCED`

For Spotify update tolerance, ColorLyric tries the currently known header classes first and falls back
to a temporary structural class-load discovery path. The fallback identifies the shaded immutable
header container by class structure rather than its R8 `p.*` name, then removes the watcher.

## Fetch lifecycle

Each request is keyed by both track ID and track generation. Rapid `A → B → A` changes therefore do
not allow an old request for A to suppress the new generation. When the track changes, active stale
HTTP calls are cancelled and their `HttpURLConnection` is disconnected. Two fetch workers are used so
a stalled request cannot create the previous single-thread head-of-line blocking behavior.

Short negative caches prevent repeated requests for known misses:

- HTTP 404: 6 hours
- HTTP 403: 5 minutes
- HTTP 429: 1 minute
- no lyric lines: 30 minutes

HTTP connections are always disconnected in `finally`.

Cached lyric payloads are rebound to the current session generation before replay. Existing
`lyricInfo` with a different `spotify:track:` `songId` is rejected instead of being cached against the
current track.

## Installation

1. Install ColorLyric.
2. In LSPosed, enable both `com.spotify.music` and `com.android.systemui`.
3. Disable the separate Spotify Lyric Provider when testing ColorLyric standalone.
4. Reboot the device.
5. Start Spotify and play a normal `spotify:track:` item.

Debug:

```sh
adb logcat -s ColorLyric
```

Expected SystemUI lines include:

```text
SystemUI minimal lyric-policy hook loading; API=102
hooked ...#getLyricEntrance
hooked ...#getLyricEnable
policy alias getLyricEntrance: Spotify -> QQ Music
policy alias getLyricEnable: Spotify -> QQ Music
```

Expected Spotify lines include:

```text
loaded in Spotify main process; API=102
Spotify auth headers ready keys=authorization,client-token,user-agent,x-client-id
Spotify Color Lyrics outcome=ok syncType=LINE_SYNCED ...
official Spotify lyricInfo committed once: ........
```

## CI

GitHub Actions runs unit tests, Android lint and a debug APK build:

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Tests cover fetch generation/negative-cache policy, Spotify Color Lyrics decoding, header readiness,
track metadata merging and `lyricInfo` track identity validation.

## Research notes

QQ Music's stock OPlus metadata behavior and ColorOS Live Lyrics Bridge / Providers were consulted for
compatibility research. ColorLyric remains GPL-3.0-only.

## License

GNU General Public License v3.0 only (`GPL-3.0-only`).
